package dev.otherguy.booxtracker

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The browser storage one website session lives in: its cookies and web storage, apart from every other service's.
 * Cookie values never leave it except as the `Cookie` header of a request to that website.
 */
interface WebProfile {
    /** Whether this device's WebView can keep a separate profile. Loads the WebView provider. */
    fun supported(): Boolean

    suspend fun cookieHeader(url: String): String?

    /** Stores the cookies a response set, so a re-issued session cookie replaces the stale one. */
    suspend fun accept(url: String, setCookies: List<String>)

    suspend fun flush()

    /** Removes every cookie and all web storage of this profile. */
    suspend fun clear()

    /** Puts [web] in this profile. Call on the main thread before anything else is done with [web]. */
    fun attach(web: WebView)
}

/** A named `androidx.webkit` profile, so signing out of one website never touches another website's session. */
class NamedWebProfile(private val name: String) : WebProfile {
    @Volatile private var profile: Profile? = null

    override fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    /** The profile store is used on the main thread; the profile's cookie manager works on any thread. */
    private suspend fun profile(): Profile = profile ?: withContext(Dispatchers.Main) {
        if (!supported()) throw SyncProblem("goodreads_webview_profiles_unsupported")
        ProfileStore.getInstance().getOrCreateProfile(name)
    }.also { profile = it }

    override suspend fun cookieHeader(url: String): String? = profile().cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }

    override suspend fun accept(url: String, setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val manager = profile().cookieManager
        setCookies.forEach { manager.setCookie(url, it) }
        manager.flush()
    }

    override suspend fun flush() = profile().cookieManager.flush()

    override suspend fun clear() {
        val profile = profile()
        // WebView answers a cookie callback on the calling thread's Looper and refuses a thread without one.
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { done -> profile.cookieManager.removeAllCookies { done.resume(Unit) } }
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
        }
    }

    override fun attach(web: WebView) {
        ProfileStore.getInstance().getOrCreateProfile(name)
        WebViewCompat.setProfile(web, name)
    }
}

/** What loading the website in a hidden browser found. */
enum class RefreshOutcome { SIGNED_IN, SIGNED_OUT, TIMEOUT }

/** Loads a page in a browser that runs the website's own scripts, so a bot check can pass and session cookies renew. */
fun interface SessionRefresher {
    suspend fun refresh(url: String): RefreshOutcome
}

/** Asks the loaded page whether it is signed in, still running a bot check, or signed out. */
private const val PAGE_STATE = """(function() {
  if (document.querySelector('a[href*="/user/sign_out"]')) return 'signed_in';
  if (/^\/(user\/sign_in|ap\/signin)/.test(location.pathname)) return 'signed_out';
  if (window.gokuProps || document.querySelector('script[src*="awswaf"], script[src*="challenge.js"]')) return 'checking';
  if (document.querySelector('a[href*="/user/sign_in"]')) return 'signed_out';
  return 'loading';
})()"""

/**
 * A WebView without a window, in [profile], that loads a page and waits until the page is signed in or signed out.
 * A bot check reloads the page once it passes. The WebView is destroyed after at most [timeoutMs].
 */
class HiddenWebViewRefresher(private val context: Context, private val profile: WebProfile, private val timeoutMs: Long = 30_000) : SessionRefresher {
    @SuppressLint("SetJavaScriptEnabled")
    override suspend fun refresh(url: String): RefreshOutcome = withContext(Dispatchers.Main) {
        val web = WebView(context.applicationContext)
        try {
            profile.attach(web)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { done ->
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            view.evaluateJavascript(PAGE_STATE) { state ->
                                val outcome = when (state.trim('"')) {
                                    "signed_in" -> RefreshOutcome.SIGNED_IN
                                    "signed_out" -> RefreshOutcome.SIGNED_OUT
                                    else -> null
                                }
                                if (outcome != null && done.isActive) done.resume(outcome)
                            }
                        }
                    }
                    web.loadUrl(url)
                }
            } ?: RefreshOutcome.TIMEOUT
        } finally {
            web.stopLoading()
            web.destroy()
            withContext(NonCancellable) { profile.flush() }
        }
    }
}
