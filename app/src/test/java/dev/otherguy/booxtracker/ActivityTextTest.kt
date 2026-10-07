package dev.otherguy.booxtracker

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ActivityTextTest {
    private var clock = 0

    private fun event(kind: String, source: String = "scheduled", vararg fields: Pair<String, Any?>) = JSONObject()
        .put("timestamp", "2026-10-07T09:${"%02d".format(clock++)}:00Z").put("source", source).put("kind", kind)
        .put("runId", "run-$clock").put("issue", false).apply { fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }

    private val book = JSONObject().put("key", "id:1").put("title", "In the Blood").put("percentage", 52)

    /** Events newest first, as the store returns them; times show as the minutes of their timestamp. */
    private fun rows(vararg newestFirst: JSONObject) = activityEntries(newestFirst.toList()).map { eventText(it) { time -> time.substring(14, 16) } }

    private fun row(vararg newestFirst: JSONObject) = rows(*newestFirst).single()

    @Test fun unchangedChecksGroupAcrossRunsAndHideCompletedRuns() {
        val checks = (0 until 3).map { event("query", fields = arrayOf("outcome" to "success", "unchanged" to true, "selected" to book)) }
        val runs = (0 until 3).map { event("run", fields = arrayOf("reason" to "completed")) }
        val text = row(runs[2], checks[2], runs[1], checks[1], runs[0], checks[0])
        assertEquals(EventText("No change", "Background · In the Blood · 52% · 3 checks since 00", "•"), text)
    }

    @Test fun aCheckFromAnOlderVersionShowsItsFullBookRecord() {
        val legacy = JSONObject().put("key", "id:1").put("title", JSONObject().put("state", "value").put("raw", "In the Blood")).put("percentage", 51.66)
        val text = row(event("query", "foreground", "outcome" to "success", "unchanged" to false, "selected" to legacy, "changes" to org.json.JSONArray()))
        assertEquals(EventText("Checked NeoReader", "App open · In the Blood · 51.66%", "•"), text)
    }

    @Test fun aCheckWithChangesCountsThem() {
        val text = row(event("query", "manual", "outcome" to "success", "unchanged" to false, "selected" to book, "changeCount" to 3))
        assertEquals(EventText("Library changed", "Manual · In the Blood · 52% · 3 changes", "•"), text)
    }

    @Test fun anUnavailableProviderIsAnIssue() {
        val text = row(event("query", fields = arrayOf("outcome" to "provider_unavailable", "issue" to true)))
        assertEquals(EventText("NeoReader unavailable", "Background · provider unavailable", "❌"), text)
    }

    @Test fun syncResultsDescribeTheProgressSent() {
        assertEquals(
            EventText("Synced to Hardcover", "Background · In the Blood · 254 of 480 pages", "✅"),
            row(event("hardcover_sync", fields = arrayOf("outcome" to "sent", "title" to "In the Blood", "progressPages" to 254, "editionPages" to 480)))
        )
        assertEquals(
            EventText("Synced to Fable", "Background delivery · In the Blood · 52%", "✅"),
            row(event("fable_sync", "delivery", "outcome" to "sent", "title" to "In the Blood", "percent" to 52))
        )
        assertEquals(
            EventText("Finished on Hardcover", "Manual · In the Blood · Finished", "✅"),
            row(event("hardcover_sync", "manual", "outcome" to "sent", "title" to "In the Blood", "finished" to true))
        )
        assertEquals(
            EventText("Hardcover kept its progress", "Background · In the Blood · 260 of 480 pages, higher than NeoReader", "⚠"),
            row(event("hardcover_sync", fields = arrayOf("outcome" to "kept_higher_remote_progress", "title" to "In the Blood", "remoteProgressPages" to 260, "editionPages" to 480)))
        )
        assertEquals(
            EventText("Fable already up to date", "Background · In the Blood · 52%", "✅"),
            row(event("fable_sync", fields = arrayOf("outcome" to "already_current", "title" to "In the Blood", "percent" to 52)))
        )
    }

    @Test fun aWaitingSendIsNeutral() {
        val text = row(event("hardcover_sync", "delivery", "outcome" to "pending", "reason" to "hardcover_http_503"))
        assertEquals(EventText("Hardcover unreachable", "Background delivery · Will retry · hardcover http 503", "•"), text)
    }

    @Test fun repeatedHeldUpdatesBecomeOneRow() {
        val held = (0 until 3).map { event("hardcover_sync", fields = arrayOf("outcome" to "held", "reason" to "edition_missing_page_count", "issue" to true)) }
        val runs = (0 until 3).map { event("run", fields = arrayOf("reason" to "completed")) }
        val text = row(runs[2], held[2], runs[1], held[1], runs[0], held[0])
        assertEquals(EventText("Hardcover update held", "Background · edition missing page count · 3 times since 00", "❌"), text)
    }

    @Test fun repeatedHeldUpdatesBetweenChecksBecomeOneRowAndChecksAnother() {
        val events = (0 until 3).flatMap {
            listOf(
                event("query", fields = arrayOf("outcome" to "success", "unchanged" to true, "selected" to book)),
                event("hardcover_sync", fields = arrayOf("outcome" to "held", "reason" to "edition_missing_page_count", "issue" to true))
            )
        }.reversed()
        assertEquals(
            listOf(
                EventText("Hardcover update held", "Background · edition missing page count · 3 times since 01", "❌"),
                EventText("No change", "Background · In the Blood · 52% · 3 checks since 00", "•")
            ),
            rows(*events.toTypedArray())
        )
    }

    @Test fun aSyncResultEndsTheGroupsBeforeIt() {
        val older = event("hardcover_sync", fields = arrayOf("outcome" to "held", "reason" to "a", "issue" to true))
        val sent = event("hardcover_sync", fields = arrayOf("outcome" to "sent", "title" to "In the Blood", "percent" to 52))
        val newer = event("hardcover_sync", fields = arrayOf("outcome" to "held", "reason" to "a", "issue" to true))
        assertEquals(3, rows(newer, sent, older).size)
    }

    @Test fun aCancelledRunSaysItStopped() {
        assertEquals("Background delivery stopped", row(event("run", "delivery", "reason" to "worker_cancelled", "issue" to true)).title)
        assertEquals("Background run stopped", row(event("run", fields = arrayOf("reason" to "worker_cancelled", "issue" to true))).title)
    }

    @Test fun differentIssuesStaySeparate() {
        val first = event("hardcover_sync", fields = arrayOf("outcome" to "held", "reason" to "a", "issue" to true))
        val second = event("hardcover_sync", fields = arrayOf("outcome" to "held", "reason" to "b", "issue" to true))
        assertEquals(2, rows(second, first).size)
    }

    @Test fun queueSignInAndSettingRowsUseTheServiceName() {
        assertEquals(EventText("Queued for Fable", "Background · In the Blood · 52%", "•"), row(event("queued", fields = arrayOf("service" to "fable", "title" to "In the Blood", "percentage" to 52))))
        assertEquals(EventText("Logged out of Fable", "Manual · 2 queued updates deleted", "•"), row(event("fable_connection", "manual", "outcome" to "logged_out", "deletedUpdates" to 2)))
        assertEquals(EventText("Signed in to Hardcover", "Manual", "✅"), row(event("hardcover_connection", "manual", "outcome" to "connected")))
        assertEquals(EventText("Hardcover sign-in failed", "Manual · identity failed", "❌"), row(event("hardcover_connection", "manual", "outcome" to "held", "reason" to "identity_failed", "issue" to true)))
        assertEquals(EventText("Fable turned Off", "Manual", "•"), row(event("fable_setting", "manual", "enabled" to false)))
    }

    @Test fun failedRunsShowAndOlderRunMarkersStayHidden() {
        val failed = event("run", fields = arrayOf("reason" to "failed", "issue" to true))
        assertEquals(listOf(EventText("Background run failed", "Background · failed", "❌")), rows(event("start"), event("hardcover_sync_start"), event("stop", fields = arrayOf("reason" to "completed")), failed))
    }

    @Test fun anUnknownEventShowsItsFields() {
        assertEquals(EventText("Mystery event", "System · odd · because", "•"), row(event("mystery_event", "system", "outcome" to "odd", "reason" to "because")))
    }
}
