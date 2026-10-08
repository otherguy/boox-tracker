package dev.otherguy.booxtracker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver

class TestFolderProvider : ContentProvider() {
    var entered: java.util.concurrent.CountDownLatch? = null
    var release: java.util.concurrent.CountDownLatch? = null
    var denied = false
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): MatrixCursor {
        entered?.countDown()
        release?.await()
        if (denied) throw SecurityException("Grant revoked")
        return MatrixCursor(projection).apply {
            if (!uri.path.orEmpty().endsWith("/children")) addRow(arrayOf(DocumentsContract.Document.MIME_TYPE_DIR))
        }
    }
    override fun getType(uri: Uri) = DocumentsContract.Document.MIME_TYPE_DIR
    override fun insert(uri: Uri, values: ContentValues?): Uri = error("No writes")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("No writes")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No writes")
}

fun grantTestFolder(app: ReadingSyncApp): TestFolderProvider {
    val provider = TestFolderProvider()
    provider.attachInfo(
        app,
        ProviderInfo().apply {
            authority = "test.folder"
            applicationInfo = app.applicationInfo
            exported = true
            grantUriPermissions = true
        }
    )
    ShadowContentResolver.registerProviderInternal("test.folder", provider)
    val uri = Uri.parse("content://test.folder/tree/books")
    app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    app.diagnostics.store.put("ebook.tree", uri.toString())
    return provider
}

class TestReadingSyncApp : ReadingSyncApp() {
    override fun onCreate() {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(
            this,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build()
        )
        super.onCreate()
    }
}

fun closeWorkDatabase() {
    androidx.work.testing.WorkManagerTestInitHelper.closeWorkDatabase()
}

/** Scrolls [view] onto the screen and touches its centre through the window, so the touch reaches whichever view handles it on a device. */
internal fun tap(view: View) {
    assertTrue("nothing handles a tap here", generateSequence(view) { it.parent as? View }.any { it.isClickable })
    view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
    assertTrue("view is not on screen", view.getGlobalVisibleRect(Rect()))
    val location = IntArray(2).also(view::getLocationInWindow)
    val time = SystemClock.uptimeMillis()
    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
        val event = MotionEvent.obtain(time, time, action, location[0] + view.width / 2f, location[1] + view.height / 2f, 0)
        view.rootView.dispatchTouchEvent(event)
        event.recycle()
    }
}

/** The popup on screen, if one is showing. */
internal fun shownDialog(): androidx.appcompat.app.AlertDialog? = (org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)?.takeIf { it.isShowing }

/** The message of the popup on screen, with its spans. */
internal fun popupMessage(): CharSequence? = shownDialog()?.findViewById<android.widget.TextView>(android.R.id.message)?.text

/** Idles the main looper until [predicate] holds, within ten seconds. */
internal suspend fun awaitUi(predicate: () -> Boolean) = withTimeout(10_000) {
    while (!predicate()) {
        shadowOf(Looper.getMainLooper()).idle()
        delay(10)
    }
}

/** The code of the [SyncProblem] that [block] throws. */
internal fun held(block: suspend () -> Unit): String = assertThrows(SyncProblem::class.java) { runBlocking { block() } }.code

/** Runs [block] with stderr captured and allows only Robolectric's zero-ID lookup line on it. */
internal fun capturingStderr(block: suspend () -> Unit) = runBlocking {
    // Earlier tests leave WorkManager's in-memory database to the finalizer. Its connection pool is only released by
    // that finalization, so the connection's own CloseGuard warning needs a second collection; a third is slack.
    repeat(3) {
        System.gc()
        @Suppress("DEPRECATION")
        System.runFinalization()
    }
    val originalError = System.err
    val capturedError = java.io.ByteArrayOutputStream()
    val stream = java.io.PrintStream(capturedError, true, Charsets.UTF_8)
    System.setErr(stream)
    try {
        block()
    } finally {
        System.setErr(originalError)
        stream.close()
    }
    // Robolectric's CppAssetManager2 reports zero-ID lookups during widget construction.
    assertEquals(emptyList<String>(), capturedError.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && it != "Invalid ID 0x00000000." }.toList())
}

/** The issues at the top of a popup message: one line each, starting with ⚠, then a blank line before the details. */
internal fun issueLines(message: CharSequence): List<String> {
    val lines = message.lines()
    val issues = lines.takeWhile { it.startsWith("⚠ ") }
    if (issues.isNotEmpty()) assertEquals(message.toString(), "", lines[issues.size])
    return issues.map { it.removePrefix("⚠ ") }
}

/** Whether [text] has a ⚠ and each one is drawn as an image, the amber warning triangle. */
internal fun warningsDrawn(text: CharSequence): Boolean {
    val spanned = text as? Spanned ?: return false
    val marks = text.indices.filter { text[it] == '⚠' }
    return marks.isNotEmpty() && marks.all { spanned.getSpans(it, it + 1, ImageSpan::class.java).isNotEmpty() }
}
