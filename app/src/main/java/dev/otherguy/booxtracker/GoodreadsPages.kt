package dev.otherguy.booxtracker

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

const val GOODREADS_ORIGIN = "https://www.goodreads.com"

/** What the app reads from an edition's book page: its title, ISBN-13, contributors, and the work it belongs to. */
data class GoodreadsBook(val id: String, val title: String?, val isbn13: String?, val authors: List<String>, val workId: String)

/** One edition of a work on the user's shelves, with its exclusive shelf and the progress its update form shows. */
data class ShelvedEdition(val bookId: String, val title: String?, val shelf: String?, val percent: Int?)

/** The user's shelved editions of one work, with the account and CSRF token of the page that listed them. */
data class ShelvedEditions(val account: String, val csrf: String, val editions: List<ShelvedEdition>) {
    fun edition(id: String) = editions.firstOrNull { it.bookId == id }
}

/** The review editor's saved state: the shelving's reading sessions and the fields its form posts back unchanged. */
data class ReviewEditor(val bookId: String, val sessions: JSONArray, val privateNotes: String, val ownedEdition: Boolean, val hasReview: Boolean)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

/** True when the page carries the signed-in site header, which links to sign-out. */
internal fun goodreadsSignedIn(html: String): Boolean = html.contains("href=\"/user/sign_out")

/** The signed-in account's numeric id, from the My Books link in the site header. */
internal fun goodreadsUserId(html: String): String? = Regex("href=\"/review/list/(\\d+)\\?ref=nav_mybooks\"").find(html)?.groupValues?.get(1)

/** The page's CSRF token from its `csrf-token` meta tag. Every classic Goodreads page has one. */
internal fun goodreadsCsrf(html: String): String = tags(html, "meta", "csrf-token").firstNotNullOfOrNull { tag ->
    attribute(tag, "content")?.takeIf { attribute(tag, "name") == "csrf-token" && it.isNotBlank() }
} ?: throw SyncProblem("goodreads_page_unrecognized")

