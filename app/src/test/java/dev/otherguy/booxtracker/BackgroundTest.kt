package dev.otherguy.booxtracker

import android.Manifest
import android.content.Intent
import android.database.MatrixCursor
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class BackgroundTest {

    @After fun releaseApplicationResources() = runBlocking {
        val diagnostics = (RuntimeEnvironment.getApplication() as ReadingSyncApp).diagnostics
        diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        diagnostics.store.close()
        closeWorkDatabase()
    }

    private suspend fun awaitEvent(app: ReadingSyncApp, predicate: (JSONObject) -> Boolean) {
        withTimeout(5000) {
            while (!app.diagnostics.store.events().any(predicate)) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    @Test fun cleanStopIsNotAnInterruptionButUnfinishedRunIs() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        val d = app.diagnostics
        d.ensureRecovered()
        d.startRun("scheduled", "clean")
        d.stopRun("scheduled", "clean", "user_stop")
        d.recover()
        assertFalse(d.store.events().any { it.optString("kind") == "interruption_detected" })
        d.store.put("active.scheduled", "interrupted")
        d.recover()
        assertTrue(d.store.events().any { it.optString("kind") == "interruption_detected" && it.optString("runId") == "interrupted" })
    }

    @Test fun collectionIsOneUniqueFifteenMinuteWork() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        scheduleCollection(app)
        scheduleCollection(app)
        val manager = WorkManager.getInstance(app)
        val work = manager.getWorkInfosForUniqueWork(WORK_NAME).get()
        assertEquals(1, work.size)
        assertEquals(900_000, periodicRequest().workSpec.intervalDuration)
    }

    @Test fun exportKeepsTheErrorClassAndDropsItsMessage() {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        app.diagnostics.event("manual", "error", detail = JSONObject().put("error", JSONObject().put("class", "SecurityException").put("message", "Authorization: Bearer SECRET /目录/private/file")))
        val exported = buildExport(app.diagnostics).toString()
        assertFalse(exported.contains("SECRET"))
        assertFalse(exported.contains("/目录/"))
        assertTrue(exported.contains("SecurityException"))
    }

    @Test fun nullFieldsAndUnmatchableRecordsDoNotProduceFalseChanges() {
        MatrixCursor(arrayOf("progress", "readingStatus")).use { cursor ->
            cursor.addRow(arrayOf(null, "999"))
            cursor.moveToFirst()
            val record = readBook(cursor)
            assertEquals("null", record.getJSONObject("progress").getString("state"))
            assertTrue(record.isNull("percentage"))
            assertTrue(record.isNull("key"))
            fun snapshot(vararg books: JSONObject) = JSONObject().put("books", JSONArray(books.toList()))
            fun book(key: String?, progress: String) = JSONObject().put("key", key ?: JSONObject.NULL).put("progress", JSONObject().put("raw", progress))
            assertEquals(1, changes(snapshot(book("a", "1/2")), snapshot(book("a", "2/2"))).length())
            // A record without a key, or with a key another record shares, cannot be followed across snapshots.
            val before = snapshot(record, book("shared", "1/2"), book("shared", "1/3"))
            val after = snapshot(book(null, "2/2"), book("shared", "2/2"), book("shared", "2/3"))
            assertEquals(0, changes(before, after).length())
        }
    }

    @Test fun cancellationDoesNotAllowAnOverlappingProviderRead() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        val provider = FixtureProvider().apply {
            entered = java.util.concurrent.CountDownLatch(1)
            release = java.util.concurrent.CountDownLatch(1)
        }
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val first = async(Dispatchers.IO) { app.diagnostics.collect("manual", "blocked") }
        try {
            assertTrue(provider.entered!!.await(5, java.util.concurrent.TimeUnit.SECONDS))
            first.cancelAndJoin()
            app.diagnostics.collect("manual", "second")
            assertEquals(1, provider.calls.get())
            assertTrue(app.diagnostics.store.events().any { it.optString("kind") == "query_skipped" })
        } finally {
            provider.release!!.countDown()
            first.cancelAndJoin()
        }
    }
}
