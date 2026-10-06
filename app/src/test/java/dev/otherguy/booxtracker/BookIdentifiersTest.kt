package dev.otherguy.booxtracker

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class BookIdentifiersTest {
    @get:Rule val folder = TemporaryFolder()

    @After fun cleanup() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        app.diagnostics.store.close()
        closeWorkDatabase()
    }

    private fun epub(identifier: String, container: String? = null, doctype: String = ""): File {
        val file = folder.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            add("META-INF/container.xml", container ?: """<container><rootfiles><rootfile media-type="application/oebps-package+xml" full-path="OPS/book.opf"/></rootfiles></container>""")
            add("OPS/book.opf", """$doctype<package xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf"><metadata><dc:title>Synthetic Book</dc:title><dc:creator>Test Author</dc:creator>$identifier</metadata></package>""")
        }
        return file
    }

    @Test fun nonEpubWithoutIsbnKeepsDatabaseTitleAndAuthor() {
        val app = RuntimeEnvironment.getApplication() as ReadingSyncApp
        fun raw(value: String) = org.json.JSONObject().put("state", "value").put("raw", value)
        val book = org.json.JSONObject().put("filename", raw("example.pdf")).put("title", raw("Synthetic Book")).put("authors", raw("Test Author"))
        val identifiers = BookIdentifierRepository(app, app.diagnostics.store).read(book)
        assertEquals("Synthetic Book", identifiers.title)
        assertEquals("Test Author", identifiers.author)
        assertEquals(emptySet<String>(), identifiers.isbns)
    }

    @Test fun serviceTagsAndHardcoverUrlsRemainDistinctAndSafe() {
        val tags = identifierTags("isbn:139850825X, goodreads:58438630, amazon:B07THCSQ27, hardcover-id:510350, hardcover-edition:33373487, hardcover:in-the-blood-2022, storygraph:book-123, fable:synthetic-book, margins:book_456, password:secret")
        assertEquals(setOf("9781398508255"), tags["isbn"])
        assertEquals(setOf("58438630"), tags["goodreads"])
        assertEquals(setOf("B07THCSQ27"), tags["asin"])
        assertEquals(setOf("510350"), tags["hardcover-id"])
        assertEquals(setOf("33373487"), tags["hardcover-edition"])
        assertEquals(setOf("in-the-blood-2022"), tags["hardcover-slug"])
        assertEquals(setOf("book-123"), tags["storygraph"])
        assertEquals(setOf("synthetic-book"), tags["fable"])
        assertEquals(setOf("book_456"), tags["margins"])
        assertEquals(null, tags["password"])
        assertEquals(setOf("33373487"), identifierTags("https://hardcover.app/books/in-the-blood-2022/editions/33373487")["hardcover-edition"])
        assertEquals(setOf("in-the-blood-2022"), identifierTags("https://hardcover.app/books/in-the-blood-2022/")["hardcover-slug"])
        assertEquals(emptyMap<String, Set<String>>(), identifierTags("amazon:bf8f17c2-8914-426c-b981-93b0a7c81b15"))
        assertEquals(emptyMap<String, Set<String>>(), identifierTags("storygraph:https://example.com/private, fable:../private, margins:secret value"))
    }

    @Test fun validChecksumsAndEquivalentIsbnTen() {
        assertEquals("9781398508255", isbn13("isbn:978-1-3985-0825-5"))
        assertEquals("9781471197376", isbn13("9781471197376"))
        assertEquals("9780804429573", isbn13("0-8044-2957-X"))
        listOf("9781398508256", "0804429570", "goodreads:58438630", "9781982181680.opf", "UUID", "1234567890123").forEach { assertNull(isbn13(it)) }
    }

    @Test fun epubThreePrefixAndEpubTwoSchemeAreReadFromMetadata() {
        val three = readEpubIdentifiers(epub("""<dc:identifier id="eisbn">uuid-not-an-isbn</dc:identifier><dc:identifier>isbn:9781398508255</dc:identifier><dc:identifier>goodreads:58438630</dc:identifier>"""))
        assertEquals(setOf("9781398508255"), three.isbns)
        assertEquals("Synthetic Book", three.title)
        assertEquals("Test Author", three.author)
        val two = readEpubIdentifiers(epub("""<dc:identifier opf:scheme="ISBN">9781471197376</dc:identifier><dc:identifier opf:scheme="GOODREADS">58895717</dc:identifier>"""))
        assertEquals(setOf("9781471197376"), two.isbns)
    }

    @Test fun malformedUnsafeAndExternalEntityMetadataCannotSupplyIdentifiers() {
        assertThrows(Exception::class.java) { readEpubIdentifiers(epub("<dc:identifier>")) }
        assertEquals(
            "epub_unsafe_metadata_path",
            assertThrows(SyncProblem::class.java) {
                readEpubIdentifiers(epub("", """<container><rootfile media-type="application/oebps-package+xml" full-path="../book.opf"/></container>"""))
            }.code
        )
        assertThrows(Exception::class.java) { readEpubIdentifiers(epub("", doctype = """<!DOCTYPE package [<!ENTITY external SYSTEM "file:///private/secret">]>""")) }
        assertEquals(emptySet<String>(), readEpubIdentifiers(epub("""<dc:identifier id="9781398508255">not-an-isbn</dc:identifier>""")).isbns)
    }

    @Test fun editionPageConversionUsesRawFractionAndRejectsUnknown() {
        assertEquals(237, editionPages("4723/10000", 502))
        assertEquals(1, editionPages("1/200", 100))
        assertEquals(0, editionPages("0/100", 500))
        listOf(null, "1/0", "101/100", "unknown").forEach { assertThrows(SyncProblem::class.java) { editionPages(it, 500) } }
        assertThrows(SyncProblem::class.java) { editionPages("1/2", 0) }
    }

    @Test fun explicitNonIsbnSchemeCannotSupplyAnIsbn() {
        val value = readEpubIdentifiers(epub("""<dc:identifier opf:scheme="GOODREADS">9781398508255</dc:identifier>"""))
        assertEquals(emptySet<String>(), value.isbns)
    }
}
