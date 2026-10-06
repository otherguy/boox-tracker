package dev.otherguy.booxtracker

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

class HardcoverServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<JSONObject>()
    var failIdentity = false
    var identity = 1
    var matchBook = 10
    var goodreadsBook: Int? = null
    var failReadOnce = false
    var failConnection = false
    var defaultPages: Int? = null
    var titleCandidates: List<Pair<Int, String>> = listOf(10 to "Synthetic Book")
    var editionCount = 1
    var editionPageCount = 502
    var mutationRejected = false
    var library = JSONArray()
    var pendingResponses = 0
    var slowDown = false
    var rejectOldAccess = false
    var invalidRefresh = false
    var graphqlError = false
    var tenOnly = false
    var deviceInterval = 5
    var tokenEntered: CountDownLatch? = null
    var tokenRelease: CountDownLatch? = null
    var identityEntered: CountDownLatch? = null
    var identityRelease: CountDownLatch? = null
    var libraryEntered: CountDownLatch? = null
    var libraryRelease: CountDownLatch? = null
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val incoming = request
                val path = incoming.path!!
                val text = incoming.body.readUtf8()
                val request = if (path == "/v1/graphql") {
                    JSONObject(text)
                } else {
                    JSONObject().apply {
                        text.split('&').filter { it.contains('=') }.forEach {
                            put(URLDecoder.decode(it.substringBefore('='), "UTF-8"), URLDecoder.decode(it.substringAfter('='), "UTF-8"))
                        }
                    }
                }
                requests.add(JSONObject(request.toString()).put("path", path).put("bearer", incoming.getHeader("Authorization")))
                if (path == "/oauth2/token" && request.optString("grant_type").contains("device_code")) {
                    tokenEntered?.countDown()
                    tokenRelease?.await(10, TimeUnit.SECONDS)
                }
                val response = when {
                    failConnection || (failIdentity && request.optString("query").startsWith("query Identity")) -> 503 to JSONObject()

                    path == "/oauth2/device" -> 200 to JSONObject().put("device_code", "synthetic-device-secret").put("user_code", "TEST-CODE").put("verification_uri", "https://hardcover.app/link").put("expires_in", 600).put("interval", deviceInterval)

                    path == "/oauth2/token" && request.optString("grant_type") == "refresh_token" && invalidRefresh -> 400 to JSONObject().put("error", "invalid_grant")

                    path == "/oauth2/token" && request.optString("grant_type") == "refresh_token" -> 200 to token("fresh-access", "rotated-refresh")

                    path == "/oauth2/token" && pendingResponses-- > 0 -> 400 to JSONObject().put("error", "authorization_pending")

                    path == "/oauth2/token" && slowDown -> {
                        slowDown = false
                        400 to JSONObject().put("error", "slow_down")
                    }

                    path == "/oauth2/token" -> 200 to token("old-access", "old-refresh")

                    path == "/oauth2/revoke" -> 200 to JSONObject()

                    rejectOldAccess && incoming.getHeader("Authorization") == "Bearer old-access" -> 401 to JSONObject()

                    graphqlError -> 200 to JSONObject().put("errors", JSONArray().put(JSONObject().put("message", "synthetic error")))

                    else -> {
                        val data = graphql(request)
                        if (failReadOnce && request.optString("query").contains("insert_user_book_read(")) {
                            failReadOnce = false
                            503 to JSONObject()
                        } else {
                            200 to JSONObject().put("data", data)
                        }
                    }
                }
                return MockResponse().setResponseCode(response.first).setHeader("Content-Type", "application/json").setBody(response.second.toString())
            }
        }
        start()
    }
    val http = HardcoverHttp(server.url("/").toString().removeSuffix("/"))

    fun withRead(pages: Int, status: Int = 2, finished: Boolean = false, edition: Int = 20): HardcoverServer = apply {
        library = JSONArray().put(
            JSONObject().put("id", 30).put("status_id", status).put("edition_id", edition).put(
                "user_book_reads",
                JSONArray().put(
                    JSONObject()
                        .put("id", 40).put("edition_id", edition).put("progress_pages", pages).put("progress_seconds", JSONObject.NULL)
                        .put("finished_at", if (finished) "2026-01-01" else JSONObject.NULL).put("paused_at", JSONObject.NULL)
                )
            )
        )
    }

    private fun graphql(request: JSONObject): JSONObject {
        val query = request.getString("query")
        val variables = request.getJSONObject("variables")
        return when {
            query.startsWith("query Match") -> JSONObject().put(
                "editions",
                JSONArray(
                    (0 until editionCount).map {
                        JSONObject().put("id", 20 + it).put("book_id", matchBook).put("isbn_13", if (tenOnly) JSONObject.NULL else "9781398508255").put("isbn_10", "139850825X").put("asin", "B07THCSQ27").put("pages", editionPageCount).put("book", JSONObject().put("title", "Synthetic Book"))
                    }
                )
            )

            query.startsWith("query BookDetails") -> JSONObject().put("books_by_pk", JSONObject().put("id", 10).put("title", "Synthetic Book").put("default_ebook_edition", JSONObject().put("id", if (defaultPages == null) 20 else 21).put("book_id", 10).put("pages", defaultPages ?: editionPageCount)))

            query.startsWith("query Edition") -> JSONObject().put("editions_by_pk", JSONObject().put("id", variables.getInt("id")).put("book_id", if (variables.getInt("id") == 99) 999 else 10).put("pages", editionPageCount))

            query.startsWith("query TitleSearch") -> JSONObject().put("search", JSONObject().put("ids", JSONArray().put(10)).put("error", JSONObject.NULL))

            query.startsWith("query Candidates") -> JSONObject().put("books", JSONArray(titleCandidates.map { (id, title) -> JSONObject().put("id", id).put("title", title).put("alternative_titles", JSONArray()).put("contributions", JSONArray().put(JSONObject().put("author", JSONObject().put("name", "Test Author")))) }))

            query.startsWith("query Goodreads") -> JSONObject().put("book_mappings", JSONArray().apply { goodreadsBook?.let { put(JSONObject().put("book_id", it)) } })

            query.startsWith("query BookIdentifier") -> JSONObject().put("books_by_pk", JSONObject().put("id", variables.getInt("id")))

            query.startsWith("query BookSlug") -> JSONObject().put("books", JSONArray().put(JSONObject().put("id", 10)))

            query.startsWith("query Identity") -> {
                identityEntered?.countDown()
                identityRelease?.await(5, TimeUnit.SECONDS)
                JSONObject().put("me", JSONArray().put(JSONObject().put("id", identity)))
            }

            query.startsWith("query Library") -> {
                libraryEntered?.countDown()
                libraryRelease?.await(5, TimeUnit.SECONDS)
                JSONObject().put("user_books", library)
            }

            else -> {
                val field = when {
                    query.contains("insert_user_book_read(") -> "insert_user_book_read"
                    query.contains("update_user_book_read(") -> "update_user_book_read"
                    query.contains("update_user_book(") -> "update_user_book"
                    else -> "insert_user_book"
                }
                if (mutationRejected) return JSONObject().put(field, JSONObject().put("error", "synthetic rejection").put("id", JSONObject.NULL))
                if (field == "insert_user_book") library.put(JSONObject().put("id", 30).put("status_id", 2).put("edition_id", 20).put("user_book_reads", JSONArray()))
                if (field == "update_user_book") library.getJSONObject(0).put("status_id", variables.getJSONObject("object").getInt("status_id"))
                if (field == "insert_user_book_read") library.getJSONObject(0).getJSONArray("user_book_reads").put(JSONObject(variables.getJSONObject("read").toString()).put("id", 40))
                if (field == "update_user_book_read") library.getJSONObject(0).getJSONArray("user_book_reads").getJSONObject(0).put("progress_pages", variables.getJSONObject("read").getInt("progress_pages"))
                JSONObject().put(field, JSONObject().put("id", if (field.endsWith("read")) 40 else 30).put("error", JSONObject.NULL))
            }
        }
    }

    fun mutations(): List<JSONObject> = requests.filter { it.optString("query").startsWith("mutation") }
    override fun close() = server.shutdown()
    private fun token(access: String, refresh: String) = JSONObject().put("access_token", access).put("refresh_token", refresh).put("token_type", "Bearer").put("expires_in", 3600)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class HardcoverTest {
    private lateinit var server: HardcoverServer
    private lateinit var vault: TokenVault
    private lateinit var auth: HardcoverAuth
    private val identifiers = BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author")

    @Before fun setup() {
        server = HardcoverServer()
        grantTestFolder(RuntimeEnvironment.getApplication() as ReadingSyncApp)
        (RuntimeEnvironment.getApplication() as ReadingSyncApp).diagnostics.store.put("hardcover.account", "1")
        vault = TokenVault(RuntimeEnvironment.getApplication()) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        vault.write(OAuthTokens("old-access", "old-refresh", System.currentTimeMillis() + 3_600_000))
        auth = HardcoverAuth(server.http, vault)
    }

    @After fun close() = runBlocking {
        server.close()
        vault.clear()
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        app.diagnostics.store.close()
        closeWorkDatabase()
    }

    private fun book(progress: String = "4723/10000", status: String = "1") = JSONObject().put("progress", JSONObject().put("state", "value").put("raw", progress))
        .put("readingStatus", JSONObject().put("state", "value").put("raw", status))

    @Test fun missingIsbnAutomaticallyMatchesUniqueTitleAndAuthor() = runBlocking {
        server.editionCount = 0
        val value = HardcoverSync(auth).send(book(), BookIdentifiers(emptySet(), "Synthetic Book", "Test Author"))
        assertEquals("sent", value.getString("outcome"))
        assertEquals("book", value.getString("matchKind"))
    }

    @Test fun multipleEditionsForOneBookDoNotRequireSelection() = runBlocking {
        server.editionCount = 2
        assertEquals("sent", HardcoverSync(auth).send(book(), identifiers).getString("outcome"))
    }

    @Test fun validDefaultEditionIsUsedWhenExactEditionHasNoPages() = runBlocking {
        server.editionPageCount = 0
        server.defaultPages = 500
        val result = HardcoverSync(auth).send(book(), identifiers)
        assertEquals(21, result.getInt("editionId"))
        assertEquals("book", result.getString("matchKind"))
        assertEquals(236, result.getInt("progressPages"))
    }

    @Test fun existingEditionForTheSameBookIsPreservedWithAmberMatch() = runBlocking {
        server.withRead(200, edition = 21)
        val result = HardcoverSync(auth).send(book(), identifiers)
        assertEquals(21, result.getInt("editionId"))
        assertEquals("book", result.getString("matchKind"))
        assertEquals(21, server.mutations().single().getJSONObject("variables").getJSONObject("read").getInt("edition_id"))
    }

    @Test fun firstSendCreatesOneReadingEntryAndRepeatDoesNotDuplicateIt() = runBlocking {
        val sync = HardcoverSync(auth)
        assertEquals("sent", sync.send(book(), identifiers).getString("outcome"))
        assertEquals(2, server.mutations().size)
        assertEquals(237, server.library.getJSONObject(0).getJSONArray("user_book_reads").getJSONObject(0).getInt("progress_pages"))
        val write = server.mutations().last().getJSONObject("variables").getJSONObject("read")
        assertEquals(setOf("edition_id", "progress_pages"), write.keys().asSequence().toSet())
        assertEquals("already_current", sync.send(book(), identifiers).getString("outcome"))
        assertEquals(2, server.mutations().size)
        assertEquals(1, server.library.length())
    }

    @Test fun advancesExistingReadAndKeepsHigherRemoteProgress() = runBlocking {
        server.withRead(200)
        val sync = HardcoverSync(auth)
        assertEquals("sent", sync.send(book(), identifiers).getString("outcome"))
        assertTrue(server.mutations().single().getString("query").contains("update_user_book_read"))
        assertEquals("kept_higher_remote_progress", sync.send(book("4000/10000"), identifiers).getString("outcome"))
        assertEquals(1, server.mutations().size)
    }

    @Test fun exactIsbnTenMatchesEquivalentThirteen() = runBlocking {
        server.tenOnly = true
        assertEquals("sent", HardcoverSync(auth).send(book(), identifiers).getString("outcome"))
        assertTrue(server.requests.first { it.optString("query").startsWith("query Match") }.getJSONObject("variables").getJSONArray("tens").toString().contains("139850825X"))
    }

    @Test fun pagelessEditionHoldsWithoutWrites() = runBlocking {
        server.editionPageCount = 0
        assertEquals("hardcover_page_count_missing", assertThrows(SyncProblem::class.java) { runBlocking { HardcoverSync(auth).send(book(), identifiers) } }.code)
        assertTrue(server.mutations().isEmpty())
    }

    @Test fun completedRereadEditionAndUnknownStatusConflictsNeverWrite() = runBlocking {
        val sync = HardcoverSync(auth)
        server.withRead(200, status = 3, finished = true)
        assertThrows(SyncProblem::class.java) { runBlocking { sync.send(book(), identifiers) } }
        server.withRead(200, finished = true)
        assertThrows(SyncProblem::class.java) { runBlocking { sync.send(book(), identifiers) } }
        server.withRead(200, edition = 99)
        assertThrows(SyncProblem::class.java) { runBlocking { sync.send(book(), identifiers) } }
        assertThrows(SyncProblem::class.java) { runBlocking { sync.send(book(status = "unrecognized"), identifiers) } }
        assertThrows(SyncProblem::class.java) { runBlocking { sync.send(book("10000/10000"), identifiers) } }
        assertTrue(server.mutations().isEmpty())
    }

    @Test fun mutationAndGraphqlErrorsCannotReportSuccess() = runBlocking {
        server.withRead(200)
        server.mutationRejected = true
        assertEquals("hardcover_mutation_rejected", assertThrows(SyncProblem::class.java) { runBlocking { HardcoverSync(auth).send(book(), identifiers) } }.code)
        assertEquals(200, server.library.getJSONObject(0).getJSONArray("user_book_reads").getJSONObject(0).getInt("progress_pages"))
        server.graphqlError = true
        assertThrows(SyncProblem::class.java) { runBlocking { HardcoverSync(auth).send(book(), identifiers) } }
        Unit
    }

    @Test fun deviceFlowHandlesPendingAndSlowDownBeforeEncryptedCredentials() = runBlocking {
        vault.clear()
        server.pendingResponses = 1
        server.slowDown = true
        val device = auth.begin()
        assertFalse(auth.poll(device))
        assertNull(vault.read())
        assertEquals("slow_down", assertThrows(HttpProblem::class.java) { runBlocking { auth.poll(device) } }.oauthError)
        assertTrue(auth.poll(device))
        assertEquals("old-refresh", vault.read()!!.refresh)
        val request = server.requests.first()
        assertEquals(HARDCOVER_CLIENT_ID, request.getString("client_id"))
        assertFalse(request.has("client_secret"))
        val disk = java.io.File(RuntimeEnvironment.getApplication().noBackupFilesDir, "hardcover.credentials").readText()
        assertFalse(disk.contains("old-refresh"))
        assertFalse(disk.contains("old-access"))
    }

    @Test fun unauthorizedAccessRefreshesOnceAndPersistsRotatedToken() = runBlocking {
        server.rejectOldAccess = true
        val data = auth.authorized { server.http.graphql(it, "query Identity { me { id } }") }
        assertEquals(1, data.getJSONArray("me").getJSONObject(0).getInt("id"))
        assertEquals("rotated-refresh", vault.read()!!.refresh)
        assertEquals(1, server.requests.count { it.optString("grant_type") == "refresh_token" })
        assertEquals("Bearer fresh-access", server.requests.last().getString("bearer"))
    }

    @Test fun expiredTokensRefreshBeforeQueriesAndInvalidGrantRequiresReconnect() = runBlocking {
        vault.write(OAuthTokens("old-access", "old-refresh", 0))
        auth.authorized { server.http.graphql(it, "query Identity { me { id } }") }
        assertEquals("refresh_token", server.requests.first().getString("grant_type"))
        vault.write(OAuthTokens("old-access", "old-refresh", 0))
        server.invalidRefresh = true
        assertThrows(HttpProblem::class.java) { runBlocking { auth.authorized { server.http.graphql(it, "query Identity { me { id } }") } } }
        assertNull(vault.read())
    }

    @Test fun disconnectClearsCredentialsAndRequestsRefreshTokenRevocation() = runBlocking {
        auth.disconnect()
        assertFalse(auth.connected())
        assertEquals("/oauth2/revoke", server.requests.single().getString("path"))
        assertEquals("old-refresh", server.requests.single().getString("token"))
    }

    @Test fun scheduledWorkerUsesFreshDetectedBookAndPreservesLocalChecksWhenSyncIsOff() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        app.hardcover = HardcoverConnection(app, auth) { true }
        val provider = FixtureProvider().withSyncBook()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        scheduleCollection(app)
        app.hardcover.setEnabled(true)
        app.visible = false
        TestListenableWorkerBuilder<ScheduledCheckWorker>(app).build().doWork()
        assertEquals(2, server.mutations().size)
        assertTrue(app.diagnostics.store.events().any { it.optString("source") == "scheduled" && it.optString("outcome") == "sent" && !it.getBoolean("appVisible") })
        val requests = server.requests.size
        provider.denied = true
        TestListenableWorkerBuilder<ScheduledCheckWorker>(app).build().doWork()
        assertEquals(requests, server.requests.size)
        assertTrue(app.diagnostics.store.events().any { it.optString("outcome") == "permission_denied" })
        provider.denied = false
        app.hardcover.setEnabled(false)
        TestListenableWorkerBuilder<ScheduledCheckWorker>(app).build().doWork()
        assertEquals(3, provider.calls.get())
        assertEquals(requests, server.requests.size)
        val export = buildExport(app.diagnostics).toString()
        assertFalse(export.contains("old-access"))
        assertFalse(export.contains("old-refresh"))
        assertFalse(export.contains("synthetic-device-secret"))
    }

    @Test fun devicePollingDoesNotShortenServerInterval() {
        server.deviceInterval = 120
        assertEquals(120, auth.begin().interval)
    }

    @Test fun anEditionForAnotherBookIsHeldEvenAtZeroProgress() = runBlocking {
        server.withRead(0, edition = 99)
        assertThrows(SyncProblem::class.java) { runBlocking { HardcoverSync(auth).send(book(), identifiers) } }
        assertTrue(server.mutations().isEmpty())
    }

    @Test fun cancellationDuringLibraryReadPreventsSubsequentWrites() = runBlocking {
        server.libraryEntered = CountDownLatch(1)
        server.libraryRelease = CountDownLatch(1)
        val sending = async(Dispatchers.IO) { HardcoverSync(auth).send(book(), identifiers) }
        try {
            assertTrue(server.libraryEntered!!.await(5, TimeUnit.SECONDS))
            sending.cancel()
        } finally {
            server.libraryRelease!!.countDown()
            sending.cancelAndJoin()
        }
        assertTrue(server.mutations().isEmpty())
    }

    @Test fun pollingScheduleAppliesSlowDownAndStopsAtExpiry() = runBlocking {
        vault.clear()
        server.pendingResponses = 1
        server.slowDown = true
        var elapsed = 0L
        val waits = mutableListOf<Long>()
        awaitDeviceAuthorization(auth, auth.begin(), now = { elapsed }, pause = {
            waits.add(it)
            elapsed += it
        })
        assertEquals(listOf(5000L, 5000L, 10_000L), waits)
        assertTrue(auth.connected())
        vault.clear()
        server.pendingResponses = 100
        waits.clear()
        val device = auth.begin().copy(expiresIn = 12)
        assertEquals(
            "hardcover_sign_in_expired",
            assertThrows(SyncProblem::class.java) {
                runBlocking {
                    awaitDeviceAuthorization(auth, device, now = { elapsed }, pause = {
                        waits.add(it)
                        elapsed += it
                    })
                }
            }.code
        )
        assertEquals(listOf(5000L, 5000L, 2000L), waits)
        assertFalse(auth.connected())
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun hardcoverToggleEnablesSavedConnectionOrStartsSignIn() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        app.hardcover = HardcoverConnection(app, auth) { true }
        val originalError = System.err
        val capturedError = java.io.ByteArrayOutputStream()
        val stream = java.io.PrintStream(capturedError, true, Charsets.UTF_8)
        System.setErr(stream)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val screen = androidx.lifecycle.ViewModelProvider(controller.get())[ScreenModel::class.java]
        fun descendants(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun toggle(): CheckBox? = descendants(controller.get().findViewById(android.R.id.content))
            .filterIsInstance<CheckBox>().singleOrNull { it.contentDescription?.startsWith("Hardcover:") == true }
        suspend fun awaitState(predicate: () -> Boolean) = withTimeout(10_000) {
            while (!predicate()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        try {
            awaitState { toggle()?.isEnabled == true && !screen.busy }
            toggle()!!.performClick()
            awaitState { app.hardcover.state().getBoolean("enabled") && toggle()?.isChecked == true && toggle()?.isEnabled == true && !screen.busy }
            assertTrue(server.requests.isEmpty())
            assertFalse(app.hardcover.signingIn)
            toggle()!!.performClick()
            awaitState { !app.hardcover.state().getBoolean("enabled") && toggle()?.isChecked == false && toggle()?.isEnabled == true && !screen.busy }
            vault.clear()
            app.diagnostics.updates.value++
            awaitState { app.hardcover.state().getBoolean("connected") == false && toggle()?.contentDescription?.contains("Not connected") == true }
            assertTrue(toggle()!!.isEnabled)
            toggle()!!.performClick()
            awaitState { server.requests.any { it.optString("path") == "/oauth2/device" } }
            awaitState { !app.hardcover.signingIn && app.hardcover.state().getBoolean("enabled") }
            assertTrue(auth.connected())
            assertTrue(server.requests.any { it.optString("grant_type").contains("device_code") })
            awaitState { toggle()?.isChecked == true && toggle()?.isEnabled == true && !screen.busy }
            toggle()!!.performClick()
            awaitState { !app.hardcover.state().getBoolean("enabled") && toggle()?.isChecked == false && !screen.busy }
            vault.clear()
            server.pendingResponses = 100
            app.diagnostics.updates.value++
            awaitState { toggle()?.contentDescription?.contains("Not connected") == true }
            toggle()!!.performClick()
            awaitState { app.hardcover.device != null && toggle()?.isChecked == true && !screen.busy }
            toggle()!!.performClick()
            awaitState { !app.hardcover.signingIn && !app.hardcover.state().getBoolean("enabled") }
            assertFalse(auth.connected())
            assertTrue(server.mutations().isEmpty())
        } finally {
            controller.pause().stop().destroy()
            System.setErr(originalError)
            stream.close()
            // Robolectric's CppAssetManager2 reports zero-ID lookups during widget construction.
            assertEquals(emptyList<String>(), capturedError.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && it != "Invalid ID 0x00000000." }.toList())
        }
    }

    @Test fun switchingOffFinishesCancellationBeforeASecondOnCanStart() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        vault.clear()
        val connection = HardcoverConnection(app, auth) { true }
        server.tokenEntered = CountDownLatch(1)
        server.tokenRelease = CountDownLatch(1)
        connection.setEnabled(true)
        try {
            assertTrue(server.tokenEntered!!.await(8, TimeUnit.SECONDS))
            val off = async(Dispatchers.IO) { connection.setEnabled(false) }
            withTimeout(5000) { while (!off.isCompleted && connection.signingIn) delay(10) }
            assertFalse(off.isCompleted)
            val on = async(Dispatchers.IO) { connection.setEnabled(true) }
            server.tokenRelease!!.countDown()
            off.await()
            on.await()
            withTimeout(10_000) { while (connection.signingIn) delay(10) }
            assertTrue(connection.state().getBoolean("connected"))
            assertTrue(connection.state().getBoolean("enabled"))
            assertTrue(server.mutations().isEmpty())
        } finally {
            server.tokenRelease!!.countDown()
            connection.setEnabled(false)
        }
    }

    @Test fun unreadableCredentialsExposeReconnectWithoutAddingEventsOnRefresh() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        val wrongKey = TokenVault(app) { SecretKeySpec(ByteArray(32) { 8 }, "AES") }
        val connection = HardcoverConnection(app, HardcoverAuth(server.http, wrongKey))
        val count = app.diagnostics.store.events().size
        val state = connection.state()
        assertFalse(state.getBoolean("connected"))
        assertTrue(state.getBoolean("credentialProblem"))
        connection.state()
        assertEquals(count, app.diagnostics.store.events().size)
        connection.setEnabled(true)
        assertTrue(connection.signingIn)
        connection.setEnabled(false)
        assertFalse(connection.signingIn)
        assertFalse(connection.state().getBoolean("enabled"))
    }
}