/** The account's display name and username from its profile page title, such as `Name (username) (12 books)`. */
internal fun goodreadsProfile(html: String): JSONObject {
    val title = Regex("<title>\\s*(.*?)\\s*</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.let(::htmlText).orEmpty()
    val match = Regex("^(.+?) \\(([^()]+)\\)").find(title)
    return JSONObject().putOpt("name", match?.groupValues?.get(1)).putOpt("username", match?.groupValues?.get(2))
}

/** The results on a search page, in page order. Each result's title link is followed by its contributor names. */
internal fun goodreadsSearchHits(html: String): List<GoodreadsHit> {
    val starts = Regex("data-testid=\"book-item-title\"").findAll(html).map { it.range.first }.toList()
    return starts.mapIndexedNotNull { index, start ->
        val segment = html.substring(start, minOf(starts.getOrElse(index + 1) { html.length }, start + 8_000))
        val link = Regex("<a\\b[^>]*href=\"/book/show/(\\d+)[^\"]*\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(segment) ?: return@mapIndexedNotNull null
        val names = Regex("data-testid=\"name\"[^>]*>([^<]*)<").findAll(segment).map { htmlText(it.groupValues[1]) }.filter { it.isNotBlank() }.toList()
        GoodreadsHit(link.groupValues[1], htmlText(link.groupValues[2]), names)
    }.distinctBy { it.id }
}

/** The book page of edition [id], read from its `__NEXT_DATA__` Apollo cache. */
internal fun goodreadsBook(html: String, id: String): GoodreadsBook {
    val script = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
    val apollo = script?.let { runCatching { JSONObject(it).getJSONObject("props").getJSONObject("pageProps").getJSONObject("apolloState") }.getOrNull() }
        ?: throw SyncProblem("goodreads_page_unrecognized")
    fun ref(value: JSONObject?): JSONObject? = value?.optString("__ref")?.takeIf { it.isNotEmpty() }?.let(apollo::optJSONObject)
    val book = ref(apollo.optJSONObject("ROOT_QUERY")?.optJSONObject("getBookByLegacyId({\"legacyId\":\"$id\"})")) ?: throw SyncProblem("goodreads_page_unrecognized")
    val work = ref(book.optJSONObject("work"))
    val workId = work?.opt("legacyId")?.toString()?.takeIf { it.matches(Regex("\\d+")) } ?: throw SyncProblem("goodreads_page_unrecognized")
    val details = book.optJSONObject("details")
    val contributors = listOfNotNull(book.optJSONObject("primaryContributorEdge")) + book.optJSONArray("secondaryContributorEdges")?.objects().orEmpty()
    val authors = contributors.mapNotNull { edge -> ref(edge.optJSONObject("node"))?.text("name") }
    val isbn = listOfNotNull(details?.text("isbn13"), details?.text("isbn")).firstNotNullOfOrNull(::isbn13)
    return GoodreadsBook(id, book.text("title"), isbn, authors, workId)
}

/**
 * The user's editions of one work from `/review/user_works/<workId>`. Each shelved edition has a row with its
 * exclusive shelf in `data-exclusive-shelf` and a progress form whose percent field shows the latest update.
 */
internal fun goodreadsEditions(html: String): ShelvedEditions {
    if (!html.contains("Editions in My Books of")) throw SyncProblem("goodreads_page_unrecognized")
    val account = goodreadsUserId(html) ?: throw SyncProblem("goodreads_identity_unavailable")
    val rows = html.split(Regex("<tr\\b[^>]*itemtype=\"http://schema.org/Book\"[^>]*>")).drop(1)
    // Shelf markup without a recognised row is a changed page, not an unshelved book; reading it as unshelved would shelve again.
    if (rows.isEmpty() && listOf("data-exclusive-shelf", "wtrButtonContainer", "/review/edit/").any(html::contains)) throw SyncProblem("goodreads_page_unrecognized")
    val editions = rows.map { row ->
        val id = Regex("<div id=\"(\\d+)\" class=\"u-anchorTarget\"").find(row)?.groupValues?.get(1)
            ?: Regex("href=\"/book/show/(\\d+)").find(row)?.groupValues?.get(1) ?: throw SyncProblem("goodreads_page_unrecognized")
        val title = Regex("itemprop=['\"]name['\"][^>]*>([^<]*)<").find(row)?.groupValues?.get(1)?.let(::htmlText)
        val shelf = Regex("data-exclusive-shelf=(?:'([^']*)'|\"([^\"]*)\")").find(row)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        fun field(name: String) = tags(row, "input", name).firstOrNull { attribute(it, "name") == name }?.let { attribute(it, "value")?.trim().orEmpty() }
        val page = field("user_status[page]")
        // A shelved edition without any progress update shows empty fields: Goodreads holds 0%. A page without a
        // percentage is progress the app cannot compare, so it is not read as 0%.
        val percent = field("user_status[percent]")?.let { value ->
            if (value.isEmpty() && !page.isNullOrEmpty() && page != "0") throw SyncProblem("goodreads_page_unrecognized")
            value.ifEmpty { "0" }.toIntOrNull() ?: throw SyncProblem("goodreads_page_unrecognized")
        }
        ShelvedEdition(id, title, shelf, percent)
    }
    return ShelvedEditions(account, goodreadsCsrf(html), editions)
}

/** The rows that `self.__next_f.push([1, "…"])` scripts stream into a Next.js page, joined in page order. */
internal fun nextFlight(html: String): String {
    val rows = mutableListOf<String>()
    var from = 0
    while (true) {
        val call = html.indexOf("self.__next_f.push(", from).takeIf { it >= 0 } ?: break
        val start = call + "self.__next_f.push(".length
        val array = runCatching { JSONTokener(html.substring(start)).nextValue() as? JSONArray }.getOrNull()
        if (array != null && array.optInt(0) == 1) array.optString(1).takeIf { it.isNotEmpty() }?.let(rows::add)
        from = start
    }
    return rows.joinToString("")
}

/** Every JSON value after `"name":` in [text] that has the expected type, in order. */
private inline fun <reified T> jsonProperties(text: String, name: String): List<T> {
    val values = mutableListOf<T>()
    var from = 0
    while (true) {
        val at = text.indexOf("\"$name\":", from).takeIf { it >= 0 } ?: return values
        val value = runCatching { JSONTokener(text.substring(at + name.length + 3)).nextValue() }.getOrNull()
        if (value is T) values.add(value)
        from = at + 1
    }
}

/** A Next.js flight reference such as `"$2b:props:…"`, which stands for a value written elsewhere in the stream. */
private fun Any?.isFlightReference() = this is String && startsWith("$")

/**
 * The review editor page's state. The form's own `initialReadingSessions` name this edition's reads, but reference
 * values written earlier in the stream; each reference is resolved from a `readingSessions` array with the same read
 * id. A value that stays a reference, or notes that are one, would be posted back as text, so the page is not used.
 */
internal fun goodreadsReviewEditor(html: String): ReviewEditor {
    val flight = nextFlight(html)
    val written = jsonProperties<JSONArray>(flight, "readingSessions").flatMap { it.objects() }.associateBy { it.optString("id") }
    val form = jsonProperties<JSONArray>(flight, "initialReadingSessions").firstOrNull() ?: throw SyncProblem("goodreads_editor_unrecognized")
    val sessions = JSONArray(
        form.objects().map { session ->
            JSONObject(session.toString()).apply {
                keys().asSequence().toList().filter { opt(it).isFlightReference() }.forEach { key ->
                    val value = written[optString("id")]?.opt(key)
                    if (value == null || value.isFlightReference()) throw SyncProblem("goodreads_editor_unrecognized")
                    put(key, value)
                }
            }
        }
    )
    val bookId = sessions.objects().map { it.text("bookId") }.distinct().singleOrNull() ?: throw SyncProblem("goodreads_editor_unrecognized")
    val privateNotes = jsonProperties<String>(flight, "initialPrivateNotes").firstOrNull() ?: ""
    if (privateNotes.isFlightReference()) throw SyncProblem("goodreads_editor_unrecognized")
    val owned = jsonProperties<Boolean>(flight, "isAlreadyOwned").firstOrNull() ?: false
    val reviewed = flight.contains("\"review\":{") || flight.contains("\"review\":\"$")
    return ReviewEditor(bookId, sessions, privateNotes, owned, reviewed)
}

/** The chunk scripts a Next.js page loads, in page order, without the framework chunks that never hold page actions. */
internal fun nextChunkPaths(html: String): List<String> = tags(html, "script", "/_next/static/chunks/").mapNotNull { tag ->
    attribute(tag, "src")?.removePrefix(GOODREADS_ORIGIN)?.takeIf { src ->
        src.startsWith("/_next/static/chunks/") && src.endsWith(".js") && !src.contains("..") && listOf("/polyfills-", "/webpack-", "/not-found-").none(src::contains)
    }
}.distinct()

/** The id of the Server Action named [name] in a chunk script, from its `createServerReference("<id>", …, "<name>")` call. */
internal fun serverActionId(script: String, name: String): String? = Regex("createServerReference\\)?\\(\\s*\"([0-9a-f]{32,})\"[^;]{0,200}?\"${Regex.escape(name)}\"").find(script)?.groupValues?.get(1)

/** True when a Server Action response has no errors: its `errors` reference, if any, resolves to an empty list. */
internal fun serverActionSucceeded(response: String): Boolean {
    val reference = Regex("\"errors\":\"\\\$Q([0-9a-zA-Z]+)\"").find(response)?.groupValues?.get(1) ?: return true
    val row = Regex("(?m)^${Regex.escape(reference)}:(.*)$").find(response)?.groupValues?.get(1) ?: return false
    return runCatching { JSONArray(row).length() == 0 }.getOrDefault(false)
}

/**
 * A Goodreads WAF challenge: the request never reached Goodreads, and only a browser can answer it. AWS WAF marks it
 * with `x-amzn-waf-action`; a 202 alone can be an accepted write, so it is not a challenge.
 */
internal fun isGoodreadsChallenge(headers: Map<String, List<String>>): Boolean = headers.containsKey("x-amzn-waf-action")

/** A Location that sends the browser to sign in: Goodreads' own page or Amazon's sign-in on the Goodreads host. */
internal fun isGoodreadsSignIn(location: String?): Boolean = location != null && Regex("^(?:https?://[^/]+)?/(?:user/sign_in|ap/signin)").containsMatchIn(location)
