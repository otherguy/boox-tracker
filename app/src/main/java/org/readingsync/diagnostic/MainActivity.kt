package org.readingsync.diagnostic

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ScreenModel : ViewModel() {
    var tab = "diagnostics"
    var issues = false
    var busy = false
    val expanded = mutableSetOf<String>()
    var transient: JSONObject? = null
    var transientReadAt: String? = null
}

class MainActivity : AppCompatActivity() {
    private lateinit var app: ReadingSyncApp
    private lateinit var diagnostics: Diagnostics
    private lateinit var content: LinearLayout
    private val model by lazy { androidx.lifecycle.ViewModelProvider(this)[ScreenModel::class.java] }
    private val notifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startSession()
            } else {
                notice(
                    "Notification permission is required for the observation session. Manual and scheduled checks remain available."
                )
            }
        }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
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
        findViewById<Button>(R.id.diagnostics).setOnClickListener {
            model.tab = "diagnostics"
            refresh()
        }
        findViewById<Button>(R.id.activity).setOnClickListener {
            model.tab = "activity"
            refresh()
        }
        lifecycleScope.launch { diagnostics.updates.collect { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        if (::diagnostics.isInitialized) refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) { diagnostics.snapshot() }
            val check = withContext(Dispatchers.IO) { diagnostics.store.get("lastCheck")?.let(::JSONObject) }
            if (model.transientReadAt != snapshot?.optString("readAt")) model.transient = null
            val selected = model.transient ?: withContext(Dispatchers.IO) { selectBook(snapshot, diagnostics.selection()) }
            val enabled = withContext(Dispatchers.IO) { diagnostics.store.get("background") == "true" }
            val events = withContext(Dispatchers.IO) { diagnostics.store.events(250) }
            content.removeAllViews()
            findViewById<Button>(R.id.diagnostics).typeface =
                android.graphics.Typeface.create("sans", if (model.tab == "diagnostics") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            findViewById<Button>(R.id.activity).typeface = android.graphics.Typeface.create("sans", if (model.tab == "activity") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (model.tab == "diagnostics") showDiagnostics(snapshot, check, selected, enabled) else showActivity(events)
        }
    }

    private fun label(
        text: String,
        bold: Boolean = false
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = if (bold) 22f else 18f
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 12, 0, 12)
        setTextIsSelectable(true)
        content.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    private fun button(
        text: String,
        action: () -> Unit
    ): Button = Button(this).apply {
        this.text = text
        textSize = 18f
        minHeight = dp(56)
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.BLACK)
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(8), 0, dp(8)) })
        setOnClickListener { action() }
    }

    private fun separator() {
        content.addView(View(this).apply { setBackgroundColor(Color.BLACK) }, LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showDiagnostics(
        snapshot: JSONObject?,
        check: JSONObject?,
        selected: JSONObject?,
        enabled: Boolean
    ) {
        val outcome = check?.optString("outcome") ?: "Not checked"
        val warning = check != null && outcome != "success"
        label(if (warning) "⚠ ${outcome.replace('_', ' ')}" else "Provider: $outcome", true).apply {
            if (warning) setBackgroundColor(Color.rgb(255, 191, 0))
        }
        label(
            "Last check: ${check?.optLong("readStartedWallMs")?.let {
                java.time.Instant
                    .ofEpochMilli(it)
                    .toString()
            } ?: "Not checked"}"
        )
        check?.optJSONObject("error")?.let { label(it.toString(2)) }
        button(if (model.busy) "Reading…" else "Read now") {
            model.busy = true
            refresh()
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { diagnostics.collect("manual", "read_now") }
                } finally {
                    model.busy = false
                    refresh()
                }
            }
        }.isEnabled = !model.busy
        separator()
        label("Library records: ${snapshot?.optInt("recordCount") ?: "Unknown"}", true)
        if (warning && snapshot != null) label("Cached library from ${snapshot.optString("readAt")}")
        if (snapshot?.optInt("recordCount") == 0) label("Query succeeded with no library records.")
        label(selected?.raw("name") ?: "No selected book", true)
        button("Choose book") {
            val records = books(snapshot).sortedBy { it.raw("name") ?: "" }
            val labels =
                records
                    .map {
                        "${it.raw("name") ?: "Unnamed record"} · ${it.raw("uuid") ?: it.raw("id") ?: "identity unavailable"}"
                    }.toTypedArray()
            AlertDialog
                .Builder(this)
                .setTitle("Library records")
                .setItems(labels) { _, index ->
                    val record = records[index]
                    if (record.isNull("key")) {
                        model.transient = record
                        model.transientReadAt = snapshot?.optString("readAt")
                        refresh()
                    } else {
                        model.transient = null
                        diagnostics.scope.launch {
                            diagnostics.store.put("selection", record.getString("key"))
                            diagnostics.event("manual", "selection", detail = JSONObject().put("selected", record))
                            withContext(Dispatchers.Main) { refresh() }
                        }
                    }
                }.setNegativeButton("Cancel", null)
                .show()
        }
        button("Select most recently accessed") {
            model.transient = null
            diagnostics.scope.launch {
                diagnostics.store.put("selection", "")
                withContext(Dispatchers.Main) { refresh() }
            }
        }
        if (selected != null) {
            label("Raw progress: ${value(selected, "progress")}")
            label(
                "Calculated: ${if (selected.isNull(
                        "percentage"
                    )
                ) {
                    "Unknown — ${selected.optString("progressProblem")}"
                } else {
                    selected.optString("percentage") + "%"
                }}"
            )
            label("Fraction units are not assumed to be physical pages.")
            label("Raw reading status: ${value(selected, "readingStatus")}")
            label("NeoReader last-access raw: ${value(selected, "lastAccess")}")
            accessTime(
                selected
            )?.let { label("Last access interpreted as Unix time (units inferred): ${java.time.Instant.ofEpochMilli(it)}") }
            label("Read from NeoReader at: ${snapshot?.optString("readAt")}")
            label("Extra attributes: ${selected.optJSONObject("extraAttributes")}")
        } else if (snapshot != null && books(snapshot).isNotEmpty()) {
            label("Selected record was not returned. Choose a book.")
        }
        separator()
        label("Short observation", true)
        label("10 minutes. Observer callbacks and five-second polls. Sleep can create gaps.")
        button(if (app.sessionActive) "Stop observation" else "Start 10-minute observation") {
            if (app.sessionActive) startService(Intent(this, ObservationService::class.java).setAction("stop")) else startSession()
        }
        separator()
        CheckBox(this).apply {
            text = getString(R.string.background_state, getString(if (enabled) R.string.on else R.string.off))
            textSize = 20f
            setTextColor(Color.BLACK)
            minHeight = dp(56)
            buttonDrawable =
                androidx.core.content.ContextCompat
                    .getDrawable(this@MainActivity, R.drawable.toggle)
            isChecked = enabled
            content.addView(this)
            setOnCheckedChangeListener {
                    _,
                    checked
                ->
                diagnostics.scope.launch {
                    setBackground(app, checked)
                    withContext(Dispatchers.Main) { refresh() }
                }
            }
        }
        label("Requests a check every 15 minutes. Execution is best-effort and may be delayed. No network connection is required.")
        separator()
        button("Device and build information") { notice(deviceInfo().toString(2) + "\nColumns: " + snapshot?.optJSONArray("columns")) }
        label("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}")
        button("About") {
            notice(
                "Reading Sync ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})\nMIT license\nLocal, read-only diagnostics. No external sync. Export only when requested."
            )
        }
    }

    private fun value(
        book: JSONObject,
        name: String
    ): String = book.raw(name) ?: book.optJSONObject(name)?.optString("state") ?: "missing"

    private fun showActivity(events: List<JSONObject>) {
        button(if (model.issues) "Issues selected — show All" else "All selected — show Issues") {
            model.issues = !model.issues
            refresh()
        }
        button("Export diagnostics") {
            lifecycleScope.launch {
                val intent = withContext(Dispatchers.IO) { exportIntent(this@MainActivity, buildExport(diagnostics)) }
                startActivity(Intent.createChooser(intent, "Export diagnostics"))
            }
        }
        label("Latest 250 events. Export includes the full retained log.")
        val visible = events.filter { !model.issues || it.optBoolean("issue") }
        var index = 0
        while (index < visible.size) {
            val event = visible[index]
            val group = mutableListOf(event)
            index++
            if (event.optBoolean("unchanged") && !event.optBoolean("issue")) {
                while (index < visible.size) {
                    val next = visible[index]
                    if (!next.optBoolean(
                            "unchanged"
                        ) || next.optBoolean("issue") || next.optString("source") != event.optString("source") ||
                        next.optString("trigger") != event.optString("trigger") ||
                        next.optString("runId") != event.optString("runId") ||
                        next.opt("selected")?.toString() != event.opt("selected")?.toString()
                    ) {
                        break
                    }
                    group.add(next)
                    index++
                }
            }
            separator()
            label("${event.optString("source")} · ${event.optString("kind")} ${event.optString("trigger")}", true)
            label(
                "${event.optString(
                    "timestamp"
                )}\n${event.optString(
                    "outcome"
                )} ${event.optString("reason")}${if (group.size > 1) "\n${group.size} unchanged observations" else ""}"
            )
            val details =
                TextView(this).apply {
                    text = group.joinToString("\n") { it.toString(2) }
                    textSize = 16f
                    setTextColor(Color.BLACK)
                    setTextIsSelectable(true)
                    visibility = if (event.optString("timestamp") in model.expanded) View.VISIBLE else View.GONE
                }
            button("Details") {
                details.isGone = !details.isGone
                if (details.isGone) model.expanded.remove(event.optString("timestamp")) else model.expanded.add(event.optString("timestamp"))
            }
            content.addView(details)
        }
        if (visible.isEmpty()) label("No retained entries for this filter.")
    }

    private fun startSession() {
        if (!notificationAllowed(this)) {
            if (Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat
                    .checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                notice(
                    "Enable Reading Sync observation notifications in Android app settings before starting. A visible Stop notification is required."
                )
            }
            return
        }
        try {
            androidx.core.content.ContextCompat
                .startForegroundService(this, Intent(this, ObservationService::class.java))
        } catch (
            error: Exception
        ) {
            diagnostics.scope.launch {
                diagnostics.event(
                    "observation",
                    "start_failed",
                    detail = errorDetails(error, "start service"),
                    issue = true
                )
            }
            notice("Observation service could not start: ${safeMessage(error.message)}")
        }
    }

    private fun notice(text: String) {
        AlertDialog
            .Builder(this)
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }
}
