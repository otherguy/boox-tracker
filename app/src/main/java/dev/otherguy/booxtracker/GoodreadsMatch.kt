package dev.otherguy.booxtracker

import java.net.URLEncoder
import org.json.JSONObject

/** One result of Goodreads' search page: an edition id with its title and contributor names. */
data class GoodreadsHit(val id: String, val title: String, val authors: List<String>)

/**
 * Matches a source book to one Goodreads edition and the work it belongs to. Goodreads book ids are edition ids; an
 * identifier hit counts only once the edition's own page shows the same ISBN-13, and every hit must share one work.
 */
class GoodreadsMatcher(private val get: suspend (String) -> GoodreadsPage, private val store: DiagnosticsStore? = null, private val now: () -> Long = { System.currentTimeMillis() }) {
    suspend fun match(sourceKey: String?, metadata: BookIdentifiers): JSONObject {
        val fingerprint = digest(metadata.json().toString())
        val cacheKey = sourceKey?.let { "goodreads.match.${digest(it)}" }
        val cached = cacheKey?.let { store?.get(it) }?.let(::JSONObject)
        if (cached?.optString("fingerprint") == fingerprint && now() - cached.optLong("matchedAt") in 0 until 3_600_000) return cached
        val evidence = linkedMapOf<String, GoodreadsBook>()
        metadata.tags["goodreads"].orEmpty().sortedBy { it.toLong() }.forEach { id ->
            try {
                evidence[id] = book(id)
            } catch (error: HttpProblem) {
                if (error.status != 404) throw error
            }
        }
        for (isbn in metadata.isbns.sorted()) {
            if (!confirmed(isbn, isbn, evidence)) isbn10(isbn)?.let { confirmed(it, isbn, evidence) }
        }
        if (evidence.values.map { it.workId }.distinct().size > 1) throw SyncProblem("goodreads_identifier_conflict")
        val found = evidence.values.firstOrNull() ?: titleMatch(metadata)
        val result = JSONObject().put("bookId", found.id).put("workId", found.workId).put("title", found.title ?: JSONObject.NULL).put("matchKind", if (evidence.isEmpty()) "book" else "edition")
            .put("editions", org.json.JSONArray(evidence.keys.toList()))
            .put("fingerprint", fingerprint).put("matchedAt", now())
        if (cacheKey != null) store?.put(cacheKey, result.toString())
        return result
    }

    private suspend fun book(id: String): GoodreadsBook = goodreadsBook(get("/book/show/$id").text, id)

    private suspend fun search(query: String): GoodreadsPage = get("/search?q=${URLEncoder.encode(query, "UTF-8")}")

    /**
     * Searches [query] and keeps the hits whose own page shows [isbn]. An exact match can arrive as the book page itself;
     * otherwise the first three results are checked. An ASIN is not searched: Goodreads search does not find ebook ASINs.
     */
    private suspend fun confirmed(query: String, isbn: String, evidence: MutableMap<String, GoodreadsBook>): Boolean {
        val page = search(query)
        val landed = Regex("^/book/show/(\\d+)").find(page.path)?.groupValues?.get(1)
        val books = if (landed != null) listOf(goodreadsBook(page.text, landed)) else goodreadsSearchHits(page.text).take(3).map { book(it.id) }
        val hits = books.filter { it.isbn13 == isbn }
        hits.forEach { evidence[it.id] = it }
        return hits.isNotEmpty()
    }

    /** One normalized title and author match. Goodreads lists one edition per work, so several matches are several works. */
    private suspend fun titleMatch(metadata: BookIdentifiers): GoodreadsBook {
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_title_missing")
        val author = metadata.author?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_author_missing")
        val names = authorNames(author).map(::authorKey).filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) throw SyncProblem("book_author_missing")
        val candidates = goodreadsSearchHits(search("$title $author").text).filter { hit ->
            normalized(hit.title) == normalized(title) && hit.authors.map(::authorKey).toSet().containsAll(names)
        }
        if (candidates.isEmpty()) throw SyncProblem("goodreads_book_not_found")
        if (candidates.size > 3) throw SyncProblem("goodreads_book_ambiguous")
        val books = candidates.map { book(it.id) }
        if (books.map { it.workId }.distinct().size > 1) throw SyncProblem("goodreads_book_ambiguous")
        return books.first()
    }
}
