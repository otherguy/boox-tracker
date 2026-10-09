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
import org.junit.Assert.assertNotEquals
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
class MarginsTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: MarginsServer
    private lateinit var vault: TokenVault
    private lateinit var auth: MarginsAuth
    private lateinit var zero: MarginsZero
    private val identifiers = BookIdentifiers(setOf(MARGINS_ISBN, MARGINS_EBOOK_ISBN), "Synthetic Book", "Test Author")
    private val defaultZone = java.util.TimeZone.getDefault()

    @Before fun setup() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Bangkok"))
        server = MarginsServer()
        grantTestFolder(app)
        vault = marginsVault(app)
        vault.write(OAuthTokens(marginsToken(MARGINS_ACCOUNT, 1), "refresh-1", System.currentTimeMillis() + 604_800_000))
        auth = MarginsAuth(server.http, vault)
        zero = MarginsZero(server.zeroUrl, store)
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
        .put("title", raw("Synthetic Book")).put("authors", raw("Test Author")).put("ISBN", raw(MARGINS_ISBN)).put("lastAccess", raw("1791225000000"))
    private fun check(book: JSONObject) = JSONObject().put("outcome", "success").put("timestamp", "2026-10-06T10:00:00Z").put("selected", book)
    private fun sync(maySend: () -> Boolean = { true }) = MarginsSync(auth, zero, store, maySend)
    private fun send(progress: String = "4723/10000", status: String = "1", ids: BookIdentifiers = identifiers) = runBlocking { sync().send(book(progress, status), ids, MARGINS_ACCOUNT) }
    private fun held(block: () -> Unit) = assertThrows(SyncProblem::class.java) { block() }.code
    private fun connection() = MarginsConnection(app, auth, zero) { true }

    @Test fun signInEmailsACodeThenVerifiesItAndKeepsOnlyTokens() = runBlocking {
        vault.clear()
        val connection = connection()
        app.margins = connection
        connection.setEnabled(true)
        assertTrue(connection.awaitingCredentials)
        server.unknownEmails.add("stranger@example.com")
        connection.requestCode("stranger@example.com")
        withTimeout(10_000) { while (connection.signingIn) delay(10) }
        // A mistyped email never creates an account; the popup stays on the email step with the reason.
        assertNull(connection.codeSentTo)
        assertEquals("margins_http_422_otp_disabled", store.get("margins.connectionError"))
        connection.requestCode("reader@example.com")
        withTimeout(10_000) { while (connection.signingIn || connection.codeSentTo == null) delay(10) }
        val otp = server.requests.last { it.getString("path") == "/auth/v1/otp" }
        assertFalse(otp.getJSONObject("body").getBoolean("create_user"))
        assertEquals("test-key", otp.getString("apikey"))
        connection.signIn("reader@example.com", "000000")
        withTimeout(10_000) { while (connection.signingIn) delay(10) }
        // A wrong code keeps the code step.
        assertEquals("reader@example.com", connection.codeSentTo)
        assertEquals("margins_http_403_otp_expired", store.get("margins.connectionError"))
        connection.signIn("reader@example.com", MARGINS_CODE)
        withTimeout(10_000) { while (connection.signingIn || connection.state().isNull("profile")) delay(10) }
        assertEquals(MARGINS_ACCOUNT, store.get("margins.account"))
        val profile = connection.state().getJSONObject("profile")
        assertEquals("Reader", profile.getString("name"))
        assertEquals("reader@example.com", profile.getString("email"))
        val export = buildExport(app.diagnostics).toString()
        listOf(MARGINS_CODE, marginsToken(MARGINS_ACCOUNT, 1), "refresh-1", "reader@example.com").forEach { assertFalse(it, export.contains(it)) }
        connection.logOut()
        assertFalse(connection.state().getBoolean("connected"))
        assertNull(vault.read())
        assertTrue(server.requests.any { it.getString("path") == "/auth/v1/logout" && it.getString("query") == "scope=local" })
        assertNull(store.get("margins.clientGroup"))
    }

    @Test fun anExpiringTokenIsRenewedFirstAndARejectedOneOnce() {
        vault.write(OAuthTokens(marginsToken(MARGINS_ACCOUNT, 1), "refresh-1", System.currentTimeMillis() + 30_000))
        send()
        assertEquals(marginsToken(MARGINS_ACCOUNT, 2), vault.read()!!.access)
        server.revoked.add(marginsToken(MARGINS_ACCOUNT, 2))
        send("6000/10000")
        assertEquals(marginsToken(MARGINS_ACCOUNT, 3), vault.read()!!.access)
        assertEquals(2, server.requests.count { it.getString("path") == "/auth/v1/token" })
    }

    @Test fun aTokenRefusedAgainAfterRenewalEndsTheSession() {
        server.revoked.addAll(listOf(marginsToken(MARGINS_ACCOUNT, 1), marginsToken(MARGINS_ACCOUNT, 2)))
        assertEquals("margins_session_expired", held { send() })
        assertNull(vault.read())
        assertTrue(server.pushes.isEmpty())
    }

    @Test fun aDeadRefreshTokenEndsTheSessionAsOneIssueOnTheRow() = runBlocking {
        val connection = connection()
        store.put("margins.account", MARGINS_ACCOUNT)
        connection.setEnabled(true)
        store.put("lastCheck", check(book()).toString())
        server.revoked.add(marginsToken(MARGINS_ACCOUNT, 1))
        server.invalidRefresh = true
        connection.send("manual", check(book()))
        val state = connection.state()
        assertFalse(state.getBoolean("connected"))
        assertEquals("margins_session_expired", state.getString("connectionError"))
        assertEquals("margins_session_expired", state.getJSONObject("last").getString("reason"))
        assertTrue(server.pushes.isEmpty())
    }

    @Test fun aNewReadStartsInProgressAndItsFirstStepIsAnEbookSession() {
        val result = send("1400/10000")
        assertEquals(listOf("librarySetReadthroughStatus", "libraryAddReadingProgress"), server.pushNames())
        val status = server.pushes.first().getJSONObject("args")
        assertEquals(MARGINS_WORK, status.getString("workId"))
        assertEquals("in_progress", status.getString("status"))
        assertTrue(status.getBoolean("startsNewReadthrough"))
        assertFalse(status.getBoolean("isInLibrary"))
        assertEquals("2026-10-06", status.getString("todayDate"))
        val session = server.sessionPushes().single()
        assertEquals(status.getString("newReadthroughId"), session.getString("readthroughId"))
        assertEquals("percentages", session.getString("unit"))
        assertEquals(listOf(0, 10, 10), listOf(session.getInt("startPosition"), session.getInt("endPosition"), session.getInt("progress")))
        assertTrue(session.getBoolean("isEbook"))
        assertEquals("2026-10-06", session.getString("sessionDate"))
        assertEquals(7 * 3600, session.getInt("sessionUtcOffsetInSeconds"))
        // 10% of the work's 480 pages, and one minute per page, as Margins counts them.
        assertEquals(48.0, session.getDouble("pagesEquivalent"), 0.0)
        assertEquals(2880, session.getInt("timeEquivalentInSeconds"))
        assertTrue(session.getJSONObject("readthroughUpdate").getBoolean("isEbook"))
        assertEquals("sent", result.getString("outcome"))
        assertEquals("none", result.getString("shelfBefore"))
        assertEquals("currently_reading", result.getString("shelfAfter"))
        assertEquals(10, result.getInt("posted"))
        assertEquals("isbn", result.getString("matchedBy"))
        assertEquals("Synthetic Book", result.getString("title"))
    }

    @Test fun aPlannedReadIsAdoptedAndBelowTheFirstStepNothingIsPosted() {
        val planned = server.read("want_to_read")
        val result = send("300/10000")
        assertEquals(planned.id, server.pushes.single().getJSONObject("args").getString("readthroughId"))
        assertEquals("in_progress", planned.status)
        assertEquals(1, server.reads.size)
        assertEquals("sent", result.getString("outcome"))
        assertEquals("want_to_read", result.getString("shelfBefore"))
    }

    @Test fun progressMovesInWholeStepsFromWhatMarginsHolds() {
        val read = server.read("in_progress").apply { isPrint = true }
        assertEquals(10, send("1400/10000").getInt("posted"))
        val second = send("2300/10000")
        assertEquals(20, second.getInt("posted"))
        assertEquals(listOf(10, 20), server.sessionPushes().map { it.getInt("startPosition") + it.getInt("progress") })
        assertEquals(10, server.sessionPushes().last().getInt("startPosition"))
        // A read the user shelved as print keeps its format.
        assertTrue(server.sessionPushes().none { it.has("readthroughUpdate") })
        assertTrue(read.isPrint)
        val waiting = send("2400/10000")
        assertEquals("already_current", waiting.getString("outcome"))
        assertEquals(25, waiting.getInt("nextUpdateAt"))
        assertEquals(2, server.sessionPushes().size)
    }

    @Test fun progressLoggedInPagesIsComparedAsAShareOfTheBookAndHigherProgressIsKept() {
        val read = server.read("in_progress").apply { session("00000000-0000-4000-8000-000000000501", 1, 240, "pages") }
        // Pages count only against the read's own edition; the work's page count may be another edition's.
        assertEquals("margins_progress_unit_unsupported", held { send("5200/10000") })
        read.pages = 480
        // 240 of the read's 480 pages is 50%; 52% has not reached the next step.
        val result = send("5200/10000")
        assertEquals("already_current", result.getString("outcome"))
        assertEquals(50, result.getInt("remotePercent"))
        assertEquals(55, result.getInt("nextUpdateAt"))
        assertEquals("kept_higher_remote_progress", send("4000/10000").getString("outcome"))
        assertTrue(server.pushes.isEmpty())
    }

    @Test fun audiobookProgressAndEarlierReadsHold() {
        server.read("in_progress").session("00000000-0000-4000-8000-000000000502", 0, 3600, "audiobook_seconds_listened")
        assertEquals("margins_progress_unit_unsupported", held { send() })
        server.reads.clear()
        server.read("finished")
        assertEquals("margins_reread_held", held { send() })
        assertEquals("already_current", send("10000/10000", "2").getString("outcome"))
        server.reads.clear()
        server.read("stopped")
        assertEquals("margins_reread_held", held { send() })
        assertTrue(server.pushes.isEmpty())
    }

    @Test fun completionFinishesTheReadOnTheDayItWasRead() {
        val read = server.read("in_progress")
        read.session("00000000-0000-4000-8000-000000000503", 0, 95, "percentages")
        val result = send("10000/10000", "2")
        assertEquals("sent", result.getString("outcome"))
        assertEquals("2026-10-06", result.getString("finishDate"))
        val finish = server.pushes.single().getJSONObject("args")
        assertEquals("finished", finish.getString("status"))
        assertEquals(read.id, finish.getString("readthroughId"))
        assertFalse(finish.getBoolean("startsNewReadthrough"))
        assertEquals("finished", read.status)
        assertTrue(server.sessionPushes().isEmpty())
    }

    @Test fun aBookFinishedBeforeItWasOnMarginsIsAddedAsFinished() {
        assertEquals("sent", send("10000/10000", "2").getString("outcome"))
        assertEquals("finished", server.pushes.single().getJSONObject("args").getString("status"))
        assertEquals("finished", server.reads.single().status)
    }

    @Test fun eachIdentifierMatchesTheWorkAndConflictsHold() {
        assertEquals(MARGINS_WORK, send(ids = BookIdentifiers(emptySet(), null, null, mapOf("goodreads" to setOf(MARGINS_GOODREADS)))).getString("workId"))
        store.deletePrefix("margins.match.")
        assertEquals("asin", send(ids = BookIdentifiers(emptySet(), null, null, mapOf("asin" to setOf(MARGINS_ASIN)))).getString("matchedBy"))
        store.deletePrefix("margins.match.")
        assertEquals("tag", send(ids = BookIdentifiers(emptySet(), null, null, mapOf("margins" to setOf(MARGINS_WORK)))).getString("matchedBy"))
        store.deletePrefix("margins.match.")
        assertEquals("margins_identifier_conflict", held { send(ids = BookIdentifiers(setOf(MARGINS_ISBN, MARGINS_OTHER_ISBN), null, null)) })
        store.deletePrefix("margins.match.")
        assertEquals("margins_book_not_found", held { send(ids = BookIdentifiers(setOf("9780000000002"), "Synthetic Book", "Test Author")) })
        store.deletePrefix("margins.match.")
        // Margins has no title search.
        assertEquals("margins_identifier_missing", held { send(ids = BookIdentifiers(emptySet(), "Synthetic Book", "Test Author")) })
    }

    @Test fun unconfirmedWritesHoldAndAnUnconfirmedSessionIsNotAddedTwice() {
        server.ignoreStatus = true
        assertEquals("margins_status_not_applied", held { send() })
        server.ignoreStatus = false
        server.read("in_progress")
        server.ignoreProgress = true
        assertEquals("margins_progress_not_applied", held { send() })
        assertEquals("margins_progress_not_applied", held { send() })
        assertEquals(1, server.sessionPushes().size)
        server.ignoreProgress = false
        assertEquals(50, send("5000/10000").getInt("posted"))
    }

    @Test fun aRefusedSessionCanBeSentAgainAndAFailedPushIsTemporary() {
        server.read("in_progress")
        server.pushAppError = true
        assertEquals("margins_push_rejected", held { send() })
        server.pushAppError = false
        assertEquals(45, send().getInt("posted"))
        // A failed push may or may not have applied, so it is retried, and the retry holds the step instead of adding it twice.
        server.pushFailedOnce = true
        assertTrue(temporaryFailure(assertThrows(HttpProblem::class.java) { send("5000/10000") }))
        assertEquals("margins_progress_not_applied", held { send("5000/10000") })
        server.dropOnce = true
        assertTrue(temporaryFailure(assertThrows(java.io.IOException::class.java) { send("5500/10000") }))
        assertEquals("margins_progress_not_applied", held { send("5500/10000") })
        assertEquals(listOf(45, 45, 50, 55), server.sessionPushes().map { it.getInt("endPosition") })
    }

    @Test fun aSessionThatNeverLeftTheDeviceOrWasRefusedIsSentNextTime() {
        server.read("in_progress")
        // Off before the push: nothing was sent, so the step is not held.
        assertEquals("service_disabled", held { runBlocking { sync { false }.send(book(), identifiers, MARGINS_ACCOUNT) } })
        server.rateLimitedOnce = true
        assertTrue(temporaryFailure(assertThrows(HttpProblem::class.java) { send() }))
        assertEquals(45, send().getInt("posted"))
        assertEquals(listOf(45, 45), server.sessionPushes().map { it.getInt("endPosition") })
    }

    @Test fun aSuccessfulPushResponseIsConfirmedByReadingTheSessionBack() {
        server.read("in_progress")
        server.confirmByResponse = true
        assertEquals(45, send().getInt("posted"))
    }

    @Test fun aQueryMarginsRejectsHoldsWithItsName() {
        server.rejectReads = true
        assertEquals("margins_query_rejected_librarymembershipreadthroughs", held { send() })
    }

    @Test fun aSchemaMarginsNoLongerServesHoldsAndAnotherAccountHoldsBeforeAnyWrite() {
        server.schemaRejected = true
        assertEquals("margins_schema_changed", held { send() })
        server.schemaRejected = false
        assertEquals("margins_account_changed", held { runBlocking { sync().send(book(), identifiers, "00000000-0000-4000-8000-0000000000a2") } })
        assertTrue(server.pushes.isEmpty())
    }

    @Test fun everySyncIsANewClientInOnePersistentClientGroup() {
        send("1400/10000")
        send("2300/10000")
        assertEquals(1, server.connections.map { it.getString("clientGroupID") }.distinct().size)
        assertEquals(server.connections.size, server.connections.map { it.getString("clientID") }.distinct().size)
        assertTrue(server.connections.all { it.getString("userID") == MARGINS_ACCOUNT })
    }

    @Test fun nothingIsSentWhileOff() {
        assertEquals("service_disabled", held { runBlocking { sync { false }.send(book(), identifiers, MARGINS_ACCOUNT) } })
        assertTrue(server.pushes.isEmpty())
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun theSwitchOpensTheCodePopupAndTheRowShowsTheMatch() = capturingStderr {
        vault.clear()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        app.margins = connection()
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
        fun views() = activity.findViewById<View>(android.R.id.content).allViews.toList()
        fun toggle() = views().filterIsInstance<CheckBox>().singleOrNull { it.contentDescription?.startsWith("Margins:") == true }
        fun popup() = shownDialog()?.takeIf { it.findViewById<EditText>(R.id.margins_email) != null }
        try {
            awaitUi { toggle()?.isEnabled == true && !screen.busy }
            toggle()!!.performClick()
            awaitUi { popup() != null && !screen.busy }
            val dialog = popup()!!
            val code = dialog.findViewById<EditText>(R.id.margins_code)!!
            val positive = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            assertEquals(View.GONE, code.visibility)
            assertEquals("Send code", positive.text.toString())
            dialog.findViewById<EditText>(R.id.margins_email)!!.setText("reader@example.com")
            positive.performClick()
            awaitUi { code.visibility == View.VISIBLE && !screen.busy && app.margins.codeSentTo != null }
            assertEquals("Sign in", positive.text.toString())
            assertFalse(dialog.findViewById<EditText>(R.id.margins_email)!!.isEnabled)
            assertFalse(positive.isEnabled)
            // Change email returns to the email step; sending again reaches the code step again.
            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick()
            awaitUi { code.visibility == View.GONE && !screen.busy }
            assertTrue(dialog.findViewById<EditText>(R.id.margins_email)!!.isEnabled)
            assertEquals("Send code", positive.text.toString())
            positive.performClick()
            awaitUi { code.visibility == View.VISIBLE && !screen.busy && app.margins.codeSentTo != null }
            code.setText("000000")
            positive.performClick()
            awaitUi { !screen.busy && !app.margins.signingIn && dialog.findViewById<TextView>(R.id.margins_sign_in_status)!!.text.isNotEmpty() }
            // A wrong code keeps the code step with the reason.
            assertEquals("Code expired or not accepted; check it or send a new one", dialog.findViewById<TextView>(R.id.margins_sign_in_status)!!.text.toString())
            assertEquals(View.VISIBLE, code.visibility)
            code.setText(MARGINS_CODE)
            server.signInRelease = java.util.concurrent.CountDownLatch(1)
            positive.performClick()
            // While Margins checks the code, a panel covers the form.
            val overlay = dialog.findViewById<ViewGroup>(R.id.sign_in_overlay)!!
            awaitUi { overlay.visibility == View.VISIBLE }
            assertEquals("Signing in to Margins…", (overlay.getChildAt(0) as TextView).text.toString())
            server.signInRelease!!.countDown()
            awaitUi { !dialog.isShowing && toggle()?.contentDescription?.contains("Book matched") == true && !screen.busy }
            val rowBody = views().filterIsInstance<TextView>().single { it.text.toString() == "Margins" }.parent as ViewGroup
            assertEquals("✅ Book matched", (rowBody.children.filterNot { it is CheckBox }.toList()[1] as TextView).text.toString())
            tap(rowBody.getChildAt(0))
            awaitUi { popupMessage()?.contains("Current book") == true }
            val details = popupMessage().toString()
            listOf("Reader", "Match: Book, by its ISBN", "Shelf: Currently Reading", "Format: Ebook").forEach { assertTrue(it, details.contains(it)) }
            assertFalse(details.contains("Edition:"))
            assertNotEquals(null, store.get("margins.clientGroup"))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
