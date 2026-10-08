package dev.otherguy.booxtracker

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ScreenModel : ViewModel() {
    var tab = "sync"
    var issues = false
    var busy = false
    var pickerActive = false
    var gateAttempted = false

    /** Fable sign-in drafts survive activity recreation; they are memory-only and never stored or logged. */
    var fableEmail = ""
    var fablePassword = ""
    var activityKeys: Map<String, String> = emptyMap()
}

class MainActivity : AppCompatActivity() {
    private lateinit var app: ReadingSyncApp
    private lateinit var diagnostics: Diagnostics
    private lateinit var content: LinearLayout
    private lateinit var activityLog: ActivityLogAdapter
    private var refreshJob: Job? = null
    private var resetActivityScroll = false
    private val model by lazy { androidx.lifecycle.ViewModelProvider(this)[ScreenModel::class.java] }
    private var gateDialog: AlertDialog? = null
    private var gateChecking = false
    private val dialogs = mutableSetOf<AlertDialog>()

    /** The Activity filter segments and whether each shows only issues. */
    private val filters = listOf(R.id.activity_filter_all to false, R.id.activity_filter_issues to true)

    /**
     * Sign-in popups, kept until their connection stops signing in. A popup the user cancelled stays here closed,
     * so a screen update cannot reopen it while the cancel is still running.
     */
    private var hardcoverSignIn: AlertDialog? = null
    private var fableSignIn: FableSignInViews? = null
    private var storyGraphSignIn: WebSignInViews? = null
    private var goodreadsSignIn: WebSignInViews? = null

    private class FableSignInViews(val dialog: AlertDialog, val email: EditText, val password: EditText, val status: TextView)

    /** A website sign-in popup and the last main-frame load failure of its page, shown on its status line. */
    private class WebSignInViews(val dialog: AlertDialog, val status: TextView) {
        var loadError: String? = null
    }
    private val ebookFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.pickerActive = false
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            gateChecking = true
            lifecycleScope.launch {
                val valid = try {
                    withContext(Dispatchers.IO) { runCatching { requireEbookFolder(this@MainActivity, diagnostics.store) }.isSuccess }
                } finally {
                    gateChecking = false
                }
                if (valid) refresh() else folderPrompt("Ebook folder access is required.")
            }
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val old = diagnostics.store.get("ebook.tree")
                    diagnostics.store.put("ebook.tree", uri.toString())
                    requireEbookFolder(this@MainActivity, diagnostics.store)
                    if (!old.isNullOrBlank() && old != uri.toString()) runCatching { contentResolver.releasePersistableUriPermission(old.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    diagnostics.event("manual", "ebook_folder", detail = JSONObject().put("outcome", "read_permission_granted"))
                }
                gateDialog?.dismiss()
                action { app.sync("foreground", "folder_granted") }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                folderPrompt("Cannot read this folder. Select your ebook folder again.")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (model.pickerActive || gateChecking) return
        gateChecking = true
        lifecycleScope.launch {
            val valid = try {
                withContext(Dispatchers.IO) { runCatching { requireEbookFolder(this@MainActivity, diagnostics.store) }.isSuccess }
            } finally {
                gateChecking = false
            }
            if (valid) {
                gateDialog?.dismiss()
                action { app.sync("foreground", "app_open") }
            } else if (!model.gateAttempted) {
                folderPrompt("Select the folder that contains your ebooks. Boox Tracker needs read access to identify books for syncing. Your files will not be changed.", "Choose folder")
            } else {
                folderPrompt("Ebook folder access is required.")
            }
        }
    }

    private fun chooseFolder() {
        model.gateAttempted = true
        model.pickerActive = true
        gateDialog?.dismiss()
        try {
            ebookFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        } catch (_: android.content.ActivityNotFoundException) {
            model.pickerActive = false
            folderPrompt("No folder picker is available on this device.")
        }
    }

    private fun folderPrompt(message: String, actionLabel: String = "Allow again") {
        if (isFinishing || gateDialog?.isShowing == true) return
        gateDialog = bordered(
            AlertDialog.Builder(this).setTitle("Ebook folder access").setMessage(message).setCancelable(false)
                .setNegativeButton("Exit") { _, _ -> finish() }.setPositiveButton(actionLabel) { _, _ -> chooseFolder() }.create()
        )
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        app = application as ReadingSyncApp
        diagnostics = app.diagnostics
        setContentView(R.layout.activity_main)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { view, insets ->
            val bars =
                insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type
                        .systemBars()
                )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content = findViewById(R.id.content)
        activityLog = ActivityLogAdapter(this, model, ::showEventDetails)
        findViewById<ListView>(R.id.activity_entries).apply {
            adapter = activityLog
            emptyView = this@MainActivity.findViewById(R.id.activity_empty)
        }
        filters.forEach { (id, issues) ->
            findViewById<Button>(id).select(issues == model.issues)
            findViewById<Button>(id).setOnClickListener {
                if (model.issues != issues) {
                    model.issues = issues
                    resetActivityScroll = true
                    refresh()
                }
            }
        }
        findViewById<Button>(R.id.export).setOnClickListener {
            lifecycleScope.launch {
                val intent = withContext(Dispatchers.IO) { exportIntent(this@MainActivity, buildExport(diagnostics)) }
                startActivity(Intent.createChooser(intent, "Export diagnostics"))
            }
        }
        findViewById<Button>(R.id.clear_activity).setOnClickListener {
            confirmClearActivity()
        }
        findViewById<Button>(R.id.device_info).setOnClickListener {
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { deviceInfo().toString(2) + "\nColumns: " + diagnostics.snapshot()?.optJSONArray("columns") }
                notice(info)
            }
        }
        findViewById<Button>(R.id.about).setOnClickListener {
            showAbout()
        }
        findViewById<Button>(R.id.diagnostics).setOnClickListener {
            model.tab = "sync"
            refresh()
        }
        findViewById<Button>(R.id.activity).setOnClickListener {
            model.tab = "activity"
            refresh()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { diagnostics.updates.collect { refresh() } }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        super.onStop()
    }

