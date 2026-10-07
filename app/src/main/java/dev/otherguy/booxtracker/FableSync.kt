package dev.otherguy.booxtracker

import java.math.RoundingMode
import java.time.format.TextStyle
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/** Whole percent, rounded down, because Fable accepts only integers and 50.07% has not yet reached 51%. */
fun flooredPercent(raw: String?): Int {
    if (Progress.parse(raw).percent == null) throw SyncProblem("progress_unavailable")
    val (numerator, denominator) = raw!!.split('/').map { it.trim().toBigDecimal() }
    return numerator.multiply(100.toBigDecimal()).divide(denominator, 0, RoundingMode.DOWN).intValueExact()
}

private val shelves = listOf("current_reading", "want_to_read", "finished", "did_not_finish")

class FableSync(private val auth: FableAuth, private val store: DiagnosticsStore? = null, private val maySend: () -> Boolean = { true }) {
    suspend fun send(book: JSONObject, identifiers: BookIdentifiers, expectedAccount: String? = null, readAt: String? = null): JSONObject = auth.authorized { token ->
        suspend fun get(path: String): JSONObject {
            coroutineContext.ensureActive()
            val result = auth.http.get(token, path)
            coroutineContext.ensureActive()
            return result
        }
        suspend fun post(path: String, body: JSONObject) {
            coroutineContext.ensureActive()
            if (!maySend()) throw SyncProblem("service_disabled")
            auth.http.post(token, path, body)
            coroutineContext.ensureActive()
        }
        val finished = when (book.raw("readingStatus")) {
            "1" -> false
            "2" -> true
            else -> throw SyncProblem("source_status_unsupported")
        }
        val percent = flooredPercent(book.raw("progress"))
        if (finished && percent != 100) throw SyncProblem("source_finish_progress_mismatch")
        if (!finished && percent >= 100) throw SyncProblem("source_status_not_finished")
        val account = fableAccountId(get("/api/settings/profile/"))
        if (expectedAccount != null && account != expectedAccount) throw SyncProblem("fable_account_changed")
        val match = FableMatcher({ get(it) }, store).match(book.optString("key").takeIf { it.isNotBlank() }, identifiers)
        val matched = match.getString("bookId")
        val editions = get("/api/books/$matched/editions/").items("results")
        if (editions.size > 20) throw SyncProblem("fable_family_too_large")
        val family = editions.map { it.optString("id") }.toSet() + matched
        val lists = get("/api/v2/users/$account/book_lists?limit=20&offset=0&media_type=book").items("results")
            .filter { it.optString("type") == "system" }.associate { it.optString("system_type") to it.optString("id") }
        if (shelves.any { lists[it].isNullOrBlank() }) throw SyncProblem("fable_lists_unavailable")

        // Shelf membership comes from the system lists, because the book detail status can lag behind a write.
        suspend fun membership(only: Collection<String> = shelves): Map<String, String> = buildMap {
            for (shelf in only) {
                var offset = 0
                while (true) {
                    if (offset >= 5_000) throw SyncProblem("fable_library_too_large")
                    val response = get("/api/v2/users/$account/book_lists/${lists.getValue(shelf)}/books?limit=100&offset=$offset")
                    val page = response.items("results")
                    page.forEach { entry ->
                        val id = (entry.optJSONObject("book") ?: entry).optString("id")
                        if (id in family && id !in this) put(id, shelf)
                    }
                    if (page.isEmpty() || response.isNull("next") || response.optString("next").isBlank()) break
                    offset += page.size
                }
            }
        }
        suspend fun shelve(target: String, shelf: String) {
            val others = JSONArray(shelves.filter { it != shelf }.map { lists.getValue(it) })
            try {
                post("/api/v2/users/$account/book_lists/book", JSONObject().put("type", "multiselect").put("book_id", target).put("book_list_ids", JSONArray().put(lists.getValue(shelf))).put("exclude_from", others))
            } catch (error: HttpProblem) {
                if (error.status != 409) throw error
            }
            if (membership(listOf(shelf))[target] != shelf) throw SyncProblem("fable_shelf_not_applied")
        }

        val shelved = membership()
        // An edition the user already shelved stays the progress record, as with Hardcover's existing edition.
        // A sibling on Finished only ever leads to "already current" or a held update, so naming it as the record writes nothing.
        val target = listOf("current_reading", "want_to_read", "finished").firstNotNullOfOrNull { shelf -> shelved.entries.firstOrNull { it.value == shelf }?.key } ?: matched
        val shelfBefore = shelved[target]
        val detail = JSONObject().put("bookId", target).put("matchedBookId", matched).put("title", editions.firstOrNull { it.optString("id") == target }?.optString("title").orEmpty())
            .put("matchKind", match.getString("matchKind")).put("existingEditionPreserved", target != matched)
            .put("rawProgress", book.raw("progress")).put("percent", percent).put("finished", finished).put("shelfBefore", shelfBefore ?: JSONObject.NULL)
        if (finished && "finished" in shelved.values) return@authorized detail.put("outcome", "already_current").put("unchanged", true).put("shelfAfter", "finished")
        if ("finished" in shelved.values || "did_not_finish" in shelved.values) throw SyncProblem("fable_status_conflict")
        val progress = get("/api/books/$target/reading_progress")
        val remote = progress.optInt("current_percentage", 0)
        detail.put("remotePercent", remote)
        if (progress.optString("selected_mode") == "page_count" && progress.optInt("current_page") > 0) throw SyncProblem("fable_progress_mode_conflict")
        if (!finished && progress.optString("status") == "finished") throw SyncProblem("fable_status_conflict")
        if (!finished && remote > percent) return@authorized detail.put("outcome", "kept_higher_remote_progress").put("shelfAfter", shelfBefore ?: JSONObject.NULL)
        if (!finished && remote == percent && shelfBefore == "current_reading") return@authorized detail.put("outcome", "already_current").put("unchanged", true).put("shelfAfter", shelfBefore)
        if (shelfBefore != "current_reading") shelve(target, "current_reading")
        if (remote != percent) {
            // Progress went up, so mark the day the user read it. This runs before the progress write: a temporary
            // failure then retries both, and repeating it for a marked day adds no second entry.
            val day = readingDay(book, readAt)
            val date = day.toString()
            detail.put("streakDate", date)
            try {
                post(
                    "/api/v2/reading/streaks/history",
                    JSONObject().put("date", date).put("day_name", day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                        .put("value", true).put("book_ids", JSONArray().put(target))
                )
            } catch (error: HttpProblem) {
                // A rejected streak day must not block progress. A bad token also fails the progress write below,
                // which refreshes it and retries the whole send; temporary failures retry the send later.
                if (temporaryFailure(error)) throw error
                detail.put("streakError", failureReason(error))
            }
            // "reading" with 100% is what Fable itself turns into a finished read.
            post("/api/books/$target/reading_progress", JSONObject().put("status", "reading").put("social_accounts", JSONArray()).put("current_percentage", percent).put("selected_mode", "percentage"))
            if (get("/api/books/$target/reading_progress").optInt("current_percentage", -1) != percent) throw SyncProblem("fable_progress_not_applied")
        }
        if (finished && membership(listOf("finished"))[target] != "finished") shelve(target, "finished")
        detail.put("outcome", "sent").put("shelfAfter", if (finished) "finished" else "current_reading")
    }
}
