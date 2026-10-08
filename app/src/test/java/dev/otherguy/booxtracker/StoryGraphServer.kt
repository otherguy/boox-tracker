package dev.otherguy.booxtracker

import android.webkit.CookieManager
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject

const val STORYGRAPH_EBOOK = "00000000-0000-4000-8000-00000000a001"
const val STORYGRAPH_PAPERBACK = "00000000-0000-4000-8000-00000000a002"
const val STORYGRAPH_OTHER = "00000000-0000-4000-8000-00000000a009"
const val STORYGRAPH_ACCOUNT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const val STORYGRAPH_SESSION_COOKIE = "_storygraph_session=test-session-cookie"

/** A sanitised StoryGraph page excerpt from the test resources, with its placeholders filled. */
internal fun storyGraphHtml(name: String, vararg values: Pair<String, String>): String = values.fold(StoryGraphServer::class.java.getResource("/storygraph/$name")!!.readText()) { text, (key, value) -> text.replace("{{$key}}", value) }

/** The progress-type select's options with [type] selected, as StoryGraph renders them. */
internal fun progressOptions(type: String): String = listOf("pages" to "pages", "percentage" to "%").joinToString(" ") { (value, label) ->
    "<option${if (value == type) " selected=\"selected\"" else ""} value=\"$value\">$label</option>"
}

/** One StoryGraph edition on the fake site, with the signed-in user's status and progress for it. */
class StoryGraphBook(
    val id: String,
    var title: String,
    var author: String,
    var isbnUid: String?,
    var pages: String = "459",
    var status: String? = null,
    var percent: Int = 0,
    var pagesRead: String = "0",
    var progressType: String = "pages",
    var siblings: Set<String> = emptySet()
)

