package dev.otherguy.booxtracker

import java.util.Base64
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Margins' Supabase project, from Margins' website. */
const val MARGINS_SUPABASE_URL = "https://dhepqjxbathvkcxyuorm.supabase.co"

/** Margins' public Supabase key, shipped in Margins' website. It identifies the project; it is not a credential. */
const val MARGINS_SUPABASE_KEY = "sb_publishable_f9S7rFZNdryEgO9lUr2O6g_ZwrRSas_"

/** Supabase Auth error codes worth naming; any other code is reported by its HTTP status only. */
private val supabaseErrors = setOf(
    "otp_expired", "otp_disabled", "signup_disabled", "user_not_found", "email_address_invalid", "email_address_not_authorized", "validation_failed",
    "over_email_send_rate_limit", "over_request_rate_limit", "refresh_token_not_found", "refresh_token_already_used", "session_not_found",
    "session_expired", "user_banned", "invalid_grant", "bad_jwt"
)

/** Refresh failures after which the stored session can never recover without a new sign-in. */
private val deadMarginsSession = setOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found", "session_expired", "user_not_found", "user_banned", "invalid_grant")

/** Marks a request that Margins refused because of the token, so the session is renewed once. */
internal const val MARGINS_REJECTED_TOKEN = "rejected_token"

/** Margins sign-in through Supabase Auth: a one-time code sent by email, then access and refresh tokens. */
class MarginsHttp(val origin: String = MARGINS_SUPABASE_URL, private val key: String = MARGINS_SUPABASE_KEY) {
    /** Emails a sign-in code. `create_user` is false, so a mistyped email never creates a Margins account. */
    fun requestCode(email: String) {
        auth("POST", "/auth/v1/otp", JSONObject().put("email", email).put("create_user", false).put("data", JSONObject()).put("gotrue_meta_security", JSONObject()), null)
    }

    fun verify(email: String, code: String): JSONObject = auth("POST", "/auth/v1/verify", JSONObject().put("type", "email").put("email", email).put("token", code), null)

    fun refresh(token: String): JSONObject = auth("POST", "/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", token), null)

    fun user(token: String): JSONObject = auth("GET", "/auth/v1/user", null, token)

    /** Ends only this device's session; the user's other Margins sessions stay signed in. */
    fun logout(token: String) {
        auth("POST", "/auth/v1/logout?scope=local", JSONObject(), token)
    }

    private fun auth(method: String, path: String, body: JSONObject?, token: String?): JSONObject {
        val response = httpRequest(method, origin + path, "application/json", body?.toString(), token?.let { "Bearer $it" }, headers = mapOf("apikey" to key))
        if (response.status !in 200..299) {
            val json = runCatching { JSONObject(response.text) }.getOrNull()
            val code = (json?.text("error_code") ?: json?.text("error"))?.lowercase()?.takeIf { it in supabaseErrors }
            val rejected = token != null && (response.status == 401 || response.status == 403)
            throw HttpProblem(response.status, if (rejected) MARGINS_REJECTED_TOKEN else code, "margins")
        }
        if (response.text.isBlank()) return JSONObject()
        return runCatching { JSONObject(response.text) }.getOrNull() ?: throw SyncProblem("margins_invalid_json")
    }
}

/** The tokens of a Supabase session response; `expires_at` is in seconds. */
internal fun marginsTokens(value: JSONObject, now: Long = System.currentTimeMillis()): OAuthTokens {
    val access = value.text("access_token")
    val refresh = value.text("refresh_token")
    val expiresAt = value.optLong("expires_at").takeIf { it > 0 }?.times(1000) ?: value.optLong("expires_in").takeIf { it > 0 }?.let { now + it * 1000 }
    if (access == null || refresh == null || expiresAt == null) throw SyncProblem("margins_invalid_token_response")
    return OAuthTokens(access, refresh, expiresAt)
}

/** The account id: the `sub` claim of a Supabase access token. The signature is Margins' to check. */
internal fun marginsAccountId(token: String): String = runCatching {
    JSONObject(String(Base64.getUrlDecoder().decode(token.split('.')[1].trimEnd('=')), Charsets.UTF_8)).text("sub")
}.getOrNull()?.takeIf { it.matches(uuidPattern) } ?: throw SyncProblem("margins_identity_unavailable")

/**
 * The Margins session in a Keystore-encrypted vault. Access tokens last a week; they are renewed a minute before they
 * expire, and once when Margins refuses one. The sign-in code is never kept.
 */
class MarginsAuth(val http: MarginsHttp, private val vault: TokenVault, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val mutex = Mutex()

    private fun credentials(): OAuthTokens? = try {
        vault.read()
    } catch (_: Exception) {
        throw SyncProblem("margins_credentials_unreadable_reconnect")
    }

    fun connected(): Boolean = credentials() != null

    fun requestCode(email: String) = http.requestCode(email)

    suspend fun verify(email: String, code: String) = mutex.withLock {
        val tokens = marginsTokens(http.verify(email, code), now())
        marginsAccountId(tokens.access)
        coroutineContext.ensureActive()
        vault.write(tokens)
    }

    suspend fun <T> authorized(block: suspend (String) -> T): T = mutex.withLock {
        coroutineContext.ensureActive()
        var tokens = credentials() ?: throw SyncProblem("margins_not_connected")
        if (tokens.expiresAt <= now() + 60_000) tokens = renew(tokens)
        try {
            block(tokens.access)
        } catch (error: HttpProblem) {
            if (error.oauthError != MARGINS_REJECTED_TOKEN) throw error
            val renewed = renew(tokens)
            try {
                block(renewed.access)
            } catch (again: HttpProblem) {
                if (again.oauthError != MARGINS_REJECTED_TOKEN) throw again
                // Margins refuses a token it issued moments ago, so the account's session is over.
                vault.clear()
                throw SyncProblem("margins_session_expired")
            }
        }
    }

    private fun renew(tokens: OAuthTokens): OAuthTokens = try {
        marginsTokens(http.refresh(tokens.refresh), now()).also(vault::write)
    } catch (error: HttpProblem) {
        if (error.oauthError !in deadMarginsSession) throw error
        vault.clear()
        throw SyncProblem("margins_session_expired")
    }

    /** Ends this device's session on Margins when it can, then removes it locally. */
    suspend fun disconnect() = mutex.withLock {
        runCatching { credentials()?.let { http.logout(it.access) } }
        vault.clear()
        Unit
    }
}
