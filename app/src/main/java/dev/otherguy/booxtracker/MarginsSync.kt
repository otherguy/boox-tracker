package dev.otherguy.booxtracker

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

private val marginsStatuses = setOf("unread", "want_to_read", "in_progress", "finished", "stopped")

/** One Margins read of a work, with its reading sessions. */
private class MarginsRead(val row: JSONObject, val sessions: List<JSONObject>) {
    val id: String = row.getString("readthrough_id")
    val status: String = row.optString("status")
}

/**
 * Sends one source book's progress to Margins: start a read or adopt a planned one, add a reading session for each
 * whole step of progress, confirm; or finish the read. Margins keeps the user's format on reads it did not start.
 */
class MarginsSync(private val auth: MarginsAuth, private val zero: MarginsZero, private val store: DiagnosticsStore, private val maySend: () -> Boolean) {
    suspend fun send(book: JSONObject, identifiers: BookIdentifiers, expectedAccount: String? = null, readAt: String? = null): JSONObject = auth.authorized { token ->
        val account = marginsAccountId(token)
        if (expectedAccount != null && account != expectedAccount) throw SyncProblem("margins_account_changed")
        suspend fun query(queries: List<ZeroQuery>): ZeroRows {
            coroutineContext.ensureActive()
            return zero.query(token, account, queries).also { coroutineContext.ensureActive() }
        }
        suspend fun push(name: String, payload: JSONObject, sending: () -> Unit = {}) {
            coroutineContext.ensureActive()
            if (!maySend()) throw SyncProblem("service_disabled")
            zero.push(token, account, name, payload, sending)
            coroutineContext.ensureActive()
        }

        val (finished, percent) = sourceStatus(book)
        val match = MarginsMatch({ query(it) }, store).match(book.optString("key").takeIf { it.isNotBlank() }, identifiers)
        val workId = match.getString("workId")
        val readsQuery = ZeroQuery("libraryMembershipReadthroughs", JSONObject().put("userId", account).put("workIds", JSONArray().put(workId)))
        suspend fun reads(rows: ZeroRows? = null): List<MarginsRead> {
            val answer = rows ?: query(listOf(readsQuery))
            val sessions = answer.rows("userspace.reading_sessions_v2").groupBy { it.optString("readthrough_id") }
            return answer.rows("userspace.readthroughs").filter { it.optString("work_id") == workId && it.optString("user_id") == account }
                .map { MarginsRead(it, sessions[it.optString("readthrough_id")].orEmpty()) }
        }

        val first = query(listOf(readsQuery, ZeroQuery("workById", workId)))
        val work = first.rows("catalog.works").firstOrNull { it.optString("work_id") == workId } ?: throw SyncProblem("margins_book_not_found")
        val current = reads(first)
        if (current.any { it.status !in marginsStatuses }) throw SyncProblem("margins_status_unrecognized")
        val day = readingDay(book, readAt)
        val touchedAt = Instant.now().toString()
        val detail = JSONObject().put("title", workTitle(first, work) ?: JSONObject.NULL).put("author", workAuthor(first, workId) ?: JSONObject.NULL).put("workId", workId)
            .put("matchKind", match.getString("matchKind")).put("matchedBy", match.getString("matchedBy"))
            .put("rawProgress", book.raw("progress")).put("percent", percent).put("finished", finished)

        val active = current.filter { it.status == "in_progress" }
        if (active.size > 1) throw SyncProblem("margins_read_history_conflict")
        var reading = active.singleOrNull()
        val shelfBefore = reading?.status ?: current.maxByOrNull { it.row.optDouble("touched_at") }?.status ?: "none"
        detail.put("shelfBefore", shelfBefore)
        fun statusChange(newId: String, readthroughId: String, status: String, startsNew: Boolean) = JSONObject().put("workId", workId).put("readthroughId", readthroughId)
            .put("newReadthroughId", newId).put("status", status).put("startsNewReadthrough", startsNew)
            // False lets Margins add the work to the library only when it is not there yet; true fails when it is missing.
            .put("isInLibrary", false).put("touchedAt", touchedAt).put("todayDate", day.toString()).put("readDateMode", "today")
        fun sentFinished() = detail.put("outcome", "sent").put("shelfAfter", "finished").put("finishDate", day.toString())

        if (reading == null) {
            if (finished && current.any { it.status == "finished" }) return@authorized detail.put("outcome", "already_current").put("unchanged", true).put("shelfAfter", "finished")
            // A finished or stopped read keeps its history; NeoReader reopening a finished book is not a new read.
            if (current.any { it.status == "finished" || it.status == "stopped" }) throw SyncProblem("margins_reread_held")
            val target = if (finished) "finished" else "in_progress"
            val id = UUID.randomUUID().toString()
            // A planned read (unread or want to read) is adopted by Margins instead of adding a second one.
            push("librarySetReadthroughStatus", statusChange(id, current.firstOrNull()?.id ?: id, target, true))
            reading = reads().singleOrNull { it.status == target } ?: throw SyncProblem("margins_status_not_applied")
            detail.put("readthroughId", reading.id)
            if (finished) return@authorized sentFinished()
        } else {
            detail.put("readthroughId", reading.id)
            if (finished) {
                // Margins closes the read itself, with a last session to the end of the book when the read has sessions.
                push("librarySetReadthroughStatus", statusChange(UUID.randomUUID().toString(), reading.id, "finished", false))
                if (reads().none { it.id == reading.id && it.status == "finished" }) throw SyncProblem("margins_status_not_applied")
                return@authorized sentFinished()
            }
        }
        val statusChanged = shelfBefore != "in_progress"
        detail.put("shelfAfter", "currently_reading").put("format", readFormat(reading.row) ?: JSONObject.NULL)
        // Progress logged in pages or locations counts only against the read's own edition, never the work's.
        val readPages = reading.row.optInt("num_pages").takeIf { it > 0 }
        val pages = readPages ?: work.optInt("num_pages").takeIf { it > 0 }
        val latest = reading.sessions.filter { !it.isNull("end_position") }.maxWithOrNull(compareBy<JSONObject> { it.optDouble("session_date") }.thenBy { it.optDouble("created_at") })
        val remote = latest?.let { session ->
            val end = session.optDouble("end_position")
            when (session.optString("unit")) {
                "percentages" -> end.toInt()
                "pages" -> readPages?.let { (end * 100 / it).toInt() }
                "kindle_locations" -> reading.row.optInt("num_locations").takeIf { it > 0 }?.let { (end * 100 / it).toInt() }
                else -> null
            } ?: throw SyncProblem("margins_progress_unit_unsupported")
        } ?: 0
        detail.put("remotePercent", remote).put("remoteUnit", latest?.text("unit") ?: JSONObject.NULL)
        if (remote > percent) return@authorized detail.put("outcome", "kept_higher_remote_progress")
        // Every session appears on the user's profile and reading days, so progress moves in whole steps.
        belowNextStep(detail, percent, remote, statusChanged)?.let { return@authorized it }
        val step = stepPercent(percent)
        // Each session is public, so a value whose earlier session Margins did not show is held instead of added again.
        val posted = "margins.book.posted.${reading.id}"
        if (store.get(posted) == step.toString()) throw SyncProblem("margins_progress_not_applied")
        val sessionId = UUID.randomUUID().toString()
        try {
            // The mark is set only once the session leaves the device; an unsent session can be sent again.
            push("libraryAddReadingProgress", session(sessionId, reading, remote, step, pages, day.toString())) { store.put(posted, step.toString()) }
        } catch (error: Exception) {
            // Margins answered and refused the session, so a retry adds nothing twice.
            if ((error as? SyncProblem)?.code == "margins_push_rejected" || (error as? HttpProblem)?.status == 429) store.delete(posted)
            throw error
        }
        if (reads().none { read -> read.id == reading.id && read.sessions.any { it.optString("reading_session_id") == sessionId } }) throw SyncProblem("margins_progress_not_applied")
        store.delete(posted)
        // A read without a format became an ebook with this session.
        detail.put("format", readFormat(reading.row) ?: "ebook").put("remotePercent", step).put("remoteUnit", "percentages").put("posted", step).put("outcome", "sent")
    }

