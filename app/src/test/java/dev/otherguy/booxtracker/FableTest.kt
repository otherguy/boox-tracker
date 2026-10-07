package dev.otherguy.booxtracker

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.work.testing.TestListenableWorkerBuilder
import java.util.concurrent.CopyOnWriteArrayList
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

const val FABLE_EBOOK = "00000000-0000-4000-8000-000000000001"
const val FABLE_PAPERBACK = "00000000-0000-4000-8000-000000000002"
const val FABLE_OTHER = "00000000-0000-4000-8000-000000000009"

/** Fake Firebase and api.fable.co endpoints with the response shapes observed on 2026-10-06. */
class FableServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<JSONObject>()
    var account = "account-1"
    var wrongPassword = false
    var tooManyAttempts = false
    var invalidRefresh = false
    var refreshUnavailable = false
    var rejectOldToken = false
    var failProgressOnce = false
    var readBackMismatch = false
    var conflictOnShelve = false
    var shelveIgnored = false
    var autoFinish = true
    var listPageSize = 100
    var signInEntered: java.util.concurrent.CountDownLatch? = null
    var signInRelease: java.util.concurrent.CountDownLatch? = null
    val books = linkedMapOf<String, JSONObject>()
    val shelf = mutableMapOf<String, String>()
    val progress = mutableMapOf<String, JSONObject>()
    private val listTypes = listOf("want_to_read", "current_reading", "finished", "did_not_finish")

    init {
        book(FABLE_EBOOK, "9781398508255", family = 1, format = "eBook", pages = 480)
        book(FABLE_PAPERBACK, "9780000000002", family = 1, format = "Paperback", pages = 480, current = true)
        book(FABLE_OTHER, "9780000000009", family = 2, author = "Other Author")
    }

    fun book(id: String, isbn: String, title: String = "Synthetic Book", author: String = "Test Author", family: Int = 1, format: String = "eBook", pages: Int? = null, current: Boolean = false) {
        books[id] = JSONObject().put("id", id).put("title", title).put("authors", JSONArray().put(JSONObject().put("name", author))).put("isbn", isbn).put("display_isbn", isbn)
            .put("family_id", family).put("format", JSONObject().put("category", format)).put("page_count", pages ?: JSONObject.NULL).put("is_current_book", current)
    }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val path = url.encodedPath
                val text = request.body.readUtf8()
                val body = runCatching { JSONObject(text) }.getOrNull()
                requests.add(
                    JSONObject().put("method", request.method).put("path", path).put("query", url.encodedQuery ?: JSONObject.NULL)
                        .put("auth", request.getHeader("Authorization") ?: JSONObject.NULL).put("body", body ?: text)
                )
                val (status, response) = route(request.method!!, path, url, body, request.getHeader("Authorization"))
                return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(response?.toString().orEmpty())
            }
        }
        start()
    }
    val http = FableHttp(api = origin(), identity = origin(), secureToken = origin(), key = "test-key")

    private fun origin() = server.url("/").toString().removeSuffix("/")

    private fun error(message: String) = JSONObject().put("error", JSONObject().put("code", 400).put("message", message))

    private fun route(method: String, path: String, url: okhttp3.HttpUrl, body: JSONObject?, token: String?): Pair<Int, JSONObject?> {
        if (path.startsWith("/identitytoolkit/") || path == "/v1/token") check(url.queryParameter("key") == "test-key")
        if (path.startsWith("/identitytoolkit/")) {
            signInEntered?.countDown()
            signInRelease?.await(10, java.util.concurrent.TimeUnit.SECONDS)
        }
        when {
            path == "/identitytoolkit/v3/relyingparty/verifyPassword" -> return when {
                tooManyAttempts -> 400 to error("TOO_MANY_ATTEMPTS_TRY_LATER : Access to this account has been temporarily disabled")
                wrongPassword -> 400 to error("INVALID_LOGIN_CREDENTIALS")
                else -> 200 to JSONObject().put("idToken", "old-id").put("refreshToken", "old-refresh").put("expiresIn", "3600").put("localId", "firebase-user")
            }

            path == "/v1/token" -> return when {
                refreshUnavailable -> 503 to JSONObject()
                invalidRefresh -> 400 to error("TOKEN_EXPIRED")
                else -> 200 to JSONObject().put("id_token", "fresh-id").put("refresh_token", "rotated-refresh").put("expires_in", "3600")
            }
        }
        if (token != "JWT fresh-id" && (token != "JWT old-id" || rejectOldToken)) return 403 to JSONObject().put("detail", "Authentication credentials were not provided.")
        val segments = path.trim('/').split('/')
        return when {
            path == "/api/settings/profile/" -> 200 to JSONObject().put("id", account).put("email", "reader@example.com")

            path == "/api/books/search/" -> {
                val query = url.queryParameter("auto").orEmpty().uppercase()
                val exact = books.values.filter { it.optString("isbn") == query || it.optString("display_isbn") == query }
                val results = if (exact.isNotEmpty()) exact + books.getValue(FABLE_OTHER) else books.values.filter { query.contains(it.getString("title").uppercase()) }
                200 to JSONObject().put("response", JSONObject().put("books", JSONArray(results.distinct().map { JSONObject(it.toString()).put("page_count", JSONObject.NULL) })))
            }

            segments.size == 4 && segments[1] == "books" && segments[3] == "editions" -> {
                val family = books[segments[2]]?.optInt("family_id") ?: return 404 to JSONObject()
                200 to JSONObject().put("results", JSONArray(books.values.filter { it.optInt("family_id") == family }))
            }

            segments.size == 4 && segments[1] == "books" && segments[3] == "reading_progress" && method == "GET" -> {
                val value = JSONObject((progress[segments[2]] ?: unread()).toString())
                if (readBackMismatch && progress.containsKey(segments[2])) value.put("current_percentage", value.getInt("current_percentage") - 1)
                200 to value
            }

            segments.size == 4 && segments[1] == "books" && segments[3] == "reading_progress" -> {
                val id = segments[2]
                val percent = body!!.get("current_percentage")
                check(percent is Int && body.getJSONArray("social_accounts").length() == 0 && body.getString("status") == "reading" && body.getString("selected_mode") == "percentage")
                val done = percent == 100
                progress[id] = JSONObject().put("current_percentage", percent).put("current_page", percent * 480 / 100).put("page_count", 480)
                    .put("status", if (done) "finished" else "reading").put("selected_mode", if (done) JSONObject.NULL else "percentage")
                if (done && autoFinish) shelf[id] = "finished"
                if (failProgressOnce) {
                    failProgressOnce = false
                    return 503 to JSONObject()
                }
                201 to progress[id]
            }

            segments.size == 3 && segments[1] == "books" -> books[segments[2]]?.let { 200 to JSONObject().put("response", JSONObject(it.toString()).put("status", shelf[segments[2]] ?: JSONObject.NULL)) } ?: (404 to JSONObject())

            path == "/api/v2/users/$account/book_lists" -> 200 to JSONObject().put(
                "results",
                JSONArray(listTypes.map { JSONObject().put("id", "list-$it").put("type", "system").put("system_type", it).put("count", shelf.values.count { s -> s == it }) } + JSONObject().put("id", "custom").put("type", "custom"))
            )

            path.startsWith("/api/v2/users/$account/book_lists/list-") && path.endsWith("/books") -> {
                val type = segments[5].removePrefix("list-")
                val offset = url.queryParameter("offset")!!.toInt()
                val limit = url.queryParameter("limit")!!.toInt()
                val all = shelf.filterValues { it == type }.keys.sorted()
                val entries = all.drop(offset).take(minOf(limit, listPageSize)).map { JSONObject().put("book", JSONObject().put("id", it)).put("source", "app") }
                val next = if (offset + entries.size < all.size) "/api/v2/next-page" else JSONObject.NULL
                200 to JSONObject().put("count", all.size).put("next", next).put("previous", JSONObject.NULL).put("results", JSONArray(entries))
            }

            path == "/api/v2/users/$account/book_lists/book" -> {
                val id = body!!.getString("book_id")
                val target = body.getJSONArray("book_list_ids").getString(0).removePrefix("list-")
                check(body.getString("type") == "multiselect" && body.getJSONArray("exclude_from").length() == 3)
                if (!shelveIgnored) shelf[id] = target
                if (conflictOnShelve) 409 to JSONObject() else 200 to null
            }

            else -> 404 to JSONObject()
        }
    }

    private fun unread() = JSONObject().put("current_percentage", 0).put("current_page", 0).put("page_count", JSONObject.NULL).put("status", "unread").put("selected_mode", JSONObject.NULL)

    fun writes(): List<JSONObject> = requests.filter { it.getString("method") == "POST" && it.getString("path").startsWith("/api/") }
    fun progressWrites(): List<JSONObject> = writes().filter { it.getString("path").endsWith("/reading_progress") }
    fun shelfWrites(): List<JSONObject> = writes().filter { it.getString("path").endsWith("/book_lists/book") }
    fun apiCalls(): List<JSONObject> = requests.filter { it.getString("path").startsWith("/api/") }
    override fun close() = server.shutdown()
}

