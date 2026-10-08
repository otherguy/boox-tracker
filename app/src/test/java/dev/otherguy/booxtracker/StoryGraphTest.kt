package dev.otherguy.booxtracker

import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.children
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowCookieManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class StoryGraphTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: StoryGraphServer
    private lateinit var session: StoryGraphSession
    private lateinit var http: StoryGraphHttp
    private val identifiers = BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author")

    @Before fun setup() {
        server = StoryGraphServer()
        grantTestFolder(app)
        session = storyGraphSession(store, server)
        http = StoryGraphHttp(session)
    }

    @After fun close() = runBlocking {
        server.close()
        ShadowCookieManager.resetCookies()
        app.diagnostics.scope.coroutineContext[Job]!!.cancelAndJoin()
        store.close()
        closeWorkDatabase()
    }

    private fun raw(value: String) = JSONObject().put("state", "value").put("raw", value)
    private fun book(progress: String = "4723/10000", status: String = "1", key: String = "first") = JSONObject().put("key", key).put("progress", raw(progress)).put("readingStatus", raw(status))
        .put("title", raw("Synthetic Book")).put("authors", raw("Test Author")).put("ISBN", raw("9781398508255")).put("lastAccess", raw("1791225000000"))
    private fun check(book: JSONObject) = JSONObject().put("outcome", "success").put("timestamp", "2026-10-06T10:00:00Z").put("selected", book)
    private fun sync(maySend: () -> Boolean = { true }) = StoryGraphSync(http, store, maySend)
    private fun connection(online: () -> Boolean = { true }) = StoryGraphConnection(app, http, online)
    private val ebook get() = server.books.getValue(STORYGRAPH_EBOOK)
    private val paperback get() = server.books.getValue(STORYGRAPH_PAPERBACK)

    /** A connected, enabled connection with the account stored and the fixture book as the current check. */
    private suspend fun enabledConnection(online: () -> Boolean = { true }) = connection(online).also {
        store.put("storygraph.account", STORYGRAPH_ACCOUNT)
        it.setEnabled(true)
        store.put("lastCheck", check(book()).toString())
    }

    /** No session on the device, as before the first sign-in. */
    private fun signedOut() {
        ShadowCookieManager.resetCookies()
        session = storyGraphSession(store, server, captured = false)
        http = StoryGraphHttp(session)
    }

    private fun popup() = shownDialog()?.takeIf { it.findViewById<WebView>(R.id.storygraph_sign_in_web) != null }

    @Test fun unshelvedBookIsShelvedBeforeAFlooredPercentageWrite() = runBlocking {
        val result = sync().send(book(), identifiers, STORYGRAPH_ACCOUNT)
        assertEquals("sent", result.getString("outcome"))
        assertEquals(47, result.getInt("percent"))
        assertEquals(0, result.getInt("remotePercent"))
        assertEquals("currently_reading", result.getString("shelfAfter"))
        assertTrue(result.isNull("shelfBefore"))
        assertEquals("edition", result.getString("matchKind"))
        assertFalse(result.getBoolean("existingEditionPreserved"))
        assertEquals("Synthetic Book", result.getString("title"))
        val shelve = server.statusWrites().single()
        assertEquals("book_id=$STORYGRAPH_EBOOK&status=currently-reading", shelve.getString("query"))
        assertEquals("csrf-token-1", shelve.getString("csrf"))
        assertEquals("XMLHttpRequest", shelve.getString("requestedWith"))
        assertEquals("csrf-token-1", shelve.getJSONObject("form").getString("authenticity_token"))
        val progress = server.progressWrites().single().getJSONObject("form")
        assertEquals("47", progress.getString("read_status[progress_number]"))
        assertEquals("percentage", progress.getString("read_status[progress_type]"))
        assertEquals("459", progress.getString("read_status[book_num_of_pages]"))
        assertEquals("0", progress.getString("read_status[last_reached_pages]"))
        assertEquals("0", progress.getString("read_status[last_reached_percent]"))
        assertEquals("", progress.getString("read_status[progress_minutes]"))
        assertEquals(STORYGRAPH_EBOOK, progress.getString("book_id"))
        assertEquals("true", progress.getString("on_book_page"))
        assertEquals("Save", progress.getString("commit"))
        assertEquals(47, ebook.percent)
        assertEquals("percentage", ebook.progressType)
        assertTrue(server.requests.all { it.getString("cookie").contains(STORYGRAPH_SESSION_COOKIE) && it.getString("userAgent") == "test-agent" })
    }

    @Test fun repeatIsCurrentAndHigherRemoteProgressIsKept() = runBlocking {
        ebook.status = "currently reading"
        ebook.percent = 47
        val current = sync().send(book(), identifiers)
        assertEquals("already_current", current.getString("outcome"))
        assertTrue(current.getBoolean("unchanged"))
        assertEquals("currently_reading", current.getString("shelfBefore"))
        ebook.percent = 60
        val kept = sync().send(book(), identifiers)
        assertEquals("kept_higher_remote_progress", kept.getString("outcome"))
        assertEquals(60, kept.getInt("remotePercent"))
        assertTrue(server.writes().isEmpty())
    }

    @Test fun toReadAndPausedEditionsAreShelvedFirst() = runBlocking {
        ebook.status = "to read"
        assertEquals("sent", sync().send(book(), identifiers).getString("outcome"))
        assertEquals(listOf("/update-status.js", "/update-progress"), server.writes().map { it.getString("path") })
        assertEquals("currently reading", ebook.status)
        server.requests.clear()
        ebook.status = "paused"
        ebook.percent = 60
        val kept = sync().send(book(), identifiers)
        assertEquals("kept_higher_remote_progress", kept.getString("outcome"))
        assertEquals("paused", kept.getString("shelfBefore"))
        assertEquals(listOf("/update-status.js"), server.writes().map { it.getString("path") })
    }

    @Test fun anEditionTheUserShelvedReceivesTheProgress() = runBlocking {
        paperback.status = "currently reading"
        paperback.percent = 10
        val result = sync().send(book(), identifiers)
        assertEquals(STORYGRAPH_PAPERBACK, result.getString("bookId"))
        assertEquals(STORYGRAPH_EBOOK, result.getString("matchedBookId"))
        assertTrue(result.getBoolean("existingEditionPreserved"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(STORYGRAPH_PAPERBACK, server.progressWrites().single().getJSONObject("form").getString("book_id"))
        assertTrue(server.statusWrites().isEmpty())
        assertEquals(47, paperback.percent)
        assertNull(ebook.status)
    }

    @Test fun finishedOrAbandonedEditionsHoldAReadingSource() = runBlocking {
        listOf("read", "did not finish", "rereading").forEach { status ->
            ebook.status = status
            assertEquals(status, "storygraph_status_conflict", held { sync().send(book(), identifiers) })
        }
        assertTrue(server.writes().isEmpty())
    }

    @Test fun completionMarksTheBookReadAndConfirmsIt() = runBlocking {
        ebook.status = "currently reading"
        ebook.percent = 47
        val result = sync().send(book("10000/10000", "2"), identifiers)
        assertEquals("sent", result.getString("outcome"))
        assertTrue(result.getBoolean("finished"))
        assertEquals("read", result.getString("shelfAfter"))
        assertEquals("book_id=$STORYGRAPH_EBOOK&status=read", server.statusWrites().single().getString("query"))
        assertTrue(server.progressWrites().isEmpty())
        assertEquals("read", ebook.status)
        val again = sync().send(book("10000/10000", "2"), identifiers)
        assertEquals("already_current", again.getString("outcome"))
        assertTrue(again.getBoolean("unchanged"))
        ebook.status = "did not finish"
        assertEquals("storygraph_status_conflict", held { sync().send(book("10000/10000", "2"), identifiers) })
        ebook.status = null
        server.ignoreStatus = true
        assertEquals("storygraph_status_not_applied", held { sync().send(book("10000/10000", "2"), identifiers) })
    }

    @Test fun unconfirmedWritesHold() = runBlocking {
        server.ignoreStatus = true
        assertEquals("storygraph_shelf_not_applied", held { sync().send(book(), identifiers) })
        server.ignoreStatus = false
        ebook.status = "currently reading"
        server.ignoreProgress = true
        assertEquals("storygraph_progress_not_applied", held { sync().send(book(), identifiers) })
        server.ignoreProgress = false
        server.readBackMismatch = true
        assertEquals("storygraph_progress_not_applied", held { sync().send(book(), identifiers) })
        server.readBackMismatch = false
        server.blankProgress = true
        assertEquals("storygraph_page_unrecognized", held { sync().send(book(), identifiers) })
        assertEquals(2, server.progressWrites().size)
        server.blankProgress = false
        server.failProgressOnce = true
        val failure = assertThrows(HttpProblem::class.java) { runBlocking { sync().send(book(), identifiers) } }
        assertEquals("storygraph_http_500", failureReason(failure))
        assertTrue(temporaryFailure(failure))
    }

    @Test fun sourceStatusRulesHoldBeforeAnyRequest() = runBlocking {
        assertEquals("progress_unavailable", held { sync().send(book("1/0"), identifiers) })
        assertEquals("source_status_unsupported", held { sync().send(book(status = "3"), identifiers) })
        assertEquals("source_finish_progress_mismatch", held { sync().send(book(status = "2"), identifiers) })
        assertEquals("source_status_not_finished", held { sync().send(book("10000/10000"), identifiers) })
        assertTrue(server.requests.isEmpty())
    }

    @Test fun accountChangesDisabledServiceAndMissingIdentityHoldWithoutWrites() = runBlocking {
        assertEquals("storygraph_account_changed", held { sync().send(book(), identifiers, "someone-else") })
        assertEquals("service_disabled", held { sync { false }.send(book(), identifiers) })
        server.omitUserId = true
        assertEquals("storygraph_identity_unavailable", held { sync().send(book(), identifiers) })
        assertTrue(session.connected())
        assertTrue(server.writes().isEmpty())
    }

    @Test fun anExpiredSessionEndsTheConnectionAndKeepsTheQueuedUpdate() = runBlocking {
        val connection = enabledConnection()
        server.sessionValid = false
        connection.send("manual", check(book()))
        val state = connection.state()
        assertFalse(state.getBoolean("connected"))
        assertTrue(state.getBoolean("enabled"))
        assertEquals("storygraph_session_expired", state.getString("connectionError"))
        assertEquals("error", state.getJSONObject("last").getString("delivery"))
        assertEquals("storygraph_session_expired", state.getJSONObject("last").getString("reason"))
        assertEquals(1, state.getInt("pending"))
        assertTrue(server.writes().isEmpty())
        // The queued update is sent once the user signs in again.
        server.sessionValid = true
        session.capture("test-agent")
        store.put("storygraph.connectionError", "")
        assertFalse(connection.drain("delivery"))
        assertEquals("synced", connection.state().getJSONObject("last").getString("delivery"))
        assertEquals(47, ebook.percent)
    }

    @Test fun aCloudflareChallengeHoldsWithoutRetryUntilTheUserReconnects() = runBlocking {
        val connection = enabledConnection()
        server.challenge = true
        connection.send("manual", check(book()))
        val state = connection.state()
        assertFalse(state.getBoolean("connected"))
        assertEquals("storygraph_browser_check_required", state.getString("connectionError"))
        assertEquals("error", state.getJSONObject("last").getString("delivery"))
        assertEquals(1, state.getInt("pending"))
        assertFalse(connection.drain("delivery"))
        assertEquals("storygraph_browser_check_required", connection.state().getJSONObject("last").getString("reason"))
        assertTrue(store.events().any { it.optString("kind") == "storygraph_sync" && it.optString("outcome") == "held" && it.optString("reason") == "storygraph_browser_check_required" })
    }

    @Test fun reissuedSessionCookiesReplaceTheStoredOne() = runBlocking {
        server.homeSetCookie = "_storygraph_session=rotated-session; Path=/; HttpOnly"
        http.get("/")
        val cookies = CookieManager.getInstance().getCookie(server.origin)
        assertTrue(cookies, cookies.contains("_storygraph_session=rotated-session"))
        assertFalse(cookies, cookies.contains("test-session-cookie"))
        assertTrue(cookies, cookies.contains("remember_user_token=test-remember"))
    }

    @Test fun logOutSignsOutOnTheServerAndDeletesOnlyThisServicesData() = runBlocking {
        val connection = enabledConnection { false }
        connection.send("manual", check(book()))
        store.put("storygraph.profile", JSONObject().put("username", "reader").toString())
        store.put("storygraph.match.${digest("first")}", "{}")
        store.enqueue("1", book(), identifiers, "earlier")
        assertEquals(1, store.pending(STORYGRAPH_ACCOUNT, "storygraph").size)
        connection.logOut()
        val signOut = server.writes().single()
        assertEquals("/users/sign_out", signOut.getString("path"))
        assertEquals("delete", signOut.getJSONObject("form").getString("_method"))
        assertEquals("csrf-token-1", signOut.getString("csrf"))
        assertTrue(CookieManager.getInstance().getCookie(server.origin)?.contains("_storygraph_session=") != true)
        assertNull(store.get("storygraph.session"))
        assertNull(store.get("storygraph.userAgent"))
        val state = connection.state()
        assertFalse(state.getBoolean("enabled"))
        assertFalse(state.getBoolean("connected"))
        assertTrue(state.isNull("profile"))
        assertTrue(state.isNull("last"))
        assertEquals(0, state.getInt("pending"))
        assertNull(store.get("storygraph.match.${digest("first")}"))
        assertEquals(1, store.pending("1").size)
        assertTrue(store.events().any { it.optString("kind") == "storygraph_connection" && it.optString("outcome") == "logged_out" && it.optInt("deletedUpdates") == 1 })
    }

    @Test fun interruptedStoryGraphSendIsReportedOnRecovery() {
        store.put("storygraph.active", JSONObject().put("source", "delivery").put("runId", "run").toString())
        app.diagnostics.recover()
        assertTrue(store.events().any { it.optString("kind") == "storygraph_interruption_detected" && it.optString("runId") == "run" })
        assertEquals("", store.get("storygraph.active"))
    }

    private class Screen(val activity: MainActivity) {
        val model = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
        fun views() = activity.findViewById<View>(android.R.id.content).allViews.toList()
        fun toggle() = views().filterIsInstance<CheckBox>().singleOrNull { it.contentDescription?.startsWith("StoryGraph:") == true }
        fun title() = views().filterIsInstance<TextView>().single { it.text.toString() == "StoryGraph" }
        fun rowLines() = (title().parent as ViewGroup).children.filterNot { it is CheckBox }.map { (it as TextView).text.toString() }.toList()
        fun warning() = activity.findViewById<View>(R.id.access_warning)
    }

    /** Runs [block] on the Sync screen with the fixture library, then destroys the screen. */
    private suspend fun onScreen(block: suspend Screen.() -> Unit) {
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            Screen(controller.get()).block()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun theSwitchOpensStoryGraphsSignInPageAndACapturedSessionConnectsAndSyncs() = capturingStderr {
        signedOut()
        app.storygraph = connection()
        onScreen {
            awaitUi { toggle()?.isEnabled == true && !model.busy }
            toggle()!!.performClick()
            awaitUi { popup() != null && !model.busy }
            val dialog = popup()!!
            val web = dialog.findViewById<WebView>(R.id.storygraph_sign_in_web)!!
            assertEquals("${server.origin}/users/sign_in", shadowOf(web).lastLoadedUrl)
            assertTrue(web.settings.javaScriptEnabled)
            // The dialog must be the input-method target, or the page's fields can never raise the keyboard.
            assertEquals(0, dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            assertTrue(server.requests.isEmpty())
            assertTrue(toggle()!!.isChecked)
            assertEquals(listOf("StoryGraph", "Sign-in required", "Sign in to StoryGraph in the popup"), rowLines())
            assertNull(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.takeIf { it.visibility == View.VISIBLE })
            // StoryGraph's page signs the user in: the cookies land in the WebView store and the page arrives at the home page.
            CookieManager.getInstance().setCookie(server.origin, "$STORYGRAPH_SESSION_COOKIE; Path=/; HttpOnly")
            shadowOf(web).webViewClient.onPageFinished(web, "${server.origin}/")
            awaitUi { popup() == null && !app.storygraph.signingIn && app.storygraph.state().getBoolean("enabled") && !model.busy }
            assertFalse(app.storygraph.awaitingCredentials)
            assertEquals(STORYGRAPH_ACCOUNT, store.get("storygraph.account"))
            assertEquals(web.settings.userAgentString, store.get("storygraph.userAgent"))
            awaitUi { !app.storygraph.state().isNull("profile") && toggle()?.contentDescription?.contains("Book matched") == true }
            assertEquals("reader", app.storygraph.state().getJSONObject("profile").getString("username"))
            assertEquals(1, server.progressWrites().size)
            assertTrue(server.requests.all { it.getString("userAgent") == web.settings.userAgentString })
            assertTrue(store.events().any { it.optString("kind") == "storygraph_connection" && it.optString("outcome") == "connected" })
            val export = buildExport(app.diagnostics).toString()
            listOf("test-session-cookie", STORYGRAPH_ACCOUNT, "csrf-token-1").forEach { assertFalse(it, export.contains(it)) }
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun aCaptureWithoutAnIdentityKeepsThePopupOpenWithTheReasonUntilCancelled() = capturingStderr {
        signedOut()
        server.omitUserId = true
        app.storygraph = connection()
        onScreen {
            awaitUi { toggle()?.isEnabled == true && !model.busy }
            toggle()!!.performClick()
            awaitUi { popup() != null && !model.busy }
            val dialog = popup()!!
            val web = dialog.findViewById<WebView>(R.id.storygraph_sign_in_web)!!
            fun status() = dialog.findViewById<TextView>(R.id.storygraph_sign_in_status)?.text?.toString()
            // Without any cookie the page cannot have signed in, so nothing is captured.
            shadowOf(web).webViewClient.onPageFinished(web, "${server.origin}/")
            awaitUi { !app.storygraph.signingIn && status() == "No StoryGraph session was saved; sign in again" }
            assertTrue(dialog.isShowing)
            assertTrue(app.storygraph.awaitingCredentials)
            CookieManager.getInstance().setCookie(server.origin, "$STORYGRAPH_SESSION_COOKIE; Path=/; HttpOnly")
            shadowOf(web).webViewClient.onPageFinished(web, "${server.origin}/")
            awaitUi { !app.storygraph.signingIn && status() == "storygraph identity unavailable" }
            assertTrue(dialog.isShowing)
            assertTrue(toggle()!!.isChecked)
            assertFalse(app.storygraph.state().getBoolean("enabled"))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            awaitUi { !dialog.isShowing && !app.storygraph.awaitingCredentials && toggle()?.isChecked == false && !model.busy }
            assertNull(warning())
            // Cancel discards the session the page established, so nothing authenticated stays on the device.
            assertNull(store.get("storygraph.session"))
            assertTrue(CookieManager.getInstance().getCookie(server.origin)?.contains("_storygraph_session=") != true)
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun aBrowserCheckAsksForAReconnectOnceAndTheReconnectSendsTheQueuedUpdate() = capturingStderr {
        app.storygraph = enabledConnection()
        server.challenge = true
        onScreen {
            awaitUi { toggle()?.contentDescription?.contains("Reconnect required") == true && !model.busy }
            assertEquals("Not sent", rowLines()[2])
            assertTrue(warning() != null)
            tap(title())
            awaitUi { popupMessage()?.contains("Current book") == true }
            val details = popupMessage()!!
            assertTrue(warningsDrawn(details))
            assertEquals(details.toString(), listOf("StoryGraph asked for a browser check. Turn StoryGraph off and on to pass it in the sign-in popup."), issueLines(details))
            shownDialog()!!.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            awaitUi { shownDialog() == null }
            // Off then On opens StoryGraph's page again; passing the check there restores the session and sends the update.
            toggle()!!.performClick()
            awaitUi { toggle()?.isChecked == false && !model.busy }
            server.challenge = false
            toggle()!!.performClick()
            awaitUi { popup() != null && !model.busy }
            val web = popup()!!.findViewById<WebView>(R.id.storygraph_sign_in_web)!!
            shadowOf(web).webViewClient.doUpdateVisitedHistory(web, "${server.origin}/", false)
            awaitUi { popup() == null && toggle()?.contentDescription?.contains("Book matched") == true && !model.busy }
            assertEquals(1, server.progressWrites().size)
            assertNull(warning())
            assertEquals(0, app.storygraph.state().getInt("pending"))
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun theStoryGraphRowOpensItsDetailsWarnsAboutKeptProgressAndLogsOut() = capturingStderr {
        // The user's shelved paperback holds more progress than NeoReader reports for the matched ebook.
        paperback.status = "currently reading"
        paperback.percent = 60
        app.storygraph = enabledConnection()
        onScreen {
            awaitUi { toggle()?.contentDescription?.contains("Book matched") == true && !app.storygraph.state().isNull("profile") && !model.busy }
            val lines = rowLines()
            assertEquals(3, lines.size)
            assertEquals("✅ Book matched · different edition", lines[1])
            assertTrue(lines[2], lines[2].startsWith("⚠ Synced at "))
            assertTrue(warning() != null)
            assertTrue(server.progressWrites().isEmpty())
            tap(title())
            awaitUi { popupMessage()?.contains("@reader") == true }
            val details = popupMessage()!!
            val issues = issueLines(details)
            assertEquals(details.toString(), 1, issues.size)
            assertTrue(issues[0], issues[0].startsWith("StoryGraph has 60%; NeoReader's"))
            listOf("Connected since", "Synthetic Book", "Exact edition, by its identifiers", "The edition you shelved on StoryGraph, not the one your ebook matched", "Progress: 60% · kept, higher than NeoReader", "Shelf: Currently Reading").forEach { assertTrue(it, details.contains(it)) }
            listOf("Email", "Membership", "Account created").forEach { assertFalse(it, details.contains(it)) }
            val detailsDialog = shownDialog()!!
            detailsDialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            awaitUi { shownDialog()?.let { it !== detailsDialog } == true }
            val confirm = shownDialog()!!
            assertEquals(
                "Boox Tracker will remove your StoryGraph sign-in from this device, turn StoryGraph off, and delete any updates that have not been sent yet.",
                confirm.findViewById<TextView>(android.R.id.message)!!.text.toString()
            )
            confirm.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            awaitUi { !app.storygraph.state().getBoolean("connected") && toggle()?.isChecked == false && toggle()?.contentDescription?.contains("Not connected") == true }
            assertTrue(app.storygraph.state().isNull("profile"))
            assertEquals(1, server.writes().count { it.getString("path") == "/users/sign_out" })
            assertNull(warning())
        }
    }
}
