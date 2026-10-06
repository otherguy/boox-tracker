package dev.otherguy.booxtracker

import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class HardcoverMatchTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private lateinit var server: HardcoverServer
    private lateinit var vault: TokenVault
    private lateinit var auth: HardcoverAuth
    private val metadata = BookIdentifiers(setOf("9781398508255"), "Synthetic Book", "Test Author")

    @Before fun setup() {
        server = HardcoverServer()
        vault = TokenVault(app) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        vault.write(OAuthTokens("access", "refresh", System.currentTimeMillis() + 3_600_000))
        auth = HardcoverAuth(server.http, vault)
    }
    private fun matcher(now: () -> Long = { System.currentTimeMillis() }) = HardcoverMatcher({ q, v -> auth.authorized { server.http.graphql(it, q, v) } }, app.diagnostics.store, now)

    @After fun close() = runBlocking {
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        server.close()
        vault.clear()
        app.diagnostics.store.close()
        closeWorkDatabase()
    }

    @Test fun explicitEditionBookSlugAndAsinIdentifyTheSameBook() = runBlocking {
        for (tag in listOf("hardcover-edition:20", "hardcover-id:10", "hardcover:synthetic-book", "amazon:B07THCSQ27")) {
            val result = matcher().match(null, metadata.copy(isbns = emptySet(), tags = identifierTags(tag)))
            assertEquals(10, result.getInt("bookId"))
        }
    }

    @Test fun goodreadsMappingIdentifiesOnlyTheBookAndDoesNotBecomeAHardcoverId() = runBlocking {
        server.goodreadsBook = 10
        val result = matcher().match(null, metadata.copy(isbns = emptySet(), tags = mapOf("goodreads" to setOf("58438630"))))
        assertEquals(10, result.getInt("bookId"))
        assertTrue(result.isNull("exactEditionId"))
        assertFalse(server.requests.any { it.optString("query").startsWith("query BookIdentifier") })
    }

    @Test fun conflictingExplicitAndExternalIdentifiersHold() = runBlocking {
        server.goodreadsBook = 11
        val problem = assertThrows(SyncProblem::class.java) { runBlocking { matcher().match(null, metadata.copy(tags = mapOf("goodreads" to setOf("58438630")))) } }
        assertEquals("hardcover_identifier_conflict", problem.code)
        assertTrue(server.mutations().isEmpty())
    }

    @Test fun exactTitleAndAuthorRejectOmnibusAndAmbiguousMatches() = runBlocking {
        val noIsbn = metadata.copy(isbns = emptySet())
        server.titleCandidates = listOf(10 to "Synthetic Book", 11 to "Synthetic Book and Another Book")
        assertEquals(10, matcher().match(null, noIsbn).getInt("bookId"))
        server.titleCandidates = listOf(10 to "Synthetic Book", 11 to "synthetic-book")
        assertEquals("hardcover_book_ambiguous", assertThrows(SyncProblem::class.java) { runBlocking { matcher().match(null, noIsbn) } }.code)
        assertEquals("hardcover_book_not_found", assertThrows(SyncProblem::class.java) { runBlocking { matcher().match(null, noIsbn.copy(author = "Another Author")) } }.code)
    }

    @Test fun cacheRevalidatesChangedMetadataAndExpiresForCatalogCorrections() = runBlocking {
        var time = 1L
        val matcher = matcher { time }
        assertEquals(10, matcher.match("stable", metadata).getInt("bookId"))
        val requests = server.requests.size
        matcher.match("stable", metadata)
        assertEquals(requests, server.requests.size)
        server.matchBook = 11
        time += 3_600_000
        assertEquals(11, matcher.match("stable", metadata).getInt("bookId"))
        assertTrue(server.requests.size > requests)
        assertEquals("hardcover_identifier_conflict", assertThrows(SyncProblem::class.java) { runBlocking { matcher.match("stable", metadata.copy(tags = mapOf("hardcover-id" to setOf("10")))) } }.code)
    }
}
