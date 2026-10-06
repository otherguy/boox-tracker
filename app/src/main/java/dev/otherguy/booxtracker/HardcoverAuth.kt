package dev.otherguy.booxtracker

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

const val HARDCOVER_CLIENT_ID = "bc5f2c0f-79d7-42b5-b525-6293454d3934"
const val HARDCOVER_SCOPES = "read:catalog read:library write:library read:me:content"

class HttpProblem(val status: Int, val oauthError: String? = null) : Exception("http_$status")

class HardcoverHttp(private val origin: String = "https://api.hardcover.app") {
    fun form(path: String, fields: Map<String, String>): JSONObject = request(
        path,
        "application/x-www-form-urlencoded",
        fields.entries.joinToString("&") { (key, value) -> "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" },
        null
    )

    fun graphql(token: String, query: String, variables: JSONObject = JSONObject()): JSONObject {
        val response = request("/v1/graphql", "application/json", JSONObject().put("query", query).put("variables", variables).toString(), token)
        if (response.optJSONArray("errors")?.length()?.let { it > 0 } == true) throw SyncProblem("hardcover_graphql_error")
        return response.optJSONObject("data") ?: throw SyncProblem("hardcover_response_missing_data")
    }

    private fun request(path: String, type: String, body: String, token: String?): JSONObject {
        val connection = URL(origin + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { readLimited(it, 2 * 1024 * 1024).toString(Charsets.UTF_8) } ?: "{}"
            val response = runCatching { JSONObject(text) }.getOrNull()
            if (status !in 200..299) {
                val code = response?.optString("error")?.takeIf { it in setOf("authorization_pending", "slow_down", "access_denied", "expired_token", "invalid_grant", "invalid_client", "invalid_scope") }
                throw HttpProblem(status, code)
            }
            return response ?: throw SyncProblem("hardcover_invalid_json")
        } finally {
            connection.disconnect()
        }
    }
}

data class OAuthTokens(val access: String, val refresh: String, val expiresAt: Long) {
    fun json(): JSONObject = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt)

    companion object {
        fun response(value: JSONObject, now: Long = System.currentTimeMillis()): OAuthTokens {
            val access = value.optString("access_token")
            val refresh = value.optString("refresh_token")
            val lifetime = value.optLong("expires_in")
            if (access.isBlank() || refresh.isBlank() || lifetime !in 1..31_536_000 || !value.optString("token_type").equals("Bearer", true)) throw SyncProblem("hardcover_invalid_token_response")
            return OAuthTokens(access, refresh, now + lifetime * 1000)
        }
    }
}

class TokenVault(context: Context, private val key: () -> SecretKey = ::credentialKey) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "hardcover.credentials"))

    @Synchronized fun read(): OAuthTokens? {
        if (!file.baseFile.exists()) return null
        val envelope = file.openRead().use { JSONObject(readLimited(it, 64 * 1024).toString(Charsets.UTF_8)) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        val plain = JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8))
        return OAuthTokens(plain.getString("access"), plain.getString("refresh"), plain.getLong("expiresAt"))
    }

    @Synchronized fun write(tokens: OAuthTokens) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val envelope = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(tokens.json().toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        val output = file.startWrite()
        try {
            output.write(envelope.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    @Synchronized fun clear() = file.delete()
}

private fun credentialKey(): SecretKey {
    val alias = "reading-sync-hardcover"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey)?.let { return it }
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()
        )
    }.generateKey()
}

data class DeviceAuthorization(val code: String, val userCode: String, val verification: String, val expiresIn: Int, val interval: Int)

class HardcoverAuth(val http: HardcoverHttp, private val vault: TokenVault) {
    private val mutex = Mutex()
    private fun credentials(): OAuthTokens? = try {
        vault.read()
    } catch (_: Exception) {
        throw SyncProblem("hardcover_credentials_unreadable_reconnect")
    }

    fun connected(): Boolean = credentials() != null

    fun begin(): DeviceAuthorization {
        val value = http.form("/oauth2/device", mapOf("client_id" to HARDCOVER_CLIENT_ID, "scope" to HARDCOVER_SCOPES))
        val verification = value.optString("verification_uri")
        if (verification != "https://hardcover.app/link" || value.optString("device_code").isBlank() || value.optString("user_code").isBlank() || value.optInt("expires_in") !in 1..3600) throw SyncProblem("hardcover_invalid_device_response")
        return DeviceAuthorization(value.getString("device_code"), value.getString("user_code"), verification, value.getInt("expires_in"), value.optInt("interval", 5).coerceAtLeast(5))
    }

    suspend fun poll(device: DeviceAuthorization): Boolean = mutex.withLock {
        try {
            val value = http.form("/oauth2/token", mapOf("client_id" to HARDCOVER_CLIENT_ID, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to device.code))
            coroutineContext.ensureActive()
            vault.write(OAuthTokens.response(value))
            true
        } catch (error: HttpProblem) {
            if (error.oauthError == "authorization_pending") false else throw error
        }
    }

    suspend fun <T> authorized(block: suspend (String) -> T): T = mutex.withLock {
        coroutineContext.ensureActive()
        var tokens = credentials() ?: throw SyncProblem("hardcover_not_connected")
        if (tokens.expiresAt <= System.currentTimeMillis() + 60_000) tokens = refresh(tokens)
        try {
            block(tokens.access)
        } catch (error: HttpProblem) {
            if (error.status != 401) throw error
            block(refresh(tokens).access)
        }
    }

    private fun refresh(tokens: OAuthTokens): OAuthTokens = try {
        val next = OAuthTokens.response(http.form("/oauth2/token", mapOf("client_id" to HARDCOVER_CLIENT_ID, "grant_type" to "refresh_token", "refresh_token" to tokens.refresh)))
        vault.write(next)
        next
    } catch (error: HttpProblem) {
        if (error.oauthError == "invalid_grant") vault.clear()
        throw error
    }

    suspend fun disconnect() = mutex.withLock {
        val tokens = runCatching { vault.read() }.getOrNull()
        vault.clear()
        if (tokens != null) runCatching { http.form("/oauth2/revoke", mapOf("client_id" to HARDCOVER_CLIENT_ID, "token" to tokens.refresh, "token_type_hint" to "refresh_token")) }
        Unit
    }
}

suspend fun awaitDeviceAuthorization(
    auth: HardcoverAuth,
    device: DeviceAuthorization,
    now: () -> Long = SystemClock::elapsedRealtime,
    pause: suspend (Long) -> Unit = { delay(it) }
) {
    val deadline = now() + device.expiresIn * 1000L
    var interval = device.interval * 1000L
    while (true) {
        pause(minOf(interval, (deadline - now()).coerceAtLeast(0)))
        coroutineContext.ensureActive()
        if (now() >= deadline) throw SyncProblem("hardcover_sign_in_expired")
        try {
            if (auth.poll(device)) return
        } catch (error: HttpProblem) {
            if (error.oauthError != "slow_down") throw error
            interval += 5000
        }
    }
}
