package org.readingsync.diagnostic

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
        "Local diagnostic evidence. Fractions are not assumed to be physical pages. Scheduled executions with appVisibleAtStart, observationActiveAtStart, appVisible, or observationActive true do not prove ordinary background execution."
    )

fun exportIntent(
    context: Context,
    data: JSONObject
): Intent {
    val directory = File(context.cacheDir, "exports").apply { mkdirs() }
    val stamp = System.currentTimeMillis()
    val json = File(directory, "diagnostics-$stamp.json").apply { writeText(data.toString(2)) }
    val summary =
        File(directory, "summary-$stamp.txt").apply {
            val events = data.getJSONArray("observations")
            writeText(
                buildString {
                    appendLine("Reading Sync diagnostic summary")
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
                        if (event.has("selected")) appendLine("Selected: ${event.opt("selected")}")
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
                ClipData.newUri(context.contentResolver, "Reading Sync diagnostics", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
        }
}

fun safeExport(value: Any): Any = when (value) {
    is JSONObject -> JSONObject().apply { value.keys().forEach { key -> if (key != "message") put(key, safeExport(value.get(key))) } }
    is org.json.JSONArray -> org.json.JSONArray().apply { for (i in 0 until value.length()) put(safeExport(value.get(i))) }
    else -> value
}