    private fun refresh() {
        refreshJob?.cancel()
        val activity = model.tab == "activity"
        val issues = model.issues
        findViewById<View>(R.id.diagnostics_underline).visibility = if (activity) View.INVISIBLE else View.VISIBLE
        findViewById<View>(R.id.activity_underline).visibility = if (activity) View.VISIBLE else View.INVISIBLE
        findViewById<View>(R.id.diagnostics_panel).isGone = activity
        findViewById<View>(R.id.activity_panel).isGone = !activity
        findViewById<Button>(R.id.diagnostics).typeface = android.graphics.Typeface.create("sans", if (activity) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        findViewById<Button>(R.id.activity).typeface = android.graphics.Typeface.create("sans", if (activity) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        refreshJob = lifecycleScope.launch {
            if (activity) {
                // The Issues view groups issues among themselves, so checks between repeats never split them.
                val entries = withContext(Dispatchers.IO) { activityEntries(diagnostics.store.events(DiagnosticsStore.MAX_EVENTS).filter { !issues || it.optBoolean("issue") }) }
                val list = findViewById<ListView>(R.id.activity_entries)
                val position = list.firstVisiblePosition
                val anchor = if (position > 0) activityLog.entries.getOrNull(position)?.key else null
                val top = list.getChildAt(0)?.top ?: 0
                activityLog.submit(entries, issues)
                if (resetActivityScroll) {
                    list.setSelectionFromTop(0, 0)
                    resetActivityScroll = false
                } else if (anchor != null) {
                    val retained = activityLog.entries.indexOfFirst { it.key == anchor }
                    list.setSelectionFromTop(if (retained >= 0) retained else position.coerceAtMost(activityLog.entries.lastIndex), top)
                }
                filters.forEach { (id, showsIssues) -> findViewById<Button>(id).select(showsIssues == issues) }
                findViewById<TextView>(R.id.activity_empty).setText(if (issues) R.string.activity_no_issues else R.string.activity_empty)
            } else {
                val snapshot = withContext(Dispatchers.IO) { diagnostics.snapshot() }
                val check = withContext(Dispatchers.IO) { diagnostics.store.get("lastCheck")?.let(::JSONObject) }
                val selected = mostRecentlyAccessedBook(snapshot)
                val hardcover = withContext(Dispatchers.IO) { app.hardcover.state() }
                val goodreads = withContext(Dispatchers.IO) { app.goodreads.state() }
                val fable = withContext(Dispatchers.IO) { app.fable.state() }
                val storygraph = withContext(Dispatchers.IO) { app.storygraph.state() }
                content.removeAllViews()
                showSync(snapshot, check, selected, hardcover, goodreads, fable, storygraph)
                updateHardcoverSignIn()
                updateFableSignIn(fable)
                updateStoryGraphSignIn(storygraph)
                updateGoodreadsSignIn(goodreads)
            }
        }
    }

    private fun label(
        text: String,
        bold: Boolean = false,
        parent: LinearLayout = content,
        size: Float = if (bold) 22f else 18f
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
        setTextIsSelectable(true)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(
        text: String,
        parent: LinearLayout = content,
        action: () -> Unit
    ): Button = Button(this, null, 0, R.style.PrimaryButton).apply {
        this.text = text
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
        setOnClickListener { action() }
    }

    // The absolute left slot, because a start-side drawable only counts towards the width once the view is attached.
    private fun syncSpinner(button: Button): SyncSpinner = SyncSpinner(dp(20)).also {
        button.setCompoundDrawablesWithIntrinsicBounds(it, null, null, null)
        button.compoundDrawablePadding = dp(8)
    }

    /** The wider of the button's two states, so the header never moves when Sync Now becomes Syncing. */
    private fun syncButtonWidth(): Int = listOf("Sync Now" to false, "Syncing" to true).maxOf { (label, syncing) ->
        Button(this, null, 0, R.style.PrimaryButton).apply {
            text = label
            if (syncing) syncSpinner(this)
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        }.measuredWidth
    }

    private fun separator(strong: Boolean = false) {
        content.addView(View(this).apply { setBackgroundColor(if (strong) Color.BLACK else Color.rgb(170, 170, 170)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun time(raw: String?): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(raw))
    }.getOrDefault("Unknown")

    /**
     * The full text of each issue an enabled service has. A book matched on a different edition is a success. Sign-in
     * and held updates are issues, and so are a rejected streak day and a tracker that keeps higher progress than NeoReader's.
     */
    private fun serviceIssues(name: String, state: JSONObject): List<String> {
        if (!state.getBoolean("enabled")) return emptyList()
        val last = state.optJSONObject("last")
        return buildList {
            val connectionError = state.optString("connectionError")
            when {
                state.getBoolean("credentialProblem") -> add("$name sign-in can no longer be read. Log out and sign in again.")

                !state.getBoolean("connected") -> add(
                    when (connectionError) {
                        "storygraph_browser_check_required" -> "StoryGraph asked for a browser check. Turn StoryGraph off and on to pass it in the sign-in popup."
                        "storygraph_session_expired" -> "StoryGraph signed you out. Turn StoryGraph off and on to sign in again."
                        "goodreads_browser_check_required" -> "Goodreads asked for a browser check that Boox Tracker could not pass. Turn Goodreads off and on to sign in again."
                        "goodreads_session_expired" -> "Goodreads signed you out. Turn Goodreads off and on to sign in again."
                        else -> "$name is not signed in. Turn $name off and on again to sign in."
                    }
                )
            }
            if (last == null) return@buildList
            // A held update whose reason is the session problem named above is the same issue, not a second one.
            if (last.optString("delivery") == "error" && last.optString("reason") != connectionError) add("Progress was not sent to $name: ${last.optString("reason").replace('_', ' ')}.")
            last.text("streakError")?.let { add("$name did not mark the streak day: ${it.replace('_', ' ')}.") }
            last.text("finishDateError")?.let { add("$name marked the book Read but did not set its finish date: ${it.replace('_', ' ')}.") }
            if (last.keptHigherProgress()) {
                // Each tracker compares in its own unit: Hardcover in pages, Fable in whole percent rounded down.
                val (held, compared) = if (last.has("remoteProgressPages")) {
                    "${last.optInt("remoteProgressPages")} of ${last.optInt("editionPages")} pages" to "${last.optInt("progressPages")} pages"
                } else {
                    "${last.optInt("remotePercent")}%" to "${last.optInt("percent")}% in whole percent"
                }
                add("$name has $held; NeoReader's ${Progress.parse(last.optString("rawProgress")).percent}% is $compared. Boox Tracker does not lower progress on a tracker.")
            }
        }
    }

    /** The full text of the NeoReader read issue that the header warns about, if there is one. */
    private fun readIssue(check: JSONObject?): String? {
        if (check?.optBoolean("issue") != true) return null
        val outcome = check.optString("outcome")
        val book = check.optJSONObject("selected")
        return when {
            outcome != "success" -> "NeoReader's library could not be read: ${outcome.replace('_', ' ')}."
            book == null -> "No single latest book: no book has a usable last access time, or two books share the latest one."
            else -> "NeoReader's progress for this book is unknown: ${progressState(book).lowercase()}."
        }
    }

    /** Each issue on its own line after ⚠, then a blank line; nothing when there is no issue. */
    private fun SpannableStringBuilder.appendIssues(issues: List<String>): SpannableStringBuilder = apply {
        issues.forEach { append("⚠ ").append(it).append('\n') }
        if (issues.isNotEmpty()) append('\n')
    }

    /** Draws each ⚠ in [view] as the amber triangle the header shows, at the size of the text. */
    private fun drawWarnings(view: TextView) {
        if ('⚠' !in view.text) return
        val text = SpannableStringBuilder(view.text)
        val size = view.textSize.toInt()
        for (index in text.indices) {
            if (text[index] != '⚠') continue
            val triangle = ContextCompat.getDrawable(this, R.drawable.warning)!!.apply { setBounds(0, 0, size, size) }
            text.setSpan(ImageSpan(triangle), index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
    }

    /**
     * Whether progress goes to the ebook's own edition, or null when the ebook's edition is unknown. Hardcover knows it
     * when the ebook's identifiers matched an edition exactly; Fable knows it from an identifier match, and then a
     * shelved sibling edition receives progress instead.
     */
    private fun JSONObject.sameEdition(name: String): Boolean? = if (name == "Hardcover") {
        if (isNull("sourceEditionId") || isNull("editionId")) null else getInt("editionId") == getInt("sourceEditionId")
    } else {
        if (optString("matchKind") == "edition") !optBoolean("existingEditionPreserved") else null
    }

    /** The latest update found the tracker ahead of NeoReader and left its progress unchanged. */
    private fun JSONObject.keptHigherProgress() = optString("delivery") == "synced" && optString("outcome") == "kept_higher_remote_progress"

    private fun showSync(snapshot: JSONObject?, check: JSONObject?, selected: JSONObject?, hardcover: JSONObject, goodreads: JSONObject, fable: JSONObject, storygraph: JSONObject) {
        val providerIssue = check?.optBoolean("issue") == true
        val issues = listOfNotNull(readIssue(check)) + serviceIssues("Hardcover", hardcover) + serviceIssues("Goodreads", goodreads) + serviceIssues("StoryGraph", storygraph) +
            serviceIssues("Fable", fable)
        val attention = issues.isNotEmpty()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(20))
            content.addView(this)
        }
        if (check != null) {
            row.addView(
                ImageView(this).apply {
                    id = if (attention) R.id.access_warning else R.id.status_icon
                    setImageResource(if (attention) R.drawable.warning else R.drawable.success)
                    contentDescription = if (attention) "Needs attention" else "OK"
                },
                LinearLayout.LayoutParams(dp(40), dp(44)).apply { marginEnd = dp(12) }
            )
        }
        val summary = LinearLayout(this).apply {
            id = R.id.book_summary
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(56)
            row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val title = selected?.raw("title")?.takeIf { it.isNotBlank() } ?: if (selected == null) "No book detected" else "Unknown title"
        val percent = selected?.optString("percentage")?.takeUnless { it.isBlank() || it == "null" }?.let { "$it%" } ?: "Unknown progress"
        label(if (selected == null) title else "$title · $percent", true, summary, 21f).setTextIsSelectable(false)
        label("${snapshot?.optInt("recordCount") ?: "—"} books · Read ${time(snapshot?.optString("readAt"))}", parent = summary, size = 16f).setTextIsSelectable(false)
        summary.setOnClickListener {
            lifecycleScope.launch {
                val details = withContext(Dispatchers.IO) { metadataDetails(selected, snapshot, check) }
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) notice(SpannableStringBuilder().appendIssues(issues).append(details))
            }
        }
        button(if (model.busy) "Syncing" else "Sync Now", row) { action { app.sync("manual", "sync_now") } }.apply {
            id = R.id.sync_now
            maxLines = 1
            isEnabled = !model.busy && !model.pickerActive
            // A running sync shows: the label changes and the spinner turns while this button is on screen.
            if (model.busy) {
                val spinner = syncSpinner(this)
                // The button joined an attached row already, so the attach callback will not fire for it.
                if (isAttachedToWindow) spinner.start()
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = spinner.start()

                    override fun onViewDetachedFromWindow(v: View) = spinner.stop()
                })
            }
            layoutParams = LinearLayout.LayoutParams(syncButtonWidth(), -2).apply { marginStart = dp(8) }
        }
        if (providerIssue) label(if (check.optString("outcome") != "success") "NeoReader: ${check.optString("outcome").replace('_', ' ')}" else "Saved book or progress unavailable", size = 16f)
        separator(true)
        showHardcover(hardcover)
        showGoodreads(goodreads)
        showStoryGraph(storygraph)
        showFable(fable)
        serviceRow("Margins", R.drawable.service_margins, getString(R.string.coming_soon))
    }

    private fun readableDate(millis: Long?): String {
        if (millis == null) return "Unknown"
        val date = java.util.Date(millis)
        return android.text.format.DateFormat.getMediumDateFormat(this).format(date) + ", " + android.text.format.DateFormat.getTimeFormat(this).format(date)
    }

    private fun metadataDetails(book: JSONObject?, snapshot: JSONObject?, check: JSONObject?): CharSequence = SpannableStringBuilder().apply {
        val outcome = when (check?.optString("outcome")) {
            "success" -> "OK"
            "provider_unavailable" -> "Unavailable"
            "permission_denied" -> "Permission Denied"
            "query_failed" -> "Query Failed"
            else -> "Not Checked"
        }
        field("NeoReader Database", outcome)
        if (book != null) {
            field("Title", value(book, "title"))
            field("Authors", value(book, "authors"))
            field("Filename", value(book, "filename"))
            val identifiers = try {
                BookIdentifierRepository(this@MainActivity, diagnostics.store).read(book)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                field("Identifier Error", if (error is SyncProblem) error.code.replace('_', ' ') else error.javaClass.simpleName)
                null
            }
            field("ISBN", identifiers?.isbns?.sorted()?.joinToString(", ")?.takeIf { it.isNotEmpty() } ?: if (identifiers == null) "Unavailable" else "Not Provided")
            identifiers?.tags?.toSortedMap()?.forEach { (tag, values) ->
                if (tag != "isbn" && values.isNotEmpty()) {
                    val label = when (tag) {
                        "asin" -> "ASIN"
                        "goodreads" -> "Goodreads"
                        "hardcover-id" -> "Hardcover ID"
                        "hardcover-slug" -> "Hardcover Slug"
                        "hardcover-edition" -> "Hardcover Edition"
                        "storygraph" -> "StoryGraph"
                        "fable" -> "Fable"
                        "margins" -> "Margins"
                        else -> tag
                    }
                    field(label, values.sorted().joinToString(", "))
                }
            }
            field("Raw Progress", value(book, "progress"))
            field("Calculated Progress", book.optString("percentage").takeUnless { it.isBlank() || it == "null" }?.let { "$it%" } ?: "Unknown")
            field("Reading Status", value(book, "readingStatus"))
            field("Progress State", progressState(book))
            field("Last Access", accessTime(book)?.let { readableDate(it) } ?: value(book, "lastAccess"))
        }
        field("Read At", readableDate(runCatching { java.time.Instant.parse(snapshot?.optString("readAt")).toEpochMilli() }.getOrNull()))
        check?.optJSONObject("error")?.let { field("Read Error", it.toString(2)) }
    }

    private fun progressState(book: JSONObject): String = when (book.optJSONObject("progress")?.optString("state")) {
        "missing" -> "Missing Column"
        "null" -> "Not Provided"
        "unreadable" -> "Unreadable"
        "value" -> if (book.isNull("progressProblem")) "OK" else book.optString("progressProblem").replaceFirstChar { it.uppercase() }
        else -> "Unknown"
    }

    private fun serviceRow(
        name: String,
        icon: Int,
        status: String,
        enabled: Boolean = false,
        available: Boolean = false,
        details: (() -> Unit)? = null,
        changed: ((Boolean) -> Unit)? = null
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(80)
            setPadding(0, dp(8), 0, dp(8))
            content.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        row.addView(
            ImageView(this).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(20) }
        )
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            row.addView(this, LinearLayout.LayoutParams(0, -2, 1f))
        }
        label(name, true, body, 22f).setTextIsSelectable(false)
        // The icon, name, and status lines all open the details, also while a sync runs; the switch keeps its own tap.
        if (details != null) {
            row.setOnClickListener { details() }
            ViewCompat.replaceAccessibilityAction(row, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, "Show $name details", null)
        }
        // Every status line is one row, so a provider row keeps the same height whatever its state.
        val lines = status.lines()
        lines.forEach { line ->
            label(line, parent = body, size = 17f).apply {
                setTextIsSelectable(false)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                drawWarnings(this)
            }
        }
        val switchParent = if (resources.configuration.screenWidthDp < 480 && resources.configuration.fontScale > 1.15f) body else row
        switchParent.addView(
            CheckBox(this).apply {
                val state = getString(if (enabled) R.string.on else R.string.off)
                text = getString(R.string.switch_state, state)
                contentDescription = getString(R.string.service_switch, name, (listOf(state) + lines.filter { it != state }).joinToString(". "))
                textSize = 18f
                setTextColor(Color.BLACK)
                minHeight = dp(48)
                minWidth = dp(96)
                buttonDrawable = ContextCompat.getDrawable(this@MainActivity, R.drawable.service_toggle)
                compoundDrawablePadding = dp(12)
                isChecked = enabled
                isEnabled = available
                setOnCheckedChangeListener { _, checked -> changed?.invoke(checked) }
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) }
        )
        separator()
    }

