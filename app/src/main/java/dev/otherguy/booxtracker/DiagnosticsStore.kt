package dev.otherguy.booxtracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class DiagnosticsStore(
    context: Context
) : SQLiteOpenHelper(context, "diagnostics.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        error("A non-destructive migration is required from $oldVersion to $newVersion")
    }

    @Synchronized fun put(
        name: String,
        value: String
    ) {
        writableDatabase.insertWithOnConflict(
            "state",
            null,
            ContentValues().apply {
                put("name", name)
                put("payload", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized fun get(name: String): String? = readableDatabase.query("state", arrayOf("payload"), "name=?", arrayOf(name), null, null, null).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    @Synchronized fun append(event: JSONObject) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply { put("payload", event.toString()) })
    }

    @Synchronized fun events(limit: Int? = null): List<JSONObject> = readableDatabase.query("events", arrayOf("payload"), null, null, null, null, "id DESC", limit?.toString()).use { cursor ->
        buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
    }

    @Synchronized fun enqueue(account: String, book: JSONObject, identifiers: BookIdentifiers, readAt: String, service: String): JSONObject {
        val key = "outbox.$service.$account.${digest(book.getString("key"))}"
        val state = sourceState(book, identifiers)
        val old = get(key)?.let(::JSONObject)
        if (old?.optString("sourceState") == state) return old
        val item = JSONObject().put("queueKey", key).put("revision", java.util.UUID.randomUUID().toString()).put("account", account)
            .put("book", book).put("identifiers", identifiers.json()).put("readAt", readAt).put("sourceState", state)
        put(key, item.toString())
        return item
    }

    @Synchronized fun pending(account: String, service: String): List<JSONObject> = readableDatabase.query("state", arrayOf("payload"), "name LIKE ?", arrayOf("outbox.$service.$account.%"), null, null, "name").use { cursor ->
        buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
    }

    @Synchronized fun delete(name: String) {
        writableDatabase.delete("state", "name=?", arrayOf(name))
    }

    /** Runs [block] in one database transaction, so its writes land together or not at all. */
    @Synchronized fun <T> transaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    /** Deletes every state row whose name starts with [prefix] and returns how many were deleted. */
    @Synchronized fun deletePrefix(prefix: String): Int = writableDatabase.delete("state", "substr(name, 1, ?) = ?", arrayOf(prefix.length.toString(), prefix))

    @Synchronized fun acknowledge(item: JSONObject): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val current = get(item.getString("queueKey"))?.let(::JSONObject)
            val same = current?.optString("revision") == item.getString("revision")
            if (same) db.delete("state", "name=?", arrayOf(item.getString("queueKey")))
            db.setTransactionSuccessful()
            same
        } finally {
            db.endTransaction()
        }
    }

    fun exportEvents() = JSONArray(events().reversed())

    /**
     * Keeps the event log bounded: no event older than [MAX_AGE_MS], no routine event from before the last successful
     * sync unless it is younger than [RECENT_MS], and at most [MAX_EVENTS] events, removing the oldest routine events
     * before any other. Rows that cannot be read are kept until the count limit. The `state` table is never touched.
     * Returns how many events were deleted.
     */
    @Synchronized fun prune(nowMs: Long, lastSyncMs: Long?): Int {
        val db = writableDatabase
        val routineBefore = lastSyncMs?.coerceAtMost(nowMs - RECENT_MS)
        db.beginTransaction()
        val deleted = try {
            val doomed = mutableListOf<Long>()
            val kept = mutableListOf<Pair<Long, Boolean>>()
            // A row too large for a cursor window is read as null instead of failing the whole query.
            db.rawQuery("SELECT id, CASE WHEN length(payload) < $MAX_READ_CHARS THEN payload END FROM events ORDER BY id", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val event = cursor.getString(1)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val time = event?.let(::eventMillis)
                    val routine = event != null && routine(event)
                    if (time != null && (time < nowMs - MAX_AGE_MS || (routineBefore != null && routine && time < routineBefore))) doomed += id else kept += id to routine
                }
            }
            val excess = kept.size - MAX_EVENTS
            if (excess > 0) doomed += (kept.filter { it.second } + kept.filterNot { it.second }).take(excess).map { it.first }
            doomed.chunked(500).forEach { ids -> db.delete("events", "id IN (${ids.joinToString(",")})", null) }
            db.setTransactionSuccessful()
            doomed.size
        } finally {
            db.endTransaction()
        }
        vacuumAfter(deleted)
        return deleted
    }

    /** Deletes every event and returns how many were deleted. The `state` table is never touched. */
    @Synchronized fun clearEvents(): Int = writableDatabase.delete("events", "1", null)

    /** Shrinks the file after a large delete. It must run outside a transaction. */
    @Synchronized fun vacuumAfter(deleted: Int) {
        // VACUUM needs free space about the size of the database; without it the file only stops growing.
        if (deleted > VACUUM_AFTER) {
            try {
                writableDatabase.execSQL("VACUUM")
            } catch (_: android.database.sqlite.SQLiteException) {
            }
        }
    }

    companion object {
        const val MAX_EVENTS = 1_000
        const val MAX_AGE_MS = 30 * 24 * 3_600_000L
        const val RECENT_MS = 48 * 3_600_000L
        private const val VACUUM_AFTER = 500
        private const val MAX_READ_CHARS = 500_000

        /** Checks, runs, queue entries, and waiting sends. `start`, `stop`, and `*_sync_start` are kinds older versions wrote. */
        private fun routine(event: JSONObject): Boolean {
            if (event.optBoolean("issue")) return false
            val kind = event.optString("kind")
            return runMarker(kind) || kind == "query" || kind == "queued" || (kind.endsWith("_sync") && event.optString("outcome") == "pending")
        }
    }
}

/** An ISO instant in epoch milliseconds; null when it does not parse. */
fun isoMillis(value: String?): Long? = runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()

/** When an event was recorded: its wall-clock milliseconds, else its ISO timestamp; null when it has neither. */
fun eventMillis(event: JSONObject): Long? = event.optLong("wallMs").takeIf { it > 0 } ?: isoMillis(event.optString("timestamp"))

/** A worker run's own event, or the start and send markers older versions wrote. */
fun runMarker(kind: String) = kind in setOf("run", "start", "stop") || kind.endsWith("_sync_start")

fun sourceState(book: JSONObject, identifiers: BookIdentifiers) = digest(
    JSONObject().put("progress", book.opt("progress"))
        .put("status", book.opt("readingStatus")).put("identifiers", identifiers.json()).toString()
)
