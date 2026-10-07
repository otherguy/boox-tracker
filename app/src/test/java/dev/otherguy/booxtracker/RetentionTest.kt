package dev.otherguy.booxtracker

import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class RetentionTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val day = 24 * hour

    @After fun close() = runBlocking {
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        store.close()
        closeWorkDatabase()
    }

    private fun event(kind: String, age: Long, issue: Boolean = false, outcome: String? = null) = JSONObject()
        .put("kind", kind).put("wallMs", now - age).put("issue", issue).apply { outcome?.let { put("outcome", it) } }

    private fun kinds() = store.events().map { listOf(it.optString("kind"), it.optString("outcome")).filter(String::isNotEmpty).joinToString(":") }

    @Test fun theCountCapKeepsTheNewestEvents() {
        repeat(DiagnosticsStore.MAX_EVENTS + 5) { store.append(event("query", hour).put("index", it)) }
        store.prune(now, null)
        val kept = store.events()
        assertEquals(DiagnosticsStore.MAX_EVENTS, kept.size)
        assertEquals(5, kept.last().getInt("index"))
        assertEquals(DiagnosticsStore.MAX_EVENTS + 4, kept.first().getInt("index"))
    }

    @Test fun theAgeCapRemovesEveryEventOlderThanThirtyDays() {
        store.append(event("hardcover_sync", 31 * day, issue = true, outcome = "held"))
        store.append(event("hardcover_connection", 31 * day, outcome = "connected"))
        store.append(event("hardcover_sync", 29 * day, outcome = "sent"))
        store.append(JSONObject().put("kind", "undated"))
        store.append(JSONObject().put("kind", "query").put("timestamp", java.time.Instant.ofEpochMilli(now - 40 * day).toString()))
        store.prune(now, null)
        assertEquals(listOf("undated", "hardcover_sync:sent"), kinds())
    }

    @Test fun aSyncRemovesOlderRoutineEventsAndKeepsResultsAndIssues() {
        listOf("run", "query", "queued", "start", "stop", "hardcover_sync_start", "fable_sync_start").forEach { store.append(event(it, 72 * hour)) }
        store.append(event("hardcover_sync", 72 * hour, outcome = "pending"))
        store.append(event("hardcover_sync", 72 * hour, outcome = "sent"))
        store.append(event("hardcover_sync", 72 * hour, issue = true, outcome = "held"))
        store.append(event("query", 72 * hour, issue = true, outcome = "provider_unavailable"))
        store.append(event("hardcover_setting", 72 * hour))
        store.append(event("query", 61 * hour))
        store.prune(now, now - 60 * hour)
        assertEquals(listOf("hardcover_setting", "query:provider_unavailable", "hardcover_sync:held", "hardcover_sync:sent"), kinds())
    }

    @Test fun routineEventsFromTheLastTwoDaysSurviveASync() {
        store.append(event("query", 50 * hour))
        store.append(event("run", 47 * hour))
        store.append(event("query", 47 * hour))
        store.prune(now, now - hour)
        assertEquals(listOf("query", "run"), kinds())
    }

    @Test fun routineEventsAfterTheLastSyncAreKept() {
        store.append(event("query", 72 * hour))
        store.append(event("query", 60 * hour))
        store.prune(now, now - 65 * hour)
        assertEquals(1, store.events().size)
        assertEquals(now - 60 * hour, store.events().single().getLong("wallMs"))
    }

    @Test fun pruningNeverTouchesSyncState() {
        store.put("outbox.hardcover.1.book", "{}")
        store.put("snapshot", "{}")
        repeat(1_600) { store.append(event("query", 40 * day)) }
        assertEquals(1_600, store.prune(now, now))
        assertTrue(store.events().isEmpty())
        assertEquals("{}", store.get("outbox.hardcover.1.book"))
        assertEquals("{}", store.get("snapshot"))
    }

    @Test fun theCountCapRemovesRoutineEventsBeforeIssues() {
        store.append(event("hardcover_sync", 5 * day, issue = true, outcome = "held"))
        repeat(DiagnosticsStore.MAX_EVENTS) { store.append(event("query", hour)) }
        store.prune(now, null)
        assertEquals(DiagnosticsStore.MAX_EVENTS, store.events().size)
        assertTrue(store.events().any { it.optString("outcome") == "held" })
    }

    @Test fun aRowThatIsNotJsonDoesNotStopPruning() {
        store.writableDatabase.execSQL("INSERT INTO events (payload) VALUES ('not json')")
        store.append(event("query", 40 * day))
        assertEquals(1, store.prune(now, null))
        assertEquals(
            "not json",
            store.readableDatabase.rawQuery("SELECT payload FROM events", null).use {
                it.moveToFirst()
                it.getString(0)
            }
        )
    }

    @Test fun aStoppingRunKeepsTheMarkerOfANewerRun() = runBlocking {
        val diagnostics = app.diagnostics
        diagnostics.ensureRecovered()
        diagnostics.startRun("scheduled", "old")
        diagnostics.startRun("scheduled", "new")
        diagnostics.stopRun("scheduled", "old", "worker_cancelled", true)
        assertEquals("new", JSONObject(store.get("active.scheduled")!!).getString("id"))
    }

    @Test fun aWorkerRunWritesOneRunEventAndPrunes() = runBlocking {
        val diagnostics = app.diagnostics
        diagnostics.ensureRecovered()
        store.append(event("hardcover_sync", 31 * day, outcome = "sent"))
        diagnostics.startRun("scheduled", "run-1")
        diagnostics.stopRun("scheduled", "run-1", "completed")
        val run = store.events().single()
        assertEquals("run", run.getString("kind"))
        assertEquals("run-1", run.getString("runId"))
        assertEquals("completed", run.getString("reason"))
        assertTrue(run.has("startedAt"))
        assertTrue(run.has("durationMs"))
        assertFalse(run.getBoolean("appVisibleAtStart"))
    }

    @Test fun anUnfinishedRunReportsWhenItStarted() = runBlocking {
        val diagnostics = app.diagnostics
        diagnostics.ensureRecovered()
        diagnostics.startRun("delivery", "lost")
        diagnostics.recover()
        val interruption = store.events().single { it.optString("kind") == "interruption_detected" }
        assertEquals("lost", interruption.getString("runId"))
        assertTrue(interruption.has("startedAt"))
    }

    @Test fun theCheckEventKeepsABookSummaryAndTheLastCheckKeepsTheFullRecord() = runBlocking {
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        app.diagnostics.collect("scheduled", "periodic", "job")
        val logged = store.events().single { it.optString("kind") == "query" }
        assertFalse(logged.has("columns"))
        val selected = logged.getJSONObject("selected")
        assertEquals(setOf("key", "title", "authors", "percentage", "readingStatus", "lastAccess"), selected.keys().asSequence().toSet())
        assertEquals("Synthetic Book", selected.getString("title"))
        val last = JSONObject(store.get("lastCheck")!!)
        assertTrue(last.has("columns"))
        assertTrue(last.getJSONObject("selected").has("ISBN"))
    }

    @Test fun theCheckReturnedToTheSyncKeepsTheFullRecordAndTheEventTime() = runBlocking {
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        val returned = app.diagnostics.collect("scheduled", "periodic", "job")!!
        assertTrue(returned.getJSONObject("selected").has("ISBN"))
        assertTrue(returned.has("columns"))
        assertEquals(store.events().single { it.optString("kind") == "query" }.getString("timestamp"), returned.getString("timestamp"))
    }

    @Test fun anIssueCheckKeepsTheFullBookRecord() {
        val book = JSONObject().put("key", "id:1").put("progress", JSONObject().put("state", "unreadable")).put("progressProblem", "unreadable").put("percentage", JSONObject.NULL)
        val logged = loggedCheck(JSONObject().put("issue", true).put("selected", book).put("columns", JSONArray()))
        assertEquals("unreadable", logged.getJSONObject("selected").getJSONObject("progress").getString("state"))
        assertFalse(logged.has("columns"))
    }

    @Test fun theCheckEventKeepsTheFirstChangesAndTheirCount() {
        val changes = JSONArray().apply { repeat(30) { put(JSONObject().put("key", "book-$it").put("kind", "added")) } }
        val logged = loggedCheck(JSONObject().put("changes", changes).put("selected", JSONObject.NULL))
        assertEquals(25, logged.getJSONArray("changes").length())
        assertEquals(30, logged.getInt("changeCount"))
        assertTrue(logged.isNull("selected"))
    }

    @Test fun aNewExportReplacesThePreviousExportFiles() {
        exportIntent(app, buildExport(app.diagnostics))
        exportIntent(app, buildExport(app.diagnostics))
        assertEquals(2, File(app.cacheDir, "exports").listFiles()!!.size)
    }
}
