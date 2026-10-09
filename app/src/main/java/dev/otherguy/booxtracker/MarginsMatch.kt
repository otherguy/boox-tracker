package dev.otherguy.booxtracker

import org.json.JSONObject

/**
 * Matches a source book to one Margins work. A `margins:` tag names the work; each ISBN, and each `goodreads:` edition
 * id, names a work through Margins' catalogue lookups; an ASIN is used only when nothing else identifies the book.
 * Margins has no title search, so a book without such an identifier is not matched.
 */
class MarginsMatch(
    private val query: suspend (List<ZeroQuery>) -> ZeroRows,
    private val store: DiagnosticsStore,
    private val now: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun match(sourceKey: String?, metadata: BookIdentifiers): JSONObject {
        val fingerprint = digest(metadata.json().toString())
        val cacheKey = sourceKey?.let { "margins.match.${digest(it)}" }
        val cached = cacheKey?.let { store.get(it) }?.let(::JSONObject)
        if (cached?.optString("fingerprint") == fingerprint && now() - cached.optLong("matchedAt") in 0 until 3_600_000) return cached
        val tags = metadata.tags["margins"].orEmpty().sorted()
        val isbns = metadata.isbns.sorted()
        val goodreads = metadata.tags["goodreads"].orEmpty().mapNotNull { it.toLongOrNull() }.sorted()
        val asins = metadata.tags["asin"].orEmpty().sorted()
        val queries = tags.map { ZeroQuery("workById", it) } + isbns.map { ZeroQuery("workByISBN13", it) } +
            goodreads.map { ZeroQuery("workByGoodreadsEditionID", it) } + asins.map { ZeroQuery("workByASIN", it) }
        if (queries.isEmpty()) throw SyncProblem("margins_identifier_missing")
        val rows = query(queries)
        val evidence = linkedMapOf<String, MutableSet<String>>()
        fun found(kind: String, work: String?) {
            work?.takeIf { it.isNotBlank() }?.let { evidence.getOrPut(kind) { mutableSetOf() }.add(it) }
        }
        rows.rows("catalog.works").filter { it.optString("work_id") in tags }.forEach { found("tag", it.optString("work_id")) }
        // Margins stores ISBNs with hyphens.
        rows.rows("catalog.work_isbn13s").filter { it.optString("isbn13").replace("-", "") in isbns }.forEach { found("isbn", it.text("work_id")) }
        rows.rows("catalog.goodreads_edition_ids").filter { it.optLong("goodreads_edition_id") in goodreads }.forEach { found("goodreads", it.text("work_id")) }
        val identified = evidence.values.flatten().toSet()
        if (identified.size > 1) throw SyncProblem("margins_identifier_conflict")
        val byAsin = rows.rows("catalog.work_asins").filter { it.optString("asin") in asins }.mapNotNull { it.text("work_id") }.toSet()
        val work = identified.singleOrNull() ?: when (byAsin.size) {
            0 -> throw SyncProblem("margins_book_not_found")
            1 -> byAsin.single().also { found("asin", it) }
            else -> throw SyncProblem("margins_identifier_conflict")
        }
        val result = JSONObject().put("workId", work).put("matchKind", "book").put("matchedBy", evidence.keys.first())
            .put("fingerprint", fingerprint).put("matchedAt", now())
        if (cacheKey != null) store.put(cacheKey, result.toString())
        return result
    }
}
