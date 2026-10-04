package org.readingsync.diagnostic

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

    fun exportEvents() = JSONArray(events().reversed())
}