/** Fake StoryGraph website built from sanitised excerpts of the pages observed on 2026-10-07. */
class StoryGraphServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<JSONObject>()
    var account = STORYGRAPH_ACCOUNT
    var username = "reader"
    var csrf = "csrf-token-1"
    var sessionValid = true
    var challenge = false
    var ignoreStatus = false
    var ignoreProgress = false
    var readBackMismatch = false
    var failProgressOnce = false
    var omitUserId = false
    var blankProgress = false
    var homeSetCookie: String? = null
    val books = linkedMapOf<String, StoryGraphBook>()

    init {
        book(STORYGRAPH_EBOOK, "9781398508255", siblings = setOf(STORYGRAPH_PAPERBACK))
        book(STORYGRAPH_PAPERBACK, "9780000000002", siblings = setOf(STORYGRAPH_EBOOK))
        book(STORYGRAPH_OTHER, "9780000000009", title = "Visiting Egypt", author = "Other Author")
    }

    fun book(id: String, isbnUid: String?, title: String = "Synthetic Book", author: String = "Test Author", status: String? = null, percent: Int = 0, siblings: Set<String> = emptySet()): StoryGraphBook = StoryGraphBook(id, title, author, isbnUid, status = status, percent = percent, siblings = siblings).also { books[id] = it }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val text = request.body.readUtf8()
                val form = text.split('&').filter { it.contains('=') }.associate { pair -> URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8") }
                requests.add(
                    JSONObject().put("method", request.method).put("path", url.encodedPath).put("query", url.encodedQuery ?: JSONObject.NULL)
                        .put("cookie", request.getHeader("Cookie") ?: JSONObject.NULL).put("userAgent", request.getHeader("User-Agent") ?: JSONObject.NULL)
                        .put("csrf", request.getHeader("X-CSRF-Token") ?: JSONObject.NULL).put("requestedWith", request.getHeader("X-Requested-With") ?: JSONObject.NULL)
                        .put("frame", request.getHeader("turbo-frame") ?: JSONObject.NULL).put("form", JSONObject(form))
                )
                return route(request, url, form)
            }
        }
        start()
    }

    val origin: String = server.url("/").toString().removeSuffix("/")

    private fun html(body: String): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    private fun redirect(location: String): MockResponse = MockResponse().setResponseCode(302).setHeader("Location", origin + location).setBody("")

    private fun signedIn(request: RecordedRequest): Boolean = sessionValid && request.getHeader("Cookie")?.contains("_storygraph_session=") == true

    private fun page(title: String, body: String): String = storyGraphHtml("head.html", "CSRF" to csrf, "TITLE" to title) + body

    private fun nav(): String = storyGraphHtml("nav-signed-in.html", "USERNAME" to username, "CSRF" to csrf, "USER_ID" to if (omitUserId) "" else account)

    private fun bookPageHtml(book: StoryGraphBook, signedIn: Boolean): String {
        val status = book.status?.takeIf { signedIn }
        val label = status?.let { storyGraphHtml("status-label.html", "STATUS" to it) }.orEmpty()
        val other = if (status == null && signedIn) book.siblings.mapNotNull { books[it] }.firstOrNull { it.status != null } else null
        val forms = listOf("to-read", "currently-reading", "read", "paused", "did-not-finish").joinToString("\n") { storyGraphHtml("status-form.html", "BOOK_ID" to book.id, "STATUS" to it, "CSRF" to csrf) }
        val pane = if (status == "currently reading") {
            storyGraphHtml("progress-pane.html", "BOOK_ID" to book.id, "PERCENT" to if (blankProgress) "" else book.percent.toString(), "PAGES_READ" to book.pagesRead, "PAGES" to book.pages, "TYPE_OPTIONS" to progressOptions(book.progressType), "CSRF" to csrf)
        } else {
            ""
        }
        val body = storyGraphHtml(
            "book.html",
            "BOOK_ID" to book.id, "TITLE" to book.title, "AUTHOR" to book.author, "PAGES" to book.pages, "ISBN" to (book.isbnUid ?: "None"),
            "OTHER_EDITION" to (other?.let { storyGraphHtml("other-edition.html", "OTHER_ID" to it.id) } ?: ""),
            "STATUS_LABEL" to label, "PROGRESS_PANE" to pane, "STATUS_FORMS" to forms
        )
        return page("${book.title} by ${book.author} | The StoryGraph", (if (signedIn) nav() else "") + body)
    }

    private fun searchHtml(term: String, hits: List<StoryGraphBook>): String = storyGraphHtml(
        "search.html",
        "TERM" to term,
        "ITEMS" to hits.joinToString("\n") { storyGraphHtml("search-item.html", "BOOK_ID" to it.id, "TITLE" to it.title, "AUTHOR" to it.author) }
    )

    private fun validPost(request: RecordedRequest, form: Map<String, String>): Boolean = request.getHeader("X-CSRF-Token") == csrf && form["authenticity_token"] == csrf && request.getHeader("X-Requested-With") == "XMLHttpRequest"

    private fun route(request: RecordedRequest, url: okhttp3.HttpUrl, form: Map<String, String>): MockResponse {
        if (challenge) return MockResponse().setResponseCode(403).setHeader("cf-mitigated", "challenge").setHeader("Content-Type", "text/html").setBody(storyGraphHtml("challenge.html"))
        val path = url.encodedPath
        val signedIn = signedIn(request)
        return when {
            path == "/users/sign_in" && request.method == "GET" -> if (signedIn) redirect("/") else html(page("Sign In | The StoryGraph", storyGraphHtml("sign-in.html", "CSRF" to csrf)))

            path == "/" -> if (!signedIn) {
                redirect("/users/sign_in")
            } else {
                val count = books.values.count { it.status == "currently reading" }
                html(page("The StoryGraph", nav() + storyGraphHtml("home.html", "USERNAME" to username, "CURRENT_COUNT" to count.toString()))).apply { homeSetCookie?.let { setHeader("Set-Cookie", it) } }
            }

            path == "/search" -> {
                if (request.getHeader("turbo-frame") != "search_results") return MockResponse().setResponseCode(406)
                val term = url.queryParameter("search_term").orEmpty().trim()
                val exact = books.values.filter { it.isbnUid != null && it.isbnUid.equals(term, ignoreCase = true) }
                // Like StoryGraph, a title search lists one edition per work, so sibling editions collapse into the first.
                val byTitle = books.values.filter { term.contains(it.title, ignoreCase = true) || it.title.contains(term, ignoreCase = true) }
                    .fold(emptyList<StoryGraphBook>()) { kept, hit -> if (kept.any { hit.id in it.siblings }) kept else kept + hit }
                // StoryGraph's search is fuzzy: an unknown ISBN still returns an unrelated book.
                val hits = exact.ifEmpty { byTitle.ifEmpty { listOf(books.getValue(STORYGRAPH_OTHER)) } }
                html(searchHtml(term, hits))
            }

            path.startsWith("/books/") -> {
                val book = books[path.removePrefix("/books/")] ?: return MockResponse().setResponseCode(404).setBody("Not found")
                html(bookPageHtml(book, signedIn))
            }

            path == "/update-status.js" && request.method == "POST" -> {
                if (!signedIn) return redirect("/users/sign_in")
                if (!validPost(request, form)) return MockResponse().setResponseCode(422).setBody("")
                val book = books[url.queryParameter("book_id")] ?: return MockResponse().setResponseCode(404)
                if (!ignoreStatus) {
                    book.status = url.queryParameter("status")!!.replace('-', ' ')
                    if (book.status == "currently reading" && book.percent == 0) book.progressType = "pages"
                }
                MockResponse().setResponseCode(200).setHeader("Content-Type", "text/javascript; charset=utf-8").setBody(storyGraphHtml("update-status.js", "BOOK_ID" to book.id))
            }

            path == "/update-progress" && request.method == "POST" -> {
                if (!signedIn) return redirect("/users/sign_in")
                if (!validPost(request, form)) return MockResponse().setResponseCode(422).setBody("")
                if (failProgressOnce) {
                    failProgressOnce = false
                    return MockResponse().setResponseCode(500).setBody("")
                }
                val book = books[form["book_id"]] ?: return MockResponse().setResponseCode(404)
                val number = form["read_status[progress_number]"]?.toIntOrNull() ?: return MockResponse().setResponseCode(422).setBody("")
                if (!ignoreProgress && form["read_status[progress_type]"] == "percentage") {
                    book.percent = if (readBackMismatch) number - 1 else number
                    book.progressType = "percentage"
                    book.pagesRead = (book.pages.toInt() * book.percent / 100).toString()
                }
                MockResponse().setResponseCode(200).setHeader("Content-Type", "text/javascript; charset=utf-8").setBody(storyGraphHtml("update-progress.js", "BOOK_ID" to book.id, "PERCENT" to book.percent.toString()))
            }

            path == "/users/sign_out" && request.method == "POST" -> {
                sessionValid = false
                MockResponse().setResponseCode(303).setHeader("Location", "$origin/").setHeader("Set-Cookie", "_storygraph_session=; Path=/; Max-Age=0").setBody("")
            }

            else -> MockResponse().setResponseCode(404).setBody("Not found")
        }
    }

    fun writes(): List<JSONObject> = requests.filter { it.getString("method") == "POST" }
    fun statusWrites(): List<JSONObject> = writes().filter { it.getString("path") == "/update-status.js" }
    fun progressWrites(): List<JSONObject> = writes().filter { it.getString("path") == "/update-progress" }
    override fun close() = server.shutdown()
}

/** A session against the fake site, with the cookie the real sign-in WebView would have left in the cookie store. */
fun storyGraphSession(store: DiagnosticsStore, server: StoryGraphServer, captured: Boolean = true): StoryGraphSession {
    val session = StoryGraphSession(store, server.origin)
    if (captured) {
        CookieManager.getInstance().setCookie(server.origin, "$STORYGRAPH_SESSION_COOKIE; Path=/; HttpOnly")
        CookieManager.getInstance().setCookie(server.origin, "remember_user_token=test-remember; Path=/; HttpOnly")
        session.capture("test-agent")
    } else {
        kotlinx.coroutines.runBlocking { session.clear() }
    }
    return session
}
