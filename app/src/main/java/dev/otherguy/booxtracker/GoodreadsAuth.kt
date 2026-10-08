package dev.otherguy.booxtracker

import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Goodreads pages run to several hundred kilobytes; Next.js chunk scripts can be larger. */
private const val GOODREADS_RESPONSE_LIMIT = 4 * 1024 * 1024

/** A page Goodreads served, with the path it ended at after redirects. */
data class GoodreadsPage(val path: String, val text: String)

/**
 * The Goodreads browser session. The cookies live in the Goodreads WebView profile, which Android keeps in the app's
 * private storage; the state table holds only whether a session was captured, why it stopped, when a hidden browser
 * last renewed it, and the WebView's User-Agent. Cookie values are never copied anywhere else.
 */
class GoodreadsSession(private val store: DiagnosticsStore, val profile: WebProfile, val origin: String = GOODREADS_ORIGIN) {
    fun connected(): Boolean = store.get("goodreads.session") == "true"

    fun userAgent(): String = store.get("goodreads.userAgent")?.takeIf { it.isNotBlank() } ?: FALLBACK_USER_AGENT

    /** Records the session the sign-in WebView established, once its home page showed the signed-in header. */
    fun capture(userAgent: String) {
        store.put("goodreads.userAgent", userAgent)
        store.put("goodreads.session", "true")
        renewed()
    }

    /** Records why the session stopped working; the row then asks for a reconnect with that reason. */
    fun mark(problem: String) {
        store.put("goodreads.session", problem)
        store.put("goodreads.connectionError", problem)
    }

    /** When a hidden browser last loaded Goodreads signed in, in epoch milliseconds. */
    fun renewedAt(): Long? = store.get("goodreads.refreshedAt")?.toLongOrNull()

    fun renewed() = store.put("goodreads.refreshedAt", System.currentTimeMillis().toString())

    /** Removes the browser session from this device: the Goodreads profile's cookies and storage, nothing else. */
    suspend fun clear() {
        profile.clear()
        listOf("goodreads.session", "goodreads.userAgent", "goodreads.refreshedAt").forEach(store::delete)
    }
}

/** A bot challenge answered instead of the page; only a browser that runs the challenge script can pass it. */
private class Challenged : Exception()

/**
 * Requests against Goodreads' website with the session cookies, classified into session problems, HTTP problems, and
 * pages. A bot challenge is passed once by loading Goodreads in [refresher]'s hidden browser, then the request is sent
 * again; a second challenge ends the session.
 */
class GoodreadsHttp(val session: GoodreadsSession, private val refresher: SessionRefresher) {
    val origin: String get() = session.origin

    /** A page, the way a browser navigation asks for it; Goodreads loops a self-redirect for requests without these headers. */
    suspend fun get(path: String): GoodreadsPage = passing(mark = true) { navigate(path, null, mark = true) }

    /** A page that must show the signed-in header. A signed-out page ends the session. */
    suspend fun signedInPage(path: String): GoodreadsPage = get(path).also { if (!goodreadsSignedIn(it.text)) throw problem("goodreads_session_expired", true) }

    /** The home page if the WebView's cookies are signed in, else null. A capture check never marks the session. */
    suspend fun signedInHome(userAgent: String): String? = try {
        passing(mark = false) { navigate("/", userAgent, mark = false) }.text.takeIf(::goodreadsSignedIn)
    } catch (error: SyncProblem) {
        if (error.code == "goodreads_session_expired") null else throw error
    }

    /** A Next.js chunk script, requested the way the page's own script tag loads it. */
    suspend fun script(path: String, referer: String): String = passing(mark = true) {
        send("GET", path, mapOf("Accept" to "*/*", "Referer" to origin + referer, "Sec-Fetch-Site" to "same-origin", "Sec-Fetch-Mode" to "no-cors", "Sec-Fetch-Dest" to "script")).text
    }

    /** A form post the way Goodreads' own pages send it: the CSRF token as a field and a header. */
    suspend fun post(path: String, form: Map<String, String>, csrf: String, referer: String): String = passing(mark = true) {
        send(
            "POST",
            path,
            mapOf(
                "Accept" to "text/javascript, text/html, application/xml, text/xml, */*",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-CSRF-Token" to csrf,
                "X-Requested-With" to "XMLHttpRequest",
                "Origin" to origin,
                "Referer" to origin + referer,
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty"
            ),
            (form + ("authenticity_token" to csrf)).entries.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
        ).text
    }

