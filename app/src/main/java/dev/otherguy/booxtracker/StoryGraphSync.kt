package dev.otherguy.booxtracker

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

private val statuses = setOf("to_read", "currently_reading", "read", "paused", "did_not_finish", "rereading")

/** StoryGraph's status label as a stored key: "currently reading" becomes `currently_reading`. */
internal fun statusKey(label: String?): String? = label?.trim()?.lowercase()?.replace(Regex("[\\s-]+"), "_")?.takeIf { it.isNotEmpty() }

/** Sends one source book's progress to StoryGraph: shelve, write the percentage, confirm; or mark the book Read. */
class StoryGraphSync(private val http: StoryGraphHttp, private val store: DiagnosticsStore, private val maySend: () -> Boolean) {
    suspend fun send(book: JSONObject, identifiers: BookIdentifiers, expectedAccount: String? = null): JSONObject {
        suspend fun get(path: String, frame: String? = null): String {
            coroutineContext.ensureActive()
            val result = http.get(path, frame)
            coroutineContext.ensureActive()
            return result
        }
        suspend fun page(id: String) = bookPage(get("/books/$id"), id)
        suspend fun post(path: String, form: Map<String, String>, csrf: String, referer: String) {
            coroutineContext.ensureActive()
            if (!maySend()) throw SyncProblem("service_disabled")
            http.post(path, form, csrf, referer)
            coroutineContext.ensureActive()
        }
        val (finished, percent) = sourceStatus(book)
        val account = http.account()
        if (expectedAccount != null && account != expectedAccount) throw SyncProblem("storygraph_account_changed")
        val match = StoryGraphMatcher({ path, frame -> get(path, frame) }, store).match(book.optString("key").takeIf { it.isNotBlank() }, identifiers)
        val matched = match.getString("bookId")
        var current = page(matched)
        // An unshelved edition names the one the user shelved instead; that edition keeps the progress, as with Hardcover and Fable.
        val other = current.otherEditionId
        if (current.status == null && other != null) current = page(other)
        val target = current.id
        val status = statusKey(current.status)?.also { if (it !in statuses) throw SyncProblem("storygraph_status_unrecognized") }
        val detail = JSONObject().put("bookId", target).put("matchedBookId", matched).put("title", current.title ?: JSONObject.NULL)
            .put("matchKind", match.getString("matchKind")).put("existingEditionPreserved", target != matched)
            .put("rawProgress", book.raw("progress")).put("percent", percent).put("finished", finished).put("shelfBefore", status ?: JSONObject.NULL)
        val referer = "/books/$target"
        if (finished && status == "read") return detail.put("outcome", "already_current").put("unchanged", true).put("shelfAfter", "read")
        // A finished, abandoned, or reread edition keeps its history; NeoReader reopening a finished book is not a new read.
        if (status in setOf("read", "did_not_finish", "rereading")) throw SyncProblem("storygraph_status_conflict")
        if (finished) {
            post("/update-status.js?book_id=$target&status=read", emptyMap(), current.csrf, referer)
            if (statusKey(page(target).status) != "read") throw SyncProblem("storygraph_status_not_applied")
            return detail.put("outcome", "sent").put("shelfAfter", "read")
        }
        if (status != "currently_reading") {
            post("/update-status.js?book_id=$target&status=currently-reading", emptyMap(), current.csrf, referer)
            current = page(target)
            if (statusKey(current.status) != "currently_reading") throw SyncProblem("storygraph_shelf_not_applied")
        }
        val progress = current.progress ?: throw SyncProblem("storygraph_page_unrecognized")
        val remote = progress.lastReachedPercent ?: progress.barPercent ?: throw SyncProblem("storygraph_page_unrecognized")
        detail.put("remotePercent", remote).put("shelfAfter", "currently_reading")
        if (remote > percent) return detail.put("outcome", "kept_higher_remote_progress")
        if (remote == percent && status == "currently_reading") return detail.put("outcome", "already_current").put("unchanged", true)
        if (remote != percent) {
            // Always a percentage, whatever unit the user's form was in; StoryGraph derives the page from it.
            post(
                "/update-progress",
                mapOf(
                    "read_status[progress_number]" to percent.toString(),
                    "read_status[progress_type]" to "percentage",
                    "read_status[progress_minutes]" to "",
                    "read_status[book_num_of_pages]" to progress.bookNumOfPages,
                    "read_status[last_reached_pages]" to progress.lastReachedPages,
                    "read_status[last_reached_percent]" to progress.lastReachedPercent?.toString().orEmpty(),
                    "book_id" to target,
                    "on_book_page" to "true",
                    "commit" to "Save"
                ),
                current.csrf,
                referer
            )
            if (page(target).progress?.lastReachedPercent != percent) throw SyncProblem("storygraph_progress_not_applied")
        }
        return detail.put("outcome", "sent")
    }
}
