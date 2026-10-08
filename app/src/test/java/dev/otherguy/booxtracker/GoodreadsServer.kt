package dev.otherguy.booxtracker

import android.webkit.WebView
import java.net.URLDecoder
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

const val GOODREADS_EBOOK = "58467253"
const val GOODREADS_PAPERBACK = "60174472"
const val GOODREADS_OTHER = "90000001"
const val GOODREADS_WORK = "91709220"
const val GOODREADS_OTHER_WORK = "91709229"
const val GOODREADS_ACCOUNT = "12345678"
const val GOODREADS_SESSION_COOKIE = "session-token=test-session-token"
const val GOODREADS_ACTION = "6020a1d4c9172ee90c2335c4737cf7f8c5fd8e9ef7"

/** A sanitised Goodreads page excerpt from the test resources, with its placeholders filled. */
internal fun goodreadsHtml(name: String, vararg values: Pair<String, String>): String = values.fold(GoodreadsServer::class.java.getResource("/goodreads/$name")!!.readText()) { text, (key, value) -> text.replace("{{$key}}", value) }

/** One reading session of a shelving, as the review editor's state holds it. */
class ReadingSessionFixture(val id: String, val started: LocalDate?, var ended: LocalDate? = null, var state: String = "READING") {
    fun json(): JSONObject = JSONObject().put("id", "kca://reading_session/$id").put("bookId", "kca://book/test")
        .put("startedDate", started?.let(::nullableDate) ?: JSONObject.NULL).put("endedDate", ended?.let(::nullableDate) ?: JSONObject.NULL)
        .put("state", state).put("__typename", "ReadingSession")
}

private fun nullableDate(date: LocalDate) = JSONObject().put("year", date.year).put("month", date.monthValue).put("day", date.dayOfMonth).put("__typename", "NullableDate")

/** One Goodreads edition on the fake site, with the signed-in user's shelf, progress, and reads for it. */
class GoodreadsEdition(
    val id: String,
    val workId: String,
    var title: String,
    var author: String,
    var isbn13: String?,
    var pages: Int = 480,
    var shelf: String? = null,
    var percent: Int? = null,
    val sessions: MutableList<ReadingSessionFixture> = mutableListOf()
)

/** A WebView profile with its own in-memory cookie jar, as a separate `androidx.webkit` profile keeps its own store. */
class FakeWebProfile : WebProfile {
    var supported = true
    var attached = 0
    private val cookies = linkedMapOf<String, String>()

    override fun supported() = supported

    @Synchronized fun cookies(): Map<String, String> = LinkedHashMap(cookies)

    @Synchronized fun set(cookie: String) {
        val pair = cookie.substringBefore(';')
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=', "")
        if (value.isEmpty() || cookie.contains("Max-Age=0", ignoreCase = true)) cookies.remove(name) else cookies[name] = value
    }

    override suspend fun cookieHeader(url: String): String? = cookies().entries.joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }

    override suspend fun accept(url: String, setCookies: List<String>) = setCookies.forEach(::set)

    override suspend fun flush() {}

    override suspend fun clear() = synchronized(this) { cookies.clear() }

    override fun attach(web: WebView) {
        attached++
    }
}

/** The hidden browser: [pass] runs the page's own scripts, here by changing the fake site, and reports the outcome. */
class FakeRefresher(var outcome: RefreshOutcome = RefreshOutcome.SIGNED_IN, var pass: () -> Unit = {}) : SessionRefresher {
    val calls = CopyOnWriteArrayList<String>()

    override suspend fun refresh(url: String): RefreshOutcome {
        calls.add(url)
        pass()
        return outcome
    }
}

