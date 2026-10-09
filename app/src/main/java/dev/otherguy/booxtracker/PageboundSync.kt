package dev.otherguy.booxtracker

import java.time.LocalDate
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

private val pageboundStatuses = setOf("current", "finished", "dnf", "paused", "tbr", "interested", "none")

/** Pagebound's date format: month/day/year without zero padding. */
private fun pageboundDate(day: LocalDate) = "${day.monthValue}/${day.dayOfMonth}/${day.year}"

/**
 * Sends one source book's progress to Pagebound: add or move the book to Reading, post the percentage in whole steps,
 * confirm; or mark the book Finished. Reading instances the app creates are digital; existing ones keep their format.
 */
class PageboundSync(private val auth: PageboundAuth, private val store: DiagnosticsStore, private val maySend: () -> Boolean) {
    suspend fun send(book: JSONObject, identifiers: BookIdentifiers, expectedAccount: String? = null, readAt: String? = null): JSONObject = auth.authorized { token ->
        suspend fun get(path: String): JSONObject {
            coroutineContext.ensureActive()
            return auth.http.get(token, path).also { coroutineContext.ensureActive() }
        }
        suspend fun write(method: String, path: String, body: JSONObject) {
            coroutineContext.ensureActive()
            if (!maySend()) throw SyncProblem("service_disabled")
            auth.http.write(token, method, path, body)
            coroutineContext.ensureActive()
        }

        val (finished, percent) = sourceStatus(book)
        val account = pageboundAccountId(get("/auth/get_authed_user"))
        if (expectedAccount != null && account != expectedAccount) throw SyncProblem("pagebound_account_changed")
        val match = PageboundMatcher({ get(it) }, { auth.http.searchBooks(it) }, store).match(book.optString("key").takeIf { it.isNotBlank() }, identifiers)
        val bookUuid = match.getString("bookUuid")
        val catalogue = get("/books/$bookUuid").optJSONObject("book") ?: throw SyncProblem("pagebound_book_not_found")
        val entry = catalogue.optJSONObject("user_book")
        val status = entry?.text("status") ?: "none"
        if (status !in pageboundStatuses) throw SyncProblem("pagebound_status_unrecognized")
        val matchedEdition = match.optLong("editionId").takeIf { it > 0 }
        val matchedEditions = match.optJSONArray("editions")?.let { ids -> (0 until ids.length()).map(ids::getLong) }.orEmpty()
        val day = readingDay(book, readAt)
        val date = pageboundDate(day)
        val pages = match.optInt("pages").takeIf { it > 0 } ?: catalogue.optInt("page_count").takeIf { it > 0 }
        val detail = JSONObject().put("title", catalogue.text("title") ?: JSONObject.NULL).put("bookUuid", bookUuid).put("bookId", catalogue.optLong("id"))
            .put("matchKind", match.getString("matchKind")).put("matchedEditionId", matchedEdition ?: JSONObject.NULL)
            .put("rawProgress", book.raw("progress")).put("percent", percent).put("finished", finished).put("shelfBefore", status)

        if (finished && status == "finished") return@authorized detail.put("outcome", "already_current").put("unchanged", true).put("shelfAfter", "finished")
        // A finished or abandoned book keeps its history; NeoReader reopening a finished book is not a new read.
        if (status == "finished" || status == "dnf") throw SyncProblem("pagebound_status_conflict")
        if (entry?.optBoolean("has_ever_finished") == true) throw SyncProblem("pagebound_reread_held")

        val target = if (finished) "finished" else "current"
        val statusChanged = status != target

        // A new reading instance is digital, because the source is an ebook.
        fun newRead() = JSONObject().put("status", target).put("date", date).put("format", "digital").put("tracking_mode", "pages")
            .put("total_page_count", pages ?: JSONObject.NULL).put("total_minutes", JSONObject.NULL)
        if (entry == null) {
            val body = newRead().put("shelf_ids", JSONArray()).put("challenge_year", JSONObject.NULL)
                .put("started_reading_at", if (finished) JSONObject.NULL else date).put("finished_reading_at", if (finished) date else JSONObject.NULL)
                .put("user_book", JSONObject().put("status", target).put("book_id", catalogue.optLong("id")).put("edition_id", matchedEdition ?: JSONObject.NULL).put("owned", false).put("muted", false))
            write("POST", "/user_books", body)
        } else {
            val uuid = entry.getString("uuid")
            // An edition Pagebound did not keep is written once, not on every sync.
            if (entry.isNull("edition_id") && matchedEdition != null && store.get("pagebound.book.edition.$uuid") != matchedEdition.toString()) {
                write("PUT", "/user_books/$uuid", JSONObject().put("user_book", JSONObject().put("edition_id", matchedEdition)))
                store.put("pagebound.book.edition.$uuid", matchedEdition.toString())
            }
            val instance = entry.optJSONObject("current_reading_instance")
            when {
                finished -> write("PUT", "/user_books/$uuid", (if (instance == null) newRead() else JSONObject().put("status", target).put("date", date)).put("finished_reading_at", date))

                status == "current" -> Unit

                // A paused read keeps its reading instance and the format the user chose.
                status == "paused" -> write("POST", "/user_books/$uuid/update_status", JSONObject().put("status", target))

                else -> write("PUT", "/user_books/$uuid", newRead().put("started_reading_at", date))
            }
        }
        val userBookUuid = entry?.text("uuid") ?: get("/books/$bookUuid").optJSONObject("book")?.optJSONObject("user_book")?.text("uuid") ?: throw SyncProblem("pagebound_status_not_applied")
        val current = get("/user_books/$userBookUuid")
        detail.put("userBookUuid", userBookUuid).put("editionId", current.opt("edition_id") ?: JSONObject.NULL)
            .put("existingEditionPreserved", !current.isNull("edition_id") && matchedEditions.isNotEmpty() && current.optLong("edition_id") !in matchedEditions)
        if (current.optString("status") != target) throw SyncProblem("pagebound_status_not_applied")
        if (current.isNull("edition_id") && matchedEdition != null && store.get("pagebound.book.edition.$userBookUuid") == matchedEdition.toString()) detail.put("editionError", "pagebound_edition_not_applied")
        if (finished) return@authorized detail.put("outcome", "sent").put("shelfAfter", "finished").put("finishDate", day.toString())
        val instance = current.optJSONObject("current_reading_instance") ?: throw SyncProblem("pagebound_status_not_applied")
        val remote = current.optInt("progress")
        detail.put("shelfAfter", "currently_reading").put("format", instance.text("format") ?: JSONObject.NULL).put("remotePercent", remote)
        if (remote > percent) return@authorized detail.put("outcome", "kept_higher_remote_progress")
        // Every update appears in the user's feed and journey, so progress moves in whole steps.
        belowNextStep(detail, percent, remote, statusChanged)?.let { return@authorized it }
        val step = stepPercent(percent)
        // Each update is public, so a value whose earlier update Pagebound did not show is held instead of posted again.
        val posted = "pagebound.book.posted.$userBookUuid"
        if (store.get(posted) == step.toString()) throw SyncProblem("pagebound_progress_not_applied")
        store.put(posted, step.toString())
        try {
            write(
                "POST",
                "/reading_updates",
                JSONObject().put("reading_update", JSONObject().put("user_book_id", current.optLong("id")).put("date", date).put("total_progress", step).put("total_pages_read", JSONObject.NULL).put("reading_instance_id", instance.optLong("id")))
                    .put("user_book", JSONObject().put("current_page", JSONObject.NULL).put("total_page_count", current.opt("total_page_count") ?: JSONObject.NULL).put("current_minute", JSONObject.NULL).put("total_minutes", JSONObject.NULL))
                    .put("no_broadcast", false).put("reading_update_id", JSONObject.NULL).put("progress_method", "percent")
            )
        } catch (error: HttpProblem) {
            // Pagebound answered and refused the update, so a retry posts nothing twice.
            store.delete(posted)
            throw error
        }
        if (get("/user_books/$userBookUuid").optInt("progress", -1) != step) throw SyncProblem("pagebound_progress_not_applied")
        store.delete(posted)
        detail.put("remotePercent", step).put("posted", step).put("outcome", "sent")
    }
}
