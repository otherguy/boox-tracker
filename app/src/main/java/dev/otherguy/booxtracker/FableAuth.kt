package dev.otherguy.booxtracker

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Fable's public Firebase web API key. It identifies Fable's Firebase project and is shipped in Fable's own clients; it is not a credential. */
const val FABLE_FIREBASE_KEY = "AIzaSyDDTAUL4hpHMG95iHIh8yRb0-2irN0YOvs"

private val firebaseErrors = setOf(
    "invalid_password", "email_not_found", "invalid_login_credentials", "invalid_email", "missing_password", "user_disabled",
    "too_many_attempts_try_later", "token_expired", "user_not_found", "invalid_refresh_token", "missing_recaptcha_token"
)

/** Refresh failures after which the stored session can never recover without a new sign-in. */
internal val deadSession = setOf("token_expired", "user_disabled", "user_not_found", "invalid_refresh_token")

class FableHttp(
    private val api: String = "https://api.fable.co",
    private val identity: String = "https://www.googleapis.com",
    private val secureToken: String = "https://securetoken.googleapis.com",
    private val key: String = FABLE_FIREBASE_KEY
) {
    fun signIn(email: String, password: String): JSONObject = request(
        "POST",
        "$identity/identitytoolkit/v3/relyingparty/verifyPassword?key=$key",
        "application/json",
        JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString(),
        null
    )

    fun refresh(token: String): JSONObject = request(
        "POST",
        "$secureToken/v1/token?key=$key",
        "application/x-www-form-urlencoded",
        "grant_type=refresh_token&refresh_token=${URLEncoder.encode(token, "UTF-8")}",
        null
    )

    fun get(token: String, path: String): JSONObject = request("GET", api + path, null, null, token)

    fun post(token: String, path: String, body: JSONObject): JSONObject = request("POST", api + path, "application/json", body.toString(), token)

    private fun request(method: String, url: String, type: String?, body: String?, token: String?): JSONObject {
        val response = httpRequest(method, url, type, body, token?.let { "JWT $it" })
        if (response.status !in 200..299) throw HttpProblem(response.status, firebaseError(response.text), "fable")
        if (response.text.isBlank()) return JSONObject()
        return runCatching { JSONObject(response.text) }.getOrNull() ?: throw SyncProblem("fable_invalid_json")
    }
}

internal class HttpResponse(val status: Int, val text: String)

/** One request without redirects, reading at most 2 MiB of the response. */
internal fun httpRequest(method: String, url: String, type: String?, body: String?, authorization: String?, connectTimeout: Int = 15_000, readTimeout: Int = 15_000, headers: Map<String, String> = emptyMap()): HttpResponse {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = connectTimeout
        connection.readTimeout = readTimeout
        connection.setRequestProperty("Accept", "application/json")
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { readLimited(it, 2 * 1024 * 1024).toString(Charsets.UTF_8) }.orEmpty()
        return HttpResponse(status, text)
    } finally {
        connection.disconnect()
    }
}

/** Firebase messages look like "TOO_MANY_ATTEMPTS_TRY_LATER : detail"; only the allowlisted code is kept. */
internal fun firebaseError(text: String): String? = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
    ?.substringBefore(' ')?.substringBefore(':')?.lowercase()?.takeIf { it in firebaseErrors }

internal fun fableAccountId(profile: JSONObject): String = profile.optString("id").takeIf { it.isNotBlank() } ?: throw SyncProblem("fable_identity_unavailable")

fun firebaseTokens(value: JSONObject, now: Long = System.currentTimeMillis()): OAuthTokens {
    val access = value.optString("idToken").ifBlank { value.optString("id_token") }
    val refresh = value.optString("refreshToken").ifBlank { value.optString("refresh_token") }
    val lifetime = (value.opt("expiresIn") ?: value.opt("expires_in"))?.toString()?.toLongOrNull() ?: 0
    if (access.isBlank() || refresh.isBlank() || lifetime !in 1..86_400) throw SyncProblem("fable_invalid_token_response")
    return OAuthTokens(access, refresh, now + lifetime * 1000)
}

/** Fable sign-in through Firebase. Only the ID and refresh tokens are stored; the password is never kept. */
class FableAuth(val http: FableHttp, private val vault: TokenVault) {
    private val mutex = Mutex()

    private fun credentials(): OAuthTokens? = try {
        vault.read()
    } catch (_: Exception) {
        throw SyncProblem("fable_credentials_unreadable_reconnect")
    }

    fun connected(): Boolean = credentials() != null

    suspend fun signIn(email: String, password: String) = mutex.withLock {
        val tokens = firebaseTokens(http.signIn(email, password))
        coroutineContext.ensureActive()
        vault.write(tokens)
    }

    suspend fun <T> authorized(block: suspend (String) -> T): T = mutex.withLock {
        coroutineContext.ensureActive()
        var tokens = credentials() ?: throw SyncProblem("fable_not_connected")
        if (tokens.expiresAt <= System.currentTimeMillis() + 60_000) tokens = refresh(tokens)
        try {
            block(tokens.access)
        } catch (error: HttpProblem) {
            if (error.status != 401 && error.status != 403) throw error
            block(refresh(tokens).access)
        }
    }

    private fun refresh(tokens: OAuthTokens): OAuthTokens = try {
        firebaseTokens(http.refresh(tokens.refresh)).also(vault::write)
    } catch (error: HttpProblem) {
        if (error.oauthError in deadSession) vault.clear()
        throw error
    }

    /** Firebase has no client-side refresh-token revocation, so disconnecting removes the local session only. */
    suspend fun disconnect() = mutex.withLock {
        vault.clear()
        Unit
    }
}
