package dev.otherguy.booxtracker

import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

const val PAGEBOUND_BOOK = "00000000-0000-4000-8000-0000000000b1"
const val PAGEBOUND_OTHER = "00000000-0000-4000-8000-0000000000b2"
const val PAGEBOUND_PAPERBACK_ISBN = "9781398508255"
const val PAGEBOUND_EBOOK_ISBN = "9781234567897"
const val PAGEBOUND_OTHER_ISBN = "9780306406157"
const val PAGEBOUND_PAPERBACK = 1734779L
const val PAGEBOUND_EBOOK = 1679532L
const val PAGEBOUND_HARDCOVER = 7648L

/** A reading instance: one read of a library entry. */
class PageboundRead(val id: Long, val format: String) {
    fun json() = JSONObject().put("id", id).put("current", true).put("finished", false).put("format", format).put("tracking_mode", "pages").put("progress_method", "percent")
}

/** A library entry ("user book") with the fields Pagebound returned on 2026-10-08. */
class PageboundEntry(val uuid: String, val id: Long, val bookId: Long, var status: String) {
    var progress = 0
    var editionId: Long? = null
    var hasEverFinished = false
    var read: PageboundRead? = null
    var totalPages: Int? = null

    fun json() = JSONObject().put("id", id).put("uuid", uuid).put("book_id", bookId).put("status", status).put("progress", progress)
        .put("edition_id", editionId ?: JSONObject.NULL).put("has_ever_finished", hasEverFinished).put("total_page_count", totalPages ?: JSONObject.NULL)
        .put("current_reading_instance", read?.json() ?: JSONObject.NULL)
}

