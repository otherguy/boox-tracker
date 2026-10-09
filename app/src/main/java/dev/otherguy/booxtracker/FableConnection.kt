package dev.otherguy.booxtracker

import org.json.JSONObject

class FableConnection(
    app: ReadingSyncApp,
    private val auth: FableAuth = FableAuth(FableHttp(), TokenVault(app, "fable")),
    online: () -> Boolean = { networkAvailable(app) }
) : PasswordConnection(app, "fable", online) {
    private val sync = FableSync(auth, store) { enabled() }

    override fun connected() = auth.connected()

    override suspend fun account(): String = auth.authorized { token ->
        fableAccountId(auth.http.get(token, "/api/settings/profile/"))
    }

    override suspend fun sendBook(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String) = sync.send(book, identifiers, account, readAt)

    override suspend fun fetchProfile(): JSONObject = auth.authorized { token ->
        val profile = auth.http.get(token, "/api/settings/profile/")
        JSONObject().putOpt("username", profile.text("username")).putOpt("name", profile.text("display_name")).putOpt("email", profile.text("email"))
            .putOpt("createdAt", profile.text("signed_up_at")).putOpt("membership", profile.text("subscription_tier")?.replaceFirstChar { it.uppercase() })
    }

    override suspend fun authenticate(email: String, password: String) = auth.signIn(email, password)

    override suspend fun clearSession() = auth.disconnect()
}
