package org.readingsync.diagnostic

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
@Config(sdk = [34])
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
class BackgroundTest {
    @After fun releaseApplicationResources() = runBlocking {
        val diagnostics = (RuntimeEnvironment.getApplication() as ReadingSyncApp).diagnostics
        diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        diagnostics.store.close()
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
        d.startRun("observation", "clean")
        d.stopRun("observation", "clean", "user_stop")
        d.recover()
        assertFalse(d.store.events().any { it.optString("kind") == "interruption_detected" })
        d.store.put("active.observation", "interrupted")
        d.recover()
        assertTrue(d.store.events().any { it.optString("kind") == "interruption_detected" && it.optString("runId") == "interrupted" })
    }

    @Test fun uniquePeriodicWorkAndCancellationUseShippingPath() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        setBackground(app, true)
        setBackground(app, true)
        val manager = WorkManager.getInstance(app)
        val work = manager.getWorkInfosForUniqueWork(WORK_NAME).get()
        assertEquals(1, work.size)
        assertEquals(900_000, periodicRequest().workSpec.intervalDuration)
        setBackground(app, false)
        assertEquals("false", app.diagnostics.store.get("background"))
        assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfosForUniqueWork(WORK_NAME).get().single().state)
    }

    @Test fun selectedChangesAndExportRetainEvidenceWithoutSecrets() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        app.visible = false
        app.sessionActive = true
        app.diagnostics.collect("scheduled", "periodic", "job")
        val event = app.diagnostics.store.events().first { it.optString("kind") == "query" }
        assertTrue(event.getBoolean("observationActiveAtStart"))
        assertFalse(event.getBoolean("appVisibleAtStart"))
        assertEquals("scheduled", event.getString("source"))
        app.diagnostics.event("manual", "error", detail = JSONObject().put("error", JSONObject().put("class", "SecurityException").put("message", "Authorization: Bearer SECRET /目录/private/file")))
        val exported = buildExport(app.diagnostics).toString()
        assertFalse(exported.contains("SECRET"))
        assertFalse(exported.contains("/目录/"))
        assertTrue(exported.contains("SecurityException"))
        val old = JSONObject().put("books", JSONArray().put(JSONObject().put("key", "a").put("progress", JSONObject().put("raw", "1/2"))))
        val current = JSONObject().put("books", JSONArray().put(JSONObject().put("key", "a").put("progress", JSONObject().put("raw", "2/2"))))
        assertEquals(1, changes(old, current).length())
    }

    @Test fun nullFieldsAndUnmatchableRecordsDoNotProduceFalseChanges() {
        MatrixCursor(arrayOf("progress", "readingStatus")).use { cursor ->
            cursor.addRow(arrayOf(null, "999"))
            cursor.moveToFirst()
            val record = readBook(cursor)
            assertEquals("null", record.getJSONObject("progress").getString("state"))
            assertTrue(record.isNull("percentage"))
            assertTrue(record.isNull("key"))
            val snapshot = JSONObject().put("books", JSONArray().put(record))
            assertEquals(0, changes(snapshot, snapshot).length())
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

    @Test fun foregroundSessionUsesRepositoryAndReleasesOnStop() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildService(ObservationService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent(app, ObservationService::class.java), 0, 1)
        awaitEvent(app) { event -> event.optString("source") == "observation" && event.optString("kind") == "query" }
        assertTrue(app.sessionActive)
        service.onStartCommand(Intent(app, ObservationService::class.java).setAction("stop"), 0, 2)
        awaitEvent(app) { event -> event.optString("kind") == "stop" && event.optString("reason") == "user_stop" }
        assertFalse(app.sessionActive)
        assertEquals("", app.diagnostics.store.get("active.observation"))
        controller.destroy()
        Unit
    }

    @Test fun foregroundSessionStopsAtDeadlineWithoutAnotherRead() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildService(ObservationService::class.java).create()
        controller.get().onStartCommand(Intent(app, ObservationService::class.java), 0, 1)
        awaitEvent(app) { it.optString("source") == "observation" && it.optString("kind") == "query" }
        val reads = provider.calls.get()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(10))
        awaitEvent(app) { it.optString("kind") == "stop" && it.optString("reason") == "deadline" }
        assertFalse(app.sessionActive)
        assertEquals(reads, provider.calls.get())
        controller.destroy()
        Unit
    }
}
