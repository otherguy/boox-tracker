package dev.otherguy.booxtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Parsers for the Goodreads pages, against sanitised excerpts of the pages observed on 2026-10-08. Runs on Robolectric for `org.json`, without the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GoodreadsPagesTest {
    private fun signedInPage(body: String) = goodreadsHtml("head.html", "TITLE" to "Goodreads", "CSRF" to "token-1") + goodreadsHtml("nav-signed-in.html", "USER_ID" to "42", "SLUG" to "reader") + body

    private fun row(id: String, shelf: String, percent: String?, page: String = "240") = goodreadsHtml(
        "user-works-row.html",
        "BOOK_ID" to id, "TITLE" to "In the Blood (Terminal List, #5)", "AUTHOR" to "Jack Carr", "FORMAT" to "Kindle Edition", "SHELF" to shelf, "SHELF_LABEL" to shelf, "CSRF" to "token-1",
        "PROGRESS_FORM" to (percent?.let { goodreadsHtml("progress-form.html", "BOOK_ID" to id, "PAGE" to page, "PAGES" to "480", "PERCENT" to it, "CSRF" to "token-1", "USER_ID" to "42") } ?: "")
    )

    @Test fun theSignedInHeaderNamesTheAccountAndThePageHasACsrfToken() {
        val page = signedInPage(goodreadsHtml("home.html", "USER_ID" to "42", "SLUG" to "reader"))
        assertTrue(goodreadsSignedIn(page))
        assertEquals("42", goodreadsUserId(page))
        assertEquals("token-1", goodreadsCsrf(page))
        val landing = goodreadsHtml("head.html", "TITLE" to "Goodreads", "CSRF" to "token-1") + goodreadsHtml("landing.html")
        assertFalse(goodreadsSignedIn(landing))
        assertNull(goodreadsUserId(landing))
        assertEquals("goodreads_page_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsCsrf("<html></html>") }.code)
    }

    @Test fun theProfileTitleGivesTheNameAndUsername() {
        val own = goodreadsProfile("<title>Reader Name (reader) (36 books) | Goodreads</title>")
        assertEquals("Reader Name", own.getString("name"))
        assertEquals("reader", own.getString("username"))
        val other = goodreadsProfile("<title>\n  Otis Chandler (otis) - San Francisco, CA (1,651 books) | Goodreads\n</title>")
        assertEquals("Otis Chandler", other.getString("name"))
        assertEquals("otis", other.getString("username"))
        assertFalse(goodreadsProfile("<title>Goodreads</title>").has("username"))
    }

    @Test fun shelvedEditionsCarryTheirExclusiveShelfAndProgress() {
        val page = signedInPage(goodreadsHtml("user-works.html", "TITLE" to "In the Blood", "CSRF" to "token-1", "USER_ID" to "42", "ROWS" to listOf(row("58467253", "currently-reading", "50"), row("60174472", "to-read", null)).joinToString("\n")))
        val editions = goodreadsEditions(page)
        assertEquals("42", editions.account)
        assertEquals("token-1", editions.csrf)
        assertEquals(listOf("58467253", "60174472"), editions.editions.map { it.bookId })
        assertEquals(ShelvedEdition("58467253", "In the Blood (Terminal List, #5)", "currently-reading", 50), editions.edition("58467253"))
        assertEquals(ShelvedEdition("60174472", "In the Blood (Terminal List, #5)", "to-read", null), editions.edition("60174472"))
        // A currently reading edition without any progress update shows an empty field.
        assertEquals(0, goodreadsEditions(signedInPage(goodreadsHtml("user-works.html", "TITLE" to "x", "CSRF" to "token-1", "USER_ID" to "42", "ROWS" to row("1", "currently-reading", "", page = "")))).edition("1")?.percent)
        assertTrue(goodreadsEditions(signedInPage(goodreadsHtml("user-works.html", "TITLE" to "x", "CSRF" to "token-1", "USER_ID" to "42", "ROWS" to ""))).editions.isEmpty())
        assertEquals("goodreads_page_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsEditions(signedInPage("<h1>Something else</h1>")) }.code)
        // Shelf markup in rows the parser does not recognise is a changed page, never an unshelved book.
        val changedRows = row("1", "read", null).replace("itemtype=\"http://schema.org/Book\"", "itemtype=\"https://schema.org/Book\"")
        assertEquals("goodreads_page_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsEditions(signedInPage(goodreadsHtml("user-works.html", "TITLE" to "x", "CSRF" to "token-1", "USER_ID" to "42", "ROWS" to changedRows))) }.code)
        // A page update without a percentage cannot be compared, so it is not read as 0%.
        val pageOnly = row("1", "currently-reading", "")
        assertEquals("goodreads_page_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsEditions(signedInPage(goodreadsHtml("user-works.html", "TITLE" to "x", "CSRF" to "token-1", "USER_ID" to "42", "ROWS" to pageOnly))) }.code)
        assertEquals("goodreads_page_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsEditions(signedInPage(goodreadsHtml("user-works.html", "TITLE" to "x", "CSRF" to "token-1", "USER_ID" to "42", "ROWS" to row("1", "currently-reading", "half")))) }.code)
    }

    @Test fun searchResultsKeepTheirTitleAndContributors() {
        val page = goodreadsHtml(
            "search.html",
            "QUERY" to "in the blood",
            "ITEMS" to listOf(
                goodreadsHtml("search-item.html", "BOOK_ID" to "60174472", "TITLE" to "In the Blood", "AUTHOR" to "Jack Carr"),
                goodreadsHtml("search-item.html", "BOOK_ID" to "253471209", "TITLE" to "In the Blood &amp; Bone", "AUTHOR" to "Other Writer")
            ).joinToString("\n")
        )
        assertEquals(listOf(GoodreadsHit("60174472", "In the Blood", listOf("Jack Carr")), GoodreadsHit("253471209", "In the Blood & Bone", listOf("Other Writer"))), goodreadsSearchHits(page))
        assertTrue(goodreadsSearchHits(goodreadsHtml("search.html", "QUERY" to "x", "ITEMS" to "")).isEmpty())
    }

    @Test fun theBookPageGivesTheEditionsIsbnAndWork() {
        GoodreadsServer().use { server ->
            val page = java.net.URL("${server.origin}/book/show/$GOODREADS_PAPERBACK-in-the-blood").readText()
            assertEquals(GoodreadsBook(GOODREADS_PAPERBACK, "Synthetic Book", "9781398508255", GOODREADS_WORK), goodreadsBook(page, GOODREADS_PAPERBACK))
            // The page's Apollo cache is keyed by the edition id it was asked for.
            assertEquals("goodreads_page_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsBook(page, GOODREADS_EBOOK) }.code)
        }
        assertEquals("goodreads_page_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsBook("<html></html>", "1") }.code)
    }

    @Test fun theReviewEditorStateJoinsTheStreamedRowsAndKeepsTheFullDates() {
        GoodreadsServer().use { server ->
            server.books.getValue(GOODREADS_EBOOK).apply {
                shelf = "currently-reading"
                sessions.add(ReadingSessionFixture("s1", java.time.LocalDate.of(2026, 9, 14)))
            }
            val page = java.net.URL("${server.origin}/review/edit/$GOODREADS_EBOOK").openConnection().apply { setRequestProperty("Cookie", GOODREADS_SESSION_COOKIE) }.getInputStream().readBytes().toString(Charsets.UTF_8)
            // The fake splits the row across two pushes, as Next.js streaming may.
            assertEquals(2, Regex("self.__next_f.push").findAll(page).count())
            val editor = goodreadsReviewEditor(page)
            assertEquals("kca://book/test", editor.bookId)
            assertEquals("kept note", editor.privateNotes)
            assertFalse(editor.ownedEdition)
            assertFalse(editor.hasReview)
            val session = editor.sessions.getJSONObject(0)
            assertEquals(14, session.getJSONObject("startedDate").getInt("day"))
            assertTrue(session.isNull("endedDate"))
            assertEquals(listOf("/_next/static/chunks/4968-0000000000000003.js", "/_next/static/chunks/9067-0000000000000006.js", "/_next/static/chunks/app/review/edit/%5Bid%5D/page-0000000000000004.js"), nextChunkPaths(page))
            server.reviewPresent = true
            val reviewed = java.net.URL("${server.origin}/review/edit/$GOODREADS_EBOOK").openConnection().apply { setRequestProperty("Cookie", GOODREADS_SESSION_COOKIE) }.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(goodreadsReviewEditor(reviewed).hasReview)
        }
        assertEquals("goodreads_editor_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsReviewEditor("<html></html>") }.code)
        fun flight(row: String) = "<script>self.__next_f.push(${org.json.JSONArray().put(1).put(row)})</script>"
        val written = "{\"readingSessions\":[{\"id\":\"r1\",\"bookId\":\"kca://book/b\",\"startedDate\":{\"year\":2026,\"month\":9,\"day\":14},\"endedDate\":null,\"state\":\"READING\"}]"
        val form = "\"initialReadingSessions\":[{\"id\":\"r1\",\"bookId\":\"kca://book/b\",\"startedDate\":\"$2b:props:startedDate\",\"endedDate\":null,\"state\":\"READING\"}]"
        // A review written as a reference is still a review.
        assertTrue(goodreadsReviewEditor(flight("5:$written,\"review\":\"$4\",$form,\"initialPrivateNotes\":\"\"}\n")).hasReview)
        // Notes or a date that stay references would be posted back as text.
        assertEquals("goodreads_editor_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsReviewEditor(flight("5:$written,$form,\"initialPrivateNotes\":\"$7\"}\n")) }.code)
        val unresolved = form.replace("\"id\":\"r1\"", "\"id\":\"r2\"")
        assertEquals("goodreads_editor_unrecognized", assertThrows(SyncProblem::class.java) { goodreadsReviewEditor(flight("5:$written,$unresolved,\"initialPrivateNotes\":\"\"}\n")) }.code)
    }

    @Test fun serverActionsAreFoundByNameAndTheirErrorsRead() {
        assertEquals(GOODREADS_ACTION, serverActionId(goodreadsHtml("chunk-action.js", "ACTION_ID" to GOODREADS_ACTION), "submitReviewFormAction"))
        assertNull(serverActionId(goodreadsHtml("chunk-other.js"), "submitReviewFormAction"))
        assertTrue(serverActionSucceeded("0:{\"a\":\"\$@1\"}\n1:{\"errors\":\"\$Q2\"}\n2:[]\n"))
        assertFalse(serverActionSucceeded("0:{\"a\":\"\$@1\"}\n1:{\"errors\":\"\$Q2\"}\n2:[{\"message\":\"Invalid\"}]\n"))
        assertFalse(serverActionSucceeded("1:{\"errors\":\"\$Q2\"}\n"))
        assertTrue(serverActionSucceeded("0:{\"a\":\"\$@1\"}\n"))
    }

    @Test fun challengesAndSignInRedirectsAreRecognised() {
        assertTrue(isGoodreadsChallenge(mapOf("x-amzn-waf-action" to listOf("challenge"))))
        // A 202 without the WAF header is an accepted request, not a challenge.
        assertFalse(isGoodreadsChallenge(emptyMap()))
        assertTrue(isGoodreadsSignIn("/user/sign_in?returnurl=%2F"))
        assertTrue(isGoodreadsSignIn("https://www.goodreads.com/ap/signin?openid.mode=checkid_setup"))
        assertFalse(isGoodreadsSignIn("/book/show/1"))
        assertFalse(isGoodreadsSignIn(null))
    }
}
