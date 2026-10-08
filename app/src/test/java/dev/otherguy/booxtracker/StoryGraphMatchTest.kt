package dev.otherguy.booxtracker

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class StoryGraphMatchTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: StoryGraphServer
    private lateinit var http: StoryGraphHttp
    private var now = 1_000_000L

    @Before fun setup() {
        server = StoryGraphServer()
        http = StoryGraphHttp(storyGraphSession(store, server))
    }

    @After fun close() {
        server.close()
        org.robolectric.shadows.ShadowCookieManager.resetCookies()
        store.close()
        closeWorkDatabase()
    }

    private fun matcher() = StoryGraphMatcher({ path, frame -> http.get(path, frame) }, store) { now }
    private fun identifiers(isbns: Set<String> = emptySet(), title: String? = "Synthetic Book", author: String? = "Test Author", tags: Map<String, Set<String>> = emptyMap()) = BookIdentifiers(isbns, title, author, tags)
    private fun searches() = server.requests.filter { it.getString("path") == "/search" }
    private fun pages() = server.requests.filter { it.getString("path").startsWith("/books/") }

    @Test fun explicitUuidTagMatchesTheEditionWithoutSearching() = runBlocking {
        val result = matcher().match("key", identifiers(tags = mapOf("storygraph" to setOf(STORYGRAPH_EBOOK, "not-a-uuid"))))
        assertEquals(STORYGRAPH_EBOOK, result.getString("bookId"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(0, searches().size)
        assertEquals(listOf("/books/$STORYGRAPH_EBOOK"), pages().map { it.getString("path") })
    }

    @Test fun anIsbnHitCountsOnlyWhenTheEditionPageShowsTheSameIsbn() = runBlocking {
        val result = matcher().match("key", identifiers(setOf("9781398508255"), title = null, author = null))
        assertEquals(STORYGRAPH_EBOOK, result.getString("bookId"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals("search_results", searches().single().getString("frame"))
        assertTrue(searches().single().getString("query").contains("search_term=9781398508255"))
        // An unknown ISBN gets a fuzzy hit whose page shows another ISBN, so the identifier is no evidence and the title decides.
        server.requests.clear()
        val fuzzy = matcher().match("other", identifiers(setOf("9789999999991")))
        assertEquals("book", fuzzy.getString("matchKind"))
        assertEquals(STORYGRAPH_EBOOK, fuzzy.getString("bookId"))
        assertEquals(listOf("/books/$STORYGRAPH_OTHER"), pages().map { it.getString("path") }.take(1))
    }

    @Test fun isbn10EditionsAndAsinsAreConfirmedTheSameWay() = runBlocking {
        server.book("00000000-0000-4000-8000-00000000a010", "0743297334", title = "Sun")
        val byIsbn10Uid = matcher().match("a", identifiers(setOf("9780743297332"), title = null, author = null))
        assertEquals("00000000-0000-4000-8000-00000000a010", byIsbn10Uid.getString("bookId"))
        assertEquals(listOf("9780743297332", "0743297334"), searches().map { it.getString("query").substringAfter("search_term=").substringBefore('&') })
        server.requests.clear()
        server.book("00000000-0000-4000-8000-00000000a011", "B000FC0SIM", title = "Asin Book")
        val byAsin = matcher().match("b", identifiers(tags = mapOf("asin" to setOf("B000FC0SIM")), title = null, author = null))
        assertEquals("00000000-0000-4000-8000-00000000a011", byAsin.getString("bookId"))
        assertEquals("edition", byAsin.getString("matchKind"))
    }

    @Test fun conflictingConfirmedIdentifiersHold() = runBlocking {
        assertEquals("storygraph_identifier_conflict", held { matcher().match("key", identifiers(setOf("9781398508255", "9780000000009"))) })
    }

    @Test fun editionsOfOneWorkEstablishTheBook() = runBlocking {
        // An ebook can carry its own ISBN and the one NeoReader shows; both confirm editions with one title and author.
        val result = matcher().match("key", identifiers(setOf("9781398508255", "9780000000002"), title = null, author = null))
        // The first confirmed edition in ISBN order is the match; a shelved sibling still receives the progress.
        assertEquals(STORYGRAPH_PAPERBACK, result.getString("bookId"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(2, pages().size)
    }

    @Test fun titleAndAuthorMatchOneBookOrHold() = runBlocking {
        val result = matcher().match("key", identifiers(title = "synthetic book", author = "Author, Test"))
        assertEquals(STORYGRAPH_EBOOK, result.getString("bookId"))
        assertEquals("book", result.getString("matchKind"))
        server.book("00000000-0000-4000-8000-00000000a012", null, title = "Synthetic Book", author = "Test Author")
        assertEquals("storygraph_book_ambiguous", held { matcher().match("two", identifiers()) })
        assertEquals("storygraph_book_not_found", held { matcher().match("none", identifiers(title = "Unknown Title")) })
        assertEquals("book_author_missing", held { matcher().match("noauthor", identifiers(author = null)) })
        assertEquals("book_title_missing", held { matcher().match("notitle", identifiers(title = null)) })
    }

    @Test fun matchesAreCachedForAnHourPerFingerprint() = runBlocking {
        val first = matcher().match("key", identifiers(setOf("9781398508255")))
        server.requests.clear()
        assertEquals(first.toString(), matcher().match("key", identifiers(setOf("9781398508255"))).toString())
        assertTrue(server.requests.isEmpty())
        now += 3_600_000
        matcher().match("key", identifiers(setOf("9781398508255")))
        assertTrue(server.requests.isNotEmpty())
    }
}
