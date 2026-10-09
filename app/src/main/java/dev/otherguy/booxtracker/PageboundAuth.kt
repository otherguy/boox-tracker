package dev.otherguy.booxtracker

import java.net.URLEncoder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Pagebound's public Firebase web API key, shipped in Pagebound's website. It identifies the Firebase project; it is not a credential. */
const val PAGEBOUND_FIREBASE_KEY = "AIzaSyDCfBJ51pRZgHueBfBz0KNDiPNev1ClnGg"

/** Pagebound's public search-only catalogue key, shipped in Pagebound's website. It is not a credential. */
const val PAGEBOUND_SEARCH_KEY = "SgSrp2Vx4V4wjJAwnME6uWUufNdi9BxM"

/** The account request that starts every send; its refusal is the only sign that Pagebound rejected the token. */
private const val PAGEBOUND_ACCOUNT_PATH = "/auth/get_authed_user"

/** Marks a refused account request: Pagebound answers a rejected token with HTTP 500 and an empty body. */
private const val PAGEBOUND_REJECTED_TOKEN = "rejected_token"

class PageboundHttp(
    private val api: String = "https://prod-pagebound-api.onrender.com/api/v1",
    private val identity: String = "https://identitytoolkit.googleapis.com",
    private val secureToken: String = "https://securetoken.googleapis.com",
    private val search: String = "https://hztadco4ku1vqi6lp.a1.typesense.net",
    private val key: String = PAGEBOUND_FIREBASE_KEY,
    private val searchKey: String = PAGEBOUND_SEARCH_KEY
) {
    fun signIn(email: String, password: String): JSONObject = firebase(
        "$identity/v1/accounts:signInWithPassword?key=$key",
        "application/json",
        JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString()
    )

    fun refresh(token: String): JSONObject = firebase(
        "$secureToken/v1/token?key=$key",
        "application/x-www-form-urlencoded",
        "grant_type=refresh_token&refresh_token=${URLEncoder.encode(token, "UTF-8")}"
    )

    /** Trades a Firebase ID token for Pagebound's own API token. The website sends the literal "null" bearer before it has one. */
    fun exchange(idToken: String): String = api("POST", "/auth/firebase_auth", JSONObject().put("id_token", idToken), "null").text("token")
        ?: throw SyncProblem("pagebound_invalid_token_response")

    fun get(token: String, path: String): JSONObject = api("GET", path, null, token)

    fun authedUser(token: String): JSONObject = get(token, PAGEBOUND_ACCOUNT_PATH)

    fun write(token: String, method: String, path: String, body: JSONObject): JSONObject = api(method, path, body, token)

    /** Up to five catalogue hits for a title and author query, each with `id`, `uuid`, `title`, and `author_name`. */
    fun searchBooks(query: String): List<JSONObject> {
        val request = JSONObject().put("searches", JSONArray().put(JSONObject().put("collection", "books").put("q", query).put("query_by", "title,author_name").put("per_page", 5)))
        val response = httpRequest("POST", "$search/multi_search?x-typesense-api-key=$searchKey", "application/json", request.toString(), null)
        if (response.status !in 200..299) throw HttpProblem(response.status, null, "pagebound_search")
        val result = runCatching { JSONObject(response.text).getJSONArray("results").getJSONObject(0) }.getOrNull() ?: throw SyncProblem("pagebound_search_failed")
        if (result.has("error")) throw SyncProblem("pagebound_search_failed")
        val hits = result.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optJSONObject("document") }
    }

    private fun firebase(url: String, type: String, body: String): JSONObject {
        val response = httpRequest("POST", url, type, body, null)
        if (response.status !in 200..299) throw HttpProblem(response.status, firebaseError(response.text), "pagebound")
        return runCatching { JSONObject(response.text) }.getOrNull() ?: throw SyncProblem("pagebound_invalid_json")
    }

    // Pagebound's API runs on a host that sleeps when idle; the first request after a pause can take most of a minute.
    private fun api(method: String, path: String, body: JSONObject?, token: String): JSONObject {
        val response = httpRequest(method, api + path, "application/json", body?.toString(), "Bearer $token", connectTimeout = 30_000, readTimeout = 90_000)
        if (response.status !in 200..299) {
            val refused = path == PAGEBOUND_ACCOUNT_PATH && (response.status == 401 || response.status == 403 || (response.status == 500 && response.text.isBlank()))
            throw HttpProblem(response.status, PAGEBOUND_REJECTED_TOKEN.takeIf { refused }, "pagebound")
        }
        if (response.text.isBlank()) return JSONObject()
        return runCatching { JSONObject(response.text) }.getOrNull() ?: throw SyncProblem("pagebound_invalid_json")
    }
}

internal fun pageboundAccountId(response: JSONObject): String = response.optJSONObject("user")?.text("uuid") ?: throw SyncProblem("pagebound_identity_unavailable")

/**
 * Pagebound sign-in through Firebase, then Pagebound's own API token. The vault holds that token (it has no expiry)
 * and the Firebase refresh token, which renews it; the password is never kept.
 */
class PageboundAuth(val http: PageboundHttp, private val vault: TokenVault) {
    private val mutex = Mutex()

    private fun credentials(): OAuthTokens? = try {
        vault.read()
    } catch (_: Exception) {
        throw SyncProblem("pagebound_credentials_unreadable_reconnect")
    }

    fun connected(): Boolean = credentials() != null

    suspend fun signIn(email: String, password: String) = mutex.withLock {
        val firebase = http.signIn(email, password)
        val tokens = session(firebase.text("idToken"), firebase.text("refreshToken"))
        coroutineContext.ensureActive()
        vault.write(tokens)
    }

    suspend fun <T> authorized(block: suspend (String) -> T): T = mutex.withLock {
        coroutineContext.ensureActive()
        val tokens = credentials() ?: throw SyncProblem("pagebound_not_connected")
        try {
            block(tokens.access)
        } catch (error: HttpProblem) {
            if (error.oauthError != PAGEBOUND_REJECTED_TOKEN) throw error
            val renewed = renew(tokens)
            try {
                block(renewed)
            } catch (again: HttpProblem) {
                if (again.oauthError != PAGEBOUND_REJECTED_TOKEN) throw again
                // Pagebound refuses a token it issued moments ago, so the account's session is over.
                vault.clear()
                throw SyncProblem("pagebound_session_expired")
            }
        }
    }

    private fun session(idToken: String?, refreshToken: String?): OAuthTokens {
        if (idToken == null || refreshToken == null) throw SyncProblem("pagebound_invalid_token_response")
        return OAuthTokens(http.exchange(idToken), refreshToken, Long.MAX_VALUE)
    }

    private fun renew(tokens: OAuthTokens): String {
        val firebase = try {
            http.refresh(tokens.refresh)
        } catch (error: HttpProblem) {
            if (error.oauthError in deadSession) vault.clear()
            throw error
        }
        return session(firebase.text("id_token"), firebase.text("refresh_token")).also(vault::write).access
    }

    /** Firebase has no client-side refresh-token revocation, so disconnecting removes the local session only. */
    suspend fun disconnect() = mutex.withLock {
        vault.clear()
        Unit
    }
}
