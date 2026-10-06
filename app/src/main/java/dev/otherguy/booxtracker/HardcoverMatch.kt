package dev.otherguy.booxtracker

import java.text.Normalizer
import org.json.JSONArray
import org.json.JSONObject

fun JSONObject.records(name: String): List<JSONObject> {
    val array = optJSONArray(name) ?: throw SyncProblem("hardcover_response_missing_$name")
    return (0 until array.length()).map { array.getJSONObject(it) }
}

private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

class HardcoverMatcher(private val query: suspend (String, JSONObject) -> JSONObject, private val store: DiagnosticsStore? = null, private val now: () -> Long = { System.currentTimeMillis() }) {
    suspend fun match(sourceKey: String?, metadata: BookIdentifiers): JSONObject {
        val fingerprint = digest(metadata.json().toString())
        val cacheKey = sourceKey?.let { "hardcover.match.${digest(it)}" }
        val cached = cacheKey?.let { store?.get(it) }?.let(::JSONObject)
        if (cached?.optString("fingerprint") == fingerprint && now() - cached.optLong("matchedAt") in 0 until 3_600_000) return cached
        val evidence = mutableListOf<Pair<Int, Int?>>()
        suspend fun edition(id: Int) {
            val value = query("query Edition(\$id: Int!) { editions_by_pk(id: \$id) { id book_id } }", JSONObject().put("id", id)).optJSONObject("editions_by_pk")
            if (value != null) evidence.add(value.getInt("book_id") to value.getInt("id"))
        }
        metadata.tags["hardcover-edition"].orEmpty().forEach { edition(it.toInt()) }
        metadata.tags["hardcover-id"].orEmpty().forEach {
            val value = query("query BookIdentifier(\$id: Int!) { books_by_pk(id: \$id) { id } }", JSONObject().put("id", it.toInt())).optJSONObject("books_by_pk")
            if (value != null) evidence.add(value.getInt("id") to null)
        }
        metadata.tags["hardcover-slug"].orEmpty().forEach {
            query("query BookSlug(\$slug: String!) { books(where: {slug: {_eq: \$slug}}) { id } }", JSONObject().put("slug", it)).records("books").forEach { book -> evidence.add(book.getInt("id") to null) }
        }
        if (metadata.isbns.isNotEmpty() || metadata.tags["asin"].orEmpty().isNotEmpty()) {
            val editions = query(
                "query Match(\$isbns: [String!]!, \$tens: [String!]!, \$asins: [String!]!) { editions(where: {_or: [{isbn_13: {_in: \$isbns}}, {isbn_10: {_in: \$tens}}, {asin: {_in: \$asins}}]}) { id book_id isbn_13 isbn_10 asin pages book { title } } }",
                JSONObject().put("isbns", JSONArray(metadata.isbns.sorted())).put("tens", JSONArray(metadata.isbns.mapNotNull(::isbn10).sorted())).put("asins", JSONArray(metadata.tags["asin"].orEmpty().sorted()))
            ).records("editions")
            editions.forEach {
                val valid = listOf(it.optString("isbn_13"), it.optString("isbn_10")).mapNotNull(::isbn13).any { isbn -> isbn in metadata.isbns } || it.optString("asin") in metadata.tags["asin"].orEmpty()
                if (!valid) throw SyncProblem("hardcover_identifier_response_mismatch")
                evidence.add(it.getInt("book_id") to it.getInt("id"))
            }
        }
        metadata.tags["goodreads"].orEmpty().forEach {
            // External mappings identify the work, not the source ebook's exact edition.
            val mappings = try {
                query("query Goodreads(\$id: String!) { book_mappings(where: {external_id: {_eq: \$id}, platform: {name: {_eq: \"goodreads\"}}}) { book_id } }", JSONObject().put("id", it)).records("book_mappings")
            } catch (error: SyncProblem) {
                if (error.code == "hardcover_graphql_error") emptyList() else throw error
            }
            mappings.forEach { value -> evidence.add(value.getInt("book_id") to null) }
        }
        val ids = evidence.map { it.first }.toSet()
        if (ids.size > 1) throw SyncProblem("hardcover_identifier_conflict")
        val bookId = ids.singleOrNull() ?: titleMatch(metadata)
        val exact = evidence.mapNotNull { it.second }.distinct().singleOrNull()
        val result = JSONObject().put("bookId", bookId).put("exactEditionId", exact ?: JSONObject.NULL)
            .put("matchKind", if (exact == null) "book" else "edition").put("fingerprint", fingerprint).put("matchedAt", now())
        if (cacheKey != null) store?.put(cacheKey, result.toString())
        return result
    }

    private suspend fun titleMatch(metadata: BookIdentifiers): Int {
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_title_missing")
        val author = metadata.author?.takeIf { it.isNotBlank() } ?: throw SyncProblem("book_author_missing")
        val names = runCatching { JSONArray(author).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrElse { author.split(Regex("[;|]")) }.map(::normalized).toSet()
        val candidates = mutableSetOf<Int>()
        for (page in 1..4) {
            val response = query("query TitleSearch(\$text: String!, \$page: Int!) { search(query: \$text, query_type: \"Book\", fields: \"title,author_names,alternative_titles\", weights: \"5,3,2\", typos: \"0,0,0\", per_page: 25, page: \$page) { ids error } }", JSONObject().put("text", "$title $author").put("page", page)).optJSONObject("search") ?: throw SyncProblem("hardcover_search_failed")
            if (!response.isNull("error") && response.optString("error").isNotBlank()) throw SyncProblem("hardcover_search_failed")
            val ids = response.optJSONArray("ids") ?: JSONArray()
            if (ids.length() == 0) break
            val records = query("query Candidates(\$ids: [Int!]!) { books(where: {id: {_in: \$ids}}) { id canonical_id title alternative_titles contributions { author { name } } } }", JSONObject().put("ids", ids)).records("books")
            records.forEach { book ->
                val titles = mutableSetOf(normalized(book.optString("title")))
                book.optJSONArray("alternative_titles")?.let { a -> (0 until a.length()).forEach { titles.add(normalized(a.optString(it))) } }
                val authors = book.records("contributions").mapNotNull { it.optJSONObject("author")?.optString("name") }.map(::normalized).toSet()
                if (normalized(title) in titles && names.isNotEmpty() && authors.containsAll(names)) candidates.add(book.optInt("canonical_id").takeIf { it > 0 } ?: book.getInt("id"))
            }
            if (ids.length() < 25) break
            if (page == 4) throw SyncProblem("hardcover_search_too_broad")
        }
        return candidates.singleOrNull() ?: throw SyncProblem(if (candidates.isEmpty()) "hardcover_book_not_found" else "hardcover_book_ambiguous")
    }
}
