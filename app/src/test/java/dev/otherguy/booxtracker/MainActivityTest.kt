package dev.otherguy.booxtracker

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestReadingSyncApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityTest {
    private val app get() = RuntimeEnvironment.getApplication() as ReadingSyncApp
    private val originalError = System.err
    private val capturedError = ByteArrayOutputStream()
    private val errorStream = PrintStream(capturedError, true, Charsets.UTF_8)

    @Before fun captureResourceWarnings() {
        System.setErr(errorStream)
    }

    @After fun releaseApplicationResources() = runBlocking {
        try {
            app.diagnostics.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
            app.diagnostics.store.close()
            closeWorkDatabase()
        } finally {
            System.setErr(originalError)
            errorStream.close()
        }
        // Robolectric's CppAssetManager2 reports zero-ID lookups during widget construction.
        val unexpected = capturedError.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() && it != "Invalid ID 0x00000000." }.toList()
        assertEquals("Unexpected UI test diagnostics", emptyList<String>(), unexpected)
    }

    private val base = System.currentTimeMillis() - 7_200_000

    /** A check event [index] seconds after a recent base time, so retention keeps it. */
    private fun event(index: Int, unchanged: Boolean = false, issue: Boolean = false): JSONObject = JSONObject()
        .put("timestamp", java.time.Instant.ofEpochMilli(base + index * 1000L).toString())
        .put("wallMs", base + index * 1000L)
        .put("source", "scheduled")
        .put("kind", "query")
        .put("trigger", "periodic")
        .put("runId", JSONObject.NULL)
        .put("outcome", "success")
        .put("recordCount", 786)
        .put("selected", JSONObject().put("key", if (unchanged) "same-book" else "book-$index").put("title", "Book $index").put("percentage", 42))
        .put("unchanged", unchanged)
        .put("issue", issue)
        .put("appVisible", false)

    /** The event popup on screen; the folder prompt these tests leave open is never it. */
    private fun popup() = (org.robolectric.shadows.ShadowDialog.getLatestDialog() as? androidx.appcompat.app.AlertDialog)
        ?.takeIf { it.isShowing && it.findViewById<View>(R.id.event_details) != null }

    private fun openActivity(controller: org.robolectric.android.controller.ActivityController<MainActivity>, count: Int): ListView {
        val activity = controller.get()
        activity.findViewById<Button>(R.id.activity).performClick()
        val list = activity.findViewById<ListView>(R.id.activity_entries)
        runBlocking { awaitUi { list.adapter.count == count } }
        layout(activity)
        return list
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private suspend fun awaitUi(predicate: () -> Boolean) {
        withTimeout(5000) {
            while (!predicate()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    private fun layout(activity: MainActivity) {
        val root = activity.findViewById<View>(android.R.id.content)
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 1600)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    @LooperMode(LooperMode.Mode.PAUSED)
    fun syncNowShowsSyncingWithASpinnerAtTheSameWidthWhileBusy() = runBlocking {
        grantTestFolder(app)
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, FixtureProvider().withSyncBook())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(R.id.content)
            val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
            fun syncButton() = descendants(content).filterIsInstance<Button>().singleOrNull { it.id == R.id.sync_now }
            awaitUi { syncButton()?.isEnabled == true && !screen.busy }
            layout(activity)
            val idle = syncButton()!!
            assertEquals("Sync Now", idle.text.toString())
            assertTrue(idle.width > 0)
            val idleWidth = idle.width
            // A running sync must be visible: the label changes and a spinner appears, without moving the header.
            screen.busy = true
            app.diagnostics.updates.value++
            awaitUi { syncButton()?.text?.toString() == "Syncing" }
            layout(activity)
            val busy = syncButton()!!
            assertFalse(busy.isEnabled)
            val spinner = busy.compoundDrawables[0] as SyncSpinner
            assertTrue(spinner.running)
            assertEquals(idleWidth, busy.width)
            assertEquals(1, busy.lineCount)
            screen.busy = false
            app.diagnostics.updates.value++
            awaitUi { syncButton()?.text?.toString() == "Sync Now" && syncButton()?.isEnabled == true }
            // The replaced button left the window, which stops its spinner.
            assertFalse(spinner.running)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun diagnosticsFollowRecentAccessAcrossQueries() = runBlocking {
        grantTestFolder(app)
        val provider = FixtureProvider().withAccessTimes("older-book" to "1700000000", "recent-book" to "1700000060000")
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(R.id.content)
            val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
            awaitUi { content.childCount > 0 && !screen.busy && app.diagnostics.store.get("lastCheck") != null }
            val callsBefore = provider.calls.get()
            descendants(content).filterIsInstance<Button>().single { it.id == R.id.sync_now }.performClick()
            awaitUi { provider.calls.get() == callsBefore + 1 && !screen.busy && descendants(content).filterIsInstance<TextView>().any { it.text.toString().startsWith("recent-book ·") } }
            assertEquals(callsBefore + 1, provider.calls.get())
            val checkedAt = JSONObject(app.diagnostics.store.get("lastCheck")!!).getLong("readStartedWallMs")
            val displayedTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(checkedAt))
            assertTrue(descendants(content).filterIsInstance<TextView>().any { it.text.toString().contains("Read $displayedTime") })
            content.findViewById<View>(R.id.book_summary).performClick()
            awaitUi { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            val popup = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue(popup.isShowing)
            assertTrue(popup.findViewById<TextView>(android.R.id.message).text.toString().contains("42/100"))
            assertTrue(popup.window!!.decorView.background is android.graphics.drawable.GradientDrawable)
            assertEquals(callsBefore + 1, provider.calls.get())
            assertEquals("uuid:recent-book", JSONObject(app.diagnostics.store.get("lastCheck")!!).getJSONObject("selected").getString("key"))
            provider.withAccessTimes("older-book" to "2026-10-06T10:00:00Z", "recent-book" to "1700000060000")
            app.diagnostics.collect("scheduled", "periodic", "job")
            awaitUi { descendants(content).filterIsInstance<TextView>().any { it.text.toString().startsWith("older-book ·") } }
            assertEquals("uuid:older-book", JSONObject(app.diagnostics.store.get("lastCheck")!!).getJSONObject("selected").getString("key"))
            provider.withAccessTimes("older-book" to "2026-10-06T10:00:00Z", "recent-book" to "2026-10-06T11:00:00Z")
            app.diagnostics.collect("scheduled", "periodic", "next-job")
            awaitUi { descendants(content).filterIsInstance<TextView>().any { it.text.toString().startsWith("recent-book ·") } }
            assertEquals("uuid:recent-book", JSONObject(app.diagnostics.store.get("lastCheck")!!).getJSONObject("selected").getString("key"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun disabledServiceFailureDoesNotWarnButProviderFailureDoes() = runBlocking {
        grantTestFolder(app)
        val provider = FixtureProvider().withAccessTimes("recent-book" to "1700000060000")
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        app.diagnostics.collect("manual", "read_now")
        app.hardcover.recordFailure("manual", "hardcover_sync", SyncProblem("isbn_missing"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val content = activity.findViewById<ViewGroup>(R.id.content)
            awaitUi { content.childCount > 0 }
            assertEquals(null, activity.findViewById<View>(R.id.access_warning))
            app.diagnostics.store.put("hardcover.enabled", "true")
            app.diagnostics.updates.value++
            awaitUi { activity.findViewById<View>(R.id.access_warning) != null }
            app.hardcover.setEnabled(false)
            awaitUi { activity.findViewById<View>(R.id.access_warning) == null }
            provider.denied = true
            descendants(content).filterIsInstance<Button>().single { it.id == R.id.sync_now }.performClick()
            awaitUi { activity.findViewById<View>(R.id.access_warning) != null }
            assertEquals("permission_denied", JSONObject(app.diagnostics.store.get("lastCheck")!!).getString("outcome"))
            // The book popup names the read failure first, with the amber triangle.
            activity.findViewById<View>(R.id.book_summary).performClick()
            awaitUi { popupMessage() != null }
            val text = popupMessage()!!
            assertTrue(warningsDrawn(text))
            val issues = issueLines(text)
            assertEquals(text.toString(), 1, issues.size)
            assertTrue(issues[0], issues[0].contains("permission denied"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun metadataPopupReadsEbookIdentifiersWhenProviderIsbnIsNull() = runBlocking {
        val oldZone = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        android.provider.Settings.System.putString(app.contentResolver, android.provider.Settings.System.TIME_12_24, "24")
        val folder = grantEpubFolder(app)
        val provider = FixtureProvider().withEpubBook()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
            awaitUi {
                val summary = activity.findViewById<View>(R.id.book_summary)
                summary != null && !screen.busy && descendants(summary).filterIsInstance<TextView>().any { it.text.toString().startsWith("Synthetic Book ·") }
            }
            activity.findViewById<View>(R.id.book_summary).performClick()
            awaitUi { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            val popup = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            val text = popup.findViewById<TextView>(android.R.id.message).text
            // Without an issue the popup has no issue section.
            assertFalse(text.toString(), text.contains("⚠") || text.startsWith("\n"))
            assertTrue(text.toString(), text.toString().contains("9781398508255"))
            assertTrue(text.toString().contains("58438630"))
            assertTrue(text.toString().contains("in-the-blood-2022"))
            assertTrue(text.toString().contains("B07THCSQ27"))
            assertTrue(text.toString().contains("story-123"))
            assertTrue(text.toString().contains("fable-456"))
            assertTrue(text.toString().contains("00000000-0000-4000-8000-000000000789"))
            assertFalse(text.toString().contains("private-fixture-secret"))
            assertEquals(1, folder.opens)
            assertFalse(folder.openedOnMainThread)
            assertTrue(text.toString().contains("Nov 14, 2023, 22:14"))
            assertFalse(text.toString().contains("1700000060"))
            assertTrue(text is android.text.Spanned)
            val styled = text as android.text.Spanned
            val labels = styled.getSpans(0, styled.length, android.text.style.StyleSpan::class.java).filter { it.style == android.graphics.Typeface.BOLD }
            assertTrue(labels.isNotEmpty())
            labels.forEach {
                assertTrue(styled.subSequence(styled.getSpanStart(it), styled.getSpanEnd(it)).toString().endsWith(": "))
            }
        } finally {
            controller.pause().stop().destroy()
            java.util.TimeZone.setDefault(oldZone)
        }
    }

    @Test fun unusableAccessTimesDoNotSelectAnArbitraryLibraryRecord() = runBlocking {
        grantTestFolder(app)
        val provider = FixtureProvider().withAccessTimes("alpha-book" to null, "omega-book" to "invalid")
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        app.diagnostics.collect("manual", "read_now")
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val content = controller.get().findViewById<ViewGroup>(R.id.content)
            awaitUi { content.childCount > 0 }
            val names = descendants(content).filterIsInstance<TextView>().map { it.text.toString() }
            assertFalse(names.any { it.startsWith("alpha-book") || it.startsWith("omega-book") })
            val check = JSONObject(app.diagnostics.store.get("lastCheck")!!)
            assertEquals(2, check.getInt("recordCount"))
            assertTrue(check.isNull("selected"))
            assertTrue(check.getBoolean("issue"))
            // The book popup explains why no book was detected.
            controller.get().findViewById<View>(R.id.book_summary).performClick()
            awaitUi { popupMessage() != null }
            assertEquals(1, issueLines(popupMessage()!!).size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun startupFolderPromptControlsPickerAndKeepsCancellationBlocked() = runBlocking {
        val provider = FixtureProvider().withSyncBook()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            awaitUi { shadowOf(activity).peekNextStartedActivityForResult() != null || org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            assertEquals(null, shadowOf(activity).peekNextStartedActivityForResult())
            val explanation = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertTrue(explanation.findViewById<TextView>(android.R.id.message)!!.text.isNotBlank())
            assertEquals(0, provider.calls.get())
            explanation.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            awaitUi { shadowOf(activity).peekNextStartedActivityForResult() != null }
            val picker = shadowOf(activity).nextStartedActivityForResult.intent
            assertEquals(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE, picker.action)
            shadowOf(activity).receiveResult(picker, android.app.Activity.RESULT_CANCELED, null)
            awaitUi { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            assertNotNull(dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE))
            assertNotNull(dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE))
            assertEquals(null, shadowOf(activity).peekNextStartedActivityForResult())
            assertEquals(0, provider.calls.get())
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            awaitUi { activity.isFinishing }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun cancellingFolderReplacementKeepsReadableGrantButBlocksRevokedAccess() = runBlocking {
        val folder = grantEpubFolder(app)
        val tree = app.diagnostics.store.get("ebook.tree")
        val provider = FixtureProvider().withSyncBook()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
            awaitUi { activity.findViewById<View>(R.id.book_summary) != null && !screen.busy && provider.calls.get() > 0 }
            for (revoked in listOf(false, true)) {
                activity.findViewById<Button>(R.id.about).performClick()
                val about = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
                about.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
                awaitUi { shadowOf(activity).peekNextStartedActivityForResult() != null }
                val picker = shadowOf(activity).nextStartedActivityForResult.intent
                folder.denied = revoked
                shadowOf(activity).receiveResult(picker, android.app.Activity.RESULT_CANCELED, null)
                if (revoked) {
                    awaitUi { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
                } else {
                    val callsBefore = provider.calls.get()
                    activity.findViewById<Button>(R.id.sync_now).performClick()
                    awaitUi { provider.calls.get() > callsBefore && !screen.busy }
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(tree, app.diagnostics.store.get("ebook.tree"))
                assertEquals(revoked, org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true)
                if (!revoked) assertEquals(tree, requireEbookFolder(app, app.diagnostics.store).toString())
            }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun recreationDuringCancelledFolderValidationStillBlocksRevokedAccess() = runBlocking {
        val folder = grantTestFolder(app)
        val provider = FixtureProvider().withSyncBook()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val screen = androidx.lifecycle.ViewModelProvider(activity)[ScreenModel::class.java]
            awaitUi { activity.findViewById<View>(R.id.book_summary) != null && !screen.busy && provider.calls.get() > 0 }
            folder.entered = java.util.concurrent.CountDownLatch(1)
            folder.release = java.util.concurrent.CountDownLatch(1)
            folder.denied = true
            activity.findViewById<Button>(R.id.about).performClick()
            val about = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            about.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            awaitUi { shadowOf(activity).peekNextStartedActivityForResult() != null }
            val picker = shadowOf(activity).nextStartedActivityForResult.intent
            shadowOf(activity).receiveResult(picker, android.app.Activity.RESULT_CANCELED, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(folder.entered!!.await(5, java.util.concurrent.TimeUnit.SECONDS))
            controller.recreate()
            folder.release!!.countDown()
            awaitUi { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            assertEquals(null, shadowOf(controller.get()).peekNextStartedActivityForResult())
            assertFalse(controller.get().isFinishing)
        } finally {
            folder.release?.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun recreationDuringFolderValidationStillCollectsAfterValidation() = runBlocking {
        val folder = grantTestFolder(app).apply {
            entered = java.util.concurrent.CountDownLatch(1)
            release = java.util.concurrent.CountDownLatch(1)
        }
        val provider = FixtureProvider().withSyncBook()
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            assertTrue(folder.entered!!.await(5, java.util.concurrent.TimeUnit.SECONDS))
            controller.recreate()
            folder.release!!.countDown()
            awaitUi { provider.calls.get() > 0 }
            assertEquals("uuid:Synthetic Book", JSONObject(app.diagnostics.store.get("lastCheck")!!).getJSONObject("selected").getString("key"))
        } finally {
            folder.release!!.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun theActivityLogShowsEveryRetainedEventAndFormatsNothingBeforeATap() = runBlocking {
        repeat(275) { app.diagnostics.store.append(event(it)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val list = openActivity(controller, 275)
            assertTrue(list.childCount > 0)
            assertTrue(descendants(list).none { it is Button })
            assertTrue((list.adapter as ActivityLogAdapter).entries.none { it.json != null })
            val row = list.getChildAt(0)
            assertTrue(row.isClickable)
            assertEquals("Checked NeoReader", row.findViewById<TextView>(R.id.event_title).text.toString())
            assertTrue(row.contentDescription.toString().startsWith("Checked NeoReader. Background · Book 274 · 42%"))
            assertEquals(275, buildExport(app.diagnostics).getJSONArray("observations").length())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun exportSavesTheSummaryAndTheJsonToThePickedFolder() = runBlocking {
        repeat(3) { app.diagnostics.store.append(event(it)) }
        val folder = WritableFolderProvider().apply {
            attachInfo(
                app,
                android.content.pm.ProviderInfo().apply {
                    authority = "test.export"
                    applicationInfo = app.applicationInfo
                }
            )
            ShadowContentResolver.registerProviderInternal("test.export", this)
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.activity).performClick()
            activity.findViewById<Button>(R.id.export).performClick()
            awaitUi { shownDialog()?.getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.text == "Share" }
            shownDialog()!!.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            awaitUi { shadowOf(activity).peekNextStartedActivityForResult()?.intent?.action == android.content.Intent.ACTION_OPEN_DOCUMENT_TREE }
            val picker = shadowOf(activity).nextStartedActivityForResult.intent
            shadowOf(activity).receiveResult(picker, android.app.Activity.RESULT_OK, android.content.Intent().setData(android.net.Uri.parse("content://test.export/tree/downloads")))
            awaitUi { folder.files.size == 2 && popupMessage() != null }
            assertEquals(listOf("text/plain", "application/json"), folder.files.map { it.type })
            assertTrue(folder.files[0].name.matches(Regex("summary-\\d+\\.txt")))
            assertEquals(3, JSONObject(folder.files[1].file.readText()).getJSONArray("observations").length())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    /** A picked folder that answers the document-create call and keeps what is written to each new document. */
    private class WritableFolderProvider : android.content.ContentProvider() {
        class Saved(val name: String, val type: String, val file: java.io.File)
        val files = mutableListOf<Saved>()
        override fun onCreate() = true
        override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
            assertEquals("android:createDocument", method)
            val name = extras!!.getString(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)!!
            files += Saved(name, extras.getString(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)!!, java.io.File.createTempFile("export", null))
            return android.os.Bundle().apply { putParcelable("uri", android.provider.DocumentsContract.buildDocumentUriUsingTree(android.net.Uri.parse("content://test.export/tree/downloads"), "${files.size - 1}")) }
        }
        override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor = android.os.ParcelFileDescriptor.open(files[android.provider.DocumentsContract.getDocumentId(uri).toInt()].file, android.os.ParcelFileDescriptor.parseMode(mode))
        override fun query(uri: android.net.Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
        override fun getType(uri: android.net.Uri) = null
        override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
        override fun update(uri: android.net.Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: android.net.Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    }

    @Test fun tappingARowOpensASummaryTabAndAMonospaceJsonTab() = runBlocking {
        repeat(3) { app.diagnostics.store.append(event(it, unchanged = true)) }
        app.diagnostics.store.append(event(3, issue = true))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val list = openActivity(controller, 2)
            val adapter = list.adapter as ActivityLogAdapter
            val group = adapter.getItem(1)
            list.getChildAt(1).performClick()
            awaitUi { popup() != null }
            val dialog = popup()!!
            assertTrue(dialog.window!!.decorView.background is android.graphics.drawable.GradientDrawable)
            val summary = dialog.findViewById<TextView>(R.id.event_summary_text)!!.text as android.text.Spanned
            val labels = summary.getSpans(0, summary.length, android.text.style.StyleSpan::class.java).filter { it.style == android.graphics.Typeface.BOLD }
                .map { summary.subSequence(summary.getSpanStart(it), summary.getSpanEnd(it)).toString() }
            assertTrue(labels.isNotEmpty() && labels.all { it.endsWith(": ") })
            assertTrue(summary.toString().contains("Checks: 3 since"))
            assertTrue(summary.toString().contains("App visible: No, for all 3"))
            val json = dialog.findViewById<TextView>(R.id.event_json)!!
            assertEquals("", json.text.toString())
            assertEquals(null, group.json)
            val body = dialog.findViewById<View>(R.id.event_details)!!
            val panes = dialog.findViewById<View>(R.id.event_panes)!!
            fun paneHeight(): Int {
                body.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1400, View.MeasureSpec.AT_MOST))
                return panes.measuredHeight
            }
            val summaryHeight = paneHeight()
            dialog.findViewById<Button>(R.id.event_tab_json)!!.performClick()
            awaitUi { json.text.isNotEmpty() }
            assertTrue(summaryHeight > 0)
            assertEquals(summaryHeight, paneHeight())
            assertEquals(View.GONE, dialog.findViewById<View>(R.id.event_summary_pane)!!.visibility)
            assertTrue(dialog.findViewById<Button>(R.id.event_tab_json)!!.isSelected)
            assertEquals(android.graphics.Typeface.MONOSPACE, json.typeface)
            assertEquals(group.events.first().getString("timestamp"), JSONObject(json.text.toString()).getString("timestamp"))
            assertEquals("Newest of 3 events. The export contains all of them.", dialog.findViewById<TextView>(R.id.event_json_note)!!.text.toString())
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            awaitUi { popup() == null }
            list.getChildAt(1).performClick()
            awaitUi { popup() != null }
            popup()!!.findViewById<Button>(R.id.event_tab_json)!!.performClick()
            assertEquals(group.json, popup()!!.findViewById<TextView>(R.id.event_json)!!.text.toString())
            popup()!!.dismiss()
            awaitUi { popup() == null }
            list.getChildAt(0).performClick()
            awaitUi { popup() != null }
            assertEquals(View.GONE, popup()!!.findViewById<View>(R.id.event_json_note)!!.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun clearAsksFirstAndThenLeavesOnlyTheClearedMarker() = runBlocking {
        repeat(5) { app.diagnostics.store.append(event(it)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val list = openActivity(controller, 5)
            suspend fun confirmation(): androidx.appcompat.app.AlertDialog {
                val shown = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                activity.findViewById<Button>(R.id.clear_activity).performClick()
                awaitUi { org.robolectric.shadows.ShadowDialog.getLatestDialog().let { it !== shown && it?.isShowing == true } }
                return org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            }
            confirmation().getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(5, app.diagnostics.store.events().size)
            confirmation().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            awaitUi { list.adapter.count == 1 }
            assertEquals("activity_cleared", (list.adapter as ActivityLogAdapter).getItem(0).events.single().getString("kind"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun theIssuesFilterShowsOnlyIssuesAndKeepsEveryEvent() = runBlocking {
        repeat(3) { app.diagnostics.store.append(event(it, unchanged = true)) }
        app.diagnostics.store.append(event(3, issue = true))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val list = openActivity(controller, 2)
            assertTrue(activity.findViewById<Button>(R.id.activity_filter_all).isSelected)
            activity.findViewById<Button>(R.id.activity_filter_issues).performClick()
            awaitUi { list.adapter.count == 1 }
            assertTrue(activity.findViewById<Button>(R.id.activity_filter_issues).isSelected)
            assertFalse(activity.findViewById<Button>(R.id.activity_filter_all).isSelected)
            assertTrue((list.adapter as ActivityLogAdapter).getItem(0).events.single().getBoolean("issue"))
            activity.findViewById<Button>(R.id.activity_filter_all).performClick()
            awaitUi { list.adapter.count == 2 }
            assertEquals(4, app.diagnostics.store.events().size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun recycledRowsAndLiveUpdatesKeepTheScrollPositionAndTheOpenPopup() = runBlocking {
        repeat(250) { app.diagnostics.store.append(event(it)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val list = openActivity(controller, 250)
            list.setSelectionFromTop(100, 0)
            layout(activity)
            val adapter = list.adapter as ActivityLogAdapter
            val anchor = adapter.getItem(list.firstVisiblePosition).key
            val row = adapter.getView(100, null, list)
            row.performClick()
            awaitUi { popup() != null }
            val dialog = popup()!!
            dialog.findViewById<Button>(R.id.event_tab_json)!!.performClick()
            val json = dialog.findViewById<TextView>(R.id.event_json)!!
            awaitUi { json.text.isNotEmpty() }
            assertEquals("book-149", JSONObject(json.text.toString()).getJSONObject("selected").getString("key"))
            assertTrue(row === adapter.getView(0, row, list))
            assertEquals("Book 249 · 42%", row.findViewById<TextView>(R.id.event_summary).text.toString().substringAfter(" · "))
            val summary = dialog.findViewById<TextView>(R.id.event_summary_text)!!.text.toString()
            app.diagnostics.event("manual", "selection", detail = event(250))
            awaitUi { adapter.getItem(0).key != eventKey(event(249)) }
            layout(activity)
            assertEquals(anchor, adapter.getItem(list.firstVisiblePosition).key)
            assertTrue(dialog.isShowing)
            assertEquals(summary, dialog.findViewById<TextView>(R.id.event_summary_text)!!.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun anUnchangedGroupKeepsGrowingAndItsPopupClosesOnRecreation() = runBlocking {
        repeat(250) { app.diagnostics.store.append(event(it, unchanged = true)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val list = openActivity(controller, 1)
            val adapter = list.adapter as ActivityLogAdapter
            val before = adapter.getItem(0).events.first().getString("timestamp")
            list.getChildAt(0).performClick()
            awaitUi { popup() != null }
            app.diagnostics.event("scheduled", "query", detail = event(250, unchanged = true))
            awaitUi { adapter.getItem(0).events.first().getString("timestamp") != before }
            assertEquals(251, adapter.getItem(0).events.size)
            assertEquals(251, app.diagnostics.store.events().size)
            assertTrue(popup()!!.findViewById<TextView>(R.id.event_summary_text)!!.text.toString().contains("Checks: 250 since"))
            val recreated = controller.recreate().get()
            val recreatedList = recreated.findViewById<ListView>(R.id.activity_entries)
            awaitUi { recreatedList.adapter.count == 1 }
            assertEquals(null, popup())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun changingFilterAfterScrollingStartsAtTheFirstMatchingEntry() = runBlocking {
        // Each issue has its own reason, so the Issues view shows one row per issue instead of one grouped row.
        repeat(250) { app.diagnostics.store.append(event(it, issue = it % 10 == 0).put("reason", "case $it")) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val list = openActivity(controller, 250)
            list.setSelectionFromTop(100, 0)
            layout(activity)
            activity.findViewById<Button>(R.id.activity_filter_issues).performClick()
            awaitUi { list.adapter.count == 25 }
            layout(activity)
            assertEquals(0, list.firstVisiblePosition)
            assertEquals(eventKey(event(240, issue = true)), (list.adapter as ActivityLogAdapter).getItem(0).key)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun anIssuesFilterWithNoMatchesShowsItsEmptyState() = runBlocking {
        app.diagnostics.store.append(event(0))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val list = openActivity(controller, 1)
            activity.findViewById<Button>(R.id.activity_filter_issues).performClick()
            awaitUi { list.adapter.count == 0 }
            layout(activity)
            val empty = activity.findViewById<TextView>(R.id.activity_empty)
            assertEquals(View.VISIBLE, empty.visibility)
            assertEquals(activity.getString(R.string.activity_no_issues), empty.text.toString())
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
