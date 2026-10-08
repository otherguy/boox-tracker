package dev.otherguy.booxtracker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** A hidden browser renews the session when its last renewal is older than this, before a send and on a schedule. */
const val GOODREADS_RENEWAL_MS = 6 * 3_600_000L

class GoodreadsConnection(
    app: ReadingSyncApp,
    profile: WebProfile = NamedWebProfile("goodreads"),
    val refresher: SessionRefresher = HiddenWebViewRefresher(app, profile),
    val http: GoodreadsHttp = GoodreadsHttp(GoodreadsSession(app.diagnostics.store, profile), refresher),
    online: () -> Boolean = { networkAvailable(app) }
) : TrackerConnection(app, "goodreads", online) {
    val session get() = http.session
    private val sync = GoodreadsSync(http, store) { enabled() }
    private var capture: Job? = null

    /** The switch is On and the sign-in popup with Goodreads' page stays open until a session is captured or the user cancels. */
    @Volatile var awaitingCredentials = false
        private set

    @Volatile var signingIn = false
        private set

    override fun connected() = session.connected()

    /** The renewal job loads Goodreads only for a connected session whose switch is On; Off pauses all Goodreads traffic. */
    fun renewable() = session.connected() && enabled()

    override suspend fun account(): String = http.account()

    override suspend fun deliver(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String): JSONObject {
        requireSession()
        if (System.currentTimeMillis() - (session.renewedAt() ?: 0) !in 0 until GOODREADS_RENEWAL_MS) renew("delivery")
        requireSession()
        return sync.send(book, identifiers, account, readAt)
    }

    /** Without a session the item waits in the queue with the session problem as its reason; the reconnect's first sync sends it. */
    private fun requireSession() {
        if (!session.connected()) throw SyncProblem(store.get("goodreads.connectionError")?.takeIf { it.isNotBlank() } ?: "goodreads_not_connected")
    }

    /**
     * Loads Goodreads in the hidden browser so its bot check and session cookies stay current. A signed-out page ends
     * the session. Returns the outcome; the caller records it.
     */
    suspend fun renew(source: String): RefreshOutcome {
        val outcome = refresher.refresh("${http.origin}/")
        when (outcome) {
            RefreshOutcome.SIGNED_IN -> session.renewed()
            RefreshOutcome.SIGNED_OUT -> session.mark("goodreads_session_expired")
            RefreshOutcome.TIMEOUT -> {}
        }
        if (outcome != RefreshOutcome.SIGNED_IN) diagnostics.event(source, "goodreads_renewal", detail = JSONObject().put("outcome", outcome.name.lowercase()), issue = true)
        return outcome
    }

    override suspend fun fetchProfile(): JSONObject {
        // An ended session would only meet the sign-in page or another bot check.
        if (!session.connected()) throw SyncProblem("goodreads_not_connected")
        val account = store.get("goodreads.account")?.takeIf { it.isNotBlank() } ?: http.account()
        return goodreadsProfile(http.get("/user/show/$account").text)
    }

    override suspend fun signOut() {
        awaitingCredentials = false
        cancelCapture()
        cancelGoodreadsRenewal(app)
        // Goodreads' sign-out link posts with the page's CSRF token; a failure still clears the device.
        if (session.connected()) {
            runCatching {
                val home = http.signedInPage("/").text
                http.post("/user/sign_out", mapOf("_method" to "post"), goodreadsCsrf(home), "/")
            }
        }
        session.clear()
    }

    /**
     * Captures the session the sign-in WebView established. Goodreads' signed-out home page is a landing page, so the
     * capture first requires the signed-in header; without it the popup keeps waiting and nothing is stored.
     */
    suspend fun sessionCaptured(userAgent: String) = settingsMutex.withLock {
        if (signingIn || !awaitingCredentials) return@withLock
        signingIn = true
        diagnostics.updates.value++
        capture = diagnostics.scope.launch {
            try {
                val home = http.signedInHome(userAgent) ?: return@launch
                val accountId = goodreadsUserId(home) ?: throw SyncProblem("goodreads_identity_unavailable")
                coroutineContext.ensureActive()
                store.put("goodreads.enabled", "false")
                store.put("goodreads.connectionError", "")
                session.capture(userAgent)
                store.put("goodreads.account", accountId)
                store.put("goodreads.enabled", "true")
                recordSignIn()
                awaitingCredentials = false
                diagnostics.event("manual", "goodreads_connection", detail = JSONObject().put("outcome", "connected"))
                scheduleDelivery(app)
                scheduleGoodreadsRenewal(app)
                syncAfterSignIn()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // awaitingCredentials stays true so the popup shows the reason and the user can sign in again or cancel.
                store.put("goodreads.enabled", "false")
                store.put("goodreads.connectionError", failureReason(error))
                recordFailure("manual", "goodreads_connection", error)
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
        if (!value) store.put("goodreads.enabled", "false")
        settingsMutex.withLock {
            if (!value) {
                val cancelling = awaitingCredentials
                awaitingCredentials = false
                // Cancel discards a session the page may already have established.
                when {
                    capture?.isActive == true -> signOut()
                    cancelling -> session.clear()
                }
            }
            if (value && !connected()) {
                // Without a separate profile, signing out of Goodreads would also remove StoryGraph's session.
                if (!session.profile.supported()) {
                    store.put("goodreads.connectionError", "goodreads_webview_profiles_unsupported")
                    diagnostics.updates.value++
                    return@withLock
                }
                awaitingCredentials = true
                store.put("goodreads.connectionError", "")
                diagnostics.updates.value++
                return@withLock
            }
            storeEnabled(value)
        }
    }
}
