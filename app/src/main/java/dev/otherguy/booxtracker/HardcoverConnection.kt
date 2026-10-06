package dev.otherguy.booxtracker

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

fun networkAvailable(app: android.content.Context): Boolean {
    val manager = app.getSystemService(ConnectivityManager::class.java)
    return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

fun failureReason(error: Exception): String = when (error) {
    is SyncProblem -> error.code
    is HttpProblem -> "hardcover_http_${error.status}${error.oauthError?.let { "_$it" } ?: ""}"
    is SecurityException -> "ebook_permission_denied"
    is java.net.UnknownHostException -> "network_dns_unavailable"
    is java.net.SocketTimeoutException -> "network_timeout"
    is java.io.IOException -> "network_unavailable"
    else -> "operation_failed_${error.javaClass.simpleName}"
}

fun temporaryFailure(error: Exception) = error is java.io.IOException || (error is HttpProblem && (error.status == 429 || error.status in 500..599))

class HardcoverConnection(private val app: ReadingSyncApp, private val auth: HardcoverAuth = HardcoverAuth(HardcoverHttp(), TokenVault(app)), private val online: () -> Boolean = { networkAvailable(app) }) {
    private val diagnostics = app.diagnostics
    private val store = diagnostics.store
    private val identifiers = BookIdentifierRepository(app, store)
    private val sync = HardcoverSync(auth, store) { enabled() }
    private val mutex = Mutex()
    private val settingsMutex = Mutex()
    private var signIn: Job? = null

    @Volatile var device: DeviceAuthorization? = null
        private set

    @Volatile var signingIn = false
        private set

    private fun enabled() = store.get("hardcover.enabled") == "true"
    private fun bookStateKey(account: String, key: String) = "hardcover.book.$account.${digest(key)}"
    fun state(): JSONObject {
        val connection = runCatching { auth.connected() }
        val current = diagnostics.store.get("lastCheck")?.let(::JSONObject)?.optJSONObject("selected")?.optString("key")
        val account = store.get("hardcover.account")
        val book = current?.let { store.get(bookStateKey(account.orEmpty(), it)) }?.let(::JSONObject)
        return JSONObject().put("connected", connection.getOrDefault(false)).put("credentialProblem", connection.isFailure)
            .put("enabled", enabled()).put("folder", !store.get("ebook.tree").isNullOrBlank())
            .put("status", store.get("hardcover.status") ?: "Not connected").put("connectionError", store.get("hardcover.connectionError").orEmpty()).put("last", book ?: JSONObject.NULL)
            .put("pending", if (account == null) 0 else store.pending(account).size)
    }

    private suspend fun account(): String = auth.authorized { token ->
        val me = auth.http.graphql(token, "query Identity { me { id } }").records("me").singleOrNull() ?: throw SyncProblem("hardcover_identity_unavailable")
        me.getInt("id").toString()
    }

    private fun connect() {
        if (signingIn) return
        signingIn = true
        diagnostics.updates.value++
        signIn = diagnostics.scope.launch {
            try {
                store.put("hardcover.enabled", "false")
                store.put("hardcover.connectionError", "")
                store.put("hardcover.account", "")
                val authorization = auth.begin()
                coroutineContext.ensureActive()
                device = authorization
                diagnostics.updates.value++
                awaitDeviceAuthorization(auth, authorization)
                val accountId = account()
                coroutineContext.ensureActive()
                store.put("hardcover.account", accountId)
                store.put("hardcover.enabled", "true")
                store.put("hardcover.status", "Connected")
                diagnostics.event("manual", "hardcover_connection", detail = JSONObject().put("outcome", "connected"))
                scheduleDelivery(app)
                try {
                    app.sync("foreground", "sign_in")
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordFailure("foreground", "hardcover_operation", error)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                store.put("hardcover.enabled", "false")
                store.put("hardcover.connectionError", failureReason(error))
                recordFailure("manual", "hardcover_connection", error)
            } finally {
                device = null
                signingIn = false
                diagnostics.updates.value++
            }
        }
    }

    suspend fun disconnect() {
        store.put("hardcover.enabled", "false")
        settingsMutex.withLock {
            cancelSignIn()
            store.put("hardcover.enabled", "false")
            auth.disconnect()
            store.put("hardcover.account", "")
            store.put("hardcover.status", "Disconnected")
            diagnostics.event("manual", "hardcover_connection", detail = JSONObject().put("outcome", "disconnected"))
        }
    }

    private suspend fun cancelSignIn() {
        val job = signIn
        signingIn = false
        device = null
        job?.cancel()
        job?.join()
        signIn = null
    }

    suspend fun setEnabled(value: Boolean) {
        if (!value) store.put("hardcover.enabled", "false")
        settingsMutex.withLock {
            if (!value && signIn?.isActive == true) {
                cancelSignIn()
                auth.disconnect()
            }
            if (value && !runCatching { auth.connected() }.getOrDefault(false)) {
                connect()
                return@withLock
            }
            if (value && store.get("hardcover.account").isNullOrBlank()) {
                try {
                    store.put("hardcover.account", account())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    recordFailure("manual", "hardcover_connection", error)
                    return@withLock
                }
            }
            store.put("hardcover.enabled", value.toString())
            store.put("hardcover.connectionError", "")
            if (value && store.pending(store.get("hardcover.account").orEmpty()).isNotEmpty()) scheduleDelivery(app)
            diagnostics.event("manual", "hardcover_setting", detail = JSONObject().put("enabled", value))
        }
    }

    suspend fun send(source: String, check: JSONObject?, runId: String? = null) {
        if (!enabled()) return
        val candidate = check?.takeIf { it.optString("outcome") == "success" }?.optJSONObject("selected")
        if (candidate == null || candidate.isNull("key")) {
            recordFailure(source, "hardcover_sync", SyncProblem("fresh_book_detection_required"), runId)
            return
        }
        val accountId = store.get("hardcover.account")?.takeIf { it.isNotBlank() } ?: run {
            if (!online()) return
            val id = account()
            store.put("hardcover.account", id)
            id
        }
        val metadata = identifiers.read(candidate)
        val key = candidate.getString("key")
        val stateKey = bookStateKey(accountId, key)
        settingsMutex.withLock {
            if (!enabled() || store.get("hardcover.account") != accountId) return
            val previous = store.get(stateKey)?.let(::JSONObject)
            if (previous?.optString("sourceState") != sourceState(candidate, metadata) || store.pending(accountId).any { it.getJSONObject("book").getString("key") == key }) {
                val item = store.enqueue(accountId, candidate, metadata, check.optString("timestamp"))
                store.put(stateKey, (previous ?: JSONObject()).put("delivery", "pending").toString())
                diagnostics.event(source, "queued", runId, JSONObject().put("key", key).put("revision", item.getString("revision")).put("outcome", "pending"))
            }
            if (store.pending(accountId).isNotEmpty()) scheduleDelivery(app)
        }
        drain(source)
    }

    suspend fun drain(source: String): Boolean = mutex.withLock {
        if (!enabled()) return@withLock false
        if (!online()) return@withLock true
        requireEbookFolder(app, store)
        val accountId = store.get("hardcover.account")?.takeIf { it.isNotBlank() } ?: return@withLock false
        var retry = false
        val attempted = mutableSetOf<String>()
        for (item in store.pending(accountId)) {
            if (!enabled()) break
            attempted.add(item.getString("revision"))
            val book = item.getJSONObject("book")
            val key = bookStateKey(accountId, book.getString("key"))
            val old = store.get(key)?.let(::JSONObject) ?: JSONObject()
            val id = UUID.randomUUID().toString()
            val started = SystemClock.elapsedRealtime()
            store.put("hardcover.active", JSONObject().put("source", source).put("runId", id).toString())
            diagnostics.event(source, "hardcover_sync_start", id, JSONObject().put("key", book.getString("key")))
            try {
                val result = sync.send(book, BookIdentifiers.fromJson(item.getJSONObject("identifiers")), accountId.toInt())
                    .put("durationMs", SystemClock.elapsedRealtime() - started).put("readAt", item.getString("readAt"))
                    .put("timestamp", Instant.now().toString()).put("sourceState", item.getString("sourceState"))
                val acknowledged = store.acknowledge(item)
                retry = retry || !acknowledged
                result.put("delivery", if (acknowledged) "synced" else "pending").put("lastSuccess", JSONObject(result.toString()))
                store.put(key, result.toString())
                diagnostics.event(source, "hardcover_sync", id, result)
            } catch (error: CancellationException) {
                diagnostics.event(source, "hardcover_sync", id, JSONObject().put("outcome", "interrupted"), true)
                throw error
            } catch (error: Exception) {
                val transient = temporaryFailure(error)
                retry = retry || transient
                val detail = old.put("delivery", if (transient || !enabled()) "pending" else "error").put("reason", failureReason(error))
                store.put(key, detail.toString())
                diagnostics.event(source, "hardcover_sync", id, JSONObject().put("outcome", if (transient) "pending" else "held").put("reason", failureReason(error)).put("errorClass", error.javaClass.name), !transient && enabled())
            } finally {
                store.put("hardcover.active", "")
                diagnostics.updates.value++
            }
        }
        retry || store.pending(accountId).any { it.getString("revision") !in attempted }
    }

    fun recordFailure(source: String, kind: String, error: Exception, runId: String? = null, duration: Long? = null) {
        val reason = failureReason(error)
        val detail = JSONObject().put("outcome", "held").put("reason", reason).put("errorClass", error.javaClass.name)
        duration?.let { detail.put("durationMs", it) }
        store.put("hardcover.status", reason.replace('_', ' '))
        diagnostics.event(source, kind, runId, detail, true)
    }
}
