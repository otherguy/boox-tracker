package dev.otherguy.booxtracker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class HardcoverConnection(
    app: ReadingSyncApp,
    private val auth: HardcoverAuth = HardcoverAuth(HardcoverHttp(), TokenVault(app)),
    online: () -> Boolean = { networkAvailable(app) }
) : TrackerConnection(app, "hardcover", online) {
    private val sync = HardcoverSync(auth, store) { enabled() }
    private var signIn: Job? = null

    @Volatile var device: DeviceAuthorization? = null
        private set

    @Volatile var signingIn = false
        private set

    override fun connected() = auth.connected()

    override suspend fun account(): String = auth.authorized { token ->
        val me = auth.http.graphql(token, "query Identity { me { id } }").records("me").singleOrNull() ?: throw SyncProblem("hardcover_identity_unavailable")
        me.getInt("id").toString()
    }

    override suspend fun deliver(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String) = sync.send(book, identifiers, account.toInt(), readAt)

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
                syncAfterSignIn()
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
            storeEnabled(value)
        }
    }
}
