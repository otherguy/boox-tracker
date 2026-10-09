package dev.otherguy.booxtracker

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Pagebound edition formats that are ebooks, preferred when several of the ebook's ISBNs name editions of one book. */
private val ebookFormats = setOf("kindle", "ebook")

/** Pagebound book titles carry the series in a trailing parenthesis, as in "In the Blood (Terminal List, #5)". */
internal fun withoutSeries(title: String) = title.replace(Regex("\\s*\\([^()]*\\)\\s*$"), "")

/**
 * Matches a source book to one Pagebound book. A `pagebound:` tag names the book; each ISBN names an edition of one
 * book through Pagebound's ISBN lookup; otherwise one catalogue hit must have the same normalized title and author.
 */
class PageboundMatcher(
    private val get: suspend (String) -> JSONObject,
    private val search: suspend (String) -> List<JSONObject>,
    private val store: DiagnosticsStore,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun match(sourceKey: String?, metadata: BookIdentifiers): JSONObject {
        val fingerprint = digest(metadata.json().toString())
        val cacheKey = sourceKey?.let { "pagebound.match.${digest(it)}" }
        val cached = cacheKey?.let { store.get(it) }?.let(::JSONObject)
        if (cached?.optString("fingerprint") == fingerprint && now() - cached.optLong("matchedAt") in 0 until 3_600_000) return cached
        val tagged = metadata.tags["pagebound"].orEmpty().sorted().filter { book(it) != null }
        val editions = metadata.isbns.sorted().mapNotNull { isbn -> edition(isbn) ?: isbn10(isbn)?.let { edition(it) } }.distinctBy { it.optLong("id") }
        val books = (tagged + editions.map { it.getString("book_uuid") }).toSet()
        if (books.size > 1) throw SyncProblem("pagebound_identifier_conflict")
        val uuid = books.singleOrNull() ?: titleMatch(metadata)
        val chosen = editions.firstOrNull { it.optString("format").lowercase() in ebookFormats } ?: editions.firstOrNull()
        val result = JSONObject().put("bookUuid", uuid).put("matchKind", if (chosen == null) "book" else "edition")
            .put("editionId", chosen?.optLong("id") ?: JSONObject.NULL).put("editions", JSONArray(editions.map { it.optLong("id") }))
            .put("pages", chosen?.optInt("pages")?.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("fingerprint", fingerprint).put("matchedAt", now())
        if (cacheKey != null) store.put(cacheKey, result.toString())
        return result
    }

    private suspend fun book(uuid: String): JSONObject? = try {
        get("/books/$uuid").optJSONObject("book")
    } catch (error: HttpProblem) {
        if (error.status == 404) null else throw error
    }

    /** The edition with this ISBN-13 or ISBN-10, or null when Pagebound has none. */
    private suspend fun edition(isbn: String): JSONObject? = get("/search?q=${URLEncoder.encode(isbn, "UTF-8")}&type=ISBN").optJSONObject("edition")
        ?.takeIf { it.text("book_uuid") != null && (it.optString("isbn13") == isbn || it.optString("isbn10").uppercase() == isbn) }

    private suspend fun titleMatch(metadata: BookIdentifiers): String {
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_title_missing")
        val author = metadata.author?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_author_missing")
        val names = authorNames(author).map(::authorKey).filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) throw SyncProblem("book_author_missing")
        val wanted = normalized(title)
        val candidates = search("$title $author").filter { hit ->
            val hitTitle = hit.optString("title")
            (wanted == normalized(hitTitle) || wanted == normalized(withoutSeries(hitTitle))) && authorKey(hit.optString("author_name")) in names
        }.mapNotNull { it.text("uuid") }.distinct()
        if (candidates.isEmpty()) throw SyncProblem("pagebound_book_not_found")
        return candidates.singleOrNull() ?: throw SyncProblem("pagebound_book_ambiguous")
    }
}
