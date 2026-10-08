package dev.otherguy.booxtracker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class StoryGraphConnection(
    app: ReadingSyncApp,
    val http: StoryGraphHttp = StoryGraphHttp(StoryGraphSession(app.diagnostics.store)),
    online: () -> Boolean = { networkAvailable(app) }
) : TrackerConnection(app, "storygraph", online) {
    private val session get() = http.session
    private val sync = StoryGraphSync(http, store) { enabled() }
    private var capture: Job? = null

    /** The switch is On and the sign-in popup with StoryGraph's page stays open until a session is captured or the user cancels. */
    @Volatile var awaitingCredentials = false
        private set

    @Volatile var signingIn = false
        private set

    override fun connected() = session.connected()

    override suspend fun account(): String = http.account()

    override suspend fun deliver(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String): JSONObject {
        // Without a session the item waits in the queue; the reconnect's first sync sends it. The stored session problem
        // stays the held reason, so the popup names the cause once rather than once per delivery attempt.
        if (!session.connected()) throw SyncProblem(store.get("storygraph.connectionError")?.takeIf { it.isNotBlank() } ?: "storygraph_not_connected")
        return sync.send(book, identifiers, account)
    }

    override suspend fun fetchProfile(): JSONObject = JSONObject().putOpt("username", storyGraphUsername(http.get("/")))

    override suspend fun signOut() {
        awaitingCredentials = false
        cancelCapture()
        // Signing out on the server invalidates the long-lived remember-me cookie; a failure still clears the device.
        if (session.connected()) runCatching { http.post("/users/sign_out", mapOf("_method" to "delete"), csrfToken(http.get("/")), "/") }
        session.clear()
    }

    /** Captures the session the sign-in WebView established. The WebView's User-Agent is kept so later requests match it. */
    suspend fun sessionCaptured(userAgent: String) = settingsMutex.withLock {
        if (signingIn || !awaitingCredentials) return@withLock
        signingIn = true
        diagnostics.updates.value++
        capture = diagnostics.scope.launch {
            try {
                store.put("storygraph.enabled", "false")
                store.put("storygraph.connectionError", "")
                store.put("storygraph.account", "")
                session.capture(userAgent)
                val accountId = account()
                coroutineContext.ensureActive()
                store.put("storygraph.account", accountId)
                store.put("storygraph.enabled", "true")
                recordSignIn()
                awaitingCredentials = false
                diagnostics.event("manual", "storygraph_connection", detail = JSONObject().put("outcome", "connected"))
                scheduleDelivery(app)
                syncAfterSignIn()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // awaitingCredentials stays true so the popup shows the reason and the user can sign in again or cancel.
                store.put("storygraph.enabled", "false")
                store.put("storygraph.connectionError", failureReason(error))
                recordFailure("manual", "storygraph_connection", error)
            } finally {
                signingIn = false
                diagnostics.updates.value++
            }
        }
    }

    private suspend fun cancelCapture() {
        val job = capture
        signingIn = false
        job?.cancel()
        job?.join()
        capture = null
    }

    suspend fun setEnabled(value: Boolean) {
        if (!value) store.put("storygraph.enabled", "false")
        settingsMutex.withLock {
            if (!value) {
                val cancelling = awaitingCredentials
                awaitingCredentials = false
                // Cancel discards a session the page may already have established, including one whose capture failed.
                when {
                    capture?.isActive == true -> signOut()
                    cancelling -> session.clear()
                }
            }
            if (value && !connected()) {
                awaitingCredentials = true
                store.put("storygraph.connectionError", "")
                diagnostics.updates.value++
                return@withLock
            }
            storeEnabled(value)
        }
    }
}
