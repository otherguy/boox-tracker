package dev.otherguy.booxtracker

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
    is HttpProblem -> "${error.service}_http_${error.status}${error.oauthError?.let { "_$it" } ?: ""}"
    is SecurityException -> "ebook_permission_denied"
    is java.net.UnknownHostException -> "network_dns_unavailable"
    is java.net.SocketTimeoutException -> "network_timeout"
    is java.io.IOException -> "network_unavailable"
    else -> "operation_failed_${error.javaClass.simpleName}"
}

fun temporaryFailure(error: Exception) = error is java.io.IOException || (error is HttpProblem && (error.status == 429 || error.status in 500..599))

/** Durable per-account queue, delivery loop, and stored state shared by every tracker service. */
abstract class TrackerConnection(protected val app: ReadingSyncApp, val service: String, private val online: () -> Boolean) {
    protected val diagnostics = app.diagnostics
    protected val store = diagnostics.store
    private val identifiers = BookIdentifierRepository(app, store)
    private val mutex = Mutex()
    protected val settingsMutex = Mutex()

    protected abstract fun connected(): Boolean

    protected abstract suspend fun account(): String

    protected abstract suspend fun deliver(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String): JSONObject

    /** Account details for the details popup: `username`, `name`, `email`, `createdAt`, `membership`, each optional. */
    protected abstract suspend fun fetchProfile(): JSONObject

    /** Removes the stored session and cancels a running sign-in. */
    protected abstract suspend fun signOut()

    protected fun enabled() = store.get("$service.enabled") == "true"

    private fun bookStateKey(account: String, key: String) = "$service.book.$account.${digest(key)}"

    fun state(): JSONObject {
        val connection = runCatching { connected() }
        val current = store.get("lastCheck")?.let(::JSONObject)?.optJSONObject("selected")?.optString("key")
        val account = store.get("$service.account")
        val book = current?.let { store.get(bookStateKey(account.orEmpty(), it)) }?.let(::JSONObject)
        return JSONObject().put("connected", connection.getOrDefault(false)).put("credentialProblem", connection.isFailure)
            .put("profile", store.get("$service.profile")?.let(::JSONObject) ?: JSONObject.NULL).put("connectedAt", store.get("$service.connectedAt")?.toLongOrNull() ?: JSONObject.NULL)
            .put("enabled", enabled()).put("folder", !store.get("ebook.tree").isNullOrBlank())
            .put("connectionError", store.get("$service.connectionError").orEmpty()).put("last", book ?: JSONObject.NULL)
            .put("pending", if (account == null) 0 else store.pending(account, service).size)
    }

    suspend fun send(source: String, check: JSONObject?, runId: String? = null) {
        if (!enabled()) return
        val candidate = check?.takeIf { it.optString("outcome") == "success" }?.optJSONObject("selected")
        if (candidate == null || candidate.isNull("key")) {
            recordFailure(source, "${service}_sync", SyncProblem("fresh_book_detection_required"), runId)
            return
        }
        val accountId = store.get("$service.account")?.takeIf { it.isNotBlank() } ?: run {
            if (!online()) return
            val id = account()
            store.put("$service.account", id)
            id
        }
        val metadata = identifiers.read(candidate)
        val key = candidate.getString("key")
        val stateKey = bookStateKey(accountId, key)
        settingsMutex.withLock {
            if (!enabled() || store.get("$service.account") != accountId) return
            val previous = store.get(stateKey)?.let(::JSONObject)
            val waiting = store.pending(accountId, service).firstOrNull { it.getJSONObject("book").getString("key") == key }
            if (previous?.optString("sourceState") != sourceState(candidate, metadata) || waiting != null) {
                val item = store.enqueue(accountId, candidate, metadata, check.optString("timestamp"), service)
                store.put(stateKey, (previous ?: JSONObject()).put("delivery", "pending").toString())
                // A book already waiting with the same state keeps its revision; only a new revision is news.
                if (item.getString("revision") != waiting?.optString("revision")) {
                    diagnostics.event(
                        source,
                        "queued",
                        runId,
                        JSONObject().put("service", service).put("key", key).put("title", candidate.raw("title") ?: JSONObject.NULL)
                            .put("percentage", candidate.opt("percentage") ?: JSONObject.NULL).put("revision", item.getString("revision")).put("outcome", "pending")
                    )
                }
            }
            if (store.pending(accountId, service).isNotEmpty()) scheduleDelivery(app)
        }
        drain(source)
    }

