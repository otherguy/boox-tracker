package dev.otherguy.booxtracker

import android.content.Intent
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class OfflineSyncTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: HardcoverServer
    private lateinit var vault: TokenVault
    private lateinit var connection: HardcoverConnection
    private var online = false
    private val identifiers = BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author")
    private fun raw(value: String) = JSONObject().put("state", "value").put("raw", value)
    private fun book(key: String = "first", progress: String = "42/100", status: String = "1") = JSONObject().put("key", key).put("progress", raw(progress)).put("readingStatus", raw(status))
        .put("title", raw("Synthetic Book")).put("authors", raw("Test Author")).put("ISBN", raw("9781398508255"))
    private fun check(book: JSONObject) = JSONObject().put("outcome", "success").put("timestamp", "2026-10-06T10:00:00Z").put("selected", book)

    @Before fun setup() = runBlocking {
        server = HardcoverServer()
        grantTestFolder(app)
        store.put("hardcover.account", "1")
        vault = TokenVault(app) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        vault.write(OAuthTokens("old-access", "old-refresh", System.currentTimeMillis() + 3_600_000))
        connection = HardcoverConnection(app, HardcoverAuth(server.http, vault)) { online }
        app.hardcover = connection
        connection.setEnabled(true)
    }

    @After fun close() = runBlocking {
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        server.close()
        vault.clear()
        store.close()
        closeWorkDatabase()
    }

    @Test fun failedIdentityLookupDoesNotBindNewTokensToThePreviousAccount() = runBlocking {
        vault.clear()
        server.identity = 2
        server.failIdentity = true
        connection.setEnabled(true)
        withTimeout(10_000) { while (connection.signingIn) delay(10) }
        assertFalse(connection.state().getBoolean("enabled"))
        assertEquals("", store.get("hardcover.account"))
        assertTrue(connection.state().getBoolean("connected"))
    }

    @Test fun finishedStatusDeliversCompletionAfterFullProgressWasHeld() = runBlocking {
        online = true
        store.put("lastCheck", check(book(progress = "100/100")).toString())
        connection.send("manual", check(book(progress = "100/100")))
        assertEquals("error", connection.state().getJSONObject("last").getString("delivery"))
        assertEquals("source_status_not_finished", connection.state().getJSONObject("last").getString("reason"))
        connection.send("manual", check(book(progress = "100/100", status = "2")))
        val last = connection.state().getJSONObject("last")
        assertEquals("synced", last.getString("delivery"))
        assertTrue(last.getJSONObject("lastSuccess").getBoolean("finished"))
        assertEquals(3, server.library.getJSONObject(0).getInt("status_id"))
    }

    @Test fun successRemainsPerBookAndSurvivesALaterHeldAttempt() = runBlocking {
        online = true
        store.put("lastCheck", check(book()).toString())
        connection.send("manual", check(book()))
        val success = connection.state().getJSONObject("last").getJSONObject("lastSuccess").getString("timestamp")
        server.mutationRejected = true
        connection.send("manual", check(book(progress = "50/100")))
        val last = connection.state().getJSONObject("last")
        assertEquals("error", last.getString("delivery"))
        assertEquals(success, last.getJSONObject("lastSuccess").getString("timestamp"))
        store.put("lastCheck", check(book("another")).toString())
        assertTrue(connection.state().isNull("last"))
    }

    @Test fun logOutWinsOverAnEarlierOnWaitingForIdentity() = runBlocking {
        connection.setEnabled(false)
        store.put("hardcover.account", "")
        server.identityEntered = CountDownLatch(1)
        server.identityRelease = CountDownLatch(1)
        val on = async(Dispatchers.IO) { connection.setEnabled(true) }
        try {
            assertTrue(server.identityEntered!!.await(5, TimeUnit.SECONDS))
            val logOut = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { connection.logOut() }
            server.identityRelease!!.countDown()
            on.await()
            logOut.await()
            assertFalse(connection.state().getBoolean("enabled"))
            assertFalse(connection.state().getBoolean("connected"))
            assertEquals("", store.get("hardcover.account"))
        } finally {
            server.identityRelease!!.countDown()
            on.cancelAndJoin()
        }
    }

    @Test fun anExistingSessionGetsAccountDetailsOnItsNextDelivery() = runBlocking {
        connection.send("manual", check(book()))
        assertTrue(connection.state().isNull("profile"))
        online = true
        connection.drain("delivery")
        val profile = connection.state().getJSONObject("profile")
        assertEquals("hardcover-reader", profile.getString("username"))
        assertEquals("Pro", profile.getString("membership"))
        assertEquals("2024-05-06T07:08:09.123456+00:00", profile.getString("createdAt"))
        assertFalse(profile.has("email"))
        assertTrue(connection.state().getLong("connectedAt") > 0)
        val profileQueries = server.requests.count { it.optString("query").startsWith("query Profile") }
        connection.drain("delivery")
        assertEquals(profileQueries, server.requests.count { it.optString("query").startsWith("query Profile") })
    }

    @Test fun aFailedAccountDetailsFetchNeverBlocksDelivery() = runBlocking {
        connection.send("manual", check(book()))
        online = true
        server.failProfile = true
        assertFalse(connection.drain("delivery"))
        assertTrue(store.pending("1").isEmpty())
        assertTrue(connection.state().isNull("profile"))
        server.failProfile = false
        connection.drain("delivery")
        assertFalse(connection.state().isNull("profile"))
    }

    @Test fun offlineCollectionCoalescesEachBookAndSurvivesDatabaseReopen() = runBlocking {
        connection.send("manual", check(book()))
        connection.send("scheduled", check(book("second")))
        connection.send("scheduled", check(book(progress = "45/100")))
        assertEquals(2, store.pending("1").size)
        assertEquals("45/100", store.pending("1").single { it.getJSONObject("book").getString("key") == "first" }.getJSONObject("book").raw("progress"))
        DiagnosticsStore(app).use { reopened -> assertEquals(2, reopened.pending("1").size) }
        assertTrue(server.requests.isEmpty())
        assertTrue(connection.state().getBoolean("enabled"))
        assertEquals(3, store.events().count { it.optString("kind") == "queued" })
    }

    @Test fun acknowledgementCannotRemoveANewerRevisionOrAnotherAccount() {
        val old = store.enqueue("1", book(), identifiers, "before")
        val newer = store.enqueue("1", book(progress = "45/100"), identifiers, "after")
        val other = store.enqueue("2", book(), identifiers, "other")
        assertFalse(store.acknowledge(old))
        assertEquals(newer.getString("revision"), store.pending("1").single().getString("revision"))
        assertTrue(store.acknowledge(newer))
        assertEquals(other.getString("revision"), store.pending("2").single().getString("revision"))
    }

    @Test fun reconnectReconcilesAnUncertainWriteWithoutDuplicateReads() = runBlocking {
        connection.send("manual", check(book()))
        online = true
        server.failReadOnce = true
        assertTrue(connection.drain("delivery"))
        assertEquals(1, store.pending("1").size)
        assertEquals(1, server.library.getJSONObject(0).getJSONArray("user_book_reads").length())
        assertFalse(connection.drain("delivery"))
        assertTrue(store.pending("1").isEmpty())
        assertEquals(2, server.mutations().size)
        assertEquals(1, server.library.getJSONObject(0).getJSONArray("user_book_reads").length())
    }

    @Test fun offPausesAndOnOfflineSchedulesRetainedItemsEvenWhenCurrentBookIsUnchanged() = runBlocking {
        connection.send("manual", check(book()))
        WorkManager.getInstance(app).cancelUniqueWork(DELIVERY_WORK_NAME).result.get()
        connection.setEnabled(false)
        online = true
        assertFalse(connection.drain("delivery"))
        assertTrue(server.requests.isEmpty())
        online = false
        connection.setEnabled(true)
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork(DELIVERY_WORK_NAME).get()
        assertTrue(work.any { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(connection.state().getBoolean("enabled"))
        assertEquals(1, store.pending("1").size)
    }

    @Test fun accountMismatchNeverSendsAndAnotherAccountsQueueIsIsolated() = runBlocking {
        connection.send("manual", check(book()))
        store.enqueue("2", book("second"), identifiers, "other")
        online = true
        server.identity = 2
        connection.drain("delivery")
        assertTrue(server.mutations().isEmpty())
        assertEquals(1, store.pending("1").size)
        assertEquals(1, store.pending("2").size)
        assertTrue(store.events().any { it.optString("reason") == "hardcover_account_changed" })
    }

    @Test fun newerObservationDuringDeliveryIsNotAcknowledgedWithTheOldWrite() = runBlocking {
        connection.send("manual", check(book()))
        online = true
        server.libraryEntered = CountDownLatch(1)
        server.libraryRelease = CountDownLatch(1)
        val delivery = async(Dispatchers.IO) { connection.drain("delivery") }
        try {
            assertTrue(server.libraryEntered!!.await(5, TimeUnit.SECONDS))
            store.enqueue("1", book(progress = "45/100"), identifiers, "newer")
            server.libraryRelease!!.countDown()
            assertTrue(delivery.await())
            assertEquals("45/100", store.pending("1").single().getJSONObject("book").raw("progress"))
            assertFalse(connection.drain("delivery"))
            assertTrue(store.pending("1").isEmpty())
            assertEquals(226, server.library.getJSONObject(0).getJSONArray("user_book_reads").getJSONObject(0).getInt("progress_pages"))
        } finally {
            server.libraryRelease!!.countDown()
            delivery.cancelAndJoin()
        }
    }

    @Test fun switchingOffDuringRemoteReadPreventsTheFollowingMutation() = runBlocking {
        connection.send("manual", check(book()))
        online = true
        server.libraryEntered = CountDownLatch(1)
        server.libraryRelease = CountDownLatch(1)
        val delivery = async(Dispatchers.IO) { connection.drain("delivery") }
        try {
            assertTrue(server.libraryEntered!!.await(5, TimeUnit.SECONDS))
            connection.setEnabled(false)
            server.libraryRelease!!.countDown()
            delivery.await()
            assertTrue(server.mutations().isEmpty())
            assertEquals(1, store.pending("1").size)
        } finally {
            server.libraryRelease!!.countDown()
            delivery.cancelAndJoin()
        }
    }

    @Test fun firstConnectionFailureReturnsOffWithoutCredentials() = runBlocking {
        vault.clear()
        server.failConnection = true
        connection.setEnabled(true)
        withTimeout(5000) { while (connection.signingIn) delay(10) }
        assertFalse(connection.state().getBoolean("enabled"))
        assertFalse(connection.state().getBoolean("connected"))
    }

    @Test fun revokedFolderStopsScheduledCollectionAndDeliveryWithoutOpeningUi() = runBlocking {
        connection.send("manual", check(book()))
        online = true
        app.contentResolver.releasePersistableUriPermission(Uri.parse(store.get("ebook.tree")), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val provider = FixtureProvider().withSyncBook()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        TestListenableWorkerBuilder<ScheduledCheckWorker>(app).build().doWork()
        TestListenableWorkerBuilder<DeliveryWorker>(app).build().doWork()
        assertEquals(0, provider.calls.get())
        assertTrue(server.requests.isEmpty())
        assertEquals(1, store.pending("1").size)
        assertTrue(store.events().any { it.optString("reason") == "ebook_permission_denied" })
        assertEquals(null, org.robolectric.Shadows.shadowOf(app).nextStartedActivity)
    }

    @Test fun changingAccountsDuringMetadataReadDoesNotContaminateCurrentBookState() = runBlocking {
        val folder = grantTestFolder(app).apply {
            entered = CountDownLatch(1)
            release = CountDownLatch(1)
        }
        val send = async(Dispatchers.IO) { connection.send("manual", check(book().put("filename", raw("book.epub")))) }
        try {
            assertTrue(folder.entered!!.await(5, TimeUnit.SECONDS))
            store.put("hardcover.account", "2")
            folder.release!!.countDown()
            send.await()
            assertTrue(store.pending("1").isEmpty())
            assertTrue(store.pending("2").isEmpty())
            assertNull(store.get("hardcover.book.2.${digest("first")}"))
        } finally {
            folder.release!!.countDown()
            send.cancelAndJoin()
        }
    }
}
