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
class GoodreadsMatchTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val store get() = app.diagnostics.store
    private lateinit var server: GoodreadsServer
    private lateinit var http: GoodreadsHttp
    private var now = 1_000_000L

    @Before fun setup() {
        server = GoodreadsServer()
        http = GoodreadsHttp(goodreadsSession(store, server, FakeWebProfile()), FakeRefresher())
    }

    @After fun close() {
        server.close()
        store.close()
        closeWorkDatabase()
    }

    private fun matcher() = GoodreadsMatcher({ http.get(it) }, store) { now }
    private fun identifiers(isbns: Set<String> = emptySet(), title: String? = "Synthetic Book", author: String? = "Test Author", tags: Map<String, Set<String>> = emptyMap()) = BookIdentifiers(isbns, title, author, tags)
    private fun searches() = server.requests.filter { it.getString("path") == "/search" }.map { it.getString("query") }
    private fun pages() = server.requests.filter { it.getString("path").startsWith("/book/show/") }.map { it.getString("path") }

    @Test fun anExplicitGoodreadsTagMatchesTheEditionWithoutSearching() = runBlocking {
        val result = matcher().match("key", identifiers(tags = mapOf("goodreads" to setOf(GOODREADS_PAPERBACK, "999"))))
        assertEquals(GOODREADS_PAPERBACK, result.getString("bookId"))
        assertEquals(GOODREADS_WORK, result.getString("workId"))
        assertEquals("edition", result.getString("matchKind"))
        assertTrue(searches().isEmpty())
        assertEquals(listOf("/book/show/999", "/book/show/$GOODREADS_PAPERBACK"), pages())
    }

    @Test fun anIsbnHitCountsOnlyWhenTheEditionPageShowsTheSameIsbn() = runBlocking {
        val result = matcher().match("key", identifiers(setOf("9781398508255"), title = null, author = null))
        assertEquals(GOODREADS_PAPERBACK, result.getString("bookId"))
        assertEquals(GOODREADS_WORK, result.getString("workId"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(listOf("q=9781398508255"), searches())
        // A search hit whose own page shows another ISBN is no evidence, so the title decides.
        server.books.getValue(GOODREADS_PAPERBACK).isbn13 = "9780000000002"
        server.book("70000001", GOODREADS_OTHER_WORK, "9781398508255", title = "Mislabelled", author = "Someone")
        server.books.getValue("70000001").isbn13 = "9789999999991"
        val fuzzy = matcher().match("other", identifiers(setOf("9789999999991")))
        assertEquals("edition", fuzzy.getString("matchKind"))
        assertEquals("70000001", fuzzy.getString("bookId"))
    }

    @Test fun anIsbn10SearchIsTriedWhenTheIsbn13FindsNothing() = runBlocking {
        server.books.getValue(GOODREADS_EBOOK).isbn13 = "9781982181680"
        // The fake answers an ISBN-10 query with the edition whose ISBN-13 it converts to.
        val result = matcher().match("key", identifiers(setOf("9781982181680"), title = null, author = null))
        assertEquals(GOODREADS_EBOOK, result.getString("bookId"))
        server.requests.clear()
        server.books.getValue(GOODREADS_EBOOK).isbn13 = null
        assertEquals("book_title_missing", held { matcher().match("none", identifiers(setOf("9781982181680"), title = null, author = null)) })
        assertEquals(listOf("q=9781982181680", "q=1982181680"), searches())
    }

    @Test fun anExactSearchThatLandsOnTheBookPageIsReadFromThatPage() = runBlocking {
        server.exactSearchLocation = true
        val result = matcher().match("key", identifiers(setOf("9781982181680")))
        assertEquals(GOODREADS_EBOOK, result.getString("bookId"))
        assertEquals(listOf("/book/show/$GOODREADS_EBOOK"), pages())
    }

    @Test fun editionsOfOneWorkAreOneBookAndDifferentWorksConflict() = runBlocking {
        val both = matcher().match("key", identifiers(setOf("9781982181680", "9781398508255")))
        assertEquals("edition", both.getString("matchKind"))
        assertEquals(GOODREADS_WORK, both.getString("workId"))
        assertEquals(GOODREADS_PAPERBACK, both.getString("bookId"))
        assertEquals("goodreads_identifier_conflict", held { matcher().match("conflict", identifiers(setOf("9781982181680", "9780000000019"))) })
    }

    @Test fun titleAndAuthorMatchOneWorkOrHold() = runBlocking {
        val result = matcher().match("key", identifiers(title = "Synthetic Book", author = "Author, Test"))
        assertEquals("book", result.getString("matchKind"))
        assertEquals(GOODREADS_EBOOK, result.getString("bookId"))
        assertEquals(GOODREADS_WORK, result.getString("workId"))
        assertEquals("Synthetic Book", result.getString("title"))
        assertEquals("goodreads_book_not_found", held { matcher().match("missing", identifiers(title = "Nothing Like It")) })
        assertEquals("goodreads_book_not_found", held { matcher().match("wrong", identifiers(author = "Somebody Else")) })
        server.book("70000002", "91700002", null)
        assertEquals("goodreads_book_ambiguous", held { matcher().match("two", identifiers()) })
    }

    @Test fun aMatchIsCachedForAnHourAndAChangedSourceMatchesAgain() = runBlocking {
        matcher().match("key", identifiers(setOf("9781398508255")))
        val count = server.requests.size
        now += 3_599_999
        matcher().match("key", identifiers(setOf("9781398508255")))
        assertEquals(count, server.requests.size)
        matcher().match("key", identifiers(setOf("9781982181680")))
        assertTrue(server.requests.size > count)
        val again = server.requests.size
        now += 3_600_000
        matcher().match("key", identifiers(setOf("9781982181680")))
        assertTrue(server.requests.size > again)
    }
}