    suspend fun drain(source: String): Boolean = mutex.withLock {
        if (!enabled()) return@withLock false
        if (!online()) return@withLock true
        requireEbookFolder(app, store)
        val accountId = store.get("$service.account")?.takeIf { it.isNotBlank() } ?: return@withLock false
        var retry = false
        var synced = false
        val attempted = mutableSetOf<String>()
        for (item in store.pending(accountId, service)) {
            if (!enabled()) break
            attempted.add(item.getString("revision"))
            val book = item.getJSONObject("book")
            val key = bookStateKey(accountId, book.getString("key"))
            val old = store.get(key)?.let(::JSONObject) ?: JSONObject()
            val id = UUID.randomUUID().toString()
            val started = SystemClock.elapsedRealtime()
            store.put("$service.active", JSONObject().put("source", source).put("runId", id).put("key", book.getString("key")).put("startedAt", Instant.now().toString()).toString())
            try {
                val result = deliver(book, BookIdentifiers.fromJson(item.getJSONObject("identifiers")), accountId, item.getString("readAt"))
                    .put("durationMs", SystemClock.elapsedRealtime() - started).put("readAt", item.getString("readAt"))
                    .put("timestamp", Instant.now().toString()).put("sourceState", item.getString("sourceState"))
                val acknowledged = store.acknowledge(item)
                retry = retry || !acknowledged
                synced = synced || acknowledged
                result.put("delivery", if (acknowledged) "synced" else "pending").put("lastSuccess", JSONObject(result.toString()))
                store.put(key, result.toString())
                diagnostics.event(source, "${service}_sync", id, result)
            } catch (error: CancellationException) {
                diagnostics.event(source, "${service}_sync", id, JSONObject().put("outcome", "interrupted"), true)
                throw error
            } catch (error: Exception) {
                val transient = temporaryFailure(error)
                retry = retry || transient
                val detail = old.put("delivery", if (transient || !enabled()) "pending" else "error").put("reason", failureReason(error))
                store.put(key, detail.toString())
                diagnostics.event(source, "${service}_sync", id, JSONObject().put("outcome", if (transient) "pending" else "held").put("reason", failureReason(error)).put("errorClass", error.javaClass.name), !transient && enabled())
            } finally {
                store.put("$service.active", "")
                diagnostics.updates.value++
            }
        }
        if (synced) {
            store.put("lastSyncMs", System.currentTimeMillis().toString())
            diagnostics.prune()
        }
        // Fetches account details when none are stored, for example for an older sign-in or after a failed fetch.
        if (enabled() && store.get("$service.profile") == null) refreshProfile()
        retry || store.pending(accountId, service).any { it.getString("revision") !in attempted }
    }

    /**
     * Stores the provider's account details on this device for the details popup. They are never written to the
     * Activity log or exports. A failure keeps the previous details and never blocks syncing.
     */
    protected suspend fun refreshProfile() {
        try {
            store.put("$service.profile", fetchProfile().toString())
            if (store.get("$service.connectedAt") == null) store.put("$service.connectedAt", System.currentTimeMillis().toString())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    /** Records a completed sign-in. Clears any earlier account's details; [syncAfterSignIn] fetches the new ones. */
    protected fun recordSignIn() {
        store.put("$service.connectedAt", System.currentTimeMillis().toString())
        store.delete("$service.profile")
    }

    /**
     * Logs out: removes the session, turns the service Off, and deletes its queued updates, book results, match cache,
     * and account details. Waits for a delivery in progress, which stops before its next write once the service is Off.
     */
    suspend fun logOut() {
        store.put("$service.enabled", "false")
        mutex.withLock {
            settingsMutex.withLock {
                signOut()
                store.put("$service.enabled", "false")
                val deleted = store.transaction {
                    listOf("$service.book.", "$service.match.").forEach(store::deletePrefix)
                    listOf("$service.profile", "$service.connectedAt").forEach(store::delete)
                    store.put("$service.account", "")
                    store.put("$service.connectionError", "")
                    store.deletePrefix("outbox.$service.")
                }
                diagnostics.event("manual", "${service}_connection", detail = JSONObject().put("outcome", "logged_out").put("deletedUpdates", deleted))
            }
        }
    }

    /** Starts the first sync after sign-in as its own job, so switching Off afterwards pauses the service instead of cancelling sign-in. */
    protected fun syncAfterSignIn() {
        diagnostics.scope.launch {
            try {
                refreshProfile()
                app.sync("foreground", "sign_in")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordFailure("foreground", "${service}_operation", error)
            }
        }
    }

    /** Applies a switch change for a connected session. Call with [settingsMutex] held. */
    protected suspend fun storeEnabled(value: Boolean) {
        if (value && store.get("$service.account").isNullOrBlank()) {
            try {
                store.put("$service.account", account())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                recordFailure("manual", "${service}_connection", error)
                return
            }
        }
        store.put("$service.enabled", value.toString())
        store.put("$service.connectionError", "")
        if (value && store.pending(store.get("$service.account").orEmpty(), service).isNotEmpty()) scheduleDelivery(app)
        diagnostics.event("manual", "${service}_setting", detail = JSONObject().put("enabled", value))
    }

    fun recordFailure(source: String, kind: String, error: Exception, runId: String? = null) {
        val reason = failureReason(error)
        diagnostics.event(source, kind, runId, JSONObject().put("outcome", "held").put("reason", reason).put("errorClass", error.javaClass.name), true)
    }
}
