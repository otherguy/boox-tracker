package dev.otherguy.booxtracker

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isGone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ActivityEntry(val events: List<JSONObject>) {
    var key = events.first().optString("timestamp")
    var details: String? = null
}

fun activityEntries(events: List<JSONObject>): List<ActivityEntry> = buildList {
    var index = 0
    while (index < events.size) {
        val event = events[index++]
        val group = mutableListOf(event)
        if (event.optBoolean("unchanged") && !event.optBoolean("issue")) {
            val selected = event.opt("selected")?.toString()
            while (index < events.size) {
                val next = events[index]
                if (!next.optBoolean("unchanged") || next.optBoolean("issue") ||
                    next.optString("source") != event.optString("source") ||
                    next.optString("trigger") != event.optString("trigger") ||
                    next.optString("runId") != event.optString("runId") ||
                    next.opt("selected")?.toString() != selected
                ) {
                    break
                }
                group.add(next)
                index++
            }
        }
        add(ActivityEntry(group))
    }
}

class ActivityLogAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val model: ScreenModel
) : BaseAdapter() {
    var entries: List<ActivityEntry> = emptyList()
        private set

    fun submit(entries: List<ActivityEntry>, issues: Boolean) {
        val previousKeys = model.activityKeys
        entries.forEach { entry ->
            entry.events.firstNotNullOfOrNull { previousKeys[it.optString("timestamp")] }?.let { entry.key = it }
        }
        model.activityKeys = entries.flatMap { entry -> entry.events.map { it.optString("timestamp") to entry.key } }.toMap()
        this.entries = entries.filter { !issues || it.events.first().optBoolean("issue") }
        notifyDataSetChanged()
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

    private inner class Row(view: View) {
        private val title = view.findViewById<TextView>(R.id.event_title)
        private val summary = view.findViewById<TextView>(R.id.event_summary)
        private val toggle = view.findViewById<Button>(R.id.event_expand)
        private val details = view.findViewById<TextView>(R.id.event_details)
        private var formatting: Job? = null

        fun bind(entry: ActivityEntry) {
            formatting?.cancel()
            val event = entry.events.first()
            title.text = context.getString(R.string.event_title, event.optString("source"), event.optString("kind"), event.optString("trigger"))
            summary.text = context.getString(
                R.string.event_summary,
                event.optString("timestamp"),
                event.optString("outcome"),
                event.optString("reason"),
                if (entry.events.size > 1) context.resources.getQuantityString(R.plurals.unchanged_observations, entry.events.size, entry.events.size) else ""
            )
            details.text = ""
            val open = entry.key in model.expanded
            details.isGone = !open
            toggle.setText(if (open) R.string.hide_details else R.string.details)
            if (open) {
                entry.details?.let { details.text = it } ?: run {
                    formatting = scope.launch {
                        val text = withContext(Dispatchers.Default) { entry.events.joinToString("\n") { it.toString(2) } }
                        entry.details = text
                        details.text = text
                    }
                }
            }
            toggle.setOnClickListener {
                if (!model.expanded.remove(entry.key)) model.expanded.add(entry.key)
                bind(entry)
            }
        }
    }
}
