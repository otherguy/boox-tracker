package dev.otherguy.booxtracker

import android.os.Looper
import android.webkit.WebView
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GoodreadsBrowserTest {
    private val profile = FakeWebProfile()
    private val url = "$GOODREADS_ORIGIN/"

    private fun idle(duration: Duration = Duration.ZERO) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    /** Starts a refresh and runs the main looper until the hidden WebView has started loading [url]. */
    private fun start(): Pair<Deferred<Refresh>, WebView> {
        val refresh = CoroutineScope(Dispatchers.Unconfined).async { HiddenWebViewRefresher(RuntimeEnvironment.getApplication(), profile).refresh(url) }
        idle()
        val web = profile.attached.last()
        assertEquals(url, shadowOf(web).lastLoadedUrl)
        assertTrue(web.settings.javaScriptEnabled)
        return refresh to web
    }

    /** Finishes a page load; the page check answers [state] as a JavaScript string, or never answers when null. */
    private fun finishPage(web: WebView, state: String?) {
        shadowOf(web).webViewClient.onPageFinished(web, url)
        if (state != null) shadowOf(web).lastEvaluatedJavascriptCallback.onReceiveValue("\"$state\"")
        idle()
    }

    private fun result(refresh: Deferred<Refresh>, web: WebView): Refresh {
        assertTrue(refresh.isCompleted)
        assertTrue(shadowOf(web).wasDestroyCalled())
        return runBlocking { refresh.await() }
    }

    @Test fun aBotCheckWaitsForTheReloadedPageToAnswer() {
        val (refresh, web) = start()
        finishPage(web, "checking")
        assertFalse(refresh.isCompleted)
        finishPage(web, "signed_in")
        assertEquals(Refresh(RefreshOutcome.SIGNED_IN), result(refresh, web))
        assertEquals(1, profile.flushes)
    }

    @Test fun aSignedOutPageEndsTheRefresh() {
        val (refresh, web) = start()
        finishPage(web, "signed_out")
        assertEquals(Refresh(RefreshOutcome.SIGNED_OUT), result(refresh, web))
    }

    @Test fun aTimeoutReportsWhereThePageStopped() {
        // Whether a page finished loading, the page check's answer, and the page state the timeout reports.
        val cases = listOf(Triple(false, null, "no_page"), Triple(true, null, "page_finished"), Triple(true, "checking", "checking"), Triple(true, "loading", "loading"))
        for ((finished, answer, pageState) in cases) {
            val (refresh, web) = start()
            if (finished) finishPage(web, answer)
            idle(Duration.ofSeconds(29))
            assertFalse(pageState, refresh.isCompleted)
            idle(Duration.ofSeconds(1))
            assertEquals(Refresh(RefreshOutcome.TIMEOUT, pageState), result(refresh, web))
        }
        assertEquals(cases.size, profile.flushes)
    }
}
