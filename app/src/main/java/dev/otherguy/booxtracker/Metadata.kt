package dev.otherguy.booxtracker

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.os.CancellationSignal
import androidx.core.net.toUri
import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONArray
import org.json.JSONObject

val METADATA_URI = "content://com.onyx.content.database.ContentProvider/Metadata".toUri()
val FIELDS = listOf("id", "name", "uuid", "nativeAbsolutePath", "progress", "readingStatus", "lastAccess", "extraAttributes", "ISBN", "title", "authors")

data class Progress(
    val raw: String?,
    val percent: String?,
    val problem: String?
) {
    companion object {
        fun parse(raw: String?): Progress {
            if (raw == null) return Progress(null, null, "null progress")
            val parts = raw.split('/')
            if (parts.size != 2) return Progress(raw, null, "malformed fraction")
            val numerator = parts[0].trim().toBigDecimalOrNull()
            val denominator = parts[1].trim().toBigDecimalOrNull()
            if (numerator == null || denominator == null) return Progress(raw, null, "malformed fraction")
            if (denominator.signum() == 0) return Progress(raw, null, "zero denominator")
            if (denominator.signum() < 0 || numerator.signum() < 0 || numerator > denominator) return Progress(raw, null, "out of range")
            return try {
                Progress(
                    raw,
                    numerator
                        .multiply(BigDecimal(100))
                        .divide(denominator, 2, RoundingMode.HALF_UP)
                        .stripTrailingZeros()
                        .toPlainString(),
                    null
                )
            } catch (_: ArithmeticException) {
                Progress(raw, null, "unreadable fraction")
            }
        }
    }
}

fun safeMessage(message: String?): String = (message ?: "No exception message")
    .replace(Regex("(?:/[A-Za-z0-9_. -]+){2,}"), "[path omitted]")
    .replace(Regex("(?i)(password|token|secret|authorization|credential)\\s*[:=]\\s*[^,;\\s]+"), "$1=[omitted]")
    .take(1000)

fun errorDetails(
    error: Exception,
    phase: String
) = JSONObject()
    .put("phase", phase)
    .put("class", error.javaClass.name)
    .put("message", safeMessage(error.message))
    .put("causeClass", error.cause?.javaClass?.name ?: JSONObject.NULL)
    .put("frames", JSONArray(error.stackTrace.take(8).map { "${it.className}.${it.methodName}:${it.lineNumber}" }))

fun field(
    cursor: Cursor,
    name: String
): JSONObject {
    val index = cursor.getColumnIndex(name)
    if (index < 0) return JSONObject().put("state", "missing")
    return try {
        if (cursor.isNull(index)) {
            JSONObject().put("state", "null")
        } else if (cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) {
            JSONObject().put("state", "unreadable").put("type", "blob")
        } else {
            JSONObject().put("state", "value").put("type", cursor.getType(index)).put("raw", cursor.getString(index))
        }
    } catch (error: Exception) {
        JSONObject().put("state", "unreadable").put("error", errorDetails(error, "read $name"))
    }
}

fun JSONObject.raw(name: String): String? = optJSONObject(name)?.let { if (it.optString("state") == "value") it.optString("raw") else null }

fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
    "%02x".format(it)
}

fun readBook(cursor: Cursor): JSONObject = JSONObject().apply {
    FIELDS.forEach { name ->
        val value = field(cursor, name)
        when (name) {
            "nativeAbsolutePath" -> {
                if (value.optString("state") == "value") {
                    val path = value.getString("raw")
                    put("pathDigest", digest(path))
                    value.put("raw", path.substringAfterLast('/'))
                }
                put("filename", value)
            }

            "extraAttributes" -> {
                val extra = JSONObject().put("state", value.optString("state"))
                if (value.optString("state") == "value") {
                    val raw = value.getString("raw")
                    extra.put("length", raw.length)
                    try {
                        val position = JSONObject(raw).opt("current_page_position_v2")
                        if (position is Number ||
                            (position is String && position.matches(Regex("[0-9]+")))
                        ) {
                            extra.put("current_page_position_v2", position)
                        }
                    } catch (_: Exception) {
                        extra.put("parse", "not a JSON object")
                    }
                }
                put(name, extra)
            }

            else -> {
                put(name, value)
            }
        }
    }
    val progress = Progress.parse(raw("progress"))
    put("percentage", progress.percent ?: JSONObject.NULL)
    put(
        "progressProblem",
        if (optJSONObject("progress")?.optString("state") ==
            "missing"
        ) {
            "missing column"
        } else {
            progress.problem ?: JSONObject.NULL
        }
    )
    put(
        "key",
        raw("id")?.let { "id:$it" } ?: raw("uuid")?.let { "uuid:$it" } ?: optString("pathDigest").takeIf { it.isNotEmpty() }
            ?: JSONObject.NULL
    )
}

fun providerFailure(
    error: Exception,
    providerResolved: Boolean
): String = when {
    error is SecurityException -> "permission_denied"
    !providerResolved && error is IllegalArgumentException -> "provider_unavailable"
    else -> "query_failed"
}

class NeoReaderRepository(
    private val context: Context,
    private val resolver: ContentResolver = context.contentResolver
) {
    fun read(signal: CancellationSignal): JSONObject {
        var resolved = false
        var phase = "resolve provider"
        return try {
            resolved = context.packageManager.resolveContentProvider(METADATA_URI.authority!!, 0) != null
            phase = "query"
            val cursor =
                resolver.query(METADATA_URI, null, null, null, null, signal)
                    ?: return JSONObject()
                        .put(
                            "outcome",
                            if (resolved) "query_failed" else "provider_unavailable"
                        ).put("error", JSONObject().put("phase", phase).put("class", "NullCursor"))
            cursor.use {
                val columns = JSONArray(it.columnNames.toList())
                val books = JSONArray()
                phase = "read cursor"
                while (it.moveToNext()) {
                    signal.throwIfCanceled()
                    books.put(readBook(it))
                }
                val records = (0 until books.length()).map { books.getJSONObject(it) }
                fun unique(value: String, extract: (JSONObject) -> String?) = records.count { extract(it) == value } == 1
                records.forEach { book ->
                    val id = book.raw("id")
                    val uuid = book.raw("uuid")
                    val path = book.optString("pathDigest").takeIf { it.isNotEmpty() }
                    val key = when {
                        id != null && unique(id) { it.raw("id") } -> "id:$id"
                        uuid != null && unique(uuid) { it.raw("uuid") } -> "uuid:$uuid"
                        path != null && unique(path) { it.optString("pathDigest") } -> "path:$path"
                        else -> null
                    }
                    book.put("key", key ?: JSONObject.NULL)
                    if (key == null) book.put("identityWarning", "No unique identifier; cross-observation matching unavailable")
                }
                JSONObject()
                    .put("outcome", "success")
                    .put("columns", columns)
                    .put("recordCount", books.length())
                    .put("books", books)
            }
        } catch (
            error: Exception
        ) {
            JSONObject().put("outcome", providerFailure(error, resolved)).put("error", errorDetails(error, phase))
        }
    }
}
