package dev.otherguy.booxtracker

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

class FixtureProvider : ContentProvider() {
    var entered: java.util.concurrent.CountDownLatch? = null
    var release: java.util.concurrent.CountDownLatch? = null
    var calls = java.util.concurrent.atomic.AtomicInteger()
    var denied = false
    var failed = false
    var empty = false
    var nullCursor = false
    var lastCursor: Cursor? = null
    var libraryRecords: List<List<Any?>>? = null
    private var providerIsbn: String? = null
    private var providerIsbnPresent = false

    fun withAccessTimes(vararg books: Pair<String, String?>) = apply {
        libraryRecords = books.map { (name, lastAccess) -> listOf(name, name, "42/100", "1", null, null, lastAccess) }
    }

    fun withSyncBook() = withAccessTimes("Synthetic Book" to "2026-10-06T10:00:00Z").apply {
        providerIsbn = "9781398508255"
        providerIsbnPresent = true
    }
    fun withEpubBook() = apply {
        providerIsbnPresent = true
        libraryRecords = listOf(listOf("Synthetic Book", "Synthetic Book", "42/100", "1", "/books/book.epub", null, "1700000060"))
    }
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        calls.incrementAndGet()
        entered?.countDown()
        release?.await()
        if (denied) throw SecurityException("requires vendor.read permission")
        if (failed) throw IllegalStateException("provider query failed")
        if (nullCursor) return null
        libraryRecords?.let { records ->
            val columns = listOf("name", "uuid", "progress", "readingStatus", "nativeAbsolutePath", "extraAttributes", "lastAccess", "title", "authors") + if (providerIsbnPresent) listOf("ISBN") else emptyList()
            return MatrixCursor(columns.toTypedArray()).apply {
                if (!empty) records.forEach { addRow(it + listOf(it[0], "Test Author") + if (providerIsbnPresent) listOf(providerIsbn) else emptyList()) }
                lastCursor = this
            }
        }
        return MatrixCursor(arrayOf("name", "uuid", "progress", "readingStatus", "nativeAbsolutePath", "extraAttributes")).apply {
            if (!empty) addRow(arrayOf("A book", "book-id", "42/100", "999", "/storage/emulated/0/private/books/book.epub", "{\"current_page_position_v2\":\"321\",\"token\":\"secret\"}"))
            lastCursor = this
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No writes allowed")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("No writes allowed")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("No writes allowed")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class MetadataTest {
    @After fun releaseApplicationResources() = runBlocking {
        val diagnostics = (RuntimeEnvironment.getApplication() as ReadingSyncApp).diagnostics
        diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        diagnostics.store.close()
        closeWorkDatabase()
    }

    private fun fixture(): Pair<FixtureProvider, NeoReaderRepository> {
        val context = RuntimeEnvironment.getApplication() as ReadingSyncApp
        val provider = FixtureProvider()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        return provider to NeoReaderRepository(context)
    }

    @Test fun missingColumnsAndUnknownStatusRemainVisible() {
        val (provider, repository) = fixture()
        val result = repository.read(CancellationSignal())
        assertEquals("success", result.getString("outcome"))
        val book = result.getJSONArray("books").getJSONObject(0)
        assertEquals("missing", book.getJSONObject("lastAccess").getString("state"))
        assertEquals("999", book.raw("readingStatus"))
        assertEquals("42", book.getString("percentage"))
        assertTrue(provider.lastCursor!!.isClosed)
        assertFalse(book.toString().contains("/storage/"))
        assertFalse(book.toString().contains("secret"))
        assertEquals("321", book.getJSONObject("extraAttributes").getString("current_page_position_v2"))
    }

    @Test fun errorsDifferFromAnEmptyLibrary() {
        val (provider, repository) = fixture()
        provider.denied = true
        assertEquals("permission_denied", repository.read(CancellationSignal()).getString("outcome"))
        provider.denied = false
        provider.failed = true
        assertEquals("query_failed", repository.read(CancellationSignal()).getString("outcome"))
        provider.failed = false
        provider.empty = true
        val result = repository.read(CancellationSignal())
        assertEquals("success", result.getString("outcome"))
        assertEquals(0, result.getInt("recordCount"))
        provider.nullCursor = true
        assertTrue(repository.read(CancellationSignal()).getString("outcome") != "success")
    }

    @Test fun failureClassificationAndMissingProgress() {
        assertEquals("provider_unavailable", providerFailure(IllegalArgumentException("unknown uri"), false))
        assertEquals("query_failed", providerFailure(IllegalArgumentException("bad query"), true))
        MatrixCursor(arrayOf("readingStatus")).use { cursor ->
            cursor.addRow(arrayOf("200"))
            cursor.moveToFirst()
            val book = readBook(cursor)
            assertTrue(book.isNull("percentage"))
            assertEquals("missing column", book.getString("progressProblem"))
        }
    }

    @Test fun persistenceAcrossReopen() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as ReadingSyncApp
        context.diagnostics.ensureRecovered()
        val store = context.diagnostics.store
        store.put("test", "retained")
        store.append(org.json.JSONObject().put("source", "manual"))
        store.close()
        DiagnosticsStore(context).use { reopened ->
            assertEquals("retained", reopened.get("test"))
            assertTrue(reopened.events().isNotEmpty())
        }
    }
}
