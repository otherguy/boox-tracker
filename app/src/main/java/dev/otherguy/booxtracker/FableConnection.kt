package dev.otherguy.booxtracker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class FableConnection(
    app: ReadingSyncApp,
    private val auth: FableAuth = FableAuth(FableHttp(), TokenVault(app, "fable")),
    online: () -> Boolean = { networkAvailable(app) }
) : TrackerConnection(app, "fable", online) {
    private val sync = FableSync(auth, store) { enabled() }
    private var signIn: Job? = null

    /** The switch is On and the email/password popup stays open until sign-in succeeds or the user cancels. */
    @Volatile var awaitingCredentials = false
        private set

    @Volatile var signingIn = false
        private set

    override fun connected() = auth.connected()

    override suspend fun account(): String = auth.authorized { token ->
        fableAccountId(auth.http.get(token, "/api/settings/profile/"))
    }

    override suspend fun deliver(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String) = sync.send(book, identifiers, account, readAt)

    override suspend fun fetchProfile(): JSONObject = auth.authorized { token ->
        val profile = auth.http.get(token, "/api/settings/profile/")
        JSONObject().putOpt("username", profile.text("username")).putOpt("name", profile.text("display_name")).putOpt("email", profile.text("email"))
            .putOpt("createdAt", profile.text("signed_up_at")).putOpt("membership", profile.text("subscription_tier")?.replaceFirstChar { it.uppercase() })
    }

    override suspend fun signOut() {
        awaitingCredentials = false
        cancelSignIn()
        auth.disconnect()
    }

    /** Signs in with the typed credentials. The password is passed to Fable's sign-in request and is not stored. */
    suspend fun signIn(email: String, password: String) = settingsMutex.withLock {
        if (signingIn || !awaitingCredentials) return@withLock
        signingIn = true
        diagnostics.updates.value++
        signIn = diagnostics.scope.launch {
            try {
                store.put("fable.enabled", "false")
                store.put("fable.connectionError", "")
                store.put("fable.account", "")
                auth.signIn(email, password)
                val accountId = account()
                coroutineContext.ensureActive()
                store.put("fable.account", accountId)
                store.put("fable.enabled", "true")
                recordSignIn()
                awaitingCredentials = false
                diagnostics.event("manual", "fable_connection", detail = JSONObject().put("outcome", "connected"))
                scheduleDelivery(app)
                syncAfterSignIn()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // awaitingCredentials stays true so the user can correct the email or password and retry.
                store.put("fable.enabled", "false")
                store.put("fable.connectionError", failureReason(error))
                recordFailure("manual", "fable_connection", error)
            } finally {
                signingIn = false
                diagnostics.updates.value++
            }
        }
    }

    private suspend fun cancelSignIn() {
        val job = signIn
        signingIn = false
        job?.cancel()
        job?.join()
        signIn = null
    }

    suspend fun setEnabled(value: Boolean) {
        if (!value) store.put("fable.enabled", "false")
        settingsMutex.withLock {
            if (!value) {
                awaitingCredentials = false
                if (signIn?.isActive == true) signOut()
            }
            if (value && !runCatching { auth.connected() }.getOrDefault(false)) {
                awaitingCredentials = true
                store.put("fable.connectionError", "")
                diagnostics.updates.value++
                return@withLock
            }
            storeEnabled(value)
        }
    }
}
