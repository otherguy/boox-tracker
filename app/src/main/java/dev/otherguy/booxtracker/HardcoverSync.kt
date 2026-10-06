package dev.otherguy.booxtracker

import java.math.RoundingMode
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

fun editionPages(raw: String?, pages: Int): Int {
    if (Progress.parse(raw).percent == null || pages <= 0) throw SyncProblem("progress_or_edition_pages_unavailable")
    val fraction = raw!!.split('/').map { it.trim().toBigDecimal() }
    return fraction[0].multiply(pages.toBigDecimal()).divide(fraction[1], 0, RoundingMode.HALF_UP).intValueExact()
}

class HardcoverSync(private val auth: HardcoverAuth, private val store: DiagnosticsStore? = null, private val maySend: () -> Boolean = { true }) {
    suspend fun send(book: JSONObject, identifiers: BookIdentifiers, expectedAccount: Int? = null): JSONObject = auth.authorized { token ->
        suspend fun query(query: String, variables: JSONObject = JSONObject()): JSONObject {
            coroutineContext.ensureActive()
            if (query.startsWith("mutation") && !maySend()) throw SyncProblem("service_disabled")
            val result = auth.http.graphql(token, query, variables)
            coroutineContext.ensureActive()
            return result
        }
        if (book.raw("readingStatus") != "1") throw SyncProblem("source_status_not_currently_reading")
        val me = query("query Identity { me { id } }").records("me").singleOrNull() ?: throw SyncProblem("hardcover_identity_unavailable")
        if (expectedAccount != null && me.getInt("id") != expectedAccount) throw SyncProblem("hardcover_account_changed")
        val match = HardcoverMatcher({ q, v -> query(q, v) }, store).match(book.optString("key").takeIf { it.isNotBlank() }, identifiers)
        val bookId = match.getInt("bookId")
        val catalog = query("query BookDetails(\$id: Int!) { books_by_pk(id: \$id) { id title default_ebook_edition { id book_id pages } default_physical_edition { id book_id pages } } }", JSONObject().put("id", bookId)).optJSONObject("books_by_pk") ?: throw SyncProblem("hardcover_book_not_found")
        val variables = JSONObject().put("user", me.getInt("id")).put("book", bookId)
        val library = query(
            "query Library(\$user: Int!, \$book: Int!) { user_books(where: {user_id: {_eq: \$user}, book_id: {_eq: \$book}}) { id status_id edition_id user_book_reads { id edition_id finished_at paused_at progress progress_pages progress_seconds } } }",
            variables
        ).records("user_books")
        if (library.size > 1) throw SyncProblem("hardcover_library_ambiguous")
        val existing = library.singleOrNull()
        var userBookId: Int
        val reads = existing?.records("user_book_reads") ?: emptyList()
        if (existing != null) {
            if (existing.getInt("status_id") !in setOf(1, 2)) throw SyncProblem("hardcover_status_conflict")
            if (reads.any { !it.isNull("finished_at") || !it.isNull("paused_at") } || reads.size > 1) throw SyncProblem("hardcover_read_history_conflict")
        }
        val read = reads.singleOrNull()
        val remoteEditionId = read?.optInt("edition_id")?.takeIf { it > 0 } ?: existing?.optInt("edition_id")?.takeIf { it > 0 }
        val exactId = match.optInt("exactEditionId").takeIf { it > 0 }
        suspend fun edition(id: Int): JSONObject? = query("query Edition(\$id: Int!) { editions_by_pk(id: \$id) { id book_id pages } }", JSONObject().put("id", id)).optJSONObject("editions_by_pk")
        val exact = exactId?.let { edition(it) }
        if (exact != null && exact.optInt("book_id") != bookId) throw SyncProblem("hardcover_edition_book_conflict")
        // Existing page progress stays on its original edition basis.
        val basis = if (remoteEditionId != null) {
            edition(remoteEditionId)
        } else {
            exact?.takeIf { it.optInt("pages") > 0 }
                ?: catalog.optJSONObject("default_ebook_edition")?.takeIf { it.optInt("pages") > 0 }
                ?: catalog.optJSONObject("default_physical_edition")?.takeIf { it.optInt("pages") > 0 }
        }
        if (basis == null || basis.optInt("pages") <= 0) throw SyncProblem("hardcover_page_count_missing")
        if (basis.optInt("book_id") != bookId) throw SyncProblem("hardcover_edition_book_conflict")
        val editionId = basis.getInt("id")
        val pages = basis.getInt("pages")
        val progressPages = editionPages(book.raw("progress"), pages)
        if (progressPages >= pages) throw SyncProblem("completion_not_supported")
        val detail = JSONObject().put("bookId", bookId).put("editionId", editionId).put("isbns", JSONArray(identifiers.isbns.sorted()))
            .put("sourceEditionId", exactId ?: JSONObject.NULL).put("existingEditionPreserved", remoteEditionId != null && exactId != null && remoteEditionId != exactId)
            .put("matchKind", if (exactId == editionId) "edition" else "book").put("title", catalog.optString("title"))
            .put("editionPages", pages).put("progressPages", progressPages).put("rawProgress", book.raw("progress"))
        if (read != null) {
            val remotePages = if (read.isNull("progress_pages")) null else read.getInt("progress_pages")
            val remoteEdition = if (read.isNull("edition_id")) existing?.optInt("edition_id", -1) else read.getInt("edition_id")
            if (read.optInt("progress_seconds") > 0 || (remotePages == null && read.optDouble("progress", 0.0) > 0)) throw SyncProblem("hardcover_progress_format_unsupported")
            if (remoteEdition != null && remoteEdition > 0 && remoteEdition != editionId) throw SyncProblem("hardcover_edition_conflict")
            if ((remoteEdition == null || remoteEdition <= 0) && (remotePages ?: 0) > 0) throw SyncProblem("hardcover_edition_unavailable")
            if ((remotePages ?: 0) > progressPages) return@authorized detail.put("outcome", "kept_higher_remote_progress").put("remoteProgressPages", remotePages)
            if (remotePages == progressPages && remoteEdition == editionId && existing?.optInt("status_id") == 2) return@authorized detail.put("outcome", "already_current").put("unchanged", true)
        }
        if (existing == null) {
            val result = mutation(
                query("mutation AddBook(\$object: UserBookCreateInput!) { insert_user_book(object: \$object) { id error } }", JSONObject().put("object", JSONObject().put("book_id", bookId).put("edition_id", editionId).put("status_id", 2))),
                "insert_user_book"
            )
            userBookId = result.getInt("id")
        } else {
            userBookId = existing.getInt("id")
            if (existing.getInt("status_id") == 1) {
                mutation(query("mutation StartBook(\$id: Int!, \$object: UserBookUpdateInput!) { update_user_book(id: \$id, object: \$object) { id error } }", JSONObject().put("id", userBookId).put("object", JSONObject().put("status_id", 2))), "update_user_book")
            }
        }
        val objectValue = JSONObject().put("edition_id", editionId).put("progress_pages", progressPages)
        val result = if (read == null) {
            mutation(query("mutation AddRead(\$id: Int!, \$read: DatesReadInput!) { insert_user_book_read(user_book_id: \$id, user_book_read: \$read) { id error } }", JSONObject().put("id", userBookId).put("read", objectValue)), "insert_user_book_read")
        } else {
            mutation(query("mutation AdvanceRead(\$id: Int!, \$read: DatesReadInput!) { update_user_book_read(id: \$id, object: \$read) { id error } }", JSONObject().put("id", read.getInt("id")).put("read", objectValue)), "update_user_book_read")
        }
        detail.put("outcome", "sent").put("userBookId", userBookId).put("readId", result.getInt("id"))
    }

    private fun mutation(data: JSONObject, field: String): JSONObject {
        val value = data.optJSONObject(field) ?: throw SyncProblem("hardcover_mutation_missing_result")
        if (!value.isNull("error") && value.optString("error").isNotBlank()) throw SyncProblem("hardcover_mutation_rejected")
        if (value.isNull("id") || value.optInt("id") <= 0) throw SyncProblem("hardcover_mutation_missing_id")
        return value
    }
}
