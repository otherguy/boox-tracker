package dev.otherguy.booxtracker

import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
import org.robolectric.shadows.ShadowCookieManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class GoodreadsTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: GoodreadsServer
    private lateinit var profile: FakeWebProfile
    private lateinit var refresher: FakeRefresher
    private lateinit var session: GoodreadsSession
    private lateinit var http: GoodreadsHttp
    private val identifiers = BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author")

    @Before fun setup() {
        server = GoodreadsServer()
        grantTestFolder(app)
        profile = FakeWebProfile()
        // The hidden browser passes a bot check by running the page's script, which the fake site answers by dropping it.
        refresher = FakeRefresher { server.challenge = false }
        session = goodreadsSession(store, server, profile)
        http = GoodreadsHttp(session, refresher)
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
    private fun sync(maySend: () -> Boolean = { true }) = GoodreadsSync(http, store, maySend)
    private fun connection(online: () -> Boolean = { true }) = GoodreadsConnection(app, profile, refresher, http, online)
    private val ebook get() = server.books.getValue(GOODREADS_EBOOK)
    private val paperback get() = server.books.getValue(GOODREADS_PAPERBACK)
    private val finishDay get() = readingDay(book("10000/10000", "2"), null)

    /** A connected, enabled connection with the account stored and the fixture book as the current check. */
    private suspend fun enabledConnection(online: () -> Boolean = { true }) = connection(online).also {
        store.put("goodreads.account", GOODREADS_ACCOUNT)
        it.setEnabled(true)
        store.put("lastCheck", check(book()).toString())
    }

    /** No session on the device, as before the first sign-in. */
    private fun signedOut() {
        profile = FakeWebProfile()
        session = goodreadsSession(store, server, profile, captured = false)
        http = GoodreadsHttp(session, refresher)
    }

    /** The edition is on Currently Reading with an open read started in September. */
    private fun GoodreadsEdition.reading(progress: Int? = null) = apply {
        shelf = "currently-reading"
        percent = progress
        sessions.add(ReadingSessionFixture("s1", LocalDate.of(2026, 9, 14)))
    }

    private fun popup() = shownDialog()?.takeIf { it.findViewById<WebView>(R.id.goodreads_sign_in_web) != null }

    private fun renewalWork() = WorkManager.getInstance(app).getWorkInfosForUniqueWork(GOODREADS_RENEWAL_WORK_NAME).get().filter { it.state != WorkInfo.State.CANCELLED }

    @Test fun anUnshelvedBookIsShelvedAndItsFlooredPercentageWritten() = runBlocking {
        val result = sync().send(book(), identifiers, GOODREADS_ACCOUNT)
        assertEquals("sent", result.getString("outcome"))
        assertEquals(47, result.getInt("percent"))
        assertEquals(47, result.getInt("remotePercent"))
        assertEquals("currently_reading", result.getString("shelfAfter"))
        assertTrue(result.isNull("shelfBefore"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(GOODREADS_WORK, result.getString("workId"))
        assertFalse(result.getBoolean("existingEditionPreserved"))
        val shelve = server.shelfWrites().single()
        assertEquals(mapOf("book_id" to GOODREADS_PAPERBACK, "name" to "currently-reading", "a" to "", "authenticity_token" to "csrf-token-1"), shelve.getJSONObject("form").toMap())
        assertEquals("csrf-token-1", shelve.getString("csrf"))
        assertEquals("XMLHttpRequest", shelve.getString("requestedWith"))
        val progress = server.progressWrites().single().getJSONObject("form")
        assertEquals(GOODREADS_PAPERBACK, progress.getString("user_status[book_id]"))
        assertEquals("47", progress.getString("user_status[percent]"))
        assertEquals("", progress.getString("user_status[body]"))
        assertEquals(47, paperback.percent)
        assertTrue(server.requests.all { it.getString("cookie").contains(GOODREADS_SESSION_COOKIE) && it.getString("userAgent") == "test-agent" })
        // The host JVM's HttpURLConnection drops Sec-Fetch-* and Origin; Android's sends them, so only the Referer is checked here.
        assertTrue(server.requests.filter { it.getString("method") == "GET" }.all { it.getString("referer") == "${server.origin}/" })
    }

    @Test fun progressMovesInStepsOfFivePointsAndHigherRemoteProgressIsKept() = runBlocking {
        paperback.reading(45)
        val small = sync().send(book(), identifiers)
        assertEquals("already_current", small.getString("outcome"))
        assertTrue(small.getBoolean("unchanged"))
        assertEquals(45, small.getInt("remotePercent"))
        assertEquals(50, small.getInt("nextUpdateAt"))
        assertTrue(server.writes().isEmpty())
        paperback.percent = 42
        assertEquals("sent", sync().send(book(), identifiers).getString("outcome"))
        assertEquals(47, paperback.percent)
        val same = sync().send(book(), identifiers)
        assertEquals("already_current", same.getString("outcome"))
        assertFalse(same.has("nextUpdateAt"))
        paperback.percent = 60
        val kept = sync().send(book(), identifiers)
        assertEquals("kept_higher_remote_progress", kept.getString("outcome"))
        assertEquals(60, kept.getInt("remotePercent"))
        assertEquals(1, server.progressWrites().size)
    }

    @Test fun aWantToReadEditionMovesToCurrentlyReadingWithItsFirstProgress() = runBlocking {
        paperback.shelf = "to-read"
        val result = sync().send(book("300/10000"), identifiers)
        assertEquals("sent", result.getString("outcome"))
        assertEquals("to_read", result.getString("shelfBefore"))
        assertEquals(listOf("/shelf/add_to_shelf", "/user_status.json"), server.writes().map { it.getString("path") })
        assertEquals(3, paperback.percent)
        server.requests.clear()
        // A new read at 0% is shelved without a progress post.
        server.book("70000003", "91700003", "9781234567897", title = "Fresh Book")
        assertEquals("sent", sync().send(book("0/10000", key = "fresh"), BookIdentifiers(setOf("9781234567897"), "Fresh Book", "Test Author")).getString("outcome"))
        assertEquals(listOf("/shelf/add_to_shelf"), server.writes().map { it.getString("path") })
    }

    @Test fun anEditionTheUserShelvedReceivesTheProgress() = runBlocking {
        ebook.reading(10)
        val result = sync().send(book(), identifiers)
        assertEquals(GOODREADS_EBOOK, result.getString("bookId"))
        assertEquals(GOODREADS_PAPERBACK, result.getString("matchedBookId"))
        assertTrue(result.getBoolean("existingEditionPreserved"))
        assertEquals(GOODREADS_EBOOK, server.progressWrites().single().getJSONObject("form").getString("user_status[book_id]"))
        assertTrue(server.shelfWrites().isEmpty())
        assertEquals(47, ebook.percent)
        assertNull(paperback.shelf)
    }

    @Test fun aShelvedEditionThatAnIdentifierConfirmedIsTheSameEdition() = runBlocking {
        ebook.reading(10)
        // Both ISBNs are confirmed editions of one work; the paperback comes first, but the user shelved the ebook.
        val result = sync().send(book(), BookIdentifiers(setOf("9781398508255", "9781982181680"), "Synthetic Book", "Test Author"))
        assertEquals(GOODREADS_EBOOK, result.getString("bookId"))
        assertEquals(GOODREADS_EBOOK, result.getString("matchedBookId"))
        assertFalse(result.getBoolean("existingEditionPreserved"))
    }

    @Test fun finishedAbandonedAndUnknownShelvesHoldAReadingSource() = runBlocking {
        listOf("read", "did-not-finish").forEach { shelf ->
            paperback.shelf = shelf
            assertEquals(shelf, "goodreads_status_conflict", held { sync().send(book(), identifiers) })
        }
        paperback.shelf = "paused"
        assertEquals("goodreads_status_unrecognized", held { sync().send(book(), identifiers) })
        paperback.shelf = "to-read"
        ebook.shelf = "currently-reading"
        assertEquals("goodreads_status_conflict", held { sync().send(book(), identifiers) })
        assertTrue(server.writes().isEmpty())
    }

    @Test fun completionShelvesTheBookReadAndSetsTheFinishDate() = runBlocking {
        paperback.reading(47)
        val result = sync().send(book("10000/10000", "2"), identifiers)
        assertEquals("sent", result.getString("outcome"))
        assertTrue(result.getBoolean("finished"))
        assertEquals("read", result.getString("shelfAfter"))
        assertEquals(finishDay.toString(), result.getString("finishDate"))
        assertFalse(result.has("finishDateError"))
        assertEquals("read", paperback.shelf)
        assertEquals(finishDay, paperback.sessions.single().ended)
        assertEquals(mapOf("book_id" to GOODREADS_PAPERBACK, "name" to "read", "a" to "", "authenticity_token" to "csrf-token-1"), server.shelfWrites().single().getJSONObject("form").toMap())
        assertTrue(server.progressWrites().isEmpty())
        val action = server.actionWrites().single()
        assertEquals(GOODREADS_ACTION, action.getString("action"))
        val payload = JSONArray(action.getString("body")).getJSONObject(0)
        // The form's other fields go back unchanged, and the date change is not posted to the update feed.
        assertEquals("kept note", payload.getString("privateNotes"))
        assertFalse(payload.getBoolean("addToUpdateFeed"))
        assertFalse(payload.getBoolean("postToBlog"))
        assertEquals("", payload.getString("reviewText"))
        assertEquals(1, payload.getJSONArray("readingSessions").length())
        // The action id came from the page's chunks, read from the last one back; it is kept for that set of chunks.
        assertEquals(listOf("/_next/static/chunks/app/review/edit/%5Bid%5D/page-0000000000000004.js", "/_next/static/chunks/9067-0000000000000006.js"), server.requests.filter { it.getString("path").startsWith("/_next/") }.map { it.getString("path") })
        val again = sync().send(book("10000/10000", "2"), identifiers)
        assertEquals("already_current", again.getString("outcome"))
        assertTrue(again.getBoolean("unchanged"))
        paperback.shelf = "currently-reading"
        paperback.sessions.add(ReadingSessionFixture("s2", finishDay))
        server.requests.clear()
        sync().send(book("10000/10000", "2"), identifiers)
        assertTrue(server.requests.none { it.getString("path").startsWith("/_next/") })
    }

    @Test fun aDateGoodreadsStampedWhenShelvingIsCorrectedToTheDayTheBookWasFinished() = runBlocking {
        paperback.reading(47)
        server.stampsReadDate = finishDay.plusDays(9)
        val result = sync().send(book("10000/10000", "2"), identifiers)
        assertEquals(finishDay.toString(), result.getString("finishDate"))
        assertEquals(finishDay, paperback.sessions.single().ended)
        assertEquals("COMPLETED", paperback.sessions.single().state)
    }

    @Test fun aFinishDateTheEditorCannotTakeIsReportedAndTheReadShelfStays() = runBlocking {
        paperback.reading(47)
        server.actionErrors = true
        val failed = sync().send(book("10000/10000", "2"), identifiers)
        assertEquals("sent", failed.getString("outcome"))
        assertEquals("read", failed.getString("shelfAfter"))
        assertEquals("goodreads_finish_date_not_applied", failed.getString("finishDateError"))
        assertFalse(failed.has("finishDate"))
        assertEquals("read", paperback.shelf)
        // An existing review would be replaced by the form's empty text, so the date is not written at all.
        val other = server.books.getValue(GOODREADS_OTHER).reading(47)
        server.reviewPresent = true
        server.requests.clear()
        val reviewed = sync().send(book("10000/10000", "2", key = "reviewed"), BookIdentifiers(setOf("9780000000019"), "Visiting Egypt", "Other Author"))
        assertEquals("goodreads_review_present", reviewed.getString("finishDateError"))
        assertTrue(server.actionWrites().isEmpty())
        assertEquals("read", other.shelf)
    }

    @Test fun unconfirmedWritesHold() = runBlocking {
        server.ignoreShelf = true
        assertEquals("goodreads_shelf_not_applied", held { sync().send(book(), identifiers) })
        assertEquals("goodreads_status_not_applied", held { sync().send(book("10000/10000", "2"), identifiers) })
        server.ignoreShelf = false
        paperback.reading(10)
        server.ignoreProgress = true
        assertEquals("goodreads_progress_not_applied", held { sync().send(book(), identifiers) })
        // Each post is public: the same value is not posted again while Goodreads does not show it.
        assertEquals("goodreads_progress_not_applied", held { sync().send(book(), identifiers) })
        assertEquals(1, server.progressWrites().size)
        server.ignoreProgress = false
        assertEquals("sent", sync().send(book("4800/10000"), identifiers).getString("outcome"))
        assertNull(store.get("goodreads.book.posted.$GOODREADS_PAPERBACK"))
        server.failProgressOnce = true
        val failure = assertThrows(HttpProblem::class.java) { runBlocking { sync().send(book("5500/10000"), identifiers) } }
        assertEquals("goodreads_http_500", failureReason(failure))
        assertTrue(temporaryFailure(failure))
        // A refused post was not made, so the retry posts it.
        assertEquals("sent", sync().send(book("5500/10000"), identifiers).getString("outcome"))
        assertEquals(55, paperback.percent)
    }

    @Test fun sourceStatusRulesHoldBeforeAnyRequest() = runBlocking {
        assertEquals("progress_unavailable", held { sync().send(book("1/0"), identifiers) })
        assertEquals("source_status_unsupported", held { sync().send(book(status = "3"), identifiers) })
        assertEquals("source_finish_progress_mismatch", held { sync().send(book(status = "2"), identifiers) })
        assertTrue(server.requests.isEmpty())
    }

    @Test fun accountChangesDisabledServiceAndMissingIdentityHoldWithoutWrites() = runBlocking {
        assertEquals("goodreads_account_changed", held { sync().send(book(), identifiers, "someone-else") })
        assertEquals("service_disabled", held { sync { false }.send(book(), identifiers) })
        server.omitUserId = true
        assertEquals("goodreads_identity_unavailable", held { sync().send(book(), identifiers) })
        assertTrue(session.connected())
        assertTrue(server.writes().isEmpty())
    }

    @Test fun aBotChallengeIsPassedOnceByTheHiddenBrowserAndTheRequestSentAgain() = runBlocking {
        store.put("goodreads.refreshedAt", "1")
        server.challenge = true
        assertEquals("sent", sync().send(book(), identifiers).getString("outcome"))
        assertEquals(listOf("${server.origin}/"), refresher.calls)
        assertTrue(session.renewedAt()!! > 1)
        assertTrue(session.connected())
    }

    @Test fun aChallengeTheHiddenBrowserCannotPassEndsTheSessionAndKeepsTheQueuedUpdate() = runBlocking {
        val connection = enabledConnection()
        refresher.pass = {}
        refresher.outcome = RefreshOutcome.TIMEOUT
        server.challenge = true
        connection.send("manual", check(book()))
        val state = connection.state()
        assertFalse(state.getBoolean("connected"))
        assertEquals("goodreads_browser_check_required", state.getString("connectionError"))
        assertEquals("error", state.getJSONObject("last").getString("delivery"))
        assertEquals(1, state.getInt("pending"))
        // Without a session nothing more is sent until the user reconnects.
        val calls = refresher.calls.size
        assertFalse(connection.drain("delivery"))
        assertEquals(calls, refresher.calls.size)
        assertTrue(server.writes().isEmpty())
        // The hidden browser finding Goodreads signed out ends the session the same way.
        session.capture("test-agent")
        refresher.outcome = RefreshOutcome.SIGNED_OUT
        assertEquals("goodreads_session_expired", held { sync().send(book(), identifiers) })
        assertEquals("goodreads_session_expired", store.get("goodreads.connectionError"))
    }

    @Test fun anExpiredSessionEndsTheConnectionAndTheReconnectSendsTheQueuedUpdate() = runBlocking {
        val connection = enabledConnection()
        server.sessionValid = false
        connection.send("manual", check(book()))
        val state = connection.state()
        assertFalse(state.getBoolean("connected"))
        assertEquals("goodreads_session_expired", state.getString("connectionError"))
        assertEquals(1, state.getInt("pending"))
        assertTrue(server.writes().isEmpty())
        server.sessionValid = true
        session.capture("test-agent")
        store.put("goodreads.connectionError", "")
        assertFalse(connection.drain("delivery"))
        assertEquals("synced", connection.state().getJSONObject("last").getString("delivery"))
        assertEquals(47, paperback.percent)
    }

    @Test fun aSendRenewsAStaleSessionInTheHiddenBrowserFirst() = runBlocking {
        val connection = enabledConnection()
        store.put("goodreads.refreshedAt", (System.currentTimeMillis() - GOODREADS_RENEWAL_MS).toString())
        connection.send("manual", check(book()))
        assertEquals(1, refresher.calls.size)
        assertEquals("synced", connection.state().getJSONObject("last").getString("delivery"))
        connection.send("manual", check(book("6000/10000")))
        assertEquals(1, refresher.calls.size)
        // A renewal that finds Goodreads signed out holds the update and records the reason.
        store.put("goodreads.refreshedAt", "1")
        refresher.outcome = RefreshOutcome.SIGNED_OUT
        connection.send("manual", check(book("7000/10000")))
        assertEquals("goodreads_session_expired", connection.state().getJSONObject("last").getString("reason"))
        assertTrue(store.events().any { it.optString("kind") == "goodreads_renewal" && it.optString("outcome") == "signed_out" && it.optBoolean("issue") })
    }

    @Test fun reissuedCookiesReplaceTheStoredOnesInTheGoodreadsProfileOnly() = runBlocking {
        CookieManager.getInstance().setCookie("https://app.thestorygraph.com", "_storygraph_session=story; Path=/")
        server.homeSetCookie = "session-token=rotated-token; Path=/; HttpOnly"
        http.get("/")
        assertEquals("rotated-token", profile.cookies()["session-token"])
        assertEquals("test-ubid", profile.cookies()["ubid-main"])
        assertNull(CookieManager.getInstance().getCookie(server.origin))
        assertEquals("_storygraph_session=story", CookieManager.getInstance().getCookie("https://app.thestorygraph.com"))
    }

    @Test fun logOutSignsOutOnTheServerAndClearsOnlyTheGoodreadsProfile() = runBlocking {
        CookieManager.getInstance().setCookie("https://app.thestorygraph.com", "_storygraph_session=story; Path=/")
        val connection = enabledConnection { false }
        scheduleGoodreadsRenewal(app)
        connection.send("manual", check(book()))
        store.put("goodreads.profile", JSONObject().put("username", "reader").toString())
        store.put("goodreads.match.${digest("first")}", "{}")
        assertEquals(1, store.pending(GOODREADS_ACCOUNT, "goodreads").size)
        assertEquals(1, renewalWork().size)
        connection.logOut()
        val signOut = server.writes().single()
        assertEquals("/user/sign_out", signOut.getString("path"))
        assertEquals("csrf-token-1", signOut.getString("csrf"))
        assertTrue(profile.cookies().isEmpty())
        assertNull(store.get("goodreads.session"))
        assertNull(store.get("goodreads.userAgent"))
        assertTrue(renewalWork().isEmpty())
        assertEquals("_storygraph_session=story", CookieManager.getInstance().getCookie("https://app.thestorygraph.com"))
        val state = connection.state()
        assertFalse(state.getBoolean("enabled"))
        assertFalse(state.getBoolean("connected"))
        assertTrue(state.isNull("profile"))
        assertEquals(0, state.getInt("pending"))
        assertNull(store.get("goodreads.match.${digest("first")}"))
    }

    @Test fun aWebViewWithoutProfilesKeepsTheSwitchOffWithTheReason() = runBlocking {
        signedOut()
        profile.supported = false
        val connection = GoodreadsConnection(app, profile, refresher, http) { true }
        connection.setEnabled(true)
        assertFalse(connection.awaitingCredentials)
        assertFalse(connection.state().getBoolean("enabled"))
        assertEquals("goodreads_webview_profiles_unsupported", store.get("goodreads.connectionError"))
    }

    @Test fun theRenewalWorkerRenewsAConnectedSessionAndSkipsWithoutOne() = runBlocking {
        app.goodreads = connection()
        store.put("goodreads.enabled", "true")
        store.put("goodreads.refreshedAt", "1")
        val worker = androidx.work.testing.TestListenableWorkerBuilder<GoodreadsRenewalWorker>(app).build()
        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        assertEquals(1, refresher.calls.size)
        assertTrue(session.renewedAt()!! > 1)
        assertTrue(store.events().any { it.optString("kind") == "run" && it.optString("source") == "renewal" && it.optString("reason") == "signed_in" && !it.optBoolean("issue") })
        assertEquals(GOODREADS_RENEWAL_MS, goodreadsRenewalRequest(null).workSpec.intervalDuration)
        // The first run comes one interval after the last renewal: at once for a stale session, not right after a sign-in.
        assertEquals(0, goodreadsRenewalRequest(1).workSpec.initialDelay)
        assertTrue(goodreadsRenewalRequest(session.renewedAt()).workSpec.initialDelay > GOODREADS_RENEWAL_MS - 60_000)
        // A timeout keeps the session and records where the hidden browser stopped.
        refresher.outcome = RefreshOutcome.TIMEOUT
        refresher.pageState = "checking"
        worker.doWork()
        assertTrue(session.connected())
        assertTrue(store.events().any { it.optString("kind") == "goodreads_renewal" && it.optString("outcome") == "timeout" && it.optString("pageState") == "checking" && it.optBoolean("issue") })
        refresher.outcome = RefreshOutcome.SIGNED_IN
        session.mark("goodreads_session_expired")
        worker.doWork()
        assertEquals(2, refresher.calls.size)
        // Off pauses all Goodreads traffic, renewals included.
        session.capture("test-agent")
        store.put("goodreads.enabled", "false")
        worker.doWork()
        assertEquals(2, refresher.calls.size)
    }

    private class Screen(val activity: MainActivity) {
        val model = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
        fun views() = activity.findViewById<View>(android.R.id.content).allViews.toList()
        fun toggle() = views().filterIsInstance<CheckBox>().singleOrNull { it.contentDescription?.startsWith("Goodreads:") == true }
        fun title() = views().filterIsInstance<TextView>().single { it.text.toString() == "Goodreads" }
        fun rowLines() = (title().parent as ViewGroup).children.filterNot { it is CheckBox }.map { (it as TextView).text.toString() }.toList()
        fun warning() = activity.findViewById<View>(R.id.access_warning)
    }

    /** Runs [block] on the Sync screen with [library], then destroys the screen. */
    private suspend fun onScreen(library: FixtureProvider = FixtureProvider().withSyncBook(), block: suspend Screen.() -> Unit) {
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, library)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            Screen(controller.get()).block()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun theSwitchOpensGoodreadsSignInPageAndOnlyASignedInHomeConnects() = capturingStderr {
        signedOut()
        app.goodreads = connection()
        onScreen {
            awaitUi { toggle()?.isEnabled == true && !model.busy }
            assertEquals(listOf("Goodreads", "Off", "Not connected"), rowLines())
            toggle()!!.performClick()
            awaitUi { popup() != null && !model.busy }
            val dialog = popup()!!
            val web = dialog.findViewById<WebView>(R.id.goodreads_sign_in_web)!!
            assertEquals("${server.origin}/user/sign_in", shadowOf(web).lastLoadedUrl)
            assertEquals(1, profile.attached.size)
            assertTrue(web.settings.javaScriptEnabled)
            // Goodreads serves a tablet its desktop page without a viewport tag; it is zoomed out to the popup's width.
            assertTrue(web.settings.useWideViewPort)
            assertTrue(web.settings.loadWithOverviewMode)
            assertTrue(web.settings.builtInZoomControls)
            assertFalse(web.settings.displayZoomControls)
            assertEquals(0, dialog.window!!.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            assertEquals(listOf("Goodreads", "Sign-in required", "Sign in to Goodreads in the popup"), rowLines())
            fun status() = dialog.findViewById<TextView>(R.id.goodreads_sign_in_status)?.text?.toString()
            // Amazon's sign-in pages stay in the popup; other sites do not.
            val client = shadowOf(web).webViewClient
            fun opens(url: String) = !client.shouldOverrideUrlLoading(web, request(url))
            assertTrue(opens("${server.origin}/ap/signin"))
            assertTrue(opens("https://www.amazon.com/ap/signin"))
            assertFalse(opens("https://example.com/"))
            // Goodreads' signed-out home page is a landing page: the popup keeps waiting and nothing is stored.
            client.onPageFinished(web, "${server.origin}/")
            awaitUi { !app.goodreads.signingIn && !model.busy }
            assertTrue(dialog.isShowing)
            assertTrue(app.goodreads.awaitingCredentials)
            assertEquals("", status().orEmpty())
            assertNull(store.get("goodreads.session"))
            // Goodreads' page signs the user in: the cookies land in the Goodreads profile and the page arrives at the home page.
            profile.set("$GOODREADS_SESSION_COOKIE; Path=/; HttpOnly")
            client.onPageFinished(web, "${server.origin}/")
            awaitUi { popup() == null && !app.goodreads.signingIn && app.goodreads.state().getBoolean("enabled") && !model.busy }
            assertEquals(GOODREADS_ACCOUNT, store.get("goodreads.account"))
            assertEquals(web.settings.userAgentString, store.get("goodreads.userAgent"))
            assertEquals(1, renewalWork().size)
            awaitUi { !app.goodreads.state().isNull("profile") && toggle()?.contentDescription?.contains("Book matched") == true }
            assertEquals("reader", app.goodreads.state().getJSONObject("profile").getString("username"))
            assertEquals(42, paperback.percent)
            assertTrue(store.events().any { it.optString("kind") == "goodreads_connection" && it.optString("outcome") == "connected" })
            val export = buildExport(app.diagnostics).toString()
            listOf("test-session-token", GOODREADS_ACCOUNT, "csrf-token-1", "synthetic-jwt").forEach { assertFalse(it, export.contains(it)) }
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun aCaptureThatFailsKeepsThePopupWithTheReasonAndCancelClearsTheProfile() = capturingStderr {
        signedOut()
        server.omitUserId = true
        app.goodreads = connection()
        onScreen {
            awaitUi { toggle()?.isEnabled == true && !model.busy }
            toggle()!!.performClick()
            awaitUi { popup() != null && !model.busy }
            val dialog = popup()!!
            val web = dialog.findViewById<WebView>(R.id.goodreads_sign_in_web)!!
            fun status() = dialog.findViewById<TextView>(R.id.goodreads_sign_in_status)?.text?.toString()
            profile.set("$GOODREADS_SESSION_COOKIE; Path=/; HttpOnly")
            shadowOf(web).webViewClient.onPageFinished(web, "${server.origin}/")
            awaitUi { !app.goodreads.signingIn && status() == "goodreads identity unavailable" }
            assertTrue(dialog.isShowing)
            assertTrue(toggle()!!.isChecked)
            assertFalse(app.goodreads.state().getBoolean("enabled"))
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
            awaitUi { !dialog.isShowing && !app.goodreads.awaitingCredentials && toggle()?.isChecked == false && !model.busy }
            // Cancel discards the session the page established; nothing authenticated stays in the Goodreads profile.
            assertTrue(profile.cookies().isEmpty())
            assertNull(store.get("goodreads.session"))
        }
    }

    private fun request(url: String) = object : android.webkit.WebResourceRequest {
        override fun getUrl() = android.net.Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun aWebViewWithoutProfilesShowsTheReasonOnTheRow() = capturingStderr {
        signedOut()
        profile.supported = false
        app.goodreads = connection()
        onScreen {
            awaitUi { toggle()?.isEnabled == true && !model.busy }
            toggle()!!.performClick()
            awaitUi { toggle()?.isChecked == false && !model.busy && rowLines().size == 3 && rowLines()[2] == "Goodreads needs a newer Android System WebView" }
            assertNull(popup())
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun theGoodreadsRowWarnsAboutAMissingFinishDateAndShowsWhatGoodreadsHolds() = capturingStderr {
        paperback.reading(47)
        server.actionErrors = true
        app.goodreads = enabledConnection()
        val finished = FixtureProvider().withSyncBook().apply { libraryRecords = listOf(listOf("Synthetic Book", "Synthetic Book", "100/100", "2", null, null, "2026-10-06T10:00:00Z")) }
        onScreen(finished) {
            awaitUi { toggle()?.contentDescription?.contains("Finish date not set") == true && !app.goodreads.state().isNull("profile") && !model.busy }
            assertEquals("⚠ Finish date not set · goodreads finish date not applied", rowLines()[1])
            assertTrue(warning() != null)
            tap(title())
            awaitUi { popupMessage()?.contains("Reader Name") == true }
            val details = popupMessage()!!
            assertTrue(warningsDrawn(details))
            assertEquals(details.toString(), listOf("Goodreads marked the book Read but did not set its finish date: goodreads finish date not applied."), issueLines(details))
            listOf("@reader · Reader Name", "Exact edition, by its identifiers", "Progress: Finished", "Finish date: Not set (goodreads finish date not applied)", "Shelf: Read").forEach { assertTrue(it, details.contains(it)) }
            assertEquals("read", paperback.shelf)
        }
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun aSmallStepShowsTheProgressGoodreadsHoldsAndWhenTheNextUpdateIsSent() = capturingStderr {
        paperback.reading(40)
        app.goodreads = enabledConnection()
        onScreen {
            awaitUi { toggle()?.contentDescription?.contains("Book matched") == true && !model.busy }
            assertEquals("✅ Book matched · same edition", rowLines()[1])
            assertNull(warning())
            tap(title())
            awaitUi { popupMessage()?.contains("Current book") == true }
            assertTrue(popupMessage()!!.contains("Progress: 40% · the next update is sent at 45%"))
            assertTrue(server.progressWrites().isEmpty())
        }
    }
}

private fun JSONObject.toMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
