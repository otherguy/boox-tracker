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

    @Test fun aSyncThatHoldsItsUpdateQueuesNoBackgroundDelivery() = runBlocking {
        online = true
        val update = check(book(progress = "100/100"))
        store.put("lastCheck", update.toString())
        val work = WorkManager.getInstance(app)
        fun deliveryWork() = work.getWorkInfosForUniqueWork(DELIVERY_WORK_NAME).get().count { it.state == WorkInfo.State.ENQUEUED }
        work.cancelUniqueWork(DELIVERY_WORK_NAME).result.get()
        connection.send("manual", update)
        connection.send("scheduled", update)
        assertEquals("source_status_not_finished", connection.state().getJSONObject("last").getString("reason"))
        assertEquals(2, store.events().count { it.optString("kind") == "hardcover_sync" })
        assertEquals(0, deliveryWork())
        online = false
        connection.send("manual", check(book(progress = "100/100", status = "2")))
        assertEquals(1, deliveryWork())
    }

    @Test fun offlineChecksLeaveOneWaitingDeliveryRun() = runBlocking {
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork(DELIVERY_WORK_NAME).result.get()
        listOf("42/100", "50/100", "60/100").forEach { connection.send("scheduled", check(book(progress = it))) }
        assertEquals(1, work.getWorkInfosForUniqueWork(DELIVERY_WORK_NAME).get().count { !it.state.isFinished })
    }

    @Test fun anOfflineSyncWithNothingQueuedQueuesNoBackgroundDelivery() = runBlocking {
        online = true
        connection.send("manual", check(book()))
        assertTrue(store.pending("1", "hardcover").isEmpty())
        val work = WorkManager.getInstance(app)
        work.cancelUniqueWork(DELIVERY_WORK_NAME).result.get()
        online = false
        connection.send("scheduled", check(book()))
        assertEquals(0, work.getWorkInfosForUniqueWork(DELIVERY_WORK_NAME).get().count { it.state == WorkInfo.State.ENQUEUED })
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
        assertTrue(store.pending("1", "hardcover").isEmpty())
        assertTrue(connection.state().isNull("profile"))
        server.failProfile = false
        connection.drain("delivery")
        assertFalse(connection.state().isNull("profile"))
    }

    @Test fun offlineCollectionCoalescesEachBookAndSurvivesDatabaseReopen() = runBlocking {
        connection.send("manual", check(book()))
        connection.send("scheduled", check(book("second")))
        connection.send("scheduled", check(book(progress = "45/100")))
        assertEquals(2, store.pending("1", "hardcover").size)
        assertEquals("45/100", store.pending("1", "hardcover").single { it.getJSONObject("book").getString("key") == "first" }.getJSONObject("book").raw("progress"))
        DiagnosticsStore(app).use { reopened -> assertEquals(2, reopened.pending("1", "hardcover").size) }
        assertTrue(server.requests.isEmpty())
        assertTrue(connection.state().getBoolean("enabled"))
        assertEquals(3, store.events().count { it.optString("kind") == "queued" })
    }

    private fun library(vararg books: List<Any?>) = FixtureProvider().apply { libraryRecords = books.toList() }.also { ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, it) }

    /** A NeoReader record whose key is `uuid:<name>`, titled [name]. */
    private fun record(name: String, progress: String, status: String, lastAccess: String?, path: String? = null) = listOf(name, name, progress, status, path, null, lastAccess)

    /** The book that the Hardcover fake finds by title. */
    private val known = "Synthetic Book"

    /** Queued Hardcover books by name, each with its raw progress and reading status. */
    private fun queued() = store.pending("1", "hardcover").map { it.getJSONObject("book") }
        .associate { it.getString("key").removePrefix("uuid:") to (it.raw("progress") to it.raw("readingStatus")) }

    @Test fun booksReadOfflineSinceTheLastCheckAreQueuedWithTheirFinalState() = runBlocking {
        val provider = library(record(known, "50/100", "1", "1791200000000"), record("E", "10/100", "1", "1791100000000"), record("F", "0/100", "0", null))
        app.sync("scheduled", "periodic")
        assertEquals(setOf(known), queued().keys)
        // Offline, the known book is finished, B and C are read, and D is the current book. The known book keeps its last
        // access time, as it would if NeoReader updates it only when a book opens. E changed but was last read before the
        // previous check; F is not started.
        provider.libraryRecords = listOf(
            record(known, "100/100", "2", "1791200000000"),
            record("B", "75/100", "1", "1791300000000"),
            record("C", "100/100", "2", "1791400000000"),
            record("D", "20/100", "1", "1791500000000"),
            record("E", "60/100", "1", "1791100000000"),
            record("F", "0/100", "0", "1791450000000")
        )
        app.sync("scheduled", "periodic")
        assertEquals(mapOf(known to ("100/100" to "2"), "B" to ("75/100" to "1"), "C" to ("100/100" to "2"), "D" to ("20/100" to "1")), queued())
        assertEquals(listOf("uuid:$known", "uuid:B", "uuid:C"), JSONObject(store.get("lastCheck")!!).getJSONArray("recent").objects().map { it.getString("key") })
        assertTrue(server.requests.isEmpty())
        // Online, the earliest read goes first, and its finish reaches Hardcover.
        online = true
        connection.drain("delivery")
        val first = store.events().last { it.optString("kind") == "hardcover_sync" }
        assertEquals("sent" to true, first.getString("outcome") to first.getBoolean("finished"))
        assertEquals(3, server.library.getJSONObject(0).getInt("status_id"))
    }

    @Test fun aRunStoppedBeforeTheServicesQueuedOffersTheEarlierBooksAgain() = runBlocking {
        val provider = library(record("A", "50/100", "1", "1791200000000"))
        app.sync("scheduled", "periodic")
        provider.libraryRecords = listOf(record("A", "100/100", "2", "1791200000000"), record("D", "20/100", "1", "1791500000000"))
        // The run stops after its check, before any service queues.
        app.diagnostics.collect("scheduled", "periodic")
        assertEquals(mapOf("A" to ("50/100" to "1")), queued())
        app.sync("scheduled", "periodic")
        assertEquals(mapOf("A" to ("100/100" to "2"), "D" to ("20/100" to "1")), queued())
    }

    @Test fun anEarlierBookThatCouldNotBeQueuedIsOfferedAgain() = runBlocking {
        val folder = grantTestFolder(app)
        val provider = library(record("A", "50/100", "1", "1791200000000", "/books/A.epub"))
        app.sync("scheduled", "periodic")
        provider.libraryRecords = listOf(record("A", "100/100", "2", "1791200000000", "/books/A.epub"), record("D", "20/100", "1", "1791500000000"))
        // Two files share A's name, so its ebook identifiers cannot be read.
        folder.files = listOf("A.epub", "A.epub")
        app.sync("scheduled", "periodic")
        // The current book is still queued, and the failure names the earlier book.
        assertEquals(mapOf("A" to ("50/100" to "1"), "D" to ("20/100" to "1")), queued())
        assertTrue(store.events().any { it.optString("kind") == "hardcover_sync" && it.optString("title") == "A" && it.optString("reason") == "epub_filename_ambiguous" })
        folder.files = emptyList()
        app.sync("scheduled", "periodic")
        assertEquals(mapOf("A" to ("100/100" to "2"), "D" to ("20/100" to "1")), queued())
    }

    @Test fun anEarlierBookReopenedAfterItsFinishWasSentIsLeftAsTheTrackerHasIt() = runBlocking {
        online = true
        val provider = library(record(known, "100/100", "2", "1791200000000"))
        app.sync("scheduled", "periodic")
        assertEquals(3, server.library.getJSONObject(0).getInt("status_id"))
        val mutations = server.mutations().size
        // Paging back in the finished book sets it to reading, and then another book becomes the current one.
        provider.libraryRecords = listOf(record(known, "90/100", "1", "1791300000000"), record("D", "20/100", "1", "1791500000000"))
        app.sync("scheduled", "periodic")
        assertEquals(mutations, server.mutations().size)
        assertFalse(queued().containsKey(known))
        assertTrue(connection.state().getJSONArray("held").objects().none { it.optString("title") == known })
    }

    @Test fun aCheckWithMoreThanTenEarlierBooksQueuesOnlyTheCurrentBook() = runBlocking {
        val provider = library(record("A", "50/100", "1", "1791200000000"))
        app.sync("scheduled", "periodic")
        provider.libraryRecords = (1..11).map { record("R$it", "10/100", "1", "${1791300000000 + it}") } + listOf(record("D", "20/100", "1", "1791500000000"))
        app.sync("scheduled", "periodic")
        assertEquals(setOf("A", "D"), queued().keys)
        val check = JSONObject(store.get("lastCheck")!!)
        assertEquals(11, check.getInt("recentSkipped"))
        assertTrue(check.getBoolean("issue"))
        assertEquals(11, store.events().first { it.optString("kind") == "query" }.getInt("recentSkipped"))
    }

    @Test fun aTieOnTheLatestBookStillQueuesTheEarlierBooks() = runBlocking {
        val provider = library(record("A", "10/100", "1", "1791200000000"))
        app.sync("scheduled", "periodic")
        provider.libraryRecords = listOf(record("A", "30/100", "1", "1791300000000"), record("B", "5/100", "1", "1791400000000"), record("C", "5/100", "1", "1791400000000"))
        app.sync("scheduled", "periodic")
        assertEquals(mapOf("A" to ("30/100" to "1")), queued())
        assertTrue(store.events().any { it.optString("kind") == "hardcover_sync" && it.optString("reason") == "fresh_book_detection_required" })
    }

    @Test fun deliverySendsTheEarliestReadFirstAndHeldBooksNameTheirTitle() = runBlocking {
        store.put("lastCheck", check(book("current")).toString())
        // The queue lists items by a digest of their key; the earlier read gets the key that the queue lists last.
        val (later, earlier) = listOf("one", "two").sortedBy { digest(it) }
        fun enqueue(key: String, title: String, lastAccess: String) = store.enqueue("1", book(key, status = "9").put("title", raw(title)).put("lastAccess", raw(lastAccess)), identifiers, "2026-11-01T10:00:00Z", "hardcover")
        enqueue(later, "Later Book", "1791300000000")
        enqueue(earlier, "Earlier Book", "1791200000000")
        online = true
        connection.drain("delivery")
        val held = store.events().filter { it.optString("kind") == "hardcover_sync" }.reversed()
        assertEquals(listOf("Earlier Book", "Later Book"), held.map { it.getString("title") })
        assertTrue(held.all { it.optString("outcome") == "held" && it.optString("reason") == "source_status_unsupported" })
        assertEquals(setOf("Earlier Book", "Later Book"), connection.state().getJSONArray("held").objects().map { it.getString("title") }.toSet())
    }

    @Test fun anUnchangedBookWaitingOfflineIsQueuedOnce() = runBlocking {
        repeat(3) { connection.send("scheduled", check(book())) }
        assertEquals(1, store.events().count { it.optString("kind") == "queued" })
        connection.send("scheduled", check(book(progress = "43/100")))
        assertEquals(2, store.events().count { it.optString("kind") == "queued" })
    }

    @Test fun aSuccessfulDeliveryPrunesOlderRoutineEvents() = runBlocking {
        val old = System.currentTimeMillis() - 72 * 3_600_000L
        store.append(JSONObject().put("kind", "query").put("wallMs", old).put("issue", false))
        store.append(JSONObject().put("kind", "hardcover_sync").put("outcome", "held").put("wallMs", old).put("issue", true))
        online = true
        store.put("lastCheck", check(book()).toString())
        connection.send("manual", check(book()))
        assertEquals("synced", connection.state().getJSONObject("last").getString("delivery"))
        assertTrue(store.get("lastSyncMs")!!.toLong() > old)
        val events = store.events()
        assertFalse(events.any { it.optLong("wallMs") == old && it.optString("kind") == "query" })
        assertTrue(events.any { it.optLong("wallMs") == old && it.optString("outcome") == "held" })
    }

    @Test fun anInterruptedSendIsReportedForEveryServiceOnRecovery() {
        val services = app.connections.map { it.service }
        services.forEach { service -> store.put("$service.active", JSONObject().put("source", "delivery").put("runId", "run-$service").toString()) }
        app.diagnostics.recover()
        val events = store.events()
        services.forEach { service ->
            assertTrue(service, events.any { it.optString("kind") == "${service}_interruption_detected" && it.optString("runId") == "run-$service" })
            assertEquals("", store.get("$service.active"))
        }
    }

    @Test fun acknowledgementCannotRemoveANewerRevisionOrAnotherAccount() {
        val old = store.enqueue("1", book(), identifiers, "before", "hardcover")
        val newer = store.enqueue("1", book(progress = "45/100"), identifiers, "after", "hardcover")
        val other = store.enqueue("2", book(), identifiers, "other", "hardcover")
        assertFalse(store.acknowledge(old))
        assertEquals(newer.getString("revision"), store.pending("1", "hardcover").single().getString("revision"))
        assertTrue(store.acknowledge(newer))
        assertEquals(other.getString("revision"), store.pending("2", "hardcover").single().getString("revision"))
    }

    @Test fun reconnectReconcilesAnUncertainWriteWithoutDuplicateReads() = runBlocking {
        connection.send("manual", check(book()))
        online = true
        server.failReadOnce = true
        assertTrue(connection.drain("delivery"))
        assertEquals(1, store.pending("1", "hardcover").size)
        assertEquals(1, server.library.getJSONObject(0).getJSONArray("user_book_reads").length())
        assertFalse(connection.drain("delivery"))
        assertTrue(store.pending("1", "hardcover").isEmpty())
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
        assertEquals(1, store.pending("1", "hardcover").size)
    }

    @Test fun accountMismatchNeverSendsAndAnotherAccountsQueueIsIsolated() = runBlocking {
        connection.send("manual", check(book()))
        store.enqueue("2", book("second"), identifiers, "other", "hardcover")
        online = true
        server.identity = 2
        connection.drain("delivery")
        assertTrue(server.mutations().isEmpty())
        assertEquals(1, store.pending("1", "hardcover").size)
        assertEquals(1, store.pending("2", "hardcover").size)
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
            store.enqueue("1", book(progress = "45/100"), identifiers, "newer", "hardcover")
            server.libraryRelease!!.countDown()
            assertTrue(delivery.await())
            assertEquals("45/100", store.pending("1", "hardcover").single().getJSONObject("book").raw("progress"))
            assertFalse(connection.drain("delivery"))
            assertTrue(store.pending("1", "hardcover").isEmpty())
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
            assertEquals(1, store.pending("1", "hardcover").size)
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
        assertEquals(1, store.pending("1", "hardcover").size)
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
            assertTrue(store.pending("1", "hardcover").isEmpty())
            assertTrue(store.pending("2", "hardcover").isEmpty())
            assertNull(store.get("hardcover.book.2.${digest("first")}"))
        } finally {
            folder.release!!.countDown()
            send.cancelAndJoin()
        }
    }
}