    private fun JSONObject.rawProgressPercent(): String? = Progress.parse(optString("rawProgress")).percent?.let { " · $it%" }

    private fun connectionText(error: String): String = when {
        listOf("_invalid_login_credentials", "_invalid_password", "_email_not_found").any(error::endsWith) -> "Email or password not accepted"
        error.endsWith("_too_many_attempts_try_later") -> "Too many attempts, try again later"
        error == "storygraph_browser_check_required" -> "StoryGraph asked for a browser check; sign in again"
        error == "storygraph_session_expired" -> "StoryGraph signed you out; sign in again"
        error == "storygraph_session_cookie_missing" -> "No StoryGraph session was saved; sign in again"
        error == "goodreads_browser_check_required" -> "Goodreads asked for a browser check; sign in again"
        error == "goodreads_session_expired" -> "Goodreads signed you out; sign in again"
        error == "goodreads_webview_profiles_unsupported" -> "Goodreads needs a newer Android System WebView"
        else -> error.replace('_', ' ')
    }

    /**
     * The two status lines under a provider's name: what is true about the current book, then how its sync stands.
     * Details live in the provider's popup, so each line stays a single short sentence.
     */
    private fun serviceSummary(state: JSONObject, name: String): String {
        val enabled = state.getBoolean("enabled")
        val connected = state.getBoolean("connected")
        val last = state.optJSONObject("last")
        val success = last?.optJSONObject("lastSuccess")
        val streakError = last?.text("streakError")
        val finishDateError = last?.text("finishDateError")
        val delivery = last?.optString("delivery")
        val edition = when (last?.sameEdition(name)) {
            true -> " · same edition"
            false -> " · different edition"
            null -> ""
        }
        val first = when {
            !enabled -> "Off"
            !connected || state.getBoolean("credentialProblem") -> "❌ Reconnect required"
            delivery == "error" -> "❌ ${last.optString("reason").replace('_', ' ')}"
            streakError != null -> "⚠ Streak day not marked · ${streakError.replace('_', ' ')}"
            finishDateError != null -> "⚠ Finish date not set · ${finishDateError.replace('_', ' ')}"
            last?.optString("matchKind") in setOf("edition", "book") -> "✅ Book matched$edition"
            else -> "Not matched yet"
        }
        val synced = success?.let { time(it.optString("timestamp")) + (if (it.optBoolean("finished")) " · Finished" else it.rawProgressPercent().orEmpty()) }
        val lastSynced = synced?.let { " · last synced $it" }.orEmpty()
        val queued = state.optInt("pending")
        // The queued count comes last: it is also in the details popup, so ellipsizing may hide it.
        val second = when {
            !enabled -> state.text("connectionError")?.let(::connectionText) ?: if (connected) "Not syncing" else "Not connected"
            delivery == "pending" -> "Pending" + lastSynced + (if (queued > 1) " · $queued queued" else "")
            delivery == "error" -> "Not sent$lastSynced"
            else -> (if (last?.keptHigherProgress() == true) "⚠ " else "") + (synced?.let { "Synced at $it" } ?: "Not synced yet") + (if (queued > 0) " · $queued queued" else "")
        }
        return "$first\n$second"
    }

