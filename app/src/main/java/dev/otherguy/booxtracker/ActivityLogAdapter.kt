package dev.otherguy.booxtracker

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import org.json.JSONObject

/** Identifies an event across refreshes; a timestamp alone can repeat within one millisecond. */
fun eventKey(event: JSONObject) = listOf("timestamp", "kind", "runId").joinToString("|") { event.optString(it) }

class ActivityEntry(val events: List<JSONObject>) {
    var key = eventKey(events.first())

    /** The newest event pretty-printed; formatted the first time its JSON tab opens. */
    var json: String? = null
}

/** A row's words: a title, a detail line that starts with where the event came from, and a status mark. */
data class EventText(val title: String, val detail: String, val mark: String)

private val sources = mapOf("scheduled" to "Background", "delivery" to "Background delivery", "renewal" to "Background renewal", "foreground" to "App open", "manual" to "Manual", "system" to "System")

fun sourceText(event: JSONObject): String = sources[event.optString("source")] ?: words(event.optString("source"))

fun words(value: String) = value.replace('_', ' ')

/** Where the hidden browser stopped when a renewal timed out. */
private val pageStates = mapOf(
    "no_page" to "no page finished loading",
    "page_finished" to "page loaded, no answer from the page check",
    "loading" to "page loaded without the account header",
    "checking" to "bot check still running"
)

private val triggers = mapOf(
    "periodic" to "Scheduled check",
    "app_open" to "App opened",
    "sync_now" to "Sync Now",
    "folder_granted" to "Folder access granted",
    "service_enabled" to "Service turned On",
    "sign_in" to "After sign-in"
)

fun triggerText(trigger: String) = triggers[trigger] ?: words(trigger)

/** The tracker an event belongs to, from its `service` field or its kind's prefix, such as "Hardcover". */
fun serviceName(event: JSONObject) = when (val service = event.text("service") ?: event.optString("kind").substringBefore('_')) {
    "storygraph" -> "StoryGraph"
    else -> service.replaceFirstChar { it.uppercase() }
}

/** How many library changes a check found; older versions logged every change and no count. */
fun changeCount(event: JSONObject): Int? = if (event.has("changeCount")) event.optInt("changeCount") else event.optJSONArray("changes")?.length()

/** Words for each row mark, read by TalkBack in place of the symbol. */
private val marks = mapOf("✅" to "Done", "⚠" to "Warning", "❌" to "Issue")

