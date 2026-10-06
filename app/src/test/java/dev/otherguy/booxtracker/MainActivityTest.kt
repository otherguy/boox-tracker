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

    private fun event(index: Int, unchanged: Boolean = false, issue: Boolean = false): JSONObject = JSONObject()
        .put("timestamp", java.time.Instant.ofEpochSecond(index.toLong()).toString())
        .put("source", "scheduled")
        .put("kind", "query")
        .put("trigger", "periodic")
        .put("runId", JSONObject.NULL)
        .put("outcome", "success")
        .put("recordCount", 786)
        .put("selected", JSONObject().put("key", if (unchanged) "same-book" else "book-$index"))
        .put("unchanged", unchanged)
        .put("issue", issue)

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

    @Test fun diagnosticsFollowRecentAccessAcrossQueriesDespiteASavedChoice() = runBlocking {
        grantTestFolder(app)
        val provider = FixtureProvider().withAccessTimes("older-book" to "1700000000", "recent-book" to "1700000060000")
        ShadowContentResolver.registerProviderInternal(METADATA_URI.authority, provider)
        app.diagnostics.store.put("selection", "uuid:older-book")
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
            descendants(content).filterIsInstance<Button>().first { it.id == R.id.sync_now }.let { content.findViewById<View>(R.id.book_summary).performClick() }
            awaitUi { org.robolectric.shadows.ShadowDialog.getLatestDialog()?.isShowing == true }
            val popup = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            assertTrue(popup.isShowing)
            assertTrue(popup.findViewById<TextView>(android.R.id.message).text.toString().contains("42/100"))
            assertTrue(popup.window!!.decorView.background is android.graphics.drawable.GradientDrawable)
            assertFalse(descendants(content).any { it is android.widget.Spinner })
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
            assertTrue(text.toString(), text.toString().contains("9781398508255"))
            assertTrue(text.toString().contains("58438630"))
            assertTrue(text.toString().contains("in-the-blood-2022"))
            assertTrue(text.toString().contains("B07THCSQ27"))
            assertTrue(text.toString().contains("story-123"))
            assertTrue(text.toString().contains("fable-456"))
            assertTrue(text.toString().contains("margins-789"))
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
            assertFalse(names.contains("alpha-book"))
            assertFalse(names.contains("omega-book"))
            val check = JSONObject(app.diagnostics.store.get("lastCheck")!!)
            assertEquals(2, check.getInt("recordCount"))
            assertTrue(check.isNull("selected"))
            assertTrue(check.getBoolean("issue"))
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

    @Test fun fullActivityLogBoundsViewWorkAndDefersCollapsedDetails() = runBlocking {
        repeat(275) { app.diagnostics.store.append(event(it)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.activity).performClick()
            val list = activity.findViewById<ListView>(R.id.activity_entries)
            awaitUi { list.adapter.count == 250 }
            layout(activity)
            assertTrue(list.childCount > 0)
            val views = descendants(activity.findViewById(android.R.id.content))
            val buttons = views.filterIsInstance<Button>().size
            assertTrue("250 entries created $buttons buttons before scrolling", buttons < 32)
            val hiddenDetails = views.filterIsInstance<TextView>().count { it.id == R.id.event_details && it.visibility == View.GONE && it.text.isNotEmpty() }
            assertTrue("Collapsed entries formatted $hiddenDetails detail blocks", hiddenDetails == 0)
            assertEquals(275, buildExport(app.diagnostics).getJSONArray("observations").length())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun groupedDetailsAndIssueFilterKeepEveryUnderlyingEvent() = runBlocking {
        repeat(3) { app.diagnostics.store.append(event(it, unchanged = true)) }
        app.diagnostics.store.append(event(3, issue = true))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.activity).performClick()
            val list = activity.findViewById<ListView>(R.id.activity_entries)
            awaitUi { list.adapter.count == 2 }
            layout(activity)
            val adapter = list.adapter as ActivityLogAdapter
            val row = adapter.getView(1, null, list)
            val details = row.findViewById<TextView>(R.id.event_details)
            row.findViewById<Button>(R.id.event_expand).performClick()
            awaitUi { details.text.isNotEmpty() }
            assertEquals(View.VISIBLE, details.visibility)
            repeat(3) { assertTrue(details.text.toString().contains(event(it, unchanged = true).toString(2))) }
            row.findViewById<Button>(R.id.event_expand).performClick()
            assertEquals(View.GONE, details.visibility)
            assertEquals("", details.text.toString())
            activity.findViewById<Button>(R.id.activity_filter).performClick()
            awaitUi { list.adapter.count == 1 }
            assertTrue(adapter.getItem(0).events.single().getBoolean("issue"))
            activity.findViewById<Button>(R.id.activity_filter).performClick()
            awaitUi { list.adapter.count == 2 }
            assertEquals(4, app.diagnostics.store.events().size)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun recycledRowsAndLiveUpdatesKeepDetailsAndScrollPosition() = runBlocking {
        repeat(250) { app.diagnostics.store.append(event(it)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.activity).performClick()
            val list = activity.findViewById<ListView>(R.id.activity_entries)
            awaitUi { list.adapter.count == 250 }
            layout(activity)
            list.setSelectionFromTop(100, 0)
            layout(activity)
            val adapter = list.adapter as ActivityLogAdapter
            val anchor = adapter.getItem(list.firstVisiblePosition).key
            val row = adapter.getView(100, null, list)
            val details = row.findViewById<TextView>(R.id.event_details)
            row.findViewById<Button>(R.id.event_expand).performClick()
            awaitUi { details.text.isNotEmpty() }
            assertEquals("book-149", JSONObject(details.text.toString()).getJSONObject("selected").getString("key"))
            assertTrue(row === adapter.getView(0, row, list))
            assertEquals("", details.text.toString())
            adapter.getView(100, row, list)
            awaitUi { details.text.isNotEmpty() }
            assertEquals("book-149", JSONObject(details.text.toString()).getJSONObject("selected").getString("key"))
            app.diagnostics.event("manual", "selection", detail = event(250))
            awaitUi { adapter.getItem(0).key != event(249).getString("timestamp") }
            layout(activity)
            assertEquals(anchor, adapter.getItem(list.firstVisiblePosition).key)
            assertTrue(descendants(list).filterIsInstance<Button>().size < 32)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun expandedUnchangedGroupSurvivesGrowthAndTheEventLimit() = runBlocking {
        repeat(250) { app.diagnostics.store.append(event(it, unchanged = true)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.activity).performClick()
            val list = activity.findViewById<ListView>(R.id.activity_entries)
            awaitUi { list.adapter.count == 1 }
            layout(activity)
            val adapter = list.adapter as ActivityLogAdapter
            val before = adapter.getItem(0).events.first().getString("timestamp")
            list.getChildAt(0).findViewById<Button>(R.id.event_expand).performClick()
            awaitUi { list.getChildAt(0).findViewById<TextView>(R.id.event_details).text.isNotEmpty() }
            app.diagnostics.event("scheduled", "query", detail = event(250, unchanged = true))
            awaitUi { adapter.getItem(0).events.first().getString("timestamp") != before }
            layout(activity)
            val details = list.getChildAt(0).findViewById<TextView>(R.id.event_details)
            assertEquals(View.VISIBLE, details.visibility)
            val newest = adapter.getItem(0).events.first().toString(2)
            awaitUi { details.text.toString().contains(newest) }
            assertEquals(250, adapter.getItem(0).events.size)
            assertEquals(251, app.diagnostics.store.events().size)
            activity.findViewById<Button>(R.id.activity_filter).performClick()
            awaitUi { list.adapter.count == 0 }
            activity.findViewById<Button>(R.id.activity_filter).performClick()
            awaitUi { list.adapter.count == 1 }
            layout(activity)
            assertEquals(View.VISIBLE, list.getChildAt(0).findViewById<View>(R.id.event_details).visibility)
            val recreated = controller.recreate().get()
            val recreatedList = recreated.findViewById<ListView>(R.id.activity_entries)
            awaitUi { recreatedList.adapter.count == 1 }
            layout(recreated)
            val retainedDetails = recreatedList.getChildAt(0).findViewById<TextView>(R.id.event_details)
            assertEquals(View.VISIBLE, retainedDetails.visibility)
            awaitUi { retainedDetails.text.toString().contains(newest) }
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun changingFilterAfterScrollingStartsAtTheFirstMatchingEntry() = runBlocking {
        repeat(250) { app.diagnostics.store.append(event(it, issue = it % 10 == 0)) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.activity).performClick()
            val list = activity.findViewById<ListView>(R.id.activity_entries)
            awaitUi { list.adapter.count == 250 }
            layout(activity)
            list.setSelectionFromTop(100, 0)
            layout(activity)
            activity.findViewById<Button>(R.id.activity_filter).performClick()
            awaitUi { list.adapter.count == 25 }
            layout(activity)
            assertEquals(0, list.firstVisiblePosition)
            assertEquals(event(240, issue = true).getString("timestamp"), (list.adapter as ActivityLogAdapter).getItem(0).key)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun filterWithNoMatchesShowsAnEmptyStateWithoutDeletingEvents() = runBlocking {
        app.diagnostics.store.append(event(0))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<Button>(R.id.activity).performClick()
            val list = activity.findViewById<ListView>(R.id.activity_entries)
            awaitUi { list.adapter.count == 1 }
            activity.findViewById<Button>(R.id.activity_filter).performClick()
            awaitUi { list.adapter.count == 0 }
            layout(activity)
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.activity_empty).visibility)
            assertEquals(1, app.diagnostics.store.events().size)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