fun fableVault(app: ReadingSyncApp) = TokenVault(app, "fable") { SecretKeySpec(ByteArray(32) { 9 }, "AES") }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class FableTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: FableServer
    private lateinit var vault: TokenVault
    private lateinit var auth: FableAuth
    private val identifiers = BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author")

    @Before fun setup() {
        server = FableServer()
        grantTestFolder(app)
        vault = fableVault(app)
        vault.write(OAuthTokens("old-id", "old-refresh", System.currentTimeMillis() + 3_600_000))
        auth = FableAuth(server.http, vault)
    }

    @After fun close() = runBlocking {
        server.close()
        vault.clear()
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        store.close()
        closeWorkDatabase()
    }

    private fun raw(value: String) = JSONObject().put("state", "value").put("raw", value)
    private fun book(progress: String = "4723/10000", status: String = "1", key: String = "first") = JSONObject().put("key", key).put("progress", raw(progress)).put("readingStatus", raw(status))
        .put("title", raw("Synthetic Book")).put("authors", raw("Test Author")).put("ISBN", raw("9781398508255"))
    private fun check(book: JSONObject) = JSONObject().put("outcome", "success").put("timestamp", "2026-10-06T10:00:00Z").put("selected", book)
    private fun sync(maySend: () -> Boolean = { true }) = FableSync(auth, store, maySend)
    private fun held(block: suspend () -> Unit) = assertThrows(SyncProblem::class.java) { runBlocking { block() } }.code

    private fun popup() = (org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)?.takeIf { it.isShowing && it.findViewById<EditText>(R.id.fable_email) != null }

    private fun capturingStderr(block: suspend () -> Unit) = runBlocking {
        val originalError = System.err
        val capturedError = java.io.ByteArrayOutputStream()
        val stream = java.io.PrintStream(capturedError, true, Charsets.UTF_8)
        System.setErr(stream)
        try {
            block()
        } finally {
            System.setErr(originalError)
            stream.close()
        }
        // Robolectric's CppAssetManager2 reports zero-ID lookups during widget construction.
        assertEquals(emptyList<String>(), capturedError.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && it != "Invalid ID 0x00000000." }.toList())
    }

    @Test fun flooredPercentNeverRoundsUp() {
        assertEquals(50, flooredPercent("5007/10000"))
        assertEquals(99, flooredPercent("9999/10000"))
        assertEquals(100, flooredPercent("10000/10000"))
        assertEquals("progress_unavailable", assertThrows(SyncProblem::class.java) { flooredPercent("1/0") }.code)
    }

    @Test fun signInStoresOnlyEncryptedTokens() = runBlocking {
        vault.clear()
        auth.signIn("reader@example.com", "secret-password")
        assertEquals("old-refresh", vault.read()!!.refresh)
        val login = server.requests.single()
        assertEquals("/identitytoolkit/v3/relyingparty/verifyPassword", login.getString("path"))
        assertEquals("secret-password", login.getJSONObject("body").getString("password"))
        assertTrue(login.getJSONObject("body").getBoolean("returnSecureToken"))
        val disk = java.io.File(app.noBackupFilesDir, "fable.credentials").readText()
        listOf("old-id", "old-refresh", "secret-password", "reader@example.com").forEach { assertFalse(it, disk.contains(it)) }
    }

    @Test fun rejectedSignInReportsTheFirebaseCodeAndStoresNothing() = runBlocking {
        vault.clear()
        server.wrongPassword = true
        val wrong = assertThrows(HttpProblem::class.java) { runBlocking { auth.signIn("reader@example.com", "wrong") } }
        assertEquals("fable_http_400_invalid_login_credentials", failureReason(wrong))
        server.tooManyAttempts = true
        val limited = assertThrows(HttpProblem::class.java) { runBlocking { auth.signIn("reader@example.com", "wrong") } }
        assertEquals("fable_http_400_too_many_attempts_try_later", failureReason(limited))
        assertNull(vault.read())
        assertTrue(server.apiCalls().isEmpty())
    }

    @Test fun expiredOrRejectedIdTokenRefreshesOnceAndPersistsRotation() = runBlocking {
        vault.write(OAuthTokens("old-id", "old-refresh", 0))
        assertEquals("account-1", auth.authorized { server.http.get(it, "/api/settings/profile/") }.getString("id"))
        assertEquals("/v1/token", server.requests.first().getString("path"))
        assertEquals("rotated-refresh", vault.read()!!.refresh)
        vault.write(OAuthTokens("old-id", "old-refresh", System.currentTimeMillis() + 3_600_000))
        server.requests.clear()
        server.rejectOldToken = true
        auth.authorized { server.http.get(it, "/api/settings/profile/") }
        assertEquals(1, server.requests.count { it.getString("path") == "/v1/token" })
        assertEquals("JWT fresh-id", server.requests.last().getString("auth"))
    }

    @Test fun deadRefreshTokenClearsTheSessionButAnOutageKeepsIt() = runBlocking {
        vault.write(OAuthTokens("old-id", "old-refresh", 0))
        server.refreshUnavailable = true
        val outage = assertThrows(HttpProblem::class.java) { runBlocking { auth.authorized { server.http.get(it, "/api/settings/profile/") } } }
        assertTrue(temporaryFailure(outage))
        assertTrue(auth.connected())
        server.refreshUnavailable = false
        server.invalidRefresh = true
        assertEquals("fable_http_400_token_expired", failureReason(assertThrows(HttpProblem::class.java) { runBlocking { auth.authorized { server.http.get(it, "/api/settings/profile/") } } }))
        assertFalse(auth.connected())
    }

    @Test fun hardcoverAndFableCredentialsUseSeparateFiles() {
        val hardcover = TokenVault(app) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        hardcover.write(OAuthTokens("hardcover-access", "hardcover-refresh", 1))
        try {
            assertEquals("old-refresh", vault.read()!!.refresh)
            assertTrue(java.io.File(app.noBackupFilesDir, "hardcover.credentials").exists())
            vault.clear()
            assertEquals("hardcover-refresh", hardcover.read()!!.refresh)
        } finally {
            hardcover.clear()
        }
    }

    @Test fun unshelvedBookIsShelvedBeforeAFlooredPercentageWrite() = runBlocking {
        val result = sync().send(book(), identifiers, "account-1")
        assertEquals("sent", result.getString("outcome"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(47, result.getInt("percent"))
        assertEquals("current_reading", server.shelf[FABLE_EBOOK])
        val writes = server.writes()
        assertEquals(listOf("/api/v2/users/account-1/book_lists/book", "/api/books/$FABLE_EBOOK/reading_progress"), writes.map { it.getString("path") })
        val shelve = writes[0].getJSONObject("body")
        assertEquals("list-current_reading", shelve.getJSONArray("book_list_ids").getString(0))
        val body = writes[1].getJSONObject("body")
        assertEquals(setOf("status", "social_accounts", "current_percentage", "selected_mode"), body.keys().asSequence().toSet())
        assertEquals(47, body.getInt("current_percentage"))
    }

    @Test fun repeatIsCurrentAndHigherRemoteProgressIsKept() = runBlocking {
        val sync = sync()
        sync.send(book(), identifiers, "account-1")
        assertEquals("already_current", sync.send(book(), identifiers, "account-1").getString("outcome"))
        assertEquals("kept_higher_remote_progress", sync.send(book("4000/10000"), identifiers, "account-1").getString("outcome"))
        assertEquals(2, server.writes().size)
    }

    @Test fun wantToReadIsMovedAndAnAlreadyShelvedConflictIsAccepted() = runBlocking {
        server.shelf[FABLE_EBOOK] = "want_to_read"
        server.conflictOnShelve = true
        assertEquals("sent", sync().send(book(), identifiers, "account-1").getString("outcome"))
        assertEquals("current_reading", server.shelf[FABLE_EBOOK])
        assertEquals(1, server.progressWrites().size)
    }

    @Test fun anEditionTheUserShelvedReceivesTheProgress() = runBlocking {
        server.shelf[FABLE_PAPERBACK] = "want_to_read"
        val result = sync().send(book(), identifiers, "account-1")
        assertEquals(FABLE_PAPERBACK, result.getString("bookId"))
        assertTrue(result.getBoolean("existingEditionPreserved"))
        assertEquals("book", result.getString("matchKind"))
        assertEquals("current_reading", server.shelf[FABLE_PAPERBACK])
        assertNull(server.shelf[FABLE_EBOOK])
        assertEquals("/api/books/$FABLE_PAPERBACK/reading_progress", server.progressWrites().single().getString("path"))
    }

    @Test fun finishedOrAbandonedEditionsHoldAReadingSource() = runBlocking {
        server.shelf[FABLE_PAPERBACK] = "finished"
        assertEquals("fable_status_conflict", held { sync().send(book(), identifiers, "account-1") })
        server.shelf[FABLE_PAPERBACK] = "did_not_finish"
        assertEquals("fable_status_conflict", held { sync().send(book(), identifiers, "account-1") })
        assertTrue(server.writes().isEmpty())
    }

    @Test fun completionWritesFullProgressAndConfirmsTheFinishedShelf() = runBlocking {
        server.shelf[FABLE_EBOOK] = "current_reading"
        val result = sync().send(book("10000/10000", "2"), identifiers, "account-1")
        assertEquals("sent", result.getString("outcome"))
        assertEquals("finished", result.getString("shelfAfter"))
        assertEquals(100, server.progressWrites().single().getJSONObject("body").getInt("current_percentage"))
        assertTrue(server.shelfWrites().isEmpty())
        assertEquals("already_current", sync().send(book("10000/10000", "2"), identifiers, "account-1").getString("outcome"))
        assertEquals(1, server.writes().size)
    }

    @Test fun completionShelvesFinishedWhenFableDoesNotMoveTheBook() = runBlocking {
        server.autoFinish = false
        sync().send(book("10000/10000", "2"), identifiers, "account-1")
        assertEquals("finished", server.shelf[FABLE_EBOOK])
        assertEquals(listOf("list-current_reading", "list-finished"), server.shelfWrites().map { it.getJSONObject("body").getJSONArray("book_list_ids").getString(0) })
    }

    @Test fun sourceStatusRulesHoldBeforeAnyRequest() = runBlocking {
        assertEquals("source_finish_progress_mismatch", held { sync().send(book("5000/10000", "2"), identifiers) })
        assertEquals("source_status_not_finished", held { sync().send(book("10000/10000", "1"), identifiers) })
        assertEquals("source_status_unsupported", held { sync().send(book(status = "0"), identifiers) })
        assertTrue(server.apiCalls().isEmpty())
    }

    @Test fun guardsHoldWithoutProgressWrites() = runBlocking {
        assertEquals("fable_account_changed", held { sync().send(book(), identifiers, "another-account") })
        assertEquals("service_disabled", held { sync { false }.send(book(), identifiers, "account-1") })
        server.shelf[FABLE_EBOOK] = "current_reading"
        server.progress[FABLE_EBOOK] = JSONObject().put("current_percentage", 10).put("current_page", 48).put("page_count", 480).put("status", "reading").put("selected_mode", "page_count")
        assertEquals("fable_progress_mode_conflict", held { sync().send(book(), identifiers, "account-1") })
        assertTrue(server.writes().isEmpty())
        server.progress.remove(FABLE_EBOOK)
        server.shelf.remove(FABLE_EBOOK)
        server.shelveIgnored = true
        assertEquals("fable_shelf_not_applied", held { sync().send(book(), identifiers, "account-1") })
        assertTrue(server.progressWrites().isEmpty())
    }

    @Test fun unconfirmedProgressHolds() = runBlocking {
        server.readBackMismatch = true
        assertEquals("fable_progress_not_applied", held { sync().send(book(), identifiers, "account-1") })
    }

    @Test fun largeFamiliesHold() = runBlocking {
        (10..30).forEach { server.book("00000000-0000-4000-8000-0000000000$it", "97800000001$it") }
        assertEquals("fable_family_too_large", held { sync().send(book(), identifiers, "account-1") })
        assertTrue(server.writes().isEmpty())
    }

    @Test fun anUncertainWriteIsReconciledWithoutASecondPost() = runBlocking {
        server.failProgressOnce = true
        assertTrue(temporaryFailure(assertThrows(HttpProblem::class.java) { runBlocking { sync().send(book(), identifiers, "account-1") } }))
        assertEquals("already_current", sync().send(book(), identifiers, "account-1").getString("outcome"))
        assertEquals(1, server.progressWrites().size)
    }

    @Test fun signInFromTheSwitchConnectsWithoutStoringThePassword() = runBlocking {
        vault.clear()
        val connection = FableConnection(app, auth) { true }
        app.fable = connection
        connection.setEnabled(true)
        assertTrue(connection.awaitingCredentials)
        assertTrue(server.requests.isEmpty())
        connection.signIn("reader@example.com", "secret-password")
        withTimeout(10_000) { while (connection.signingIn || !connection.state().getBoolean("enabled")) delay(10) }
        assertFalse(connection.awaitingCredentials)
        assertEquals("account-1", store.get("fable.account"))
        val export = buildExport(app.diagnostics).toString()
        listOf("secret-password", "old-id", "old-refresh").forEach { assertFalse(it, export.contains(it)) }
        assertFalse(store.events().any { it.toString().contains("secret-password") })
    }

    @Test fun rejectedSignInStaysPendingWithTheReasonUntilCancelled() = runBlocking {
        vault.clear()
        server.wrongPassword = true
        val connection = FableConnection(app, auth) { true }
        connection.setEnabled(true)
        connection.signIn("reader@example.com", "wrong")
        withTimeout(10_000) { while (connection.signingIn) delay(10) }
        val state = connection.state()
        assertFalse(state.getBoolean("enabled"))
        assertFalse(state.getBoolean("connected"))
        assertTrue(connection.awaitingCredentials)
        assertEquals("fable_http_400_invalid_login_credentials", state.getString("connectionError"))
        server.wrongPassword = false
        connection.signIn("reader@example.com", "secret-password")
        withTimeout(10_000) { while (connection.signingIn || !connection.state().getBoolean("enabled")) delay(10) }
        assertFalse(connection.awaitingCredentials)
        assertEquals("", connection.state().getString("connectionError"))
        connection.setEnabled(false)
        assertFalse(connection.state().getBoolean("enabled"))
    }

    @Test fun cancellingAPendingSignInTurnsFableOff() = runBlocking {
        vault.clear()
        server.wrongPassword = true
        val connection = FableConnection(app, auth) { true }
        connection.setEnabled(true)
        connection.signIn("reader@example.com", "wrong")
        withTimeout(10_000) { while (connection.signingIn) delay(10) }
        connection.setEnabled(false)
        assertFalse(connection.awaitingCredentials)
        assertEquals("", connection.state().getString("connectionError"))
    }

    @Test fun offlineFableQueueIsSeparateFromHardcoverAndDeliversLater() = runBlocking {
        var online = false
        val connection = FableConnection(app, auth) { online }
        store.put("lastCheck", check(book()).toString())
        store.put("fable.account", "account-1")
        connection.setEnabled(true)
        connection.send("manual", check(book()))
        assertEquals(1, store.pending("account-1", "fable").size)
        assertTrue(store.pending("account-1").isEmpty())
        assertTrue(server.requests.isEmpty())
        online = true
        assertFalse(connection.drain("delivery"))
        assertTrue(store.pending("account-1", "fable").isEmpty())
        assertEquals("synced", connection.state().getJSONObject("last").getString("delivery"))
    }

    @Test fun aHardcoverFailureDoesNotStopFableDeliveryOrCollection() = runBlocking {
        app.hardcover = HardcoverConnection(app, HardcoverAuth(HardcoverHttp("http://127.0.0.1:9"), TokenVault(app) { SecretKeySpec(ByteArray(32) { 7 }, "AES") })) { error("network check failed") }
        store.put("hardcover.enabled", "true")
        store.put("hardcover.account", "1")
        store.enqueue("1", book(), identifiers, "earlier")
        app.fable = FableConnection(app, auth) { true }
        store.put("fable.account", "account-1")
        app.fable.setEnabled(true)
        store.enqueue("account-1", book(), identifiers, "earlier", "fable")
        TestListenableWorkerBuilder<DeliveryWorker>(app).build().doWork()
        assertEquals(1, server.progressWrites().size)
        assertTrue(store.events().any { it.optString("kind") == "worker_failed" && it.optString("service") == "hardcover" })
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        store.put("hardcover.account", "")
        app.sync("manual", "sync_now")
        assertTrue(store.events().any { it.optString("kind") == "hardcover_sync" && it.optString("reason") == "operation_failed_IllegalStateException" })
        // The collected book is at 42%, below the 47% already delivered, so Fable keeps the higher value.
        assertTrue(store.events().any { it.optString("kind") == "fable_sync" && it.optString("outcome") == "kept_higher_remote_progress" })
    }

    @Test fun shelfMembershipFollowsSmallerPages() = runBlocking {
        server.listPageSize = 2
        listOf("00000000-0000-0000-0000-000000000000", "00000000-0000-0000-0000-000000000001").forEach { server.shelf[it] = "current_reading" }
        server.shelf[FABLE_PAPERBACK] = "current_reading"
        val result = sync().send(book(), identifiers, "account-1")
        assertEquals(FABLE_PAPERBACK, result.getString("bookId"))
        assertTrue(server.shelfWrites().isEmpty())
    }

    @Test fun switchingOffDuringSignInDiscardsTheNewSession() = runBlocking {
        vault.clear()
        server.signInEntered = java.util.concurrent.CountDownLatch(1)
        server.signInRelease = java.util.concurrent.CountDownLatch(1)
        val connection = FableConnection(app, auth) { true }
        connection.setEnabled(true)
        connection.signIn("reader@example.com", "secret-password")
        try {
            assertTrue(server.signInEntered!!.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val off = async(Dispatchers.IO) { connection.setEnabled(false) }
            server.signInRelease!!.countDown()
            off.await()
            withTimeout(5000) { while (connection.signingIn) delay(10) }
            assertFalse(connection.state().getBoolean("enabled"))
            assertFalse(connection.state().getBoolean("connected"))
            assertFalse(connection.awaitingCredentials)
        } finally {
            server.signInRelease!!.countDown()
        }
    }

    @Test fun switchingOffAfterSignInPausesWithoutDiscardingTheSession() = runBlocking {
        vault.clear()
        val provider = FixtureProvider().withSyncBook().apply {
            entered = java.util.concurrent.CountDownLatch(1)
            release = java.util.concurrent.CountDownLatch(1)
        }
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val connection = FableConnection(app, auth) { true }
        app.fable = connection
        connection.setEnabled(true)
        connection.signIn("reader@example.com", "secret-password")
        try {
            assertTrue(provider.entered!!.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(connection.signingIn)
            connection.setEnabled(false)
            assertTrue(connection.state().getBoolean("connected"))
            assertFalse(connection.state().getBoolean("enabled"))
            assertEquals("account-1", store.get("fable.account"))
        } finally {
            provider.release!!.countDown()
        }
    }

    @Test fun disconnectDuringSignInClearsTheSessionAndAccount() = runBlocking {
        vault.clear()
        server.signInEntered = java.util.concurrent.CountDownLatch(1)
        server.signInRelease = java.util.concurrent.CountDownLatch(1)
        val connection = FableConnection(app, auth) { true }
        connection.setEnabled(true)
        connection.signIn("reader@example.com", "secret-password")
        try {
            assertTrue(server.signInEntered!!.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val disconnect = async(Dispatchers.IO) { connection.disconnect() }
            server.signInRelease!!.countDown()
            disconnect.await()
            assertFalse(connection.state().getBoolean("connected"))
            assertFalse(connection.state().getBoolean("enabled"))
            assertEquals("", store.get("fable.account"))
            assertTrue(store.events().any { it.optString("kind") == "fable_connection" && it.optString("outcome") == "disconnected" })
        } finally {
            server.signInRelease!!.countDown()
        }
    }

    @Test fun interruptedFableSendIsReportedOnRecovery() {
        store.put("fable.active", JSONObject().put("source", "delivery").put("runId", "run").toString())
        app.diagnostics.recover()
        assertTrue(store.events().any { it.optString("kind") == "fable_interruption_detected" && it.optString("runId") == "run" })
        assertEquals("", store.get("fable.active"))
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun rejectedSignInKeepsThePopupOpenAndOnlyAnEnabledFableWarns() = capturingStderr {
        vault.clear()
        server.wrongPassword = true
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        app.fable = FableConnection(app, auth) { true }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun toggle() = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().singleOrNull { it.contentDescription?.startsWith("Fable:") == true }
        suspend fun awaitUi(predicate: () -> Boolean) = withTimeout(10_000) {
            while (!predicate()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        try {
            awaitUi { toggle()?.isEnabled == true && !screen.busy }
            assertNull(activity.findViewById<View>(R.id.access_warning))
            toggle()!!.performClick()
            awaitUi { popup() != null && !screen.busy }
            val dialog = popup()!!
            assertNull(activity.findViewById<EditText>(R.id.fable_email))
            dialog.findViewById<EditText>(R.id.fable_email)!!.setText("reader@example.com")
            dialog.findViewById<EditText>(R.id.fable_password)!!.setText("wrong")
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            awaitUi { !app.fable.signingIn && !screen.busy && dialog.findViewById<TextView>(R.id.fable_sign_in_status)?.text?.toString() == "Email or password not accepted" }
            assertTrue(dialog.isShowing)
            assertTrue(toggle()!!.isChecked)
            assertEquals("reader@example.com", dialog.findViewById<EditText>(R.id.fable_email)!!.text.toString())
            assertEquals("", dialog.findViewById<EditText>(R.id.fable_password)!!.text.toString())
            assertNull(activity.findViewById<View>(R.id.access_warning))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            awaitUi { !dialog.isShowing && !app.fable.awaitingCredentials && toggle()?.isChecked == false && !screen.busy }
            store.put("fable.enabled", "true")
            app.diagnostics.updates.value++
            awaitUi { activity.findViewById<View>(R.id.access_warning) != null && toggle()?.contentDescription?.contains("Reconnect required") == true }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun theSwitchOpensTheSignInPopupAndConnects() = capturingStderr {
        vault.clear()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        app.fable = FableConnection(app, auth) { true }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun toggle() = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<CheckBox>().singleOrNull { it.contentDescription?.startsWith("Fable:") == true }
        suspend fun awaitUi(predicate: () -> Boolean) = withTimeout(10_000) {
            while (!predicate()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        try {
            awaitUi { toggle()?.isEnabled == true && !screen.busy }
            toggle()!!.performClick()
            awaitUi { popup() != null && !screen.busy }
            assertTrue(server.requests.isEmpty())
            val dialog = popup()!!
            val signIn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            dialog.findViewById<EditText>(R.id.fable_email)!!.setText("reader@example.com")
            assertFalse(signIn.isEnabled)
            dialog.findViewById<EditText>(R.id.fable_password)!!.setText("secret-password")
            assertTrue(signIn.isEnabled)
            // The Sync screen rebuilds its rows on every update; a new switch view proves the update ran.
            val before = toggle()
            app.diagnostics.updates.value++
            awaitUi { toggle().let { it != null && it !== before } && !screen.busy }
            assertTrue(dialog === popup())
            assertEquals("secret-password", dialog.findViewById<EditText>(R.id.fable_password)!!.text.toString())
            signIn.performClick()
            awaitUi { app.fable.state().getBoolean("enabled") && !app.fable.signingIn && !dialog.isShowing && toggle()?.isChecked == true && !screen.busy }
            assertEquals("", screen.fablePassword)
            assertTrue(server.requests.any { it.getString("path").endsWith("verifyPassword") })
            assertFalse(buildExport(app.diagnostics).toString().contains("secret-password"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