    private fun showHardcover(state: JSONObject) {
        val signIn = when {
            !app.hardcover.signingIn -> null
            app.hardcover.device == null -> "Sign-in pending\nRequesting a sign-in code…"
            else -> "Sign-in pending\nApprove the code in the popup"
        }
        serviceRow(
            "Hardcover",
            R.drawable.service_hardcover,
            signIn ?: serviceSummary(state, "Hardcover"),
            state.getBoolean("enabled") || app.hardcover.signingIn,
            !model.busy,
            details = { showServiceDetails("Hardcover", app.hardcover, hardcoverReport) },
            changed = { checked ->
                action {
                    app.hardcover.setEnabled(checked)
                    if (checked && app.hardcover.state().getBoolean("enabled")) app.sync("manual", "service_enabled")
                }
            }
        )
    }

    private fun showFable(state: JSONObject) {
        val fable = app.fable
        val signIn = when {
            fable.signingIn -> "Signing in…\nChecking your email and password"
            fable.awaitingCredentials -> "Sign-in required\nEnter your email and password in the popup"
            else -> null
        }
        serviceRow(
            "Fable",
            R.drawable.service_fable,
            signIn ?: serviceSummary(state, "Fable"),
            state.getBoolean("enabled") || fable.signingIn || fable.awaitingCredentials,
            !model.busy,
            details = { showServiceDetails("Fable", fable, fableReport) },
            changed = { checked ->
                if (!checked) model.fablePassword = ""
                action(fableReport) {
                    fable.setEnabled(checked)
                    if (checked && fable.state().getBoolean("enabled")) app.sync("manual", "service_enabled")
                }
            }
        )
    }

    private fun showGoodreads(state: JSONObject) {
        val goodreads = app.goodreads
        val signIn = when {
            goodreads.signingIn -> "Signing in…\nReading your Goodreads session"
            goodreads.awaitingCredentials -> "Sign-in required\nSign in to Goodreads in the popup"
            else -> null
        }
        serviceRow(
            "Goodreads",
            R.drawable.service_goodreads,
            signIn ?: serviceSummary(state, "Goodreads"),
            state.getBoolean("enabled") || goodreads.signingIn || goodreads.awaitingCredentials,
            !model.busy,
            details = { showServiceDetails("Goodreads", goodreads, goodreadsReport) },
            changed = { checked ->
                action(goodreadsReport) {
                    goodreads.setEnabled(checked)
                    if (checked && goodreads.state().getBoolean("enabled")) app.sync("manual", "service_enabled")
                }
            }
        )
    }

    private fun showStoryGraph(state: JSONObject) {
        val storygraph = app.storygraph
        val signIn = when {
            storygraph.signingIn -> "Signing in…\nReading your StoryGraph session"
            storygraph.awaitingCredentials -> "Sign-in required\nSign in to StoryGraph in the popup"
            else -> null
        }
        serviceRow(
            "StoryGraph",
            R.drawable.service_storygraph,
            signIn ?: serviceSummary(state, "StoryGraph"),
            state.getBoolean("enabled") || storygraph.signingIn || storygraph.awaitingCredentials,
            !model.busy,
            details = { showServiceDetails("StoryGraph", storygraph, storyGraphReport) },
            changed = { checked ->
                action(storyGraphReport) {
                    storygraph.setEnabled(checked)
                    if (checked && storygraph.state().getBoolean("enabled")) app.sync("manual", "service_enabled")
                }
            }
        )
    }

