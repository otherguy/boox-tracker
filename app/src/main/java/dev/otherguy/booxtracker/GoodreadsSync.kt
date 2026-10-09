package dev.otherguy.booxtracker

import java.time.LocalDate
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

private val goodreadsShelves = setOf("to-read", "currently-reading", "read", "did-not-finish")

/** Next.js' serialized router tree for `/review/edit/[id]`, whose dynamic segment is the edition id. */
private fun reviewEditTree(bookId: String) = "[\"\",{\"children\":[\"review\",{\"children\":[\"edit\",{\"children\":[[\"id\",\"$bookId\",\"d\"],{\"children\":[\"__PAGE__\",{},null,null]},null,null]}]},null,null]},null,null,true]"

private fun JSONObject.day(name: String): LocalDate? = optJSONObject(name)?.let { date ->
    runCatching { LocalDate.of(date.getInt("year"), date.getInt("month"), date.getInt("day")) }.getOrNull()
}

/**
 * Sends one source book's progress to Goodreads: shelve, post the percentage when it moved far enough, confirm; or
 * shelve the book Read and set its finish date. The edition the user shelved keeps receiving the book's progress.
 */
class GoodreadsSync(private val http: GoodreadsHttp, private val store: DiagnosticsStore, private val maySend: () -> Boolean) {
    private suspend fun get(path: String): GoodreadsPage {
        coroutineContext.ensureActive()
        return http.get(path).also { coroutineContext.ensureActive() }
    }

    private suspend fun editions(workId: String): ShelvedEditions {
        coroutineContext.ensureActive()
        return goodreadsEditions(http.signedInPage("/review/user_works/$workId").text).also { coroutineContext.ensureActive() }
    }

    /** Stops before a write when the sync was cancelled or the service was turned off. */
    private suspend fun ensureMaySend() {
        coroutineContext.ensureActive()
        if (!maySend()) throw SyncProblem("service_disabled")
    }

    private suspend fun post(path: String, form: Map<String, String>, csrf: String, referer: String) {
        ensureMaySend()
        http.post(path, form, csrf, referer)
        coroutineContext.ensureActive()
    }

