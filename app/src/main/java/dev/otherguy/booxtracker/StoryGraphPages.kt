package dev.otherguy.booxtracker

/** The progress form of a book the user is currently reading. Hidden values stay as text so a write can echo them verbatim. */
data class ReadingProgress(val lastReachedPercent: Int?, val lastReachedPages: String, val bookNumOfPages: String, val progressType: String?, val barPercent: Int?)

/**
 * What the app reads from a StoryGraph book page: the edition's title and `ISBN/UID`, the user's status for this
 * edition, the edition the user shelved instead when this one is unshelved, the page's CSRF token, and the progress form.
 */
data class BookPage(val id: String, val title: String?, val isbnUid: String?, val status: String?, val otherEditionId: String?, val csrf: String, val progress: ReadingProgress?)

private val namedEntities = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")

/** The text of an HTML fragment: tags removed, entities decoded, whitespace collapsed. */
internal fun htmlText(fragment: String): String = unescape(fragment.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

internal fun unescape(text: String): String = text.replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")) { match ->
    val entity = match.groupValues[1]
    val codePoint = when {
        entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
        entity.startsWith("#") -> entity.drop(1).toIntOrNull()
        else -> return@replace namedEntities[entity] ?: match.value
    }
    codePoint?.takeIf(Character::isValidCodePoint)?.let { Character.toChars(it).concatToString() } ?: match.value
}