/** Fake Goodreads website built from sanitised excerpts of the pages observed on 2026-10-08. */
class GoodreadsServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<JSONObject>()
    var account = GOODREADS_ACCOUNT
    var csrf = "csrf-token-1"
    var sessionValid = true
    var challenge = false
    var exactSearchLocation = false
    var ignoreShelf = false
    var ignoreProgress = false
    var failProgressOnce = false
    var omitUserId = false
    var stampsReadDate: LocalDate? = null
    var actionErrors = false
    var reviewPresent = false
    var homeSetCookie: String? = null
    val books = linkedMapOf<String, GoodreadsEdition>()

    init {
        book(GOODREADS_EBOOK, GOODREADS_WORK, "9781982181680")
        book(GOODREADS_PAPERBACK, GOODREADS_WORK, "9781398508255")
        book(GOODREADS_OTHER, GOODREADS_OTHER_WORK, "9780000000019", title = "Visiting Egypt", author = "Other Author")
    }

    fun book(id: String, workId: String, isbn13: String?, title: String = "Synthetic Book", author: String = "Test Author", shelf: String? = null, percent: Int? = null): GoodreadsEdition = GoodreadsEdition(id, workId, title, author, isbn13, shelf = shelf, percent = percent).also { books[id] = it }

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                val text = request.body.readUtf8()
                val form = if (request.getHeader("Content-Type")?.startsWith("application/x-www-form-urlencoded") == true) {
                    text.split('&').filter { it.contains('=') }.associate { pair -> URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('='), "UTF-8") }
                } else {
                    emptyMap()
                }
                requests.add(
                    JSONObject().put("method", request.method).put("path", url.encodedPath).put("query", url.encodedQuery ?: JSONObject.NULL)
                        .put("cookie", request.getHeader("Cookie") ?: JSONObject.NULL).put("userAgent", request.getHeader("User-Agent") ?: JSONObject.NULL)
                        .put("csrf", request.getHeader("X-CSRF-Token") ?: JSONObject.NULL).put("requestedWith", request.getHeader("X-Requested-With") ?: JSONObject.NULL)
                        .put("action", request.getHeader("Next-Action") ?: JSONObject.NULL).put("referer", request.getHeader("Referer") ?: JSONObject.NULL)
                        .put("form", JSONObject(form)).put("body", text)
                )
                return route(request, url, form, text)
            }
        }
        start()
    }

    val origin: String = server.url("/").toString().removeSuffix("/")

    private fun html(body: String): MockResponse = MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    private fun redirect(location: String): MockResponse = MockResponse().setResponseCode(302).setHeader("Location", origin + location).setBody("")

    private fun signedIn(request: RecordedRequest): Boolean = sessionValid && request.getHeader("Cookie")?.contains(GOODREADS_SESSION_COOKIE) == true

    private fun page(title: String, signedIn: Boolean, body: String): String = goodreadsHtml("head.html", "TITLE" to title, "CSRF" to csrf) +
        (if (signedIn) goodreadsHtml("nav-signed-in.html", "USER_ID" to if (omitUserId) "" else account, "SLUG" to "reader") else "") + body + "</body></html>"

    private fun shelfLabel(shelf: String) = mapOf("to-read" to "Want to Read", "currently-reading" to "Currently Reading", "read" to "Read", "did-not-finish" to "Did Not Finish")[shelf] ?: shelf

    private fun editionsHtml(workId: String): String {
        val shelved = books.values.filter { it.workId == workId && it.shelf != null }
        val rows = shelved.joinToString("\n") { book ->
            val form = if (book.shelf == "currently-reading") {
                goodreadsHtml(
                    "progress-form.html",
                    "BOOK_ID" to book.id,
                    "PAGE" to (book.percent?.let { (book.pages * it / 100).toString() } ?: ""),
                    "PAGES" to book.pages.toString(),
                    "PERCENT" to (book.percent?.toString() ?: ""),
                    "CSRF" to csrf,
                    "USER_ID" to account
                )
            } else {
                ""
            }
            goodreadsHtml(
                "user-works-row.html",
                "BOOK_ID" to book.id, "TITLE" to book.title, "AUTHOR" to book.author, "FORMAT" to "Kindle Edition", "SHELF" to book.shelf!!,
                "SHELF_LABEL" to shelfLabel(book.shelf!!), "PROGRESS_FORM" to form, "CSRF" to csrf
            )
        }
        val title = books.values.firstOrNull { it.workId == workId }?.title ?: "Unknown"
        return page("Editions in My Books of '$title' | Goodreads", true, goodreadsHtml("user-works.html", "TITLE" to title, "ROWS" to rows, "CSRF" to csrf, "USER_ID" to account))
    }

    private fun nextData(book: GoodreadsEdition): String {
        val bookKey = "Book:kca://book/amzn1.gr.book.v3.synthetic${book.id}"
        val workKey = "Work:kca://work/amzn1.gr.work.v3.synthetic${book.workId}"
        val apollo = JSONObject()
            .put("ROOT_QUERY", JSONObject().put("__typename", "Query").put("getBookByLegacyId({\"legacyId\":\"${book.id}\"})", JSONObject().put("__ref", bookKey)))
            .put(
                bookKey,
                JSONObject().put("__typename", "Book").put("legacyId", book.id.toLong()).put("title", book.title).put("titleComplete", "${book.title} (Synthetic Series, #1)")
                    .put("details", JSONObject().put("__typename", "BookDetails").put("isbn13", book.isbn13 ?: JSONObject.NULL).put("isbn", JSONObject.NULL).put("numPages", book.pages))
                    .put("primaryContributorEdge", JSONObject().put("node", JSONObject().put("__ref", "Contributor:kca://author/${book.author.hashCode()}")))
                    .put("secondaryContributorEdges", JSONArray()).put("work", JSONObject().put("__ref", workKey))
            )
            .put("Contributor:kca://author/${book.author.hashCode()}", JSONObject().put("__typename", "Contributor").put("name", book.author))
            .put(workKey, JSONObject().put("__typename", "Work").put("legacyId", book.workId.toLong()).put("viewerShelvingsUrl", "/review/user_works/${book.workId}"))
        return JSONObject().put("props", JSONObject().put("pageProps", JSONObject().put("apolloState", apollo).put("jwtToken", "synthetic-jwt"))).put("page", "/book/show/[book_id]").toString()
    }

    /** The review editor's streamed state: the full sessions in the shelving, then the form's props, which only reference their dates. */
    private fun editorFlight(book: GoodreadsEdition): String {
        val sessions = JSONArray(book.sessions.map { it.json() })
        val shelving = JSONObject().put("readingSessions", sessions).put("privateData", JSONObject().put("isOwnedEdition", false).put("privateNotes", JSONObject.NULL))
            .put("review", if (reviewPresent) JSONObject().put("id", "kca://review/1") else JSONObject.NULL).put("__typename", "Shelving")
        val references = JSONArray(book.sessions.mapIndexed { i, session -> session.json().put("startedDate", "\$2b:props:children:props:book:work:viewerShelvings:0:readingSessions:$i:startedDate") })
        val props = JSONObject().put("book", JSONObject().put("id", "kca://book/test").put("work", JSONObject().put("viewerShelvings", JSONArray().put(shelving))))
            .put("isAlreadyOwned", false).put("initialPrivateNotes", "kept note").put("initialReadingSessions", references).put("initialShelfName", book.shelf ?: JSONObject.NULL)
        val row = "1:\"\$Sreact.fragment\"\n5:" + JSONArray().put("\$").put("div").put(JSONObject.NULL).put(props) + "\n"
        // Next.js streams rows in pieces that can split a row; the page joins them back in order.
        val cut = row.length / 2
        return listOf(row.substring(0, cut), row.substring(cut)).joinToString("\n") { "<script>self.__next_f.push(${JSONArray().put(1).put(it)})</script>" }
    }

    private fun validPost(request: RecordedRequest, form: Map<String, String>): Boolean = request.getHeader("X-CSRF-Token") == csrf && form["authenticity_token"] == csrf && request.getHeader("X-Requested-With") == "XMLHttpRequest"

    private fun route(request: RecordedRequest, url: okhttp3.HttpUrl, form: Map<String, String>, body: String): MockResponse {
        if (challenge) return MockResponse().setResponseCode(202).setHeader("x-amzn-waf-action", "challenge").setHeader("Content-Type", "text/html").setBody(goodreadsHtml("challenge.html"))
        val path = url.encodedPath
        val signedIn = signedIn(request)
        return when {
            path == "/user/sign_in" -> if (signedIn) redirect("/") else html(page("Sign in | Goodreads", false, goodreadsHtml("sign-in.html")))

            path == "/" -> html(page("Recent updates | Goodreads", signedIn, if (signedIn) goodreadsHtml("home.html", "USER_ID" to account, "SLUG" to "reader") else goodreadsHtml("landing.html")))
                .apply { if (signedIn) homeSetCookie?.let { setHeader("Set-Cookie", it) } }

            path.startsWith("/review/user_works/") -> if (signedIn) html(editionsHtml(path.removePrefix("/review/user_works/"))) else redirect("/user/sign_in")

            path.startsWith("/user/show/") -> html(page("Reader Name (reader) (36 books) | Goodreads", signedIn, goodreadsHtml("profile.html", "NAME" to "Reader Name")))

            path == "/search" -> {
                val query = url.queryParameter("q").orEmpty().trim()
                val exact = books.values.filter { it.isbn13 != null && (it.isbn13 == query || isbn10(it.isbn13!!) == query) }
                if (exact.size == 1 && exactSearchLocation) {
                    val book = exact.single()
                    return html(goodreadsHtml("book.html", "TITLE" to book.title, "AUTHOR" to book.author, "NEXT_DATA" to nextData(book))).setHeader("Location", "/book/show/${book.id}")
                }
                // A title search lists one edition per work.
                val byTitle = books.values.filter { query.contains(it.title, ignoreCase = true) }.distinctBy { it.workId }
                val hits = exact.ifEmpty { byTitle }
                html(page("Search | Goodreads", signedIn, goodreadsHtml("search.html", "QUERY" to query, "ITEMS" to hits.joinToString("\n") { goodreadsHtml("search-item.html", "BOOK_ID" to it.id, "TITLE" to it.title, "AUTHOR" to it.author) })))
            }

            path.startsWith("/book/show/") -> {
                val book = books[path.removePrefix("/book/show/").substringBefore('-')] ?: return MockResponse().setResponseCode(404).setBody("Not found")
                html(goodreadsHtml("book.html", "TITLE" to book.title, "AUTHOR" to book.author, "NEXT_DATA" to nextData(book)))
            }

            path == "/shelf/add_to_shelf" && request.method == "POST" -> {
                if (!signedIn) return redirect("/user/sign_in")
                if (!validPost(request, form)) return MockResponse().setResponseCode(422).setBody("")
                val book = books[form["book_id"]] ?: return MockResponse().setResponseCode(404)
                val name = form["name"]!!
                if (!ignoreShelf) {
                    if (name == "currently-reading" && book.shelf != "currently-reading") book.sessions.add(ReadingSessionFixture("s${book.sessions.size + 1}", LocalDate.of(2026, 9, 14)))
                    if (name == "read") {
                        val open = book.sessions.lastOrNull { it.ended == null }
                        stampsReadDate?.let { day ->
                            open?.apply {
                                ended = day
                                state = "COMPLETED"
                            }
                        }
                    }
                    book.shelf = name
                }
                MockResponse().setResponseCode(200).setHeader("Content-Type", "text/javascript; charset=utf-8").setBody("Element.update('shelfList${account}_${book.id}', '<span>$name</span>');")
            }

            path == "/user_status.json" && request.method == "POST" -> {
                if (!signedIn) return redirect("/user/sign_in")
                if (!validPost(request, form)) return MockResponse().setResponseCode(422).setBody("")
                if (failProgressOnce) {
                    failProgressOnce = false
                    return MockResponse().setResponseCode(500).setBody("")
                }
                val book = books[form["user_status[book_id]"]] ?: return MockResponse().setResponseCode(404)
                val percent = form["user_status[percent]"]?.toIntOrNull() ?: return MockResponse().setResponseCode(422).setBody("")
                if (!ignoreProgress) book.percent = percent
                MockResponse().setResponseCode(201).setHeader("Content-Type", "application/json").setBody(JSONObject().put("id", 1).put("percent", percent).toString())
            }

            path.startsWith("/review/edit/") && request.method == "GET" -> {
                if (!signedIn) return redirect("/user/sign_in")
                val book = books[path.removePrefix("/review/edit/")] ?: return MockResponse().setResponseCode(404)
                html(goodreadsHtml("review-edit.html", "TITLE" to book.title, "ACTION_CHUNK" to "9067-0000000000000006.js", "FLIGHT" to editorFlight(book)))
            }

            path.startsWith("/review/edit/") && request.method == "POST" -> {
                if (!signedIn) return redirect("/user/sign_in")
                if (request.getHeader("Next-Action") != GOODREADS_ACTION || request.getHeader("Accept") != "text/x-component") return MockResponse().setResponseCode(404).setBody("")
                val book = books[path.removePrefix("/review/edit/")] ?: return MockResponse().setResponseCode(404)
                val call = JSONArray(body)
                if (call.getString(1) != "/review/edit/[id]") return MockResponse().setResponseCode(400)
                val sessions = call.getJSONObject(0).getJSONArray("readingSessions")
                if (!actionErrors) {
                    (0 until sessions.length()).map(sessions::getJSONObject).forEach { sent ->
                        val session = book.sessions.firstOrNull { "kca://reading_session/${it.id}" == sent.getString("id") } ?: return@forEach
                        sent.optJSONObject("endedDate")?.let { session.ended = LocalDate.of(it.getInt("year"), it.getInt("month"), it.getInt("day")) }
                    }
                }
                val errors = if (actionErrors) "[{\"message\":\"Invalid date\"}]" else "[]"
                MockResponse().setResponseCode(200).setHeader("Content-Type", "text/x-component").setBody("0:{\"a\":\"\$@1\",\"f\":\"\",\"b\":\"build\"}\n1:{\"success\":${!actionErrors},\"errors\":\"\$Q2\"}\n2:$errors\n")
            }

            path.startsWith("/_next/static/chunks/") -> MockResponse().setResponseCode(200).setHeader("Content-Type", "application/javascript")
                .setBody(if (path.contains("/9067-")) goodreadsHtml("chunk-action.js", "ACTION_ID" to GOODREADS_ACTION) else goodreadsHtml("chunk-other.js"))

            path == "/user/sign_out" && request.method == "POST" -> {
                sessionValid = false
                MockResponse().setResponseCode(302).setHeader("Location", "$origin/").setHeader("Set-Cookie", "session-token=; Path=/; Max-Age=0").setBody("")
            }

            else -> MockResponse().setResponseCode(404).setBody("Not found")
        }
    }

    fun writes(): List<JSONObject> = requests.filter { it.getString("method") == "POST" }
    fun shelfWrites(): List<JSONObject> = writes().filter { it.getString("path") == "/shelf/add_to_shelf" }
    fun progressWrites(): List<JSONObject> = writes().filter { it.getString("path") == "/user_status.json" }
    fun actionWrites(): List<JSONObject> = writes().filter { it.getString("path").startsWith("/review/edit/") }
    override fun close() = server.shutdown()
}

/** A session against the fake site, with the cookie the real sign-in WebView would have left in the Goodreads profile. */
fun goodreadsSession(store: DiagnosticsStore, server: GoodreadsServer, profile: FakeWebProfile, captured: Boolean = true): GoodreadsSession {
    val session = GoodreadsSession(store, profile, server.origin)
    if (captured) {
        profile.set("$GOODREADS_SESSION_COOKIE; Path=/; HttpOnly")
        profile.set("ubid-main=test-ubid; Path=/")
        session.capture("test-agent")
    } else {
        kotlinx.coroutines.runBlocking { session.clear() }
    }
    return session
}
