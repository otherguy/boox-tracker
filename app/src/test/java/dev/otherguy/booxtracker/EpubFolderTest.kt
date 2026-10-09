package dev.otherguy.booxtracker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

internal class FolderProvider(private val file: File) : ContentProvider() {
    var duplicate = false
    var denied = false
    var opens = 0
    var openedOnMainThread = false
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (denied) throw SecurityException("Read grant revoked")
        return MatrixCursor(projection).apply {
            if (!uri.path.orEmpty().endsWith("/children")) {
                addRow(arrayOf(DocumentsContract.Document.MIME_TYPE_DIR))
            } else if (DocumentsContract.getDocumentId(uri) == "books") {
                addRow(arrayOf<Any?>("sub", "Subfolder", DocumentsContract.Document.MIME_TYPE_DIR, 100, 0))
                if (duplicate) addRow(arrayOf<Any?>("other", "book.epub", "application/epub+zip", 100, file.length()))
            } else {
                addRow(arrayOf<Any?>("book", "book.epub", "application/epub+zip", 100, file.length()))
            }
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check(mode == "r")
        opens++
        openedOnMainThread = Thread.currentThread() == android.os.Looper.getMainLooper().thread
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/epub+zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri = error("No writes")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("No writes")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No writes")
}

internal fun grantEpubFolder(app: ReadingSyncApp): FolderProvider {
    val file = File(app.cacheDir, "synthetic.epub")
    ZipOutputStream(file.outputStream()).use { zip ->
        listOf(
            "META-INF/container.xml" to """<container><rootfile media-type="application/oebps-package+xml" full-path="book.opf"/></container>""",
            "book.opf" to """<package xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:identifier>isbn:9781398508255, goodreads:58438630, hardcover:in-the-blood-2022, amazon:B07THCSQ27, storygraph:story-123, fable:fable-456, margins:00000000-0000-4000-8000-000000000789, unrelated:private-fixture-secret</dc:identifier></metadata></package>"""
        ).forEach { (path, text) ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    val provider = FolderProvider(file)
    provider.attachInfo(
        app,
        ProviderInfo().apply {
            authority = "test.documents"
            applicationInfo = app.applicationInfo
            exported = true
            grantUriPermissions = true
        }
    )
    ShadowContentResolver.registerProviderInternal("test.documents", provider)
    val uri = Uri.parse("content://test.documents/tree/books")
    app.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    app.diagnostics.store.put("ebook.tree", uri.toString())
    return provider
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
class EpubFolderTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private lateinit var provider: FolderProvider
    private lateinit var repository: BookIdentifierRepository
    private fun book() = JSONObject().put("filename", JSONObject().put("state", "value").put("raw", "book.epub"))

    @Before fun setup() {
        provider = grantEpubFolder(app)
        repository = BookIdentifierRepository(app, app.diagnostics.store)
    }

    @After fun cleanup() = runBlocking {
        app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        app.diagnostics.store.close()
        closeWorkDatabase()
    }

    @Test fun grantedFolderUsesReadOnlyStreamAndCachesOnlyMetadata() {
        val documentSize = File(app.cacheDir, "synthetic.epub").length()
        val versionOneKey = "ebook.identity.${digest("content://test.documents/tree/books" + "book" + 100 + documentSize)}"
        app.diagnostics.store.put(versionOneKey, BookIdentifiers(setOf("9781398508255"), null, null).json().toString())
        assertEquals(setOf("9781398508255"), repository.read(book()).isbns)
        assertEquals(setOf("story-123"), repository.read(book()).tags["storygraph"])
        assertEquals(1, provider.opens)
        assertEquals(emptyList<File>(), app.cacheDir.listFiles()!!.filter { it.name.startsWith("metadata-") })
    }

    @Test fun ambiguousFilenamesAndRevokedPermissionCannotReadAChosenFile() {
        provider.duplicate = true
        assertEquals("epub_filename_ambiguous", assertThrows(SyncProblem::class.java) { repository.read(book()) }.code)
        assertEquals(0, provider.opens)
        provider.denied = true
        assertThrows(SecurityException::class.java) { repository.read(book()) }
    }

    @Test fun providerIsbnDoesNotRequireFolderAccess() {
        app.diagnostics.store.put("ebook.tree", "")
        val value = book().put("ISBN", JSONObject().put("state", "value").put("raw", "9781398508255"))
        assertEquals(setOf("9781398508255"), repository.read(value).isbns)
        assertEquals(0, provider.opens)
    }
}
