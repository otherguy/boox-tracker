package dev.otherguy.booxtracker

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.time.Instant
import org.json.JSONObject

fun buildExport(diagnostics: Diagnostics): JSONObject = JSONObject()
    .put("schemaVersion", 1)
    .put("exportedAt", Instant.now().toString())
    .put("appDeviceBuild", deviceInfo())
    .put("providerUri", METADATA_URI.toString())
    .put("latestSnapshot", safeExport(diagnostics.snapshot() ?: JSONObject.NULL))
    .put("observations", safeExport(diagnostics.store.exportEvents()))
    .put(
        "notes",
        "Local reads and optional Hardcover and Fable sends are separate events. Provider percentages may differ from NeoReader's in-book percentage. Fractions are not physical pages. Automatic detection uses saved lastAccess and may not identify the open book. Hardcover progress_pages is an approximate equivalent for the chosen positive-page-count edition. Fable receives the whole percentage rounded down through its unofficial app API; Fable passwords are never stored. Matching and pending delivery are separate. Collection and delivery events with appVisibleAtStart or appVisible true do not prove independent background execution. Queues retain the latest observation for each account and source book. Observations are the retained events: at most 1,000 and none older than 30 days; routine events from before the last successful sync are removed once they are 48 hours old. Check events summarize the selected book unless the check reports an issue; latestSnapshot has the full records and columns. Credentials, sign-in codes, folder URIs, and full paths are omitted."
    )

fun exportIntent(
    context: Context,
    data: JSONObject
): Intent {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    // Only the newest export is kept; earlier files were already shared or abandoned.
    directory.listFiles()?.forEach { it.delete() }
    val stamp = System.currentTimeMillis()
    val json = File(directory, "diagnostics-$stamp.json").apply { writeText(data.toString(2)) }
    val summary =
        File(directory, "summary-$stamp.txt").apply {
            val events = data.getJSONArray("observations")
            writeText(
                buildString {
                    appendLine("Boox Tracker diagnostic summary")
                    appendLine(data.getJSONObject("appDeviceBuild").toString(2))
                    appendLine(data.getString("notes"))
                    appendLine("${events.length()} retained events")
                    for (i in 0 until events.length()) {
                        val event = events.getJSONObject(i)
                        appendLine(
                            "${event.optString(
                                "timestamp"
                            )} | ${event.optString(
                                "source"
                            )} | ${event.optString(
                                "kind"
                            )} | ${event.optString("trigger")} | ${event.optString("outcome")} | ${event.optString("reason")}"
                        )
                        if (event.has("selected")) appendLine("Detected book: ${event.opt("selected")}")
                        if (event.optString("kind") == "hardcover_sync") appendLine("Hardcover: $event")
                        if (event.optString("kind") == "fable_sync") appendLine("Fable: $event")
                        if (event.has("error")) appendLine("Error: ${event.opt("error")}")
                        if (event.has("changes")) appendLine("Changes: ${event.opt("changes")}")
                    }
                }
            )
        }
    val uris = arrayListOf(summary, json).map { FileProvider.getUriForFile(context, "${context.packageName}.exports", it) }
    return Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType("*/*")
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply {
            clipData =
                ClipData.newUri(context.contentResolver, "Boox Tracker diagnostics", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
        }
}

fun safeExport(value: Any): Any = when (value) {
    is JSONObject -> JSONObject().apply { value.keys().forEach { key -> if (key != "message") put(key, safeExport(value.get(key))) } }
    is org.json.JSONArray -> org.json.JSONArray().apply { for (i in 0 until value.length()) put(safeExport(value.get(i))) }
    else -> value
}