private fun count(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

/** Run markers stay in the log and export but are not rows unless they report an issue. */
private fun hidden(event: JSONObject) = !event.optBoolean("issue") && runMarker(event.optString("kind"))

/** Consecutive events with the same key share one row: unchanged checks of one book, and repeats of one issue. */
private fun groupKey(event: JSONObject): String? = when {
    event.optBoolean("issue") -> listOf("issue", event.optString("kind"), event.optString("outcome"), event.optString("reason"), event.optString("service")).joinToString("|")

    event.optString("kind") == "query" && event.optBoolean("unchanged") ->
        listOf("check", event.optString("source"), event.optString("trigger"), event.optJSONObject("selected")?.optString("key")).joinToString("|")

    else -> null
}

/**
 * Rows for [events], newest first. An unchanged check or an issue joins the newest open row with the same key, so
 * repeats that alternate with other checks still share one row; an event without a key, such as a send, closes them.
 */
fun activityEntries(events: List<JSONObject>): List<ActivityEntry> {
    val groups = mutableListOf<MutableList<JSONObject>>()
    val open = mutableMapOf<String, MutableList<JSONObject>>()
    for (event in events.filterNot(::hidden)) {
        val key = groupKey(event)
        val group = key?.let(open::get)
        if (group != null) {
            group.add(event)
        } else {
            val created = mutableListOf(event)
            groups.add(created)
            if (key == null) open.clear() else open[key] = created
        }
    }
    return groups.map(::ActivityEntry)
}

/** A book's title: a plain value, or the provider's `{state, raw}` field that older versions logged. */
fun bookTitle(book: JSONObject) = book.raw("title") ?: book.text("title")

fun bookParts(book: JSONObject?): List<String> = if (book == null) emptyList() else listOfNotNull(bookTitle(book), book.text("percentage")?.let { "$it%" })

/** The progress a tracker holds after a send: pages for Hardcover, a percentage for the others. */
private fun progressText(result: JSONObject, kept: Boolean): String? = when {
    result.optBoolean("finished") -> "Finished"

    // Goodreads and Pagebound receive whole steps of 5 and hold their own value until NeoReader reaches the next one.
    result.optInt("nextUpdateAt") > 0 -> "${result.optInt("remotePercent")}%"

    result.has("posted") -> "${result.optInt("posted")}%"

    result.optInt("editionPages") > 0 -> "${result.optInt(if (kept) "remoteProgressPages" else "progressPages")} of ${result.optInt("editionPages")} pages"

    result.has(if (kept) "remotePercent" else "percent") -> "${result.optInt(if (kept) "remotePercent" else "percent")}%"

    else -> null
}

/** Describes [entry] in plain words; [time] formats a stored timestamp for "since" notes. */
fun eventText(entry: ActivityEntry, time: (String) -> String): EventText {
    val event = entry.events.first()
    val kind = event.optString("kind")
    val outcome = event.optString("outcome")
    val reason = event.text("reason")?.let(::words)
    val service = serviceName(event)
    val background = if (event.optString("source") == "delivery") "Background delivery" else "Background run"
    val issue = event.optBoolean("issue")
    var mark = if (issue) "❌" else "•"
    val parts = mutableListOf(sourceText(event))
    val title = when {
        kind == "query" -> {
            val book = event.optJSONObject("selected")
            val changes = changeCount(event) ?: 0
            when {
                outcome != "success" -> {
                    parts += words(outcome)
                    when (outcome) {
                        "provider_unavailable" -> "NeoReader unavailable"
                        "permission_denied" -> "NeoReader access denied"
                        else -> "NeoReader check failed"
                    }
                }

                book == null -> if (event.optInt("recordCount") == 0) "Library empty" else "No book detected"

                else -> {
                    parts += bookParts(book)
                    when {
                        issue -> "Progress unreadable"
                        event.optBoolean("unchanged") -> "No change"
                        changes > 0 -> "Library changed".also { parts += count(changes, "change") }
                        else -> "Checked NeoReader"
                    }
                }
            }
        }

        kind == "query_skipped" -> "Check skipped".also { parts += "a check was still running" }

        kind == "run" || kind == "stop" -> {
            parts += listOfNotNull(reason)
            if (event.optString("reason") == "worker_cancelled") "$background stopped" else "$background failed"
        }

        kind == "queued" -> "Queued for $service".also { parts += bookParts(event) }

        kind.endsWith("_sync") -> {
            val book = listOfNotNull(event.text("title"))
            when (outcome) {
                "sent" -> {
                    mark = "✅"
                    parts += book + listOfNotNull(progressText(event, kept = false))
                    if (event.optBoolean("finished")) "Finished on $service" else "Synced to $service"
                }

                "kept_higher_remote_progress" -> {
                    mark = "⚠"
                    parts += book + listOfNotNull(progressText(event, kept = true)?.let { "$it, higher than NeoReader" })
                    "$service kept its progress"
                }

                "already_current" -> {
                    mark = "✅"
                    parts += book + listOfNotNull(progressText(event, kept = false))
                    "$service already up to date"
                }

                "pending" -> "$service unreachable".also { parts += listOfNotNull("Will retry", reason) }

                "held" -> "$service update held".also { parts += listOfNotNull(reason) }

                "interrupted" -> "$service send interrupted"

                else -> "$service sync".also { parts += listOfNotNull(words(outcome).ifEmpty { null }, reason) }
            }
        }

        kind.endsWith("_connection") -> when (outcome) {
            "connected" -> "Signed in to $service".also { mark = "✅" }
            "logged_out" -> "Logged out of $service".also { event.optInt("deletedUpdates").takeIf { it > 0 }?.let { parts += count(it, "queued update") + " deleted" } }
            else -> "$service sign-in failed".also { parts += listOfNotNull(reason) }
        }

        kind.endsWith("_setting") -> "$service turned ${if (event.optBoolean("enabled")) "On" else "Off"}"

        kind.endsWith("_operation") -> "$service action failed".also { parts += listOfNotNull(reason) }

        kind.endsWith("_renewal") -> "$service session not renewed".also { parts += listOfNotNull(words(outcome).ifEmpty { null }, pageStates[event.text("pageState")]) }

        kind.endsWith("_interruption_detected") -> "$service send interrupted".also { parts += "the app stopped during a send" }

        kind == "interruption_detected" -> "$background interrupted"

        kind == "worker_failed" -> "$background failed".also { parts += listOfNotNull(reason) }

        kind == "prune_failed" -> "Log cleanup failed".also { parts += listOfNotNull(event.text("class")?.substringAfterLast('.')) }

        kind == "schedule_failed" -> "Background checks not scheduled".also { parts += listOfNotNull(event.text("class")?.substringAfterLast('.')) }

        kind == "ebook_folder" -> "Ebook folder access granted"

        kind == "activity_cleared" -> "Activity cleared".also { parts += count(event.optInt("deletedEvents"), "event") + " deleted" }

        else -> words(kind).replaceFirstChar { it.uppercase() }.also { parts += listOfNotNull(words(outcome).ifEmpty { null }, reason) }
    }
    if (entry.events.size > 1) {
        val since = time(entry.events.last().optString("timestamp"))
        parts += if (kind == "query" && !issue) "${entry.events.size} checks since $since" else "${entry.events.size} times since $since"
    }
    return EventText(title, parts.filter { it.isNotBlank() }.joinToString(" · "), mark)
}

class ActivityLogAdapter(
    private val context: Context,
    private val model: ScreenModel,
    /** Opens the details popup for a tapped row. */
    private val onOpen: (ActivityEntry, EventText) -> Unit
) : BaseAdapter() {
    var entries: List<ActivityEntry> = emptyList()
        private set

    fun submit(entries: List<ActivityEntry>, issues: Boolean) {
        val previousKeys = model.activityKeys
        entries.forEach { entry ->
            entry.events.firstNotNullOfOrNull { previousKeys[eventKey(it)] }?.let { entry.key = it }
        }
        model.activityKeys = entries.flatMap { entry -> entry.events.map { eventKey(it) to entry.key } }.toMap()
        this.entries = entries.filter { !issues || it.events.first().optBoolean("issue") }
        notifyDataSetChanged()
    }

    private fun timeOfDay(millis: Long): String = DateFormat.getTimeFormat(context).format(java.util.Date(millis))

    /** A stored timestamp as a time of day in the device's format. */
    private fun clock(timestamp: String): String = isoMillis(timestamp)?.let(::timeOfDay) ?: timestamp

    /** The newest event's time of day, with its date on the line above when it is not today. */
    private fun whenText(event: JSONObject): String {
        val millis = eventMillis(event) ?: return ""
        val time = timeOfDay(millis)
        return if (DateUtils.isToday(millis)) time else DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR) + "\n" + time
    }

    override fun getCount() = entries.size
    override fun getItem(position: Int) = entries[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.activity_entry, parent, false).apply {
            tag = Row(this)
        }
        (view.tag as Row).bind(getItem(position))
        return view
    }

    private inner class Row(private val view: View) {
        private val mark = view.findViewById<TextView>(R.id.event_mark)
        private val title = view.findViewById<TextView>(R.id.event_title)
        private val summary = view.findViewById<TextView>(R.id.event_summary)
        private val time = view.findViewById<TextView>(R.id.event_time)

        init {
            ViewCompat.replaceAccessibilityAction(view, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, context.getString(R.string.event_open_details), null)
        }

        fun bind(entry: ActivityEntry) {
            val text = eventText(entry, ::clock)
            mark.text = text.mark
            title.text = text.title
            summary.text = text.detail
            time.text = whenText(entry.events.first())
            view.contentDescription = listOf(marks[text.mark].orEmpty(), text.title, text.detail, time.text.toString().replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(". ")
            view.setOnClickListener { onOpen(entry, text) }
        }
    }
}
