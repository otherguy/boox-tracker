package dev.otherguy.booxtracker

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertTrue
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