/** An element `<tag …>…</tag>`, or only one whose class attribute contains [cssClass]. */
private fun element(tag: String, cssClass: String? = null): Regex {
    val classAttribute = cssClass?.let { "[^>]*class=\"[^\"]*\\b$it\\b[^\"]*\"" }.orEmpty()
    return Regex("<$tag\\b$classAttribute[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
}

private fun Regex.text(html: String): String? = find(html)?.groupValues?.get(1)?.let(::htmlText)?.takeIf { it.isNotBlank() }

/** Every opening tag named [name] whose attributes contain [marker], whatever the attribute order. */
private fun tags(html: String, name: String, marker: String): List<String> = Regex("<$name\\b[^>]*>").findAll(html).map { it.value }.filter { it.contains(marker) }.toList()

/** An attribute's value, whether StoryGraph quotes it or not; `data-book-id` is written bare on the page's own blocks. */
private fun attribute(tag: String, name: String): String? = Regex("\\b$name=(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))").find(tag)
    ?.let { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty() }?.let(::unescape)

/** The opening tag of the `div` with [marker] in its attributes that belongs to edition [id]. */
private fun bookDiv(html: String, marker: String, id: String): String? = tags(html, "div", marker).firstOrNull { attribute(it, "data-book-id").equals(id, ignoreCase = true) }

/** The page's CSRF token from its `csrf-token` meta tag. Every StoryGraph page has one. */
internal fun csrfToken(html: String): String = tags(html, "meta", "csrf-token").firstNotNullOfOrNull { tag ->
    attribute(tag, "content")?.takeIf { attribute(tag, "name") == "csrf-token" && it.isNotBlank() }
} ?: throw SyncProblem("storygraph_page_unrecognized")

/** True when the page carries the signed-in chrome: the sign-out form. The sign-in page has none. */
internal fun storyGraphSignedIn(html: String): Boolean = html.contains("action=\"/users/sign_out\"")

/** The account's stable id from the home page, which names it once in an inline script. */
internal fun storyGraphUserId(html: String): String? = Regex("userId:\\s*'([0-9a-fA-F-]{36})'").find(html)?.groupValues?.get(1)

/** The account's username from the first profile link in the navigation. */
internal fun storyGraphUsername(html: String): String? = Regex("href=\"/profile/([A-Za-z0-9_.-]+)\"").find(html)?.groupValues?.get(1)?.takeIf { it != "edit" }

/** The editions listed in a `search_results` turbo frame, in page order. */
internal fun searchHits(fragment: String): List<SearchHit> = element("a", "book-list-option").findAll(fragment).mapNotNull { match ->
    val id = attribute(match.value, "href")?.substringAfter("/books/", "")?.takeIf { it.matches(uuidPattern) } ?: return@mapNotNull null
    val title = element("h1").text(match.groupValues[1]) ?: return@mapNotNull null
    SearchHit(id, title, element("h2").text(match.groupValues[1]).orEmpty())
}.toList()

/** The book page of edition [id]. A page without the book's status forms or heading is not a book page. */
internal fun bookPage(html: String, id: String): BookPage {
    val statusForms = html.contains("/update-status.js?book_id=$id&amp;status=") || html.contains("/update-status.js?book_id=$id&status=")
    val title = element("h3").text(html) ?: element("title").text(html)?.substringBefore(" | The StoryGraph")?.substringBeforeLast(" by ")
    // The page repeats the label for its layouts; two different labels would mean another book's status is on the page.
    val labels = element("button", "read-status-label").findAll(html).mapNotNull { htmlText(it.groupValues[1]).lowercase().takeIf(String::isNotBlank) }.distinct().toList()
    if (labels.size > 1) throw SyncProblem("storygraph_page_unrecognized")
    val status = labels.singleOrNull()
    if (!statusForms && status == null && title == null) throw SyncProblem("storygraph_page_unrecognized")
    val otherEdition = Regex("<a\\b[^>]*href=\"/books/([0-9a-fA-F-]{36})\"[^>]*>([^<]*another edition[^<]*)</a>", RegexOption.IGNORE_CASE).find(html)
        ?.groupValues?.get(1)?.takeIf { !it.equals(id, ignoreCase = true) }
    val editionInfo = bookDiv(html, "edition-info", id)?.let { tag -> html.substring(html.indexOf(tag) + tag.length).substringBefore("</div>", "") }
    val isbnUid = editionInfo?.let { Regex("ISBN/UID:</span>\\s*([^<]*)<").find(it)?.groupValues?.get(1) }?.let(::htmlText)?.takeIf { it.isNotBlank() && it != "None" }
    return BookPage(id, title, isbnUid, status, otherEdition, csrfToken(html), progressPane(html, id))
}

private fun progressPane(html: String, id: String): ReadingProgress? {
    val pane = bookDiv(html, "progress-tracker-pane", id) ?: return null
    val start = html.indexOf(pane)
    val formStart = html.indexOf("action=\"/update-progress\"", start).takeIf { it > 0 } ?: return null
    val formEnd = html.indexOf("</form>", formStart).takeIf { it > 0 } ?: return null
    val form = html.substring(formStart, formEnd)
    fun hidden(name: String): String = tags(form, "input", "read_status[$name]").firstOrNull { attribute(it, "name") == "read_status[$name]" }?.let { attribute(it, "value") }.orEmpty()
    val bar = Regex("style=\"width:\\s*(\\d{1,3})%\"").find(html.substring(start, formStart))?.groupValues?.get(1)?.toIntOrNull()
    val selected = tags(form, "option", "selected=\"selected\"").firstNotNullOfOrNull { attribute(it, "value")?.takeIf(String::isNotEmpty) }
    return ReadingProgress(hidden("last_reached_percent").toIntOrNull(), hidden("last_reached_pages"), hidden("book_num_of_pages"), selected, bar)
}

/** Cloudflare's challenge: the request never reached StoryGraph and only a browser can answer it. */
internal fun isChallenge(status: Int, headers: Map<String, List<String>>, body: String): Boolean {
    val mitigated = headers["cf-mitigated"]?.any { it.contains("challenge", ignoreCase = true) } == true
    return (status == 403 && mitigated) || (status in setOf(403, 503) && body.contains("Just a moment"))
}

/** A redirect to the sign-in page or a 401: the session is gone. */
internal fun isSignInRedirect(status: Int, location: String?): Boolean = status == 401 || (status in 300..399 && location?.contains("/users/sign_in") == true)