/** Fake Firebase, Pagebound API, and catalogue search endpoints with the response shapes observed on 2026-10-08. */
class PageboundServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<JSONObject>()
    var account = "account-uuid-1"
    var wrongPassword = false
    var signInRelease: java.util.concurrent.CountDownLatch? = null
    var invalidRefresh = false
    val revoked = mutableSetOf<String>()
    var ignoreStatus = false
    var ignoreProgress = false
    var failProgressOnce = false
    var emptyProgressFailureOnce = false
    var ignoreEdition = false
    val entries = linkedMapOf<String, PageboundEntry>()
    private val catalogue = linkedMapOf<String, JSONObject>()
    private val editions = mutableListOf<JSONObject>()
    private var nextId = 100L

    init {
        catalogue[PAGEBOUND_BOOK] = JSONObject().put("id", 7646).put("uuid", PAGEBOUND_BOOK).put("title", "Synthetic Book (Synthetic Series, #1)").put("author_name", "Test Author").put("page_count", 480)
        catalogue[PAGEBOUND_OTHER] = JSONObject().put("id", 9001).put("uuid", PAGEBOUND_OTHER).put("title", "Other Book").put("author_name", "Other Author").put("page_count", 200)
        edition(PAGEBOUND_PAPERBACK, PAGEBOUND_PAPERBACK_ISBN, "139850825X", "Paperback", PAGEBOUND_BOOK)
        edition(PAGEBOUND_EBOOK, PAGEBOUND_EBOOK_ISBN, "1234567897", "Kindle", PAGEBOUND_BOOK)
        edition(9002, PAGEBOUND_OTHER_ISBN, "0306406152", "Hardcover", PAGEBOUND_OTHER)
    }

    private fun edition(id: Long, isbn13: String, isbn10: String, format: String, book: String) {
        editions.add(JSONObject().put("id", id).put("isbn13", isbn13).put("isbn10", isbn10).put("format", format).put("pages", 480).put("book_uuid", book))
    }

    /** Adds [book] to the library with [status]; statuses other than tbr, interested, and none have a reading instance. */
    fun entry(status: String, book: String = PAGEBOUND_BOOK, format: String = "print"): PageboundEntry {
        val id = nextId++
        return PageboundEntry("entry-$id", id, catalogue.getValue(book).getLong("id"), status).also {
            if (status !in setOf("tbr", "interested", "none")) it.read = PageboundRead(id * 10, format)
            entries[book] = it
        }
    }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val text = request.body.readUtf8()
                val body = runCatching { JSONObject(text) }.getOrNull()
                requests.add(
                    JSONObject().put("method", request.method).put("path", url.encodedPath).put("query", url.encodedQuery ?: JSONObject.NULL)
                        .put("auth", request.getHeader("Authorization") ?: JSONObject.NULL).put("body", body ?: text)
                )
                val (status, response) = route(request.method!!, url, body, request.getHeader("Authorization"))
                return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(response?.toString().orEmpty())
            }
        }
        start()
    }
    val origin = server.url("/").toString().removeSuffix("/")
    val http = PageboundHttp(api = "$origin/api/v1", identity = origin, secureToken = origin, search = origin, key = "test-key", searchKey = "search-key")

    private fun firebaseFailure(message: String) = JSONObject().put("error", JSONObject().put("code", 400).put("message", message))

    private fun route(method: String, url: okhttp3.HttpUrl, body: JSONObject?, auth: String?): Pair<Int, Any?> {
        val path = url.encodedPath
        when (path) {
            "/v1/accounts:signInWithPassword" -> {
                check(url.queryParameter("key") == "test-key")
                signInRelease?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return if (wrongPassword) 400 to firebaseFailure("INVALID_LOGIN_CREDENTIALS") else 200 to JSONObject().put("idToken", "id-1").put("refreshToken", "refresh-1").put("expiresIn", "3600")
            }

            "/v1/token" -> return if (invalidRefresh) 400 to firebaseFailure("TOKEN_EXPIRED") else 200 to JSONObject().put("id_token", "id-2").put("refresh_token", "refresh-2").put("expires_in", "3600")

            "/multi_search" -> {
                check(url.queryParameter("x-typesense-api-key") == "search-key")
                val query = body!!.getJSONArray("searches").getJSONObject(0).getString("q").lowercase()
                val hits = catalogue.values.filter { book -> book.getString("author_name").lowercase().split(' ').all { it in query } }
                    .map { JSONObject().put("document", JSONObject().put("id", it.getLong("id").toString()).put("uuid", it.getString("uuid")).put("title", it.getString("title")).put("author_name", it.getString("author_name"))) }
                return 200 to JSONObject().put("results", JSONArray().put(JSONObject().put("found", hits.size).put("hits", JSONArray(hits))))
            }
        }
        val api = path.removePrefix("/api/v1")
        if (api == "/auth/firebase_auth") {
            check(auth == "Bearer null")
            return 200 to JSONObject().put("token", "api-${body!!.getString("id_token")}").put("user", JSONObject().put("id", 1))
        }
        // Catalogue reads answer without a session; account requests with a rejected token fail with an empty 500.
        val catalogueRead = api == "/search" || (api.startsWith("/books/") && method == "GET")
        val token = auth?.removePrefix("Bearer ")
        if (!catalogueRead && (token == null || !token.startsWith("api-") || token in revoked)) return 500 to null
        val segments = api.trim('/').split('/')
        return when {
            api == "/auth/get_authed_user" -> 200 to JSONObject().put("user", JSONObject().put("uuid", account).put("username", "reader").put("email", "reader@example.com").put("paid_subscriber", false))
                .put("preferences", JSONObject().put("created_at", "2026-10-08T12:09:19.255Z"))

            api == "/search" -> {
                check(url.queryParameter("type") == "ISBN")
                val q = url.queryParameter("q")!!
                200 to JSONObject().put("edition", editions.firstOrNull { it.getString("isbn13") == q || it.getString("isbn10") == q } ?: JSONObject.NULL)
            }

            segments.size == 2 && segments[0] == "books" -> catalogue[segments[1]]?.let { 200 to JSONObject().put("book", JSONObject(it.toString()).put("user_book", entries[segments[1]]?.json() ?: JSONObject.NULL)) } ?: (404 to null)

            segments.size == 2 && segments[0] == "user_books" && method == "GET" -> entries.values.firstOrNull { it.uuid == segments[1] }?.let { 200 to it.json() } ?: (404 to null)

            api == "/user_books" && method == "POST" -> {
                val user = body!!.getJSONObject("user_book")
                val book = catalogue.values.first { it.getLong("id") == user.getLong("book_id") }.getString("uuid")
                val entry = entry(if (ignoreStatus) "tbr" else user.getString("status"), book, body.getString("format"))
                entry.editionId = user.optLong("edition_id").takeIf { it > 0 }
                entry.totalPages = body.optInt("total_page_count").takeIf { it > 0 }
                200 to entry.json()
            }

            segments.size == 2 && segments[0] == "user_books" && method == "PUT" -> {
                val entry = entries.values.first { it.uuid == segments[1] }
                body!!.optJSONObject("user_book")?.let { if (it.has("edition_id") && !ignoreEdition) entry.editionId = it.getLong("edition_id") }
                if (body.has("status") && !ignoreStatus) {
                    entry.status = body.getString("status")
                    if (entry.read == null) entry.read = PageboundRead(entry.id * 10, body.optString("format").ifBlank { "print" })
                }
                200 to entry.json()
            }

            segments.size == 3 && segments[0] == "user_books" && segments[2] == "update_status" -> {
                val entry = entries.values.first { it.uuid == segments[1] }
                if (!ignoreStatus) entry.status = body!!.getString("status")
                200 to entry.json()
            }

            api == "/reading_updates" && method == "POST" -> {
                val update = body!!.getJSONObject("reading_update")
                val entry = entries.values.first { it.id == update.getLong("user_book_id") }
                check(update.getLong("reading_instance_id") == entry.read!!.id && body.getString("progress_method") == "percent" && !body.getBoolean("no_broadcast"))
                if (failProgressOnce) {
                    failProgressOnce = false
                    return 500 to JSONObject().put("error", "Internal Server Error")
                }
                if (emptyProgressFailureOnce) {
                    emptyProgressFailureOnce = false
                    return 500 to null
                }
                if (!ignoreProgress) entry.progress = update.getInt("total_progress")
                200 to JSONObject().put("user_book", entry.json()).put("reading_update", JSONObject().put("link", "/activity/1"))
            }

            else -> 404 to null
        }
    }

    fun writes(): List<JSONObject> = requests.filter { it.getString("method") in setOf("POST", "PUT") && it.getString("path").startsWith("/api/v1/") && it.getString("path") != "/api/v1/auth/firebase_auth" }
    fun progressWrites(): List<Int> = writes().filter { it.getString("path") == "/api/v1/reading_updates" }.map { it.getJSONObject("body").getJSONObject("reading_update").getInt("total_progress") }
    override fun close() = server.shutdown()
}

fun pageboundVault(app: ReadingSyncApp) = TokenVault(app, "pagebound") { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
