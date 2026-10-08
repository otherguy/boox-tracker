package dev.otherguy.booxtracker

import java.net.URLEncoder
import org.json.JSONObject

internal fun JSONObject.items(name: String): List<JSONObject> {
    val array = optJSONArray(name) ?: throw SyncProblem("fable_response_missing_$name")
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** Word-order-independent author key, so "Carr, Jack" and "Jack Carr" compare equal. */
internal fun authorKey(name: String) = normalized(name).split(' ').filter { it.isNotEmpty() }.sorted().joinToString(" ")

/** Matches a source book to one Fable book record. Each Fable book record is one edition. */
class FableMatcher(private val get: suspend (String) -> JSONObject, private val store: DiagnosticsStore? = null, private val now: () -> Long = { System.currentTimeMillis() }) {
    suspend fun match(sourceKey: String?, metadata: BookIdentifiers): JSONObject {
        val fingerprint = digest(metadata.json().toString())
        val cacheKey = sourceKey?.let { "fable.match.${digest(it)}" }
        val cached = cacheKey?.let { store?.get(it) }?.let(::JSONObject)
        if (cached?.optString("fingerprint") == fingerprint && now() - cached.optLong("matchedAt") in 0 until 3_600_000) return cached
        // Evidence is (book id, priority); a lower priority is the stronger identifier.
        val evidence = mutableListOf<Pair<String, Int>>()
        metadata.tags["fable"].orEmpty().filter { it.matches(uuidPattern) }.sorted().forEach { id ->
            val book = try {
                get("/api/books/$id").optJSONObject("response")
            } catch (error: HttpProblem) {
                if (error.status == 404) null else throw error
            }
            book?.optString("id")?.takeIf { it.isNotBlank() }?.let { evidence.add(it to 0) }
        }
        suspend fun exact(value: String, priority: Int): Boolean {
            val hits = search(value).filter { book -> listOf(book.optString("isbn"), book.optString("display_isbn")).any { it.trim().uppercase() == value } }
            hits.forEach { evidence.add(it.getString("id") to priority) }
            return hits.isNotEmpty()
        }
        for (isbn in metadata.isbns.sorted()) {
            if (!exact(isbn, 1)) isbn10(isbn)?.let { exact(it, 2) }
        }
        if (evidence.isEmpty()) metadata.tags["asin"].orEmpty().sorted().forEach { exact(it, 3) }
        val ids = evidence.map { it.first }.distinct()
        val bookId = when {
            ids.isEmpty() -> null

            ids.size == 1 -> ids.single()

            ids.size > 5 -> throw SyncProblem("fable_identifier_conflict")

            else -> {
                val families = ids.map { get("/api/books/$it").optJSONObject("response")?.opt("family_id")?.takeUnless { family -> family == JSONObject.NULL } }
                if (families.any { it == null } || families.distinct().size != 1) throw SyncProblem("fable_identifier_conflict")
                evidence.sortedWith(compareBy({ it.second }, { it.first })).first().first
            }
        }
        val result = JSONObject().put("bookId", bookId ?: titleMatch(metadata)).put("matchKind", if (bookId == null) "book" else "edition")
            .put("fingerprint", fingerprint).put("matchedAt", now())
        if (cacheKey != null) store?.put(cacheKey, result.toString())
        return result
    }

    private suspend fun search(text: String): List<JSONObject> = get("/api/books/search/?auto=${URLEncoder.encode(text, "UTF-8")}&include=out_of_catalog&type=book&limit=20&offset=0")
        .optJSONObject("response")?.items("books") ?: throw SyncProblem("fable_search_failed")

    private suspend fun titleMatch(metadata: BookIdentifiers): String {
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_title_missing")
        val author = metadata.author?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_author_missing")
        val names = authorNames(author).map(::authorKey).filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) throw SyncProblem("book_author_missing")
        val candidates = search("$title $author").filter { book ->
            normalized(book.optString("title")) == normalized(title) &&
                (if (book.has("authors")) book.items("authors") else emptyList()).map { authorKey(it.optString("name")) }.toSet().containsAll(names)
        }.map { it.getString("id") }.distinct()
        if (candidates.isEmpty()) throw SyncProblem("fable_book_not_found")
        if (candidates.size == 1) return candidates.single()
        // Several matching records are acceptable only as editions of one family.
        val editions = get("/api/books/${candidates.first()}/editions/").items("results").associateBy { it.optString("id") }
        if (!editions.keys.containsAll(candidates)) throw SyncProblem("fable_book_ambiguous")
        return candidates.sortedWith(
            compareByDescending<String> { editions.getValue(it).let { e -> e.optJSONObject("format")?.optString("category") == "eBook" && e.optInt("page_count") > 0 } }
                .thenByDescending { editions.getValue(it).optBoolean("is_current_book") }
                .thenBy { it }
        ).first()
    }
}
