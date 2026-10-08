package dev.otherguy.booxtracker

import android.webkit.CookieManager
import android.webkit.WebStorage
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

const val STORYGRAPH_ORIGIN = "https://app.thestorygraph.com"

/** The User-Agent for requests made before a sign-in stored the WebView's own string. */
internal const val FALLBACK_USER_AGENT = "Mozilla/5.0 (Linux; Android 12; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"

/** A response with lower-case header names, so `cf-mitigated` and `Set-Cookie` are found whatever the server's casing. */
internal class WebResponse(val status: Int, val headers: Map<String, List<String>>, val text: String)

/** Sends one request with `HttpURLConnection`, never following redirects, and reads at most [limit] bytes. */
internal fun fetchPage(method: String, url: String, headers: Map<String, String>, body: String?, limit: Int = 2 * 1024 * 1024): WebResponse {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val text = stream?.use { readLimited(it, limit).toString(Charsets.UTF_8) }.orEmpty()
        return WebResponse(status, connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }, text)
    } finally {
        connection.disconnect()
    }
}

/**
 * The StoryGraph browser session. The cookies live in the WebView cookie store, which Android keeps in the app's
 * private storage; the state table holds only whether a session was captured, why it stopped, and the WebView's
 * User-Agent. Cookie values are never copied anywhere else.
 */
class StoryGraphSession(private val store: DiagnosticsStore, val origin: String = STORYGRAPH_ORIGIN) {
    /** Loads the WebView provider, so only requests and session changes call it; [connected] never does. */
    private fun cookies(): CookieManager = CookieManager.getInstance()

    fun connected(): Boolean = store.get("storygraph.session") == "true"

    fun userAgent(): String = store.get("storygraph.userAgent")?.takeIf { it.isNotBlank() } ?: FALLBACK_USER_AGENT

    fun cookieHeader(): String? = cookies().getCookie(origin)?.takeIf { it.isNotBlank() }

    /** Stores the cookies a response set, so a re-issued session cookie replaces the stale one. */
    fun accept(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val manager = cookies()
        setCookies.forEach { manager.setCookie(origin, it) }
        manager.flush()
    }

    /** Records the session the sign-in WebView established. The session cookie must be present. */
    fun capture(userAgent: String) {
        if (cookieHeader()?.contains("_storygraph_session=") != true) throw SyncProblem("storygraph_session_cookie_missing")
        cookies().flush()
        store.put("storygraph.userAgent", userAgent)
        store.put("storygraph.session", "true")
    }

    /** Records why the session stopped working; the row then asks for a reconnect with that reason. */
    fun mark(problem: String) {
        store.put("storygraph.session", problem)
        store.put("storygraph.connectionError", problem)
    }

    /**
     * Removes the browser session from this device. StoryGraph is the only user of the default WebView profile, so
     * clearing every cookie in it is safe. WebView answers a cookie callback on the calling thread's Looper and
     * refuses a thread without one, so the removal runs on the main thread.
     */
    suspend fun clear() {
        withContext(Dispatchers.Main.immediate) {
            val manager = cookies()
            suspendCancellableCoroutine { done -> manager.removeAllCookies { done.resume(Unit) } }
            manager.flush()
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
        store.delete("storygraph.session")
        store.delete("storygraph.userAgent")
    }
}

/** Requests against StoryGraph's website with the session cookies, classified into session problems, HTTP problems, and text. */
class StoryGraphHttp(val session: StoryGraphSession) {
    val origin: String get() = session.origin

    /** A page, or a `turbo-frame` fragment when [frame] names one. */
    fun get(path: String, frame: String? = null): String {
        val headers = buildMap {
            put("Accept", "text/html,application/xhtml+xml")
            frame?.let { put("turbo-frame", it) }
        }
        return send("GET", path, headers, null)
    }

    /** A form post the way StoryGraph's own pages send it: the CSRF token as a field and a header, from a page the user has. */
    fun post(path: String, form: Map<String, String>, csrf: String, referer: String): String = send(
        "POST",
        path,
        mapOf(
            "Accept" to "text/javascript, application/javascript, */*; q=0.01",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-CSRF-Token" to csrf,
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to origin,
            "Referer" to origin + referer
        ),
        (form + ("authenticity_token" to csrf)).entries.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
    )

    private fun send(method: String, path: String, headers: Map<String, String>, body: String?): String {
        val sent = buildMap {
            putAll(headers)
            put("User-Agent", session.userAgent())
            session.cookieHeader()?.let { put("Cookie", it) }
        }
        val response = fetchPage(method, origin + path, sent, body)
        session.accept(response.headers["set-cookie"].orEmpty())
        when {
            isChallenge(response.status, response.headers, response.text) -> throw problem("storygraph_browser_check_required")
            isSignInRedirect(response.status, response.headers["location"]?.firstOrNull()) -> throw problem("storygraph_session_expired")
            response.status !in 200..299 -> throw HttpProblem(response.status, null, "storygraph")
        }
        return response.text
    }

    private fun problem(code: String): SyncProblem {
        session.mark(code)
        return SyncProblem(code)
    }

    /** The signed-in account's stable id from the home page. A signed-out page ends the session; a missing id holds. */
    fun account(): String {
        val home = get("/")
        if (!storyGraphSignedIn(home)) throw problem("storygraph_session_expired")
        return storyGraphUserId(home) ?: throw SyncProblem("storygraph_identity_unavailable")
    }
}
