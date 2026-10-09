package dev.otherguy.booxtracker

import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.children
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class PageboundTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: PageboundServer
    private lateinit var vault: TokenVault
    private lateinit var auth: PageboundAuth
    private val identifiers = BookIdentifiers(setOf(PAGEBOUND_PAPERBACK_ISBN, PAGEBOUND_EBOOK_ISBN), "Synthetic Book", "Test Author")
    private val defaultZone = java.util.TimeZone.getDefault()

    @Before fun setup() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
        server = PageboundServer()
        grantTestFolder(app)
        vault = pageboundVault(app)
        vault.write(OAuthTokens("api-id-1", "refresh-1", Long.MAX_VALUE))
        auth = PageboundAuth(server.http, vault)
    }

    @After fun close() = runBlocking {
        java.util.TimeZone.setDefault(defaultZone)
        server.close()
        vault.clear()
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        store.close()
        closeWorkDatabase()
    }

    private fun raw(value: String) = JSONObject().put("state", "value").put("raw", value)

    // 1791225000000 is 2026-10-05T18:30Z, which is Tuesday 2026-10-06 in the Asia/Bangkok test zone.
    private fun book(progress: String = "4723/10000", status: String = "1", key: String = "first") = JSONObject().put("key", key).put("progress", raw(progress)).put("readingStatus", raw(status))
        .put("title", raw("Synthetic Book")).put("authors", raw("Test Author")).put("lastAccess", raw("1791225000000"))
    private fun check(book: JSONObject) = JSONObject().put("outcome", "success").put("timestamp", "2026-10-06T10:00:00Z").put("selected", book)
    private fun sync(maySend: () -> Boolean = { true }) = PageboundSync(auth, store, maySend)
    private fun send(progress: String = "4723/10000", status: String = "1", ids: BookIdentifiers = identifiers) = runBlocking { sync().send(book(progress, status), ids, "account-uuid-1") }
    private fun held(block: () -> Unit) = assertThrows(SyncProblem::class.java) { block() }.code

    @Test fun signInExchangesTheFirebaseTokenAndStoresOnlyTokens() = runBlocking {
        vault.clear()
        auth.signIn("reader@example.com", "secret-password")
        assertEquals(OAuthTokens("api-id-1", "refresh-1", Long.MAX_VALUE), vault.read())
        val exchange = server.requests.single { it.getString("path") == "/api/v1/auth/firebase_auth" }
        assertEquals("Bearer null", exchange.getString("auth"))
        assertEquals("id-1", exchange.getJSONObject("body").getString("id_token"))
    }

    @Test fun aRejectedTokenIsRenewedOnceAndADeadRefreshTokenEndsTheSession() {
        // Pagebound rejects a token with an empty 500; one Firebase refresh and exchange renews it.
        server.revoked.add("api-id-1")
        assertEquals("sent", send().getString("outcome"))
        assertEquals(OAuthTokens("api-id-2", "refresh-2", Long.MAX_VALUE), vault.read())
        assertEquals(1, server.requests.count { it.getString("path") == "/v1/token" })
        server.revoked.add("api-id-2")
        server.invalidRefresh = true
        val error = assertThrows(HttpProblem::class.java) { send("6000/10000") }
        assertEquals("pagebound_http_400_token_expired", failureReason(error))
        assertNull(vault.read())
    }

    @Test fun aTokenRefusedAgainAfterRenewalEndsTheSession() {
        server.revoked.addAll(listOf("api-id-1", "api-id-2"))
        assertEquals("pagebound_session_expired", held { send() })
        assertNull(vault.read())
        assertTrue(server.writes().isEmpty())
    }

    @Test fun anEndedSessionIsOneIssueOnTheRow() = runBlocking {
        val connection = PageboundConnection(app, auth) { true }
        store.put("pagebound.account", "account-uuid-1")
        connection.setEnabled(true)
        store.put("lastCheck", check(book()).toString())
        server.revoked.addAll(listOf("api-id-1", "api-id-2"))
        connection.send("manual", check(book()))
        val state = connection.state()
        assertFalse(state.getBoolean("connected"))
        assertEquals("pagebound_session_expired", state.getString("connectionError"))
        assertEquals("pagebound_session_expired", state.getJSONObject("last").getString("reason"))
    }

    @Test fun anEmptyServerErrorOnAWriteIsTemporaryAndKeepsTheToken() {
        server.entry("current")
        server.emptyProgressFailureOnce = true
        assertTrue(temporaryFailure(assertThrows(HttpProblem::class.java) { send() }))
        assertEquals(0, server.requests.count { it.getString("path") == "/v1/token" })
        assertEquals(OAuthTokens("api-id-1", "refresh-1", Long.MAX_VALUE), vault.read())
    }

    @Test fun aServerErrorWithABodyIsTemporaryAndKeepsTheSession() {
        server.entry("current")
        server.failProgressOnce = true
        val error = assertThrows(HttpProblem::class.java) { send() }
        assertTrue(temporaryFailure(error))
        assertEquals(0, server.requests.count { it.getString("path") == "/v1/token" })
        // Pagebound answered and refused the update, so the retry posts it.
        assertEquals("sent", send().getString("outcome"))
        assertEquals(listOf(45, 45), server.progressWrites())
    }

    @Test fun aBookNotInTheLibraryIsAddedAsADigitalReadOnTheEbookEdition() {
        val result = send("1400/10000")
        val add = server.writes().first().getJSONObject("body")
        assertEquals("/api/v1/user_books", server.writes().first().getString("path"))
        assertEquals("current", add.getJSONObject("user_book").getString("status"))
        assertEquals(7646, add.getJSONObject("user_book").getInt("book_id"))
        // Both ISBNs name editions of one book; the Kindle edition is the ebook's.
        assertEquals(PAGEBOUND_EBOOK, add.getJSONObject("user_book").getLong("edition_id"))
        assertEquals("digital", add.getString("format"))
        assertEquals(480, add.getInt("total_page_count"))
        assertEquals("10/6/2026", add.getString("started_reading_at"))
        assertEquals(listOf(10), server.progressWrites())
        assertEquals("sent", result.getString("outcome"))
        assertEquals("none", result.getString("shelfBefore"))
        assertEquals("currently_reading", result.getString("shelfAfter"))
        assertEquals("digital", result.getString("format"))
        assertEquals("edition", result.getString("matchKind"))
        assertFalse(result.getBoolean("existingEditionPreserved"))
        assertEquals(10, result.getInt("posted"))
        val update = server.writes().last().getJSONObject("body").getJSONObject("reading_update")
        assertEquals("10/6/2026", update.getString("date"))
    }

    @Test fun progressMovesInWholeStepsOfFiveAndHigherRemoteProgressIsKept() {
        server.entry("current")
        assertEquals(10, send("1400/10000").getInt("posted"))
        assertEquals(20, send("2300/10000").getInt("posted"))
        val waiting = send("2400/10000")
        assertEquals("already_current", waiting.getString("outcome"))
        assertEquals(25, waiting.getInt("nextUpdateAt"))
        server.entries.getValue(PAGEBOUND_BOOK).progress = 30
        assertEquals("kept_higher_remote_progress", send("2400/10000").getString("outcome"))
        assertEquals(listOf(10, 20), server.progressWrites())
    }

    @Test fun aNewReadBelowTheFirstStepIsAddedWithoutAnUpdate() {
        val result = send("300/10000")
        assertEquals("sent", result.getString("outcome"))
        assertEquals(listOf("/api/v1/user_books"), server.writes().map { it.getString("path") })
    }

    @Test fun aToBeReadBookStartsADigitalReadAndAPausedReadKeepsItsFormat() {
        server.entry("tbr").editionId = PAGEBOUND_HARDCOVER
        val started = send()
        val move = server.writes().first()
        assertEquals("PUT", move.getString("method"))
        assertEquals("current", move.getJSONObject("body").getString("status"))
        assertEquals("digital", move.getJSONObject("body").getString("format"))
        assertEquals("digital", started.getString("format"))
        // The edition the user chose is kept.
        assertTrue(started.getBoolean("existingEditionPreserved"))
        assertTrue(server.writes().none { it.getJSONObject("body").has("user_book") && it.getString("method") == "PUT" })
        server.requests.clear()
        server.entry("paused", format = "print").progress = 45
        val resumed = send()
        val resume = server.writes().single { it.getString("path").endsWith("/update_status") }
        assertEquals("current", resume.getJSONObject("body").getString("status"))
        assertFalse(resume.getJSONObject("body").has("format"))
        assertEquals("print", resumed.getString("format"))
        assertEquals("sent", resumed.getString("outcome"))
    }

    @Test fun anEmptyEditionIsSetToTheMatchedEdition() {
        server.entry("current")
        send()
        val edition = server.writes().first()
        assertEquals(PAGEBOUND_EBOOK, edition.getJSONObject("body").getJSONObject("user_book").getLong("edition_id"))
        assertEquals(PAGEBOUND_EBOOK, server.entries.getValue(PAGEBOUND_BOOK).editionId)
    }

    @Test fun anEditionPageboundDoesNotKeepIsWrittenOnceAndReported() {
        server.entry("current")
        server.ignoreEdition = true
        assertEquals("pagebound_edition_not_applied", send().getString("editionError"))
        assertEquals("pagebound_edition_not_applied", send("5000/10000").getString("editionError"))
        assertEquals(1, server.writes().count { it.getString("method") == "PUT" && it.getJSONObject("body").has("user_book") })
    }

    @Test fun aBookNotInTheLibraryFinishedInNeoReaderIsAddedAsAFinishedDigitalRead() {
        val result = send("10000/10000", "2")
        assertEquals("sent", result.getString("outcome"))
        val add = server.writes().single().getJSONObject("body")
        assertEquals("finished", add.getJSONObject("user_book").getString("status"))
        assertEquals("10/6/2026", add.getString("finished_reading_at"))
        assertTrue(add.isNull("started_reading_at"))
        assertEquals("digital", add.getString("format"))
    }

    @Test fun aPageboundTagMatchesItsBookAndNothingIsSentWhileOff() {
        val tagged = BookIdentifiers(emptySet(), null, null, mapOf("pagebound" to setOf(PAGEBOUND_BOOK)))
        assertEquals(PAGEBOUND_BOOK, send(ids = tagged).getString("bookUuid"))
        server.requests.clear()
        assertEquals("service_disabled", held { runBlocking { sync { false }.send(book("6000/10000"), tagged, "account-uuid-1") } })
        assertTrue(server.writes().isEmpty())
    }

    @Test fun finishedAbandonedAndEarlierReadsHold() {
        server.entry("finished")
        assertEquals("pagebound_status_conflict", held { send() })
        assertEquals("already_current", send("10000/10000", "2").getString("outcome"))
        server.entry("dnf")
        assertEquals("pagebound_status_conflict", held { send() })
        server.entry("tbr").hasEverFinished = true
        assertEquals("pagebound_reread_held", held { send() })
        assertTrue(server.writes().isEmpty())
    }

    @Test fun completionMarksTheBookFinishedOnTheDayItWasRead() {
        server.entry("current").progress = 95
        val result = send("10000/10000", "2")
        assertEquals("sent", result.getString("outcome"))
        assertEquals("2026-10-06", result.getString("finishDate"))
        val finish = server.writes().last()
        assertEquals("finished", finish.getJSONObject("body").getString("status"))
        assertEquals("10/6/2026", finish.getJSONObject("body").getString("finished_reading_at"))
        assertFalse(finish.getJSONObject("body").has("format"))
        assertTrue(server.progressWrites().isEmpty())
    }

    @Test fun unconfirmedWritesHoldAndAnUnconfirmedUpdateIsNotPostedTwice() {
        server.ignoreStatus = true
        assertEquals("pagebound_status_not_applied", held { send() })
        server.ignoreStatus = false
        server.entry("current")
        server.ignoreProgress = true
        assertEquals("pagebound_progress_not_applied", held { send() })
        assertEquals("pagebound_progress_not_applied", held { send() })
        assertEquals(listOf(45), server.progressWrites())
        server.ignoreProgress = false
        assertEquals(50, send("5000/10000").getInt("posted"))
    }

    @Test fun titleAndAuthorMatchIgnoresTheSeriesAndConflictsHold() {
        val byTitle = send(ids = BookIdentifiers(emptySet(), "Synthetic Book", "Test Author"))
        assertEquals("book", byTitle.getString("matchKind"))
        assertEquals(PAGEBOUND_BOOK, byTitle.getString("bookUuid"))
        store.deletePrefix("pagebound.match.")
        assertEquals("pagebound_identifier_conflict", held { send(ids = BookIdentifiers(setOf(PAGEBOUND_PAPERBACK_ISBN, PAGEBOUND_OTHER_ISBN), "Synthetic Book", "Test Author")) })
        store.deletePrefix("pagebound.match.")
        assertEquals("pagebound_book_not_found", held { send(ids = BookIdentifiers(emptySet(), "Unknown Book", "Test Author")) })
    }

    @Test fun anotherAccountHoldsBeforeAnyWrite() {
        server.account = "someone-else"
        assertEquals("pagebound_account_changed", held { send() })
        assertTrue(server.writes().isEmpty())
    }

    @Test fun signInFromTheSwitchConnectsAndLogOutClearsEverything() = runBlocking {
        vault.clear()
        val connection = PageboundConnection(app, auth) { true }
        app.pagebound = connection
        connection.setEnabled(true)
        assertTrue(connection.awaitingCredentials)
        connection.signIn("reader@example.com", "secret-password")
        withTimeout(10_000) { while (connection.signingIn || connection.state().isNull("profile")) delay(10) }
        assertEquals("account-uuid-1", store.get("pagebound.account"))
        val profile = connection.state().getJSONObject("profile")
        assertEquals("reader", profile.getString("username"))
        assertEquals("Free", profile.getString("membership"))
        assertFalse(profile.has("createdAt"))
        val export = buildExport(app.diagnostics).toString()
        listOf("secret-password", "api-id-1", "refresh-1", "reader@example.com").forEach { assertFalse(it, export.contains(it)) }
        connection.logOut()
        assertFalse(connection.state().getBoolean("connected"))
        assertNull(vault.read())
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun theSwitchOpensTheEmailPopupAndTheRowShowsTheMatch() = capturingStderr {
        vault.clear()
        server.entry("current", format = "digital")
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        app.pagebound = PageboundConnection(app, auth) { true }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
        fun views() = activity.findViewById<View>(android.R.id.content).allViews.toList()
        fun toggle() = views().filterIsInstance<CheckBox>().singleOrNull { it.contentDescription?.startsWith("Pagebound:") == true }
        fun popup() = shownDialog()?.takeIf { it.findViewById<EditText>(R.id.pagebound_email) != null }
        try {
            awaitUi { toggle()?.isEnabled == true && !screen.busy }
            // Pagebound's row comes after Fable's and before Margins'.
            val names = views().filterIsInstance<TextView>().map { it.text.toString() }.filter { it in setOf("Fable", "Pagebound", "Margins") }
            assertEquals(listOf("Fable", "Pagebound", "Margins"), names)
            toggle()!!.performClick()
            awaitUi { popup() != null && !screen.busy }
            val dialog = popup()!!
            dialog.findViewById<EditText>(R.id.pagebound_email)!!.setText("reader@example.com")
            dialog.findViewById<EditText>(R.id.pagebound_password)!!.setText("secret-password")
            server.signInRelease = java.util.concurrent.CountDownLatch(1)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            // While Pagebound answers, a panel covers the form; the typed values stay and cannot change.
            val overlay = dialog.findViewById<ViewGroup>(R.id.sign_in_overlay)!!
            awaitUi { overlay.visibility == View.VISIBLE }
            val panel = overlay.children.map { (it as TextView).text.toString() }.toList()
            assertEquals("Signing in to Pagebound…", panel[0])
            assertTrue(panel[1], panel[1].contains("up to a minute"))
            assertEquals("secret-password", dialog.findViewById<EditText>(R.id.pagebound_password)!!.text.toString())
            assertFalse(dialog.findViewById<EditText>(R.id.pagebound_email)!!.isEnabled)
            assertFalse(dialog.findViewById<EditText>(R.id.pagebound_password)!!.isEnabled)
            assertFalse(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled)
            server.signInRelease!!.countDown()
            awaitUi { !dialog.isShowing && toggle()?.contentDescription?.contains("Book matched") == true && !screen.busy }
            val rowBody = views().filterIsInstance<TextView>().single { it.text.toString() == "Pagebound" }.parent as ViewGroup
            assertEquals("✅ Book matched · same edition", (rowBody.children.filterNot { it is CheckBox }.toList()[1] as TextView).text.toString())
            tap(rowBody.getChildAt(0))
            awaitUi { popupMessage()?.contains("Current book") == true }
            val details = popupMessage().toString()
            listOf("@reader", "Membership: Free", "Format: Digital", "Shelf: Currently Reading").forEach { assertTrue(it, details.contains(it)) }
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
