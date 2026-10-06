package dev.otherguy.booxtracker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isGone
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
    val expanded = mutableSetOf<String>()
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
        activityLog = ActivityLogAdapter(this, lifecycleScope, model)
        findViewById<ListView>(R.id.activity_entries).apply {
            adapter = activityLog
            itemsCanFocus = true
            emptyView = this@MainActivity.findViewById(R.id.activity_empty)
        }
        findViewById<Button>(R.id.activity_filter).setOnClickListener {
            model.issues = !model.issues
            resetActivityScroll = true
            refresh()
        }
        findViewById<Button>(R.id.export).setOnClickListener {
            lifecycleScope.launch {
                val intent = withContext(Dispatchers.IO) { exportIntent(this@MainActivity, buildExport(diagnostics)) }
                startActivity(Intent.createChooser(intent, "Export diagnostics"))
            }
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
                val entries = withContext(Dispatchers.IO) { activityEntries(diagnostics.store.events(250)) }
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
                findViewById<Button>(R.id.activity_filter).setText(if (issues) R.string.issues_selected else R.string.all_selected)
            } else {
                val snapshot = withContext(Dispatchers.IO) { diagnostics.snapshot() }
                val check = withContext(Dispatchers.IO) { diagnostics.store.get("lastCheck")?.let(::JSONObject) }
                val selected = mostRecentlyAccessedBook(snapshot)
                val hardcover = withContext(Dispatchers.IO) { app.hardcover.state() }
                content.removeAllViews()
                showSync(snapshot, check, selected, hardcover)
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
        outlined: Boolean = false,
        action: () -> Unit
    ): Button = Button(this, null, 0, if (outlined) R.style.OutlineButton else R.style.PrimaryButton).apply {
        this.text = text
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
        setOnClickListener { action() }
    }

    private fun separator(strong: Boolean = false) {
        content.addView(View(this).apply { setBackgroundColor(if (strong) Color.BLACK else Color.rgb(170, 170, 170)) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun time(raw: String?): String = runCatching {
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(raw))
    }.getOrDefault("Unknown")

    private fun showSync(snapshot: JSONObject?, check: JSONObject?, selected: JSONObject?, hardcover: JSONObject) {
        val last = hardcover.optJSONObject("last")
        val providerIssue = check?.optBoolean("issue") == true
        val serviceIssue = hardcover.getBoolean("enabled") && (hardcover.getBoolean("credentialProblem") || !hardcover.getBoolean("connected") || last?.optString("delivery") == "error" || last?.optString("matchKind") == "book")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(20), 0, dp(20))
            content.addView(this)
        }
        if (check != null) {
            row.addView(
                ImageView(this).apply {
                    id = if (providerIssue || serviceIssue) R.id.access_warning else R.id.status_icon
                    setImageResource(if (providerIssue || serviceIssue) R.drawable.warning else R.drawable.success)
                    contentDescription = if (providerIssue || serviceIssue) "Needs attention" else "OK"
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
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) notice(details)
            }
        }
        button("Sync Now", row) { action { app.sync("manual", "sync_now") } }.apply {
            id = R.id.sync_now
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) }
            isEnabled = !model.busy && !model.pickerActive
        }
        if (providerIssue) label(if (check.optString("outcome") != "success") "NeoReader: ${check.optString("outcome").replace('_', ' ')}" else "Saved book or progress unavailable", size = 16f)
        separator(true)
        showHardcover(hardcover)
        serviceRow("Goodreads", R.drawable.service_goodreads, getString(R.string.coming_soon))
        serviceRow("StoryGraph", R.drawable.service_storygraph, getString(R.string.coming_soon))
        serviceRow("Fable", R.drawable.service_fable, getString(R.string.coming_soon))
        serviceRow("Margins", R.drawable.service_margins, getString(R.string.coming_soon))
    }

    private fun readableDate(millis: Long?): String {
        if (millis == null) return "Unknown"
        val date = java.util.Date(millis)
        return android.text.format.DateFormat.getMediumDateFormat(this).format(date) + ", " + android.text.format.DateFormat.getTimeFormat(this).format(date)
    }

    private fun metadataDetails(book: JSONObject?, snapshot: JSONObject?, check: JSONObject?): CharSequence = SpannableStringBuilder().apply {
        fun row(label: String, value: String) {
            val start = length
            append("$label: ")
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(value).append('\n')
        }
        val outcome = when (check?.optString("outcome")) {
            "success" -> "OK"
            "provider_unavailable" -> "Unavailable"
            "permission_denied" -> "Permission Denied"
            "query_failed" -> "Query Failed"
            else -> "Not Checked"
        }
        row("NeoReader Database", outcome)
        if (book != null) {
            row("Title", value(book, "title"))
            row("Authors", value(book, "authors"))
            row("Filename", value(book, "filename"))
            val identifiers = try {
                BookIdentifierRepository(this@MainActivity, diagnostics.store).read(book)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                row("Identifier Error", if (error is SyncProblem) error.code.replace('_', ' ') else error.javaClass.simpleName)
                null
            }
            row("ISBN", identifiers?.isbns?.sorted()?.joinToString(", ")?.takeIf { it.isNotEmpty() } ?: if (identifiers == null) "Unavailable" else "Not Provided")
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
                    row(label, values.sorted().joinToString(", "))
                }
            }
            row("Raw Progress", value(book, "progress"))
            row("Calculated Progress", book.optString("percentage").takeUnless { it.isBlank() || it == "null" }?.let { "$it%" } ?: "Unknown")
            row("Reading Status", value(book, "readingStatus"))
            val progressState = when (book.optJSONObject("progress")?.optString("state")) {
                "missing" -> "Missing Column"
                "null" -> "Not Provided"
                "unreadable" -> "Unreadable"
                "value" -> if (book.isNull("progressProblem")) "OK" else book.optString("progressProblem").replaceFirstChar { it.uppercase() }
                else -> "Unknown"
            }
            row("Progress State", progressState)
            row("Last Access", accessTime(book)?.let { readableDate(it) } ?: value(book, "lastAccess"))
        }
        row("Read At", readableDate(runCatching { java.time.Instant.parse(snapshot?.optString("readAt")).toEpochMilli() }.getOrNull()))
        check?.optJSONObject("error")?.let { row("Read Error", it.toString(2)) }
    }

    private fun serviceRow(
        name: String,
        icon: Int,
        status: String,
        enabled: Boolean = false,
        available: Boolean = false,
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
        if (available) body.setOnClickListener { serviceDetails() }
        label(status, parent = body, size = 17f)
        val switchParent = if (resources.configuration.screenWidthDp < 480 && resources.configuration.fontScale > 1.15f) body else row
        switchParent.addView(
            CheckBox(this).apply {
                val state = getString(if (enabled) R.string.on else R.string.off)
                text = getString(R.string.switch_state, state)
                contentDescription = getString(R.string.service_switch, name, "$state. $status")
                textSize = 18f
                setTextColor(Color.BLACK)
                minHeight = dp(48)
                minWidth = dp(96)
                buttonDrawable = androidx.core.content.ContextCompat.getDrawable(this@MainActivity, R.drawable.service_toggle)
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

    private fun showHardcover(state: JSONObject) {
        val connected = state.getBoolean("connected")
        val last = state.optJSONObject("last")
        val success = last?.optJSONObject("lastSuccess")
        val savedProgress = if (success?.optBoolean("finished") == true) " · Finished" else success?.rawProgressPercent()
        val summary = when {
            app.hardcover.signingIn -> "Sign-in pending"
            !state.getBoolean("enabled") -> state.optString("connectionError").takeIf { it.isNotBlank() }?.replace('_', ' ') ?: if (!connected) "Off · Not connected" else "Off"
            !connected || state.getBoolean("credentialProblem") -> "❌ Reconnect required"
            last?.optString("delivery") == "error" -> "❌ ${last.optString("reason").replace('_', ' ')}"
            last?.optString("matchKind") == "edition" -> "✅ Exact edition matched"
            last?.optString("matchKind") == "book" -> "⚠ Book matched · No exact edition"
            else -> "Pending matching"
        } + if (state.getBoolean("enabled")) {
            "\n" + when {
                last?.optString("delivery") == "pending" -> "Pending"
                last?.optString("delivery") == "error" -> "Not sent"
                success != null -> "Synced at ${time(success.optString("timestamp"))}${savedProgress.orEmpty()}"
                else -> "Not synced yet"
            } + (if (success != null && last.optString("delivery") == "pending") "\nLast synced ${time(success.optString("timestamp"))}${savedProgress.orEmpty()}" else "")
        } else {
            ""
        }
        val editionNote = if (last?.optBoolean("existingEditionPreserved") == true) "\nUsing your Hardcover edition" else ""
        val queueNote = if (state.getBoolean("enabled") && state.optInt("pending") > 0) "\n${state.optInt("pending")} queued update(s)" else ""
        serviceRow(
            "Hardcover",
            R.drawable.service_hardcover,
            summary + editionNote + queueNote,
            state.getBoolean("enabled") || app.hardcover.signingIn,
            !model.busy,
            changed = { checked ->
                action {
                    app.hardcover.setEnabled(checked)
                    if (checked && app.hardcover.state().getBoolean("enabled")) app.sync("manual", "service_enabled")
                }
            }
        )
        if (app.hardcover.signingIn) {
            val device = app.hardcover.device
            if (device != null) {
                label("Sign-in code: ${device.userCode}", true)
                label("Open hardcover.app/link on this device or another device. Sign in and approve the connection. The code expires automatically.")
                button("Open Hardcover sign-in", outlined = true) { startActivity(Intent(Intent.ACTION_VIEW, device.verification.toUri())) }
            } else {
                label("Requesting a sign-in code…")
            }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (model.busy) return
        model.busy = true
        refresh()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.IO) { app.hardcover.recordFailure("manual", "hardcover_operation", error) }
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
                .setMessage("Reading progress from NeoReader to your enabled trackers.\n\nOffline updates stay on this device until they can be sent. Android and BOOX can delay background work.\n\nRead-only book access. Logs stay local until exported.\n\nMIT licence · ${BuildConfig.BUILD_TYPE} build")
                .setPositiveButton("Close", null).setNeutralButton("GitHub") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, "https://github.com/otherguy/boox-tracker".toUri())) }
                .setNegativeButton("Change ebook folder") { _, _ -> chooseFolder() }.create()
        )
    }

    private fun serviceDetails() {
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { app.hardcover.state() }
            bordered(
                AlertDialog.Builder(this@MainActivity).setTitle("Hardcover").setMessage(state.optJSONObject("last")?.toString(2) ?: state.optString("status"))
                    .setPositiveButton("Close", null).setNegativeButton("Disconnect") { _, _ -> action { app.hardcover.disconnect() } }.create()
            )
        }
    }

    override fun onDestroy() {
        dialogs.toList().forEach { it.dismiss() }
        super.onDestroy()
    }

    private fun bordered(dialog: AlertDialog): AlertDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog) }
        dialog.show()
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
