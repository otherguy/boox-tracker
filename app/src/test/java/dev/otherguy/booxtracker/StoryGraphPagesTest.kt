package dev.otherguy.booxtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryGraphPagesTest {
    private val id = STORYGRAPH_EBOOK
    private val forms = listOf("to-read", "currently-reading", "read").joinToString("\n") { storyGraphHtml("status-form.html", "BOOK_ID" to id, "STATUS" to it, "CSRF" to "token-a") }

    private fun bookHtml(label: String?, pane: String = "", other: String = "", isbn: String = "9781398508255"): String {
        val head = storyGraphHtml("head.html", "CSRF" to "token-a", "TITLE" to "Synthetic Book &amp; More by Test Author | The StoryGraph")
        val statusLabel = label?.let { storyGraphHtml("status-label.html", "STATUS" to it) } ?: ""
        return head + storyGraphHtml(
            "book.html",
            "BOOK_ID" to id,
            "TITLE" to "Synthetic Book &amp; More",
            "AUTHOR" to "Test Author",
            "PAGES" to "459",
            "ISBN" to isbn,
            "OTHER_EDITION" to other,
            "STATUS_LABEL" to statusLabel,
            "PROGRESS_PANE" to pane,
            "STATUS_FORMS" to forms
        )
    }

    private fun pane(percent: String, pagesRead: String, pages: String, type: String) = storyGraphHtml("progress-pane.html", "BOOK_ID" to id, "PERCENT" to percent, "PAGES_READ" to pagesRead, "PAGES" to pages, "CSRF" to "token-a", "TYPE_OPTIONS" to progressOptions(type))

    @Test fun headAndNavigationYieldTokenIdentityAndSignedInState() {
        val home = storyGraphHtml("head.html", "CSRF" to "token-a", "TITLE" to "The StoryGraph") + storyGraphHtml("nav-signed-in.html", "USERNAME" to "reader", "CSRF" to "token-a", "USER_ID" to STORYGRAPH_ACCOUNT) +
            storyGraphHtml("home.html", "USERNAME" to "reader", "CURRENT_COUNT" to "1")
        assertEquals("token-a", csrfToken(home))
        assertEquals(STORYGRAPH_ACCOUNT, storyGraphUserId(home))
        assertEquals("reader", storyGraphUsername(home))
        assertTrue(storyGraphSignedIn(home))
        val signIn = storyGraphHtml("head.html", "CSRF" to "token-b", "TITLE" to "Sign In") + storyGraphHtml("sign-in.html", "CSRF" to "token-b")
        assertFalse(storyGraphSignedIn(signIn))
        assertNull(storyGraphUserId(signIn))
        assertEquals("storygraph_page_unrecognized", assertThrows(SyncProblem::class.java) { csrfToken("<html><head></head></html>") }.code)
    }

    @Test fun searchFragmentListsEditionsWithTitlesAndAuthors() {
        val items = storyGraphHtml("search-item.html", "BOOK_ID" to id, "TITLE" to "Synthetic Book &amp; More", "AUTHOR" to "Test Author, Second Author") +
            storyGraphHtml("search-item.html", "BOOK_ID" to STORYGRAPH_OTHER, "TITLE" to "Other", "AUTHOR" to "Someone")
        val fragment = storyGraphHtml("search.html", "TERM" to "9781398508255", "ITEMS" to items)
        assertEquals(listOf(SearchHit(id, "Synthetic Book & More", "Test Author, Second Author"), SearchHit(STORYGRAPH_OTHER, "Other", "Someone")), searchHits(fragment))
        assertEquals(emptyList<SearchHit>(), searchHits(storyGraphHtml("search.html", "TERM" to "x", "ITEMS" to "")))
    }

    @Test fun bookPageReadsTitleIsbnStatusAndProgress() {
        val unshelved = bookPage(bookHtml(null), id)
        assertEquals("Synthetic Book & More", unshelved.title)
        assertEquals("9781398508255", unshelved.isbnUid)
        assertNull(unshelved.status)
        assertNull(unshelved.progress)
        assertEquals("token-a", unshelved.csrf)
        assertNull(bookPage(bookHtml(null, isbn = "None"), id).isbnUid)
        assertEquals("to read", bookPage(bookHtml("to read"), id).status)
        val twoLabels = bookHtml("to read", other = storyGraphHtml("status-label.html", "STATUS" to "read"))
        assertEquals("storygraph_page_unrecognized", assertThrows(SyncProblem::class.java) { bookPage(twoLabels, id) }.code)
        val reading = bookPage(bookHtml("currently reading", pane("51", "234", "459", "percentage")), id)
        assertEquals("currently reading", reading.status)
        assertEquals(ReadingProgress(51, "234", "459", "percentage", 51), reading.progress)
        assertEquals(ReadingProgress(0, "0", "", "pages", 0), bookPage(bookHtml("currently reading", pane("0", "0", "", "pages")), id).progress)
    }

    @Test fun unquotedBookIdAttributesAreReadLikeQuotedOnes() {
        // StoryGraph writes data-book-id without quotes on the edition-info, action-menu and progress blocks.
        val unquoted = bookHtml("currently reading", pane("51", "234", "459", "percentage")).replace("data-book-id=\"$id\"", "data-book-id=$id")
        val page = bookPage(unquoted, id)
        assertEquals("9781398508255", page.isbnUid)
        assertEquals(ReadingProgress(51, "234", "459", "percentage", 51), page.progress)
    }

    @Test fun anUnshelvedEditionNamesTheShelvedOne() {
        val page = bookPage(bookHtml(null, other = storyGraphHtml("other-edition.html", "OTHER_ID" to STORYGRAPH_PAPERBACK)), id)
        assertEquals(STORYGRAPH_PAPERBACK, page.otherEditionId)
        assertNull(bookPage(bookHtml("to read"), id).otherEditionId)
    }

    @Test fun pagesWithoutBookChromeAreRejected() {
        val challenge = storyGraphHtml("challenge.html")
        assertEquals("storygraph_page_unrecognized", assertThrows(SyncProblem::class.java) { bookPage(challenge, id) }.code)
        assertTrue(isChallenge(403, mapOf("cf-mitigated" to listOf("challenge")), ""))
        assertTrue(isChallenge(503, emptyMap(), challenge))
        assertFalse(isChallenge(403, emptyMap(), "Forbidden"))
        assertTrue(isSignInRedirect(302, "https://app.thestorygraph.com/users/sign_in"))
        assertTrue(isSignInRedirect(401, null))
        assertFalse(isSignInRedirect(302, "https://app.thestorygraph.com/"))
        assertFalse(isSignInRedirect(200, null))
    }

    @Test fun entitiesAndTagsAreRemovedFromText() {
        assertEquals("Tom & Jerry's \"Day\" <3", htmlText("<span> Tom &amp; Jerry&#39;s &quot;Day&quot;\n &lt;3 </span>"))
        assertEquals("é", unescape("&#xe9;"))
        assertEquals("&unknown;", unescape("&unknown;"))
        assertEquals("&#x110000;", unescape("&#x110000;"))
        assertEquals("currently_reading", statusKey("Currently reading"))
        assertEquals("to_read", statusKey("to-read"))
        assertNull(statusKey(null))
    }
}