    /** Calls a Next.js Server Action of the page at [path], as the page's own form does. */
    suspend fun action(path: String, actionId: String, routerTree: String, body: String): String = passing(mark = true) {
        send(
            "POST",
            path,
            mapOf(
                "Accept" to "text/x-component",
                "Content-Type" to "text/plain;charset=UTF-8",
                "Next-Action" to actionId,
                "Next-Router-State-Tree" to URLEncoder.encode(routerTree, "UTF-8"),
                "Origin" to origin,
                "Referer" to origin + path,
                "Sec-Fetch-Site" to "same-origin",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty"
            ),
            body
        ).text
    }

    /** The signed-in account's numeric id from the home page. A signed-out page ends the session; a missing id holds. */
    suspend fun account(): String = goodreadsUserId(signedInPage("/").text) ?: throw SyncProblem("goodreads_identity_unavailable")

    private suspend fun navigate(path: String, userAgent: String?, mark: Boolean): GoodreadsPage = send(
        "GET",
        path,
        mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Referer" to "$origin/",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1"
        ),
        userAgent = userAgent,
        mark = mark
    )

    /** Sends [block] and, after one bot challenge, loads Goodreads in the hidden browser and sends it once more. */
    private suspend fun <T> passing(mark: Boolean, block: suspend () -> T): T {
        try {
            return block()
        } catch (_: Challenged) {
        }
        when (refresher.refresh("$origin/").outcome) {
            RefreshOutcome.SIGNED_OUT -> throw problem("goodreads_session_expired", mark)

            // A capture check stores nothing until the page shows the signed-in header.
            RefreshOutcome.SIGNED_IN -> if (mark) session.renewed()

            RefreshOutcome.TIMEOUT -> {}
        }
        try {
            return block()
        } catch (_: Challenged) {
            throw problem("goodreads_browser_check_required", mark)
        }
    }

    /**
     * Sends one request and follows a GET's redirects inside the Goodreads origin, up to five. Each response's cookies
     * are stored before the next hop, because Goodreads' session bootstrap redirects to the same page and expects them.
     */
    private suspend fun send(method: String, path: String, headers: Map<String, String>, body: String? = null, userAgent: String? = null, mark: Boolean = true): GoodreadsPage {
        var current = path
        repeat(6) { hop ->
            val url = origin + current
            val response = withContext(Dispatchers.IO) {
                val sent = buildMap {
                    putAll(headers)
                    put("User-Agent", userAgent ?: session.userAgent())
                    session.profile.cookieHeader(url)?.let { put("Cookie", it) }
                }
                fetchPage(method, url, sent, body, GOODREADS_RESPONSE_LIMIT).also { session.profile.accept(url, it.headers["set-cookie"].orEmpty()) }
            }
            if (isGoodreadsChallenge(response.headers)) throw Challenged()
            val location = response.headers["location"]?.firstOrNull()
            if (response.status == 401 || isGoodreadsSignIn(location)) throw problem("goodreads_session_expired", mark)
            // Goodreads answers a signed-in search with one exact hit by a 200 that carries the book page's Location.
            val next = location?.let { inOrigin(current, it) }?.takeIf { response.status in 300..399 || (response.status == 200 && it != current) }
            if (method == "GET" && next != null && hop < 5) {
                current = next
                return@repeat
            }
            if (response.status !in 200..299) throw HttpProblem(response.status, null, "goodreads")
            return GoodreadsPage(current, response.text)
        }
        throw HttpProblem(310, null, "goodreads")
    }

    /** [location] as a path inside the Goodreads origin, or null when it leaves the origin. */
    private fun inOrigin(current: String, location: String): String? {
        val base = URI(origin)
        val target = runCatching { URI(origin + current).resolve(location) }.getOrNull() ?: return null
        if (target.scheme != base.scheme || target.host != base.host || target.port != base.port) return null
        return target.rawPath + (target.rawQuery?.let { "?$it" } ?: "")
    }

    private fun problem(code: String, mark: Boolean): SyncProblem {
        if (mark) session.mark(code)
        return SyncProblem(code)
    }
}
