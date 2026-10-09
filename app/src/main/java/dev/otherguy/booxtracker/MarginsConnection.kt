package dev.otherguy.booxtracker

import org.json.JSONObject

class MarginsConnection(
    app: ReadingSyncApp,
    private val auth: MarginsAuth = MarginsAuth(MarginsHttp(), TokenVault(app, "margins")),
    private val zero: MarginsZero = MarginsZero(store = app.diagnostics.store),
    online: () -> Boolean = { networkAvailable(app) }
) : CodeConnection(app, "margins", online) {
    private val sync = MarginsSync(auth, zero, store) { enabled() }

    override fun connected() = auth.connected()

    override suspend fun account(): String = auth.authorized { token -> marginsAccountId(token) }

    override suspend fun sendBook(book: JSONObject, identifiers: BookIdentifiers, account: String, readAt: String) = sync.send(book, identifiers, account, readAt)

    override suspend fun fetchProfile(): JSONObject = auth.authorized { token ->
        val user = auth.http.user(token)
        val account = marginsAccountId(token)
        val profile = zero.query(token, account, listOf(ZeroQuery("profile", account))).rows("userspace.profiles").firstOrNull { it.optString("user_id") == account }
        JSONObject().putOpt("name", profile?.text("display_name")).putOpt("email", user.text("email")).putOpt("createdAt", user.text("created_at"))
    }

    override suspend fun sendCode(email: String) = auth.requestCode(email)

    override suspend fun verifyCode(email: String, code: String) = auth.verify(email, code)

    override suspend fun clearSession() {
        auth.disconnect()
        zero.forget()
    }
}