    suspend fun send(book: JSONObject, identifiers: BookIdentifiers, expectedAccount: String? = null, readAt: String? = null): JSONObject {
        val (finished, percent) = sourceStatus(book)
        val match = GoodreadsMatcher({ get(it) }, store).match(book.optString("key").takeIf { it.isNotBlank() }, identifiers)
        // Any edition the identifiers confirmed is the ebook's edition; the first one stands in when none is shelved.
        val confirmed = match.optJSONArray("editions")?.let { ids -> (0 until ids.length()).map(ids::getString) }.orEmpty()
        var matched = match.getString("bookId")
        val workId = match.getString("workId")
        var shelved = editions(workId)
        if (expectedAccount != null && shelved.account != expectedAccount) throw SyncProblem("goodreads_account_changed")
        // Goodreads keeps one exclusive shelf per edition; two editions of one book on different shelves leave the book's state unclear.
        if (shelved.editions.map { it.shelf }.distinct().size > 1) throw SyncProblem("goodreads_status_conflict")
        val current = shelved.editions.firstOrNull { it.bookId == matched || it.bookId in confirmed } ?: shelved.editions.firstOrNull()
        if (current != null && current.bookId in confirmed) matched = current.bookId
        val status = current?.let { it.shelf?.takeIf(goodreadsShelves::contains) ?: throw SyncProblem("goodreads_status_unrecognized") }
        val target = current?.bookId ?: matched
        val detail = JSONObject().put("bookId", target).put("matchedBookId", matched).put("workId", workId).put("title", current?.title ?: match.opt("title"))
            .put("matchKind", match.getString("matchKind")).put("existingEditionPreserved", target != matched)
            .put("rawProgress", book.raw("progress")).put("percent", percent).put("finished", finished).put("shelfBefore", status?.replace('-', '_') ?: JSONObject.NULL)
        val referer = "/review/user_works/$workId"
        suspend fun shelve(name: String) {
            post("/shelf/add_to_shelf", mapOf("book_id" to target, "name" to name, "a" to ""), shelved.csrf, referer)
            shelved = editions(workId)
        }
        if (finished && status == "read") return detail.put("outcome", "already_current").put("unchanged", true).put("shelfAfter", "read")
        // A finished or abandoned edition keeps its history; NeoReader reopening a finished book is not a new read.
        if (status == "read" || status == "did-not-finish") throw SyncProblem("goodreads_status_conflict")
        if (finished) {
            val before = try {
                goodreadsReviewEditor(get("/review/edit/$target").text)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            shelve("read")
            if (shelved.edition(target)?.shelf != "read") throw SyncProblem("goodreads_status_not_applied")
            detail.put("outcome", "sent").put("shelfAfter", "read")
            val day = readingDay(book, readAt)
            // The Read shelf is the finish; a date the editor cannot take is reported, never a reason to hold.
            try {
                finishDate(target, day, before)
                detail.put("finishDate", day.toString())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                detail.put("finishDateError", failureReason(error))
            }
            return detail
        }
        val shelfChanged = status != "currently-reading"
        if (shelfChanged) {
            shelve("currently-reading")
            if (shelved.edition(target)?.shelf != "currently-reading") throw SyncProblem("goodreads_shelf_not_applied")
        }
        val remote = shelved.edition(target)?.percent ?: throw SyncProblem("goodreads_page_unrecognized")
        detail.put("remotePercent", remote).put("shelfAfter", "currently_reading")
        if (remote > percent) return detail.put("outcome", "kept_higher_remote_progress")
        // Every update is a post in friends' feeds, so progress moves in whole steps.
        belowNextStep(detail, percent, remote, shelfChanged)?.let { return it }
        val step = stepPercent(percent)
        // Each post is public, so a value whose earlier post Goodreads did not show is held instead of posted again.
        val posted = "goodreads.book.posted.$target"
        if (store.get(posted) == step.toString()) throw SyncProblem("goodreads_progress_not_applied")
        store.put(posted, step.toString())
        try {
            post("/user_status.json", mapOf("user_status[book_id]" to target, "user_status[percent]" to step.toString(), "user_status[body]" to ""), shelved.csrf, referer)
        } catch (error: HttpProblem) {
            // Goodreads answered and refused the post, so a retry posts nothing twice.
            store.delete(posted)
            throw error
        }
        shelved = editions(workId)
        if (shelved.edition(target)?.percent != step) throw SyncProblem("goodreads_progress_not_applied")
        store.delete(posted)
        return detail.put("remotePercent", step).put("posted", step).put("outcome", "sent")
    }

    /**
     * Sets [day] as the end of the read that the Read shelf just finished, through the review editor's Server Action.
     * The read is the editor's open session, or the one that [before] showed open or did not show. No session is added.
     */
    private suspend fun finishDate(target: String, day: LocalDate, before: ReviewEditor?) {
        val path = "/review/edit/$target"
        val page = get(path)
        val editor = goodreadsReviewEditor(page.text)
        // The form posts the review text back; without reading it, an existing review would be replaced.
        if (editor.hasReview) throw SyncProblem("goodreads_review_present")
        val sessions = editor.sessions.objects()
        if (sessions.any { it.day("endedDate") == day }) return
        val earlier = before?.sessions?.objects()?.associate { it.optString("id") to it.day("endedDate") }
        val open = sessions.indices.filter { sessions[it].day("endedDate") == null && sessions[it].optString("state") == "READING" }
        val candidates = when {
            open.isNotEmpty() -> open
            earlier == null -> emptyList()
            else -> sessions.indices.filter { earlier[sessions[it].optString("id")] == null }
        }
        val index = candidates.singleOrNull() ?: throw SyncProblem("goodreads_finish_session_unrecognized")
        if (sessions[index].day("startedDate")?.isAfter(day) == true) throw SyncProblem("goodreads_finish_before_start")
        val ended = JSONObject().put("year", day.year).put("month", day.monthValue).put("day", day.dayOfMonth).put("__typename", "NullableDate")
        val updated = JSONArray(sessions.mapIndexed { i, session -> if (i == index) JSONObject(session.toString()).put("endedDate", ended) else session })
        val payload = JSONObject().put("bookId", editor.bookId).put("reviewText", "").put("spoilerStatus", false).put("isOwnedEdition", editor.ownedEdition)
            .put("privateNotes", editor.privateNotes).put("postToBlog", false).put("addToUpdateFeed", false).put("readingSessions", updated).put("initialReadingSessions", editor.sessions)
        val actionId = editorAction(page.text, path)
        ensureMaySend()
        val response = http.action(path, actionId, reviewEditTree(target), JSONArray().put(payload).put("/review/edit/[id]").toString())
        if (!serverActionSucceeded(response)) throw SyncProblem("goodreads_finish_date_not_applied")
        // Only the finished read's end date may differ; any other change to the reads or notes is reported.
        fun reads(sessions: JSONArray) = sessions.objects().associate { it.optString("id") to (it.day("startedDate") to it.day("endedDate")) }
        val saved = goodreadsReviewEditor(get(path).text)
        if (reads(saved.sessions) != reads(updated) || saved.privateNotes != editor.privateNotes) throw SyncProblem("goodreads_finish_date_not_applied")
    }

    /**
     * The review form's Server Action id. It is compiled into one of the page's chunk scripts and changes with each
     * Goodreads build, so it is found by reading the chunks from the last one back, and kept for that set of chunks.
     */
    private suspend fun editorAction(html: String, referer: String): String {
        val paths = nextChunkPaths(html).ifEmpty { throw SyncProblem("goodreads_editor_unrecognized") }
        val chunks = digest(paths.joinToString("\n"))
        store.get("goodreads.editorAction")?.let(::JSONObject)?.takeIf { it.optString("chunks") == chunks }?.text("id")?.let { return it }
        for (path in paths.asReversed()) {
            coroutineContext.ensureActive()
            val id = serverActionId(http.script(path, referer), "submitReviewFormAction") ?: continue
            store.put("goodreads.editorAction", JSONObject().put("chunks", chunks).put("id", id).toString())
            return id
        }
        throw SyncProblem("goodreads_editor_action_missing")
    }
}
