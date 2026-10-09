package dev.otherguy.booxtracker

import org.json.JSONObject

class PageboundConnection(
    app: ReadingSyncApp,
    private val auth: PageboundAuth = PageboundAuth(PageboundHttp(), TokenVault(app, "pagebound")),
    online: () -> Boolean = { networkAvailable(app) }
) : PasswordConnection(app, "pagebound", online) {
    private val sync = PageboundSync(auth, store) { enabled() }

    override fun connected() = auth.connected()

    override suspend fun account(): String = auth.authorized { token -> pageboundAccountId(auth.http.authedUser(token)) }

    override suspend fun sendBook(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String) = sync.send(book, identifiers, account, readAt)

    // The response has no sign-up date: `preferences.created_at` is when Pagebound added preferences, not the account.
    override suspend fun fetchProfile(): JSONObject = auth.authorized { token ->
        val user = auth.http.authedUser(token).optJSONObject("user") ?: JSONObject()
        JSONObject().putOpt("username", user.text("username")).putOpt("email", user.text("email"))
            .putOpt("membership", user.optBoolean("paid_subscriber").takeIf { user.has("paid_subscriber") }?.let { if (it) "Paid subscriber" else "Free" })
    }

    override suspend fun authenticate(email: String, password: String) = auth.signIn(email, password)

    override suspend fun clearSession() = auth.disconnect()
}