    /** Runs a connection change from a popup in the app scope, so it completes even if the screen closes. */
    private fun runDetached(report: (Exception) -> Unit, block: suspend () -> Unit) {
        diagnostics.scope.launch {
            try {
                block()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                report(error)
            }
        }
    }

    private fun updateHardcoverSignIn() {
        val hardcover = app.hardcover
        if (!hardcover.signingIn) {
            hardcoverSignIn?.dismiss()
            hardcoverSignIn = null
            return
        }
        val device = hardcover.device
        val message: CharSequence = if (device == null) {
            "Requesting a sign-in code…"
        } else {
            // The code is what the user copies or reads out, so it stands out from the instructions.
            SpannableStringBuilder("Sign-in code: ").apply {
                val start = length
                append(device.userCode)
                setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("\n\nOpen hardcover.app/link on this device or another device. Sign in, enter the code, and approve the connection. The code expires automatically.")
            }
        }
        val dialog = hardcoverSignIn ?: bordered(
            AlertDialog.Builder(this).setTitle("Hardcover sign-in").setMessage(message)
                .setPositiveButton("Open Hardcover sign-in", null).setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
                .setOnCancelListener { runDetached(hardcoverReport) { hardcover.setEnabled(false) } }
                .create().apply { setCanceledOnTouchOutside(false) }
        ).also { dialog ->
            // Open Hardcover sign-in keeps the popup open; it closes when sign-in finishes or is cancelled.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { app.hardcover.device?.let { startActivity(Intent(Intent.ACTION_VIEW, it.verification.toUri())) } }
            // The user may need to copy the code into a browser on this device.
            dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            hardcoverSignIn = dialog
        }
        // Rewriting unchanged text would clear a selection the user started.
        if (dialog.findViewById<TextView>(android.R.id.message)?.text?.toString() != message.toString()) dialog.setMessage(message)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = device != null
    }

    private fun updateFableSignIn(state: JSONObject) {
        val fable = app.fable
        if (!fable.awaitingCredentials && !fable.signingIn) {
            fableSignIn?.dialog?.dismiss()
            fableSignIn = null
            return
        }
        val form = fableSignIn ?: fableSignInDialog().also { fableSignIn = it }
        val error = state.optString("connectionError")
        form.status.text = when {
            fable.signingIn -> "Signing in…"
            error.isNotBlank() -> connectionText(error)
            else -> ""
        }
        form.status.isGone = form.status.text.isEmpty()
        form.email.isEnabled = !fable.signingIn
        form.password.isEnabled = !fable.signingIn
        form.dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = fableReady()
    }

    private fun updateStoryGraphSignIn(state: JSONObject) {
        val storygraph = app.storygraph
        if (!storygraph.awaitingCredentials && !storygraph.signingIn) {
            storyGraphSignIn?.dialog?.dismiss()
            storyGraphSignIn = null
            return
        }
        val form = storyGraphSignIn ?: webSignInDialog(
            "StoryGraph",
            "Sign in on StoryGraph's own website. Boox Tracker never sees or remembers your password; it keeps the browser session on this device.",
            R.id.storygraph_sign_in_web,
            R.id.storygraph_sign_in_status,
            storygraph.http.origin,
            "/users/sign_in",
            homeLoaded = { userAgent ->
                if (storygraph.awaitingCredentials && !storygraph.signingIn) runDetached(storyGraphReport) { storygraph.sessionCaptured(userAgent) }
            },
            cancelled = { runDetached(storyGraphReport) { storygraph.setEnabled(false) } }
        ).also { storyGraphSignIn = it }
        showWebSignInStatus(form, "StoryGraph", storygraph.signingIn, state)
    }

    private fun updateGoodreadsSignIn(state: JSONObject) {
        val goodreads = app.goodreads
        if (!goodreads.awaitingCredentials && !goodreads.signingIn) {
            goodreadsSignIn?.dialog?.dismiss()
            goodreadsSignIn = null
            return
        }
        val form = goodreadsSignIn ?: webSignInDialog(
            "Goodreads",
            "Sign in on Goodreads' own website. Boox Tracker never sees or remembers your password; it keeps the browser session on this device.",
            R.id.goodreads_sign_in_web,
            R.id.goodreads_sign_in_status,
            goodreads.http.origin,
            "/user/sign_in",
            // Goodreads signs in through Amazon's pages on its own host; Apple, Google, and Facebook sign-in leave it.
            otherSites = listOf("goodreads.com", "amazon.com", "apple.com", "google.com", "facebook.com"),
            prepare = goodreads.session.profile::attach,
            homeLoaded = { userAgent ->
                if (goodreads.awaitingCredentials && !goodreads.signingIn) runDetached(goodreadsReport) { goodreads.sessionCaptured(userAgent) }
            },
            cancelled = { runDetached(goodreadsReport) { goodreads.setEnabled(false) } }
        ).also { goodreadsSignIn = it }
        showWebSignInStatus(form, "Goodreads", goodreads.signingIn, state)
    }

    /** The sign-in popup's status line: the session read while it runs, else the connection problem, else the page's load failure. */
    private fun showWebSignInStatus(form: WebSignInViews, name: String, signingIn: Boolean, state: JSONObject) {
        val error = state.optString("connectionError")
        form.status.text = when {
            signingIn -> "Reading your $name session…"
            error.isNotBlank() -> connectionText(error)
            else -> form.loadError.orEmpty()
        }
        form.status.isGone = form.status.text.isEmpty()
    }

