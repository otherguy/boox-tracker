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

    @Synchronized fun enqueue(account: String, book: JSONObject, identifiers: BookIdentifiers, readAt: String): JSONObject {
        val key = "outbox.hardcover.$account.${digest(book.getString("key"))}"
        val state = sourceState(book, identifiers)
        val old = get(key)?.let(::JSONObject)
        if (old?.optString("sourceState") == state) return old
        val item = JSONObject().put("queueKey", key).put("revision", java.util.UUID.randomUUID().toString()).put("account", account)
            .put("book", book).put("identifiers", identifiers.json()).put("readAt", readAt).put("sourceState", state)
        put(key, item.toString())
        return item
    }

    @Synchronized fun pending(account: String): List<JSONObject> = readableDatabase.query("state", arrayOf("payload"), "name LIKE ?", arrayOf("outbox.hardcover.$account.%"), null, null, "name").use { cursor ->
        buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
    }

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
}

fun sourceState(book: JSONObject, identifiers: BookIdentifiers) = digest(
    JSONObject().put("progress", book.opt("progress"))
        .put("status", book.opt("readingStatus")).put("identifiers", identifiers.json()).toString()
)