    /**
     * A percentage session from [remote] to [step]. Pages and time follow Margins' own arithmetic: the page count times
     * the share read, rounded half-even to hundredths, and one minute per page. A read without a format becomes an ebook.
     */
    private fun session(id: String, read: MarginsRead, remote: Int, step: Int, pages: Int?, day: String): JSONObject {
        val pagesRead = pages?.let { BigDecimal(it).multiply(BigDecimal(step - remote)).divide(BigDecimal(100), 2, RoundingMode.HALF_EVEN) }
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds
        return JSONObject().put("id", id).put("sessionDate", day).put("sessionUtcOffsetInSeconds", offset).put("startTime", JSONObject.NULL)
            .put("secondsRead", JSONObject.NULL).put("readthroughId", read.id).put("startPosition", remote).put("endPosition", step).put("progress", step - remote)
            .put("unit", "percentages").put("isAudio", false).put("isEbook", true).put("isPrint", false)
            .put("pagesEquivalent", pagesRead ?: JSONObject.NULL).put("timeEquivalentInSeconds", pagesRead?.multiply(BigDecimal(60))?.setScale(0, RoundingMode.FLOOR)?.toInt() ?: JSONObject.NULL)
            .put("createdAt", Instant.now().toString())
            .apply {
                if (readFormat(read.row) == null) {
                    put(
                        "readthroughUpdate",
                        JSONObject().put("isAudio", false).put("isEbook", true).put("isPrint", false).put("pageCount", pages ?: JSONObject.NULL)
                            .put("locationCount", read.row.positive("num_locations")).put("audiobookDuration", read.row.positive("num_seconds"))
                    )
                }
            }
    }

    private fun JSONObject.positive(name: String): Any = optInt(name).takeIf { it > 0 } ?: JSONObject.NULL

    private fun readFormat(read: JSONObject): String? = when {
        read.optBoolean("is_ebook") -> "ebook"
        read.optBoolean("is_print") -> "print"
        read.optBoolean("is_audio") -> "audiobook"
        else -> null
    }

    private fun workTitle(rows: ZeroRows, work: JSONObject): String? = rows.rows("catalog.work_titles").filter { it.optString("work_id") == work.optString("work_id") }
        .sortedByDescending { it.optBoolean("is_primary") }.firstNotNullOfOrNull { it.text("title") } ?: work.text("original_title")

    private fun workAuthor(rows: ZeroRows, workId: String): String? {
        val contributor = rows.rows("catalog.work_contributors").filter { it.optString("work_id") == workId }.minByOrNull { it.optInt("position") }?.optString("contributor_id") ?: return null
        return rows.rows("catalog.contributor_names").filter { it.optString("contributor_id") == contributor }.sortedByDescending { it.optBoolean("is_primary") }.firstNotNullOfOrNull { it.text("name") }
    }
}