    /**
     * A website's own sign-in page in a WebView, so the password never reaches the app and the site sees a browser.
     * The WebView stays on [origin] and [otherSites] with their subdomains. [prepare] runs before anything else touches
     * the WebView. The page is loaded once; screen updates only change the status line. Every load of the origin's home
     * page, as a finished page or a history update, goes to [homeLoaded] with the WebView's User-Agent.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun webSignInDialog(
        name: String,
        intro: String,
        webId: Int,
        statusId: Int,
        origin: String,
        signInPath: String,
        otherSites: List<String> = emptyList(),
        prepare: (WebView) -> Unit = {},
        homeLoaded: (userAgent: String) -> Unit,
        cancelled: () -> Unit
    ): WebSignInViews {
        val host = origin.toUri().host
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        label(intro, parent = body, size = 17f).setTextIsSelectable(false)
        val web = WebView(this).also(prepare).apply {
            id = webId
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // A site that serves a tablet its desktop page without a viewport tag, such as Goodreads, is zoomed out
            // to the popup's width instead of scrolling sideways; pages with a viewport tag keep their own layout.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            setBackgroundColor(Color.WHITE)
        }
        fixedPane(0.6f).apply {
            addView(web, FrameLayout.LayoutParams(-1, -1))
            body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
        }
        val status = label("", parent = body, size = 17f).apply {
            id = statusId
            setTextIsSelectable(false)
        }
        lateinit var views: WebSignInViews

        // WebView settings are read here on the main thread; the capture itself runs detached, because it is a page
        // event rather than a tap and must not be dropped while another action keeps the screen busy.
        fun capture(url: String?) {
            val uri = url?.toUri() ?: return
            if (uri.host == host && uri.path.orEmpty().trimEnd('/').isEmpty()) homeLoaded(web.settings.userAgentString)
        }

        fun allowed(uri: Uri): Boolean {
            val target = uri.host.orEmpty()
            return uri.host == host || otherSites.any { target == it || target.endsWith(".$it") }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame && !allowed(request.url)

            override fun onPageFinished(view: WebView, url: String?) = capture(url)

            // A single-page sign-in can finish by replacing history instead of a full page load.
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = capture(url)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                views.loadError = "$name could not be loaded: ${error.description}. Check the connection, then turn $name off and on."
                refresh()
            }
        }
        val dialog = bordered(
            AlertDialog.Builder(this).setTitle("$name sign-in").setView(body)
                .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
                .setOnCancelListener { cancelled() }
                .create().apply { setCanceledOnTouchOutside(false) }
        ) { web.destroy() }
        // The dialog marks itself as no input-method target when its view has no text editor at show time, and a
        // WebView has none until its page focuses a field; the page's fields can raise the keyboard only without that mark.
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        web.loadUrl(origin + signInPath)
        views = WebSignInViews(dialog, status)
        return views
    }

    private fun fableReady() = model.fableEmail.isNotBlank() && model.fablePassword.isNotEmpty() && !model.busy && !app.fable.signingIn

    private fun fableSignInDialog(): FableSignInViews {
        val fable = app.fable
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        label("Sign in with your Fable email and password. Boox Tracker keeps only Fable's sign-in tokens on this device and never stores your password. Fable has no public API, so this connection may stop working if Fable changes.", parent = body, size = 17f).setTextIsSelectable(false)
        lateinit var dialog: AlertDialog
        val email = textField(body, R.id.fable_email, "Email", model.fableEmail, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, View.AUTOFILL_HINT_EMAIL_ADDRESS, EditorInfo.IME_ACTION_NEXT) {
            model.fableEmail = it
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = fableReady()
        }
        val password = textField(body, R.id.fable_password, "Password", model.fablePassword, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, View.AUTOFILL_HINT_PASSWORD, EditorInfo.IME_ACTION_DONE) {
            model.fablePassword = it
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = fableReady()
        }.apply { isSaveEnabled = false }
        val status = label("", parent = body, size = 17f).apply {
            id = R.id.fable_sign_in_status
            setTextIsSelectable(false)
        }
        fun submit() {
            if (!fableReady()) return
            val typedEmail = model.fableEmail.trim()
            val typedPassword = model.fablePassword
            password.setText("")
            action(fableReport) { fable.signIn(typedEmail, typedPassword) }
        }
        password.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) submit() }
        }
        dialog = bordered(
            AlertDialog.Builder(this).setTitle("Fable sign-in").setView(body)
                .setPositiveButton("Sign in", null).setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
                .setOnCancelListener {
                    model.fablePassword = ""
                    runDetached(fableReport) { fable.setEnabled(false) }
                }.create().apply { setCanceledOnTouchOutside(false) }
        )
        // Sign in keeps the popup open; it closes when sign-in succeeds or is cancelled.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        return FableSignInViews(dialog, email, password, status)
    }

    private fun textField(parent: LinearLayout, fieldId: Int, hint: String, value: String, type: Int, autofill: String, ime: Int, changed: (String) -> Unit): EditText = EditText(this).apply {
        id = fieldId
        this.hint = hint
        inputType = type
        imeOptions = ime
        setAutofillHints(autofill)
        textSize = 18f
        setTextColor(Color.BLACK)
        setHintTextColor(Color.rgb(85, 85, 85))
        minHeight = dp(56)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            setStroke(dp(2), Color.BLACK)
        }
        setText(value)
        setSelection(value.length)
        doAfterTextChanged { changed(it?.toString().orEmpty()) }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
    }

    private val hardcoverReport: (Exception) -> Unit = { app.hardcover.recordFailure("manual", "hardcover_operation", it) }
    private val fableReport: (Exception) -> Unit = { app.fable.recordFailure("manual", "fable_operation", it) }
    private val storyGraphReport: (Exception) -> Unit = { app.storygraph.recordFailure("manual", "storygraph_operation", it) }
    private val goodreadsReport: (Exception) -> Unit = { app.goodreads.recordFailure("manual", "goodreads_operation", it) }

    private fun action(report: (Exception) -> Unit = hardcoverReport, block: suspend () -> Unit) {
        if (model.busy) return
        model.busy = true
        refresh()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { report(error) }
            } finally {
                model.busy = false
                refresh()
            }
        }
    }

    private fun value(
        book: JSONObject,
        name: String
    ): String = book.raw(name) ?: when (book.optJSONObject(name)?.optString("state")) {
        "null" -> "Not Provided"
        "unreadable" -> "Unreadable"
        else -> "Missing Column"
    }

    private fun showAbout() {
        bordered(
            AlertDialog.Builder(this).setTitle("Boox Tracker ${BuildConfig.VERSION_NAME}")
                .setMessage("Reading progress from NeoReader to your enabled trackers.\n\nOffline updates stay on this device until they can be sent. Android and BOOX can delay background work.\n\nRead-only book access. Logs stay local until exported. Activity keeps up to 1,000 events for 30 days; routine checks from before the last successful sync are removed after two days.\n\nMIT licence · ${BuildConfig.BUILD_TYPE} build")
                .setPositiveButton("Close", null).setNeutralButton("GitHub") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/otherguy/boox-tracker".toUri())) }
                .setNegativeButton("Change ebook folder") { _, _ -> chooseFolder() }.create()
        )
    }

    /** Provider popup: account, connection, and the current book's match and delivery, with Log out. */
    private fun showServiceDetails(name: String, connection: TrackerConnection, report: (Exception) -> Unit) {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { connection.state() }
            val builder = AlertDialog.Builder(this@MainActivity).setTitle(name).setMessage(serviceDetailsText(name, state)).setPositiveButton("Close", null)
            if (state.getBoolean("connected") || state.getBoolean("credentialProblem") || state.optInt("pending") > 0) {
                builder.setNegativeButton("Log out") { _, _ -> confirmLogOut(name, connection, report) }
            }
            bordered(builder.create())
        }
    }

    private fun confirmLogOut(name: String, connection: TrackerConnection, report: (Exception) -> Unit) {
        bordered(
            AlertDialog.Builder(this).setTitle("Log out of $name?")
                .setMessage("Boox Tracker will remove your $name sign-in from this device, turn $name off, and delete any updates that have not been sent yet.")
                .setPositiveButton("Log out") { _, _ -> runDetached(report) { connection.logOut() } }
                .setNegativeButton("Cancel", null).create()
        )
    }

    private fun confirmClearActivity() {
        bordered(
            AlertDialog.Builder(this).setTitle("Clear activity?")
                .setMessage("Boox Tracker will delete every event in Activity on this device and keep your sync state and queued updates.")
                .setPositiveButton("Clear") { _, _ ->
                    resetActivityScroll = true
                    runDetached({ diagnostics.event("manual", "activity_clear_failed", detail = errorDetails(it, "clear"), issue = true) }) { diagnostics.clearActivity() }
                }
                .setNegativeButton("Cancel", null).create()
        )
    }

    private fun SpannableStringBuilder.field(label: String, value: String) {
        val start = length
        append("$label: ")
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        append(value).append('\n')
    }

    private fun SpannableStringBuilder.heading(text: String) {
        val start = length
        append('\n').append(text).append('\n')
        setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** A provider date (ISO instant, offset date-time, or date) in the device's date format; the raw value if unparsed. */
    private fun readableDay(raw: String): String {
        val date = runCatching { java.time.Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
            .recoverCatching { java.time.OffsetDateTime.parse(raw).toLocalDate() }
            .recoverCatching { java.time.LocalDate.parse(raw.take(10)) }.getOrNull() ?: return raw
        return android.text.format.DateFormat.getMediumDateFormat(this).format(java.util.Date.from(date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()))
    }

    private fun serviceDetailsText(name: String, state: JSONObject): CharSequence = SpannableStringBuilder().apply {
        appendIssues(serviceIssues(name, state))
        val profile = state.optJSONObject("profile")
        if (!state.getBoolean("connected")) {
            field("Account", if (state.getBoolean("credentialProblem")) "Sign-in can no longer be read; log out and sign in again" else "Not signed in")
            state.text("connectionError")?.let { field("Sign-in problem", connectionText(it)) }
        } else {
            field("Account", listOfNotNull(profile?.text("username")?.let { "@$it" }, profile?.text("name")).joinToString(" · ").ifEmpty { "Signed in" })
            profile?.text("email")?.let { field("Email", it) }
            field("Connected since", state.optLong("connectedAt").takeIf { it > 0 }?.let { android.text.format.DateFormat.getMediumDateFormat(this@MainActivity).format(java.util.Date(it)) } ?: "Unknown")
            profile?.text("createdAt")?.let { field("Account created", readableDay(it)) }
            profile?.text("membership")?.let { field("Membership", it) }
        }
        field("Sync", if (state.getBoolean("enabled")) "On" else "Off")
        state.optInt("pending").takeIf { it > 0 }?.let { field("Queued updates", it.toString()) }
        heading("Current book")
        val last = state.optJSONObject("last")
        val result = last?.optJSONObject("lastSuccess")
        if (last == null || result == null) {
            // Nothing has reached the provider for this book, so there is no match to describe yet.
            append("No update has been sent for the current book yet.\n")
            last?.let { field("Last sync", syncText(it)) }
            return@apply
        }
        resultFields(name, result)
        field("Last sync", syncText(last))
    }

    /** What a tracker reported for a send: the book, how it matched, the edition, and the progress it holds. */
    private fun SpannableStringBuilder.resultFields(name: String, result: JSONObject) {
        field("Book", result.text("title") ?: "Unknown title")
        // Hardcover reports a book-only match when it keeps your existing edition; the source edition shows the match itself.
        val exact = if (name == "Hardcover") !result.isNull("sourceEditionId") else result.optString("matchKind") == "edition"
        val preserved = result.optBoolean("existingEditionPreserved")
        val pages = result.optInt("editionPages").takeIf { it > 0 }
        // A provider keeps its own progress when it is ahead of NeoReader, so the popup shows what the provider holds.
        val kept = result.optString("outcome") == "kept_higher_remote_progress"
        val keptNote = if (kept) " · kept, higher than NeoReader" else ""
        if (name == "Hardcover") {
            field("Match", if (exact) "Exact edition" else "Book only; your ebook's edition was not found on Hardcover")
            // Without an existing edition, only a missing page count sends progress past the ebook's own edition.
            val edition = if (preserved) {
                "Your existing Hardcover edition, not the one your ebook matched"
            } else {
                when (result.sameEdition(name)) {
                    true -> "Your ebook's edition"
                    false -> "Another Hardcover edition; your ebook's edition has no page count"
                    null -> "A Hardcover edition of this book"
                }
            }
            field("Edition", edition + (pages?.let { " · $it pages" } ?: ""))
            if (result.optBoolean("finished")) {
                field("Progress", "Finished" + (result.text("finishedAt")?.let { " on ${readableDay(it)}" } ?: ""))
            } else {
                pages?.let { field("Progress", "${result.optInt(if (kept) "remoteProgressPages" else "progressPages")} of $it pages$keptNote") }
            }
        } else {
            field("Match", if (exact) "Exact edition, by its identifiers" else "Book, by title and author")
            field("Edition", if (preserved) "The edition you shelved on $name, not the one your ebook matched" else "The edition your ebook matched")
            val waiting = result.optInt("nextUpdateAt").takeIf { it > 0 }
            field(
                "Progress",
                when {
                    result.optBoolean("finished") -> "Finished" + (result.text("finishDate")?.let { " on ${readableDay(it)}" } ?: "")

                    // Goodreads posts every update to friends' feeds, so a small step waits for the next one.
                    waiting != null -> "${result.optInt("remotePercent")}% · the next update is sent at $waiting%"

                    else -> "${result.optInt(if (kept) "remotePercent" else "percent")}%$keptNote"
                }
            )
            result.text("finishDateError")?.let { field("Finish date", "Not set (${it.replace('_', ' ')})") }
            result.text("shelfAfter")?.let { shelf ->
                field("Shelf", mapOf("current_reading" to "Currently Reading", "currently_reading" to "Currently Reading", "want_to_read" to "Want to Read", "to_read" to "To Read", "finished" to "Finished", "read" to "Read", "did_not_finish" to "Did Not Finish", "paused" to "Paused", "rereading" to "Rereading")[shelf] ?: shelf)
            }
            val streakError = result.text("streakError")
            val streakDate = result.text("streakDate")
            when {
                streakError != null -> field("Streak day", "Not marked (${streakError.replace('_', ' ')})")
                streakDate != null -> field("Streak day", readableDay(streakDate))
            }
        }
        result.text("rawProgress")?.let { raw -> Progress.parse(raw).percent?.let { field("NeoReader progress", "$it%") } }
    }

    private fun syncText(last: JSONObject): String {
        val sent = last.optJSONObject("lastSuccess")?.text("timestamp")?.let(::time)
        val lastSent = sent?.let { " · last sent at $it" }.orEmpty()
        return when (last.optString("delivery")) {
            "pending" -> "Waiting to send$lastSent"
            "error" -> "Not sent: ${last.optString("reason").replace('_', ' ')}$lastSent"
            else -> sent?.let { "Sent at $it" } ?: "Sent"
        }
    }

    /** Event popup: a Summary tab of labelled fields and a JSON tab with the newest event's stored record. */
    private fun showEventDetails(entry: ActivityEntry, text: EventText) {
        var formatting: Job? = null
        val body = LinearLayout(this).apply {
            id = R.id.event_details
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val tabs = LinearLayout(this).apply { body.addView(this) }
        fun tab(id: Int?, label: Int): Pair<Button, View> {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tabs.addView(this)
            }
            val button = Button(this, null, 0, R.style.TextButton).apply {
                id?.let { this.id = it }
                setText(label)
                column.addView(this, LinearLayout.LayoutParams(-2, dp(56)))
            }
            val underline = View(this).apply { setBackgroundColor(Color.BLACK) }
            column.addView(underline, LinearLayout.LayoutParams(-1, dp(4)))
            return button to underline
        }
        val (summaryTab, summaryLine) = tab(null, R.string.event_tab_summary)
        val (jsonTab, jsonLine) = tab(R.id.event_tab_json, R.string.event_tab_json)
        body.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(1)))
        // The panes take a fixed share of the screen, or less when the popup has less room, whatever each tab holds,
        // so switching tabs never resizes the popup and the Close button stays visible.
        val panes = fixedPane(0.55f).apply {
            id = R.id.event_panes
            body.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val summaryPane = ScrollView(this).apply {
            id = R.id.event_summary_pane
            overScrollMode = View.OVER_SCROLL_NEVER
            panes.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        TextView(this).apply {
            id = R.id.event_summary_text
            this.text = eventSummary(entry, text)
            textSize = 17f
            setTextColor(Color.BLACK)
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(0, dp(12), 0, dp(12))
            setTextIsSelectable(true)
            summaryPane.addView(this)
        }
        val jsonPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            panes.addView(this, FrameLayout.LayoutParams(-1, -1))
        }
        TextView(this).apply {
            id = R.id.event_json_note
            this.text = resources.getQuantityString(R.plurals.event_json_note, entry.events.size, entry.events.size)
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, 0)
            isGone = entry.events.size < 2
            jsonPane.addView(this)
        }
        val jsonScroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            jsonPane.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val json = TextView(this).apply {
            id = R.id.event_json
            typeface = Typeface.MONOSPACE
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, dp(12), 0, dp(12))
            setTextIsSelectable(true)
            jsonScroll.addView(this)
        }
        fun select(showJson: Boolean) {
            summaryPane.isGone = showJson
            jsonPane.isGone = !showJson
            summaryLine.isInvisible = showJson
            jsonLine.isInvisible = !showJson
            summaryTab.select(!showJson)
            jsonTab.select(showJson)
            // The record is formatted the first time its tab opens, off the main thread, and kept on the entry.
            if (showJson && json.text.isEmpty() && formatting?.isActive != true) {
                formatting = lifecycleScope.launch {
                    json.text = entry.json ?: withContext(Dispatchers.Default) { entry.events.first().toString(2) }.also { entry.json = it }
                }
            }
        }
        summaryTab.setOnClickListener { select(false) }
        jsonTab.setOnClickListener { select(true) }
        select(false)
        bordered(AlertDialog.Builder(this).setTitle(text.title).setView(body).setPositiveButton("Close", null).create()) { formatting?.cancel() }
    }

    /** Marks a tab or filter segment as chosen: selected state for TalkBack and the drawable, bold text. */
    private fun Button.select(selected: Boolean) {
        isSelected = selected
        typeface = Typeface.create("sans", if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun duration(millis: Long) = if (millis < 1000) "$millis ms" else String.format(java.util.Locale.getDefault(), "%.1f s", millis / 1000.0)

    /** The Summary tab: the event's important fields with bold labels, and counts for a grouped row. */
    private fun eventSummary(entry: ActivityEntry, text: EventText): CharSequence = SpannableStringBuilder().apply {
        fun bookFields(book: JSONObject?) {
            book?.let(::bookTitle)?.let { field("Book", it) }
            book?.text("percentage")?.let { field("Progress", "$it%") }
        }
        val event = entry.events.first()
        val kind = event.optString("kind")
        val size = entry.events.size
        field("Time", readableDate(eventMillis(event)))
        field("Source", sourceText(event) + (event.text("trigger")?.let { " · ${triggerText(it)}" } ?: ""))
        field("Event", text.title)
        if (size > 1) {
            field(if (kind == "query" && !event.optBoolean("issue")) "Checks" else "Times", "$size since ${readableDate(eventMillis(entry.events.last()))}")
            val gaps = entry.events.mapNotNull { it.optLong("gapMs").takeIf { gap -> gap > 0 }?.let { gap -> (gap + 30_000) / 60_000 } }
            if (gaps.isNotEmpty()) field("Time between checks", if (gaps.min() == gaps.max()) "${gaps.min()} min" else "${gaps.min()}–${gaps.max()} min")
        }
        event.text("outcome")?.let { field("Outcome", words(it)) }
        event.text("reason")?.let { field("Reason", words(it)) }
        when {
            kind == "query" -> {
                bookFields(event.optJSONObject("selected"))
                if (event.has("recordCount") && !event.isNull("recordCount")) field("Library", "${event.optInt("recordCount")} books")
                changeCount(event)?.takeIf { it > 0 }?.let { field("Changes", it.toString()) }
            }

            kind == "queued" -> bookFields(event)

            kind.endsWith("_sync") && event.optString("outcome") in setOf("sent", "kept_higher_remote_progress", "already_current") ->
                resultFields(serviceName(event), event)
        }
        event.optInt("deletedUpdates").takeIf { it > 0 }?.let { field("Queued updates deleted", it.toString()) }
        if (kind == "activity_cleared") field("Events deleted", event.optInt("deletedEvents").toString())
        event.text("startedAt")?.let { field("Started", readableDate(isoMillis(it))) }
        if (event.has("durationMs")) field("Duration", duration(event.optLong("durationMs")))
        // Background evidence needs the app hidden from the start of a read or run until its event.
        val visible = entry.events.map { it.optBoolean("appVisibleAtStart") || it.optBoolean("appVisible") }
        fun yesNo(value: Boolean) = if (value) "Yes" else "No"
        field(
            "App visible",
            when {
                size == 1 && event.has("appVisibleAtStart") -> "${yesNo(event.optBoolean("appVisibleAtStart"))} at start, ${yesNo(event.optBoolean("appVisible")).lowercase()} at end"
                size == 1 -> yesNo(visible.single())
                visible.none { it } -> "No, for all $size"
                visible.all { it } -> "Yes, for all $size"
                else -> "Yes for ${visible.count { it }} of $size"
            }
        )
        (event.optJSONObject("error") ?: event.takeIf { it.has("class") && it.has("phase") })?.let { error ->
            field("Error", listOfNotNull(error.text("class")?.substringAfterLast('.'), error.text("phase")).joinToString(" · "))
        } ?: event.text("errorClass")?.let { field("Error", it.substringAfterLast('.')) }
        event.text("runId")?.let { field("Run", it) }
    }

    /** A pane that takes [fraction] of the screen height, or less when the popup has less room, whatever it holds. */
    private fun fixedPane(fraction: Float): FrameLayout = object : FrameLayout(this) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val target = (resources.displayMetrics.heightPixels * fraction).toInt()
            val height = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) target else minOf(target, MeasureSpec.getSize(heightSpec))
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    override fun onDestroy() {
        dialogs.toList().forEach { it.dismiss() }
        super.onDestroy()
    }

    /** Shows [dialog] with a black border and no animation, with each ⚠ in its message drawn as the amber triangle. */
    private fun bordered(dialog: AlertDialog, onDismiss: (() -> Unit)? = null): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener {
            dialogs.remove(dialog)
            onDismiss?.invoke()
        }
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.let(::drawWarnings)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
            }
        )
        dialog.window?.setWindowAnimations(0)
        return dialog
    }

    private fun notice(text: CharSequence) {
        bordered(AlertDialog.Builder(this).setMessage(text).setPositiveButton("Close", null).create())
    }
}
