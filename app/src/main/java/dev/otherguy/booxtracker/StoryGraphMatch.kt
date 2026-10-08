package dev.otherguy.booxtracker

import java.net.URLEncoder
import org.json.JSONObject

/** One search result from StoryGraph's search fragment: an edition id with its title and author line. */
data class SearchHit(val id: String, val title: String, val authors: String)

/**
 * Matches a source book to one StoryGraph edition. StoryGraph's search is fuzzy and answers an unknown ISBN with an
 * unrelated book, so an identifier hit counts only once the edition's own page shows the same `ISBN/UID`.
 */
class StoryGraphMatcher(private val get: suspend (path: String, frame: String?) -> String, private val store: DiagnosticsStore, private val now: () -> Long = { System.currentTimeMillis() }) {
    suspend fun match(sourceKey: String?, metadata: BookIdentifiers): JSONObject {
        val fingerprint = digest(metadata.json().toString())
        val cacheKey = sourceKey?.let { "storygraph.match.${digest(it)}" }
        val cached = cacheKey?.let { store.get(it) }?.let(::JSONObject)
        if (cached?.optString("fingerprint") == fingerprint && now() - cached.optLong("matchedAt") in 0 until 3_600_000) return cached
        // Evidence is edition id to (normalized title, author key): editions that share both are one book.
        val evidence = linkedMapOf<String, Pair<String?, String?>>()
        metadata.tags["storygraph"].orEmpty().filter { it.matches(uuidPattern) }.sorted().forEach { id ->
            try {
                evidence[id] = bookPage(get("/books/$id", null), id).title?.let(::normalized) to null
            } catch (error: HttpProblem) {
                if (error.status != 404) throw error
            }
        }
        suspend fun confirmed(value: String, accepted: Set<String>): Boolean {
            // The exact identifier hit comes first; later hits are fuzzy, and each confirmation loads a book page.
            val hits = search(value).take(3).filter { hit -> bookPage(get("/books/${hit.id}", null), hit.id).isbnUid?.replace("-", "")?.uppercase() in accepted }
            hits.forEach { evidence[it.id] = normalized(it.title) to authorsKey(it.authors) }
            return hits.isNotEmpty()
        }
        for (isbn in metadata.isbns.sorted()) {
            val isbn10 = isbn10(isbn)
            val accepted = setOfNotNull(isbn, isbn10)
            if (!confirmed(isbn, accepted) && isbn10 != null) confirmed(isbn10, accepted)
        }
        if (evidence.isEmpty()) metadata.tags["asin"].orEmpty().sorted().forEach { confirmed(it, setOf(it)) }
        // StoryGraph has no cheap family id, so sibling editions are recognised by their shared title and author.
        val sameWork = evidence.values.map { it.first }.distinct().size == 1 && evidence.values.mapNotNull { it.second }.distinct().size <= 1
        if (evidence.size > 1 && !sameWork) throw SyncProblem("storygraph_identifier_conflict")
        val bookId = evidence.keys.firstOrNull()
        val result = JSONObject().put("bookId", bookId ?: titleMatch(metadata)).put("matchKind", if (bookId == null) "book" else "edition")
            .put("fingerprint", fingerprint).put("matchedAt", now())
        if (cacheKey != null) store.put(cacheKey, result.toString())
        return result
    }

    private fun authorsKey(authors: String): String = authors.split(',').map(::authorKey).filter { it.isNotEmpty() }.sorted().joinToString("; ")

    private suspend fun search(text: String): List<SearchHit> = searchHits(get("/search?search_term=${URLEncoder.encode(text, "UTF-8")}&button=", "search_results"))

    private suspend fun titleMatch(metadata: BookIdentifiers): String {
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_title_missing")
        val author = metadata.author?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_author_missing")
        val names = authorNames(author).map(::authorKey).filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) throw SyncProblem("book_author_missing")
        val candidates = search("$title $author").filter { hit ->
            normalized(hit.title) == normalized(title) && hit.authors.split(',').map(::authorKey).toSet().containsAll(names)
        }.map { it.id }.distinct()
        if (candidates.isEmpty()) throw SyncProblem("storygraph_book_not_found")
        // Several records with the same title can be editions of one work, but checking that needs the editions page.
        if (candidates.size > 1) throw SyncProblem("storygraph_book_ambiguous")
        return candidates.single()
    }
}
