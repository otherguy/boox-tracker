package dev.otherguy.booxtracker

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class FableMatchTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private lateinit var server: FableServer
    private var clock = 1_000_000L

    @Before fun setup() {
        server = FableServer()
    }

    @After fun close() = runBlocking {
        server.close()
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        app.diagnostics.store.close()
        closeWorkDatabase()
    }

    private fun matcher(store: DiagnosticsStore? = null) = FableMatcher({ server.http.get("old-id", it) }, store) { clock }
    private fun match(identifiers: BookIdentifiers, key: String? = null, store: DiagnosticsStore? = null) = runBlocking { matcher(store).match(key, identifiers) }
    private fun held(identifiers: BookIdentifiers) = assertThrows(SyncProblem::class.java) { match(identifiers) }.code
    private fun searches() = server.requests.count { it.getString("path") == "/api/books/search/" }

    @Test fun explicitFableIdResolvesWithoutSearchAndOtherTagsAreIgnored() {
        val result = match(BookIdentifiers(emptySet(), null, null, mapOf("fable" to setOf(FABLE_PAPERBACK, "fable-456"))))
        assertEquals(FABLE_PAPERBACK, result.getString("bookId"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(0, searches())
    }

    @Test fun isbnSearchKeepsOnlyTheExactRecord() {
        val result = match(BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author"))
        assertEquals(FABLE_EBOOK, result.getString("bookId"))
        assertEquals("edition", result.getString("matchKind"))
        assertEquals(1, searches())
    }

    @Test fun isbnTenAndAsinAreTriedOnlyWhenNeeded() {
        server.books.clear()
        server.book(FABLE_EBOOK, "139850825X")
        server.book(FABLE_OTHER, "B07THCSQ27", family = 2)
        assertEquals(FABLE_EBOOK, match(BookIdentifiers(setOf("9781398508255"), null, null, mapOf("asin" to setOf("B07THCSQ27")))).getString("bookId"))
        assertEquals(2, searches())
        server.requests.clear()
        assertEquals(FABLE_OTHER, match(BookIdentifiers(setOf("9790000000001"), null, null, mapOf("asin" to setOf("B07THCSQ27")))).getString("bookId"))
    }

    @Test fun identifiersAcrossFamiliesConflictButEditionsOfOneFamilyAgree() {
        assertEquals("fable_identifier_conflict", held(BookIdentifiers(setOf("9781398508255", "9780000000009"), null, null)))
        assertEquals(FABLE_PAPERBACK, match(BookIdentifiers(setOf("9781398508255"), null, null, mapOf("fable" to setOf(FABLE_PAPERBACK)))).getString("bookId"))
        assertEquals(FABLE_EBOOK, match(BookIdentifiers(setOf("9781398508255", "9780000000002"), null, null)).getString("bookId"))
    }

    @Test fun titleAndAuthorPreferTheEbookOfOneFamily() {
        server.book(FABLE_OTHER, "9780000000009", title = "Unrelated Book")
        val result = match(BookIdentifiers(emptySet(), "Synthetic Book", "Author, Test"))
        assertEquals(FABLE_EBOOK, result.getString("bookId"))
        assertEquals("book", result.getString("matchKind"))
    }

    @Test fun titleMatchesAcrossFamiliesOrWithoutResultsHold() {
        server.book(FABLE_OTHER, "9780000000009", author = "Test Author", family = 2)
        assertEquals("fable_book_ambiguous", held(BookIdentifiers(emptySet(), "Synthetic Book", "Test Author")))
        assertEquals("fable_book_not_found", held(BookIdentifiers(emptySet(), "Synthetic Book", "Nobody Known")))
        assertEquals("fable_book_not_found", held(BookIdentifiers(emptySet(), "Synthetic Book: Omnibus", "Test Author")))
        assertEquals("book_author_missing", held(BookIdentifiers(emptySet(), "Synthetic Book", null)))
    }

    @Test fun cachedMatchIsReusedUntilMetadataChangesOrAnHourPasses() {
        val store = app.diagnostics.store
        val identifiers = BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author")
        match(identifiers, "book-key", store)
        match(identifiers, "book-key", store)
        assertEquals(1, searches())
        match(identifiers.copy(title = "Synthetic Book Revised"), "book-key", store)
        assertEquals(2, searches())
        clock += 3_600_000
        match(identifiers.copy(title = "Synthetic Book Revised"), "book-key", store)
        assertEquals(3, searches())
        assertTrue(store.get("fable.match.${digest("book-key")}") != null)
    }
}
