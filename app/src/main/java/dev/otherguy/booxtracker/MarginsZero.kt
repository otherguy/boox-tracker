package dev.otherguy.booxtracker

import java.io.IOException
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** Margins' Zero sync server. */
const val MARGINS_ZERO_URL = "wss://zero.margins.app"

/** The origin of Margins' website, whose sync client sends it on every connection. */
private const val MARGINS_ORIGIN = "https://margins.app"

/** The Zero sync protocol version in the connect path. Version 52 changes pokes to binary chunks, which this client does not read. */
private const val ZERO_PROTOCOL = 51

/** How long one connection may take from opening to its last answer. */
private const val ZERO_ANSWER_MS = 30_000L

/**
 * The replicated Margins tables this client reads, by server name: `primary key` then `column:type` pairs. Zero
 * refuses a connection whose schema names a table it does not replicate, so the list stays as small as the queries
 * allow.
 */
private val marginsTables = mapOf(
    "catalog.works" to "work_id|work_id:s original_title:s original_language:s original_publication_date:s num_pages:n num_seconds:n num_locations:n goodreads_num_1_star_ratings:n goodreads_num_2_star_ratings:n goodreads_num_3_star_ratings:n goodreads_num_4_star_ratings:n goodreads_num_5_star_ratings:n goodreads_num_reviews:n",
    "catalog.work_titles" to "title_id|title_id:s work_id:s title:s subtitle:s language:s is_primary:b",
    "catalog.work_contributors" to "work_contributor_id|work_contributor_id:s work_id:s contributor_id:s position:n",
    "catalog.contributor_names" to "name_id|name_id:s contributor_id:s name:s language:s is_primary:b",
    "catalog.contributors" to "contributor_id|contributor_id:s num_works:n",
    "catalog.work_covers" to "cover_id|cover_id:s work_id:s url:s is_primary:b width_px:n height_px:n background_color_hex:s",
    "catalog.work_isbn13s" to "isbn13|isbn13:s work_id:s cover_id:s",
    "catalog.work_asins" to "asin|asin:s work_id:s",
    "catalog.goodreads_edition_ids" to "goodreads_edition_id|goodreads_edition_id:n goodreads_work_id:n work_id:s",
    "catalog.goodreads_work_ids" to "goodreads_work_id|goodreads_work_id:n work_id:s",
    "userspace.works" to "user_id,work_id|user_id:s work_id:s position:n is_owned:b is_private:b preferred_cover_id:s created_at:n",
    "userspace.readthroughs" to "readthrough_id|readthrough_id:s user_id:s work_id:s language:s language_2:s language_3:s status:s is_audio:b is_ebook:b is_print:b touched_at:n num_pages:n num_locations:n num_seconds:n rating:n preferred_cover_id:s start_date:s end_date:s review:s notes:s is_private:b created_at:n",
    "userspace.reading_sessions_v2" to "reading_session_id|reading_session_id:s user_id:s readthrough_id:s session_date:n session_utc_offset_in_seconds:n start_time:n created_at:n seconds_read:n start_position:n end_position:n progress:n unit:s is_audio:b is_ebook:b is_print:b is_private:b pages_equivalent:n time_equivalent_in_seconds:n",
    "userspace.reading_day_activity" to "user_id,day|user_id:s day:n activity_count:n session_minutes:n session_pages:n",
    "userspace.library_summary" to "user_id|user_id:s works_count:n owned_count:n is_private_count:n series_count:n contributors_count:n favorites_count:n unread_count:n want_to_read_count:n in_progress_readthroughs_count:n finished_readthroughs_count:n stopped_readthroughs_count:n recommendations_count:n",
    "userspace.profiles" to "user_id|user_id:s display_name:s user_privacy_level:s user_profile_picture_preset_id:s custom_profile_picture_id:s followers_count:n following_count:n member_since:n joined_at:n created_at:n pfp_url:s",
    "userspace.want_to_read" to "want_to_read_id|want_to_read_id:s user_id:s position:n readthrough_id:s contributor_id:s series_id:s list_id:s target_date:s is_private:b created_at:n",
    "userspace.social_connections" to "user_id,target_user_id|user_id:s target_user_id:s social_connection_type:s is_reciprocal_follow:b target_privacy_level:s created_at:n"
)

private val primaryKeys = marginsTables.mapValues { (_, spec) -> spec.substringBefore('|').split(',') }

/** The client schema Zero expects in `initConnection`, keyed by server table name. */
internal val MARGINS_CLIENT_SCHEMA: JSONObject = JSONObject().put(
    "tables",
    JSONObject().apply {
        marginsTables.forEach { (table, spec) ->
            val columns = JSONObject()
            spec.substringAfter('|').split(' ').forEach { column ->
                val type = when (column.substringAfter(':')) {
                    "s" -> "string"
                    "n" -> "number"
                    else -> "boolean"
                }
                columns.put(column.substringBefore(':'), JSONObject().put("type", type))
            }
            put(table, JSONObject().put("columns", columns).put("primaryKey", JSONArray(primaryKeys.getValue(table))))
        }
    }
)

/** One named Zero query; its hash is derived from the name and arguments, as Zero's own client does. */
class ZeroQuery(val name: String, vararg arguments: Any) {
    val args = JSONArray(arguments.toList())
    val hash = digest("$name:$args").take(20)

    fun put(): JSONObject = JSONObject().put("op", "put").put("hash", hash).put("name", name).put("args", args).put("ttl", 60_000)
}

/** The rows a connection received, by server table name. */
class ZeroRows(private val tables: Map<String, Map<String, JSONObject>>) {
    fun rows(table: String): List<JSONObject> = tables[table]?.values?.toList().orEmpty()
}

private sealed interface ZeroEvent {
    class Text(val text: String) : ZeroEvent
    class Closed(val code: Int) : ZeroEvent
    class Failed(val error: Throwable, val status: Int?) : ZeroEvent
}

/** The client group is gone from the server; a new group and one retry fix it. */
private class ZeroGroupReset : Exception()

/**
 * A minimal Zero sync client over one short WebSocket per call. It speaks protocol [ZERO_PROTOCOL] with JSON pokes,
 * subscribes to Margins' named queries, and pushes Margins' named mutators. The client group persists per account in
 * [store] so the server keeps one record of it; every connection is a new client.
 */
class MarginsZero(private val url: String = MARGINS_ZERO_URL, private val store: DiagnosticsStore, private val http: OkHttpClient = defaultClient()) {
    suspend fun query(token: String, account: String, queries: List<ZeroQuery>): ZeroRows = withGroup(account) { group ->
        Connection(token, account, group).use { it.query(queries) }
    }

    /**
     * Pushes one mutator. Returns when the server applied it; an app error from Margins is `margins_push_rejected`.
     * [sending] runs just before the push leaves the device, so a caller can tell an unsent push from an uncertain one.
     */
    suspend fun push(token: String, account: String, name: String, payload: JSONObject, sending: () -> Unit = {}) = withGroup(account) { group ->
        Connection(token, account, group).use { it.push(name, payload, sending) }
    }

    /** Forgets the client group, for example on Log out. */
    fun forget() = store.delete(GROUP_KEY)

    private suspend fun <T> withGroup(account: String, block: suspend (String) -> T): T = try {
        block(group(account))
    } catch (_: ZeroGroupReset) {
        forget()
        try {
            block(group(account))
        } catch (_: ZeroGroupReset) {
            throw SyncProblem("margins_client_group_rejected")
        }
    }

    /** The account's client group, renewed when the account or the client schema changes. */
    private fun group(account: String): String {
        val owner = digest("$account:$MARGINS_CLIENT_SCHEMA")
        store.get(GROUP_KEY)?.let(::JSONObject)?.takeIf { it.optString("owner") == owner }?.let { return it.getString("id") }
        val id = UUID.randomUUID().toString().replace("-", "")
        store.put(GROUP_KEY, JSONObject().put("owner", owner).put("id", id).toString())
        return id
    }

    private inner class Connection(token: String, account: String, private val group: String) : AutoCloseable {
        private val events = LinkedBlockingQueue<ZeroEvent>()
        private val clientId = UUID.randomUUID().toString().replace("-", "")
        private val deadline = System.currentTimeMillis() + ZERO_ANSWER_MS
        private val socket: WebSocket

        init {
            val connect = "$url/sync/v$ZERO_PROTOCOL/connect?clientID=$clientId&clientGroupID=$group&userID=$account&baseCookie=&ts=${System.currentTimeMillis()}&lmid=0" +
                "&wsid=${UUID.randomUUID().toString().take(8)}&profileID=${UUID.randomUUID().toString().take(8)}"
            // Zero reads the token from the WebSocket protocol header as base64 JSON, as its own client sends it.
            val protocol = Base64.getEncoder().encodeToString(JSONObject().put("authToken", token).toString().toByteArray(Charsets.UTF_8))
            val request = Request.Builder().url(connect).header("Sec-WebSocket-Protocol", URLEncoder.encode(protocol, "UTF-8")).header("Origin", MARGINS_ORIGIN).build()
            socket = http.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        events.add(ZeroEvent.Text(text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        events.add(ZeroEvent.Closed(code))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        events.add(ZeroEvent.Closed(code))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        events.add(ZeroEvent.Failed(t, response?.code))
                    }
                }
            )
        }

        private fun send(message: JSONArray) {
            if (!socket.send(message.toString())) throw IOException("margins_sync_closed")
        }

        private suspend fun init(queries: List<ZeroQuery>) {
            expect("connected")
            send(JSONArray().put("initConnection").put(JSONObject().put("desiredQueriesPatch", JSONArray(queries.map { it.put() })).put("clientSchema", MARGINS_CLIENT_SCHEMA).put("activeClients", JSONArray().put(clientId))))
        }

        suspend fun query(queries: List<ZeroQuery>): ZeroRows {
            init(queries)
            val wanted = queries.associateBy { it.hash }
            val answered = mutableSetOf<String>()
            val tables = mutableMapOf<String, MutableMap<String, JSONObject>>()
            val pending = mutableListOf<JSONObject>()
            val got = mutableListOf<String>()
            while (!answered.containsAll(wanted.keys)) {
                val message = next()
                when (message.getString(0)) {
                    "pokeStart" -> {
                        pending.clear()
                        got.clear()
                    }

                    "pokePart" -> {
                        val part = message.getJSONObject(1)
                        part.optJSONArray("rowsPatch")?.let { rows -> (0 until rows.length()).forEach { pending.add(rows.getJSONObject(it)) } }
                        part.optJSONArray("gotQueriesPatch")?.let { patch ->
                            (0 until patch.length()).map(patch::getJSONObject).filter { it.optString("op") == "put" }.forEach { got.add(it.getString("hash")) }
                        }
                    }

                    // Rows apply only once their poke ends; a cancelled poke is dropped whole.
                    "pokeEnd" -> if (!message.getJSONObject(1).optBoolean("cancel")) {
                        pending.forEach { applyRow(tables, it) }
                        answered.addAll(got)
                    }

                    "transformError" -> {
                        val failed = message.getJSONArray(1)
                        (0 until failed.length()).map(failed::getJSONObject).firstOrNull { it.optString("id") in wanted }?.let {
                            throw SyncProblem("margins_query_rejected_${wanted.getValue(it.getString("id")).name.lowercase()}")
                        }
                    }
                }
                coroutineContext.ensureActive()
            }
            return ZeroRows(tables)
        }

        suspend fun push(name: String, payload: JSONObject, sending: () -> Unit) {
            init(emptyList())
            val now = System.currentTimeMillis()
            val mutation = JSONObject().put("type", "custom").put("id", 1).put("clientID", clientId).put("name", name).put("args", JSONArray().put(payload)).put("timestamp", now)
            coroutineContext.ensureActive()
            sending()
            send(JSONArray().put("push").put(JSONObject().put("clientGroupID", group).put("mutations", JSONArray().put(mutation)).put("pushVersion", 1).put("requestID", UUID.randomUUID().toString()).put("timestamp", now)))
            try {
                awaitPush()
            } catch (_: ZeroGroupReset) {
                // The push left the device, so sending it again with a new group could apply it twice.
                throw IOException("margins_sync_closed")
            }
        }

        /**
         * Zero reports the outcome as a push response, inside a poke as an error result, or as the client's last
         * mutation id reaching this mutation. A successful push response still waits for that poke, so the next read
         * sees the write; the deadline ends the wait without an error once the response said the push succeeded.
         */
        private suspend fun awaitPush() {
            var confirmed = false
            var failure: JSONObject? = null
            var applied = false
            while (true) {
                val message = try {
                    next()
                } catch (error: IOException) {
                    if (confirmed) return
                    throw error
                }
                when (message.getString(0)) {
                    "pushResponse" -> {
                        val mutations = message.getJSONObject(1).optJSONArray("mutations") ?: throw HttpProblem(503, "push_failed", "margins")
                        (0 until mutations.length()).map(mutations::getJSONObject).firstOrNull { it.optJSONObject("id")?.optString("clientID") == clientId }?.let {
                            result(it.optJSONObject("result"))
                            confirmed = true
                        }
                    }

                    "pokeStart" -> {
                        failure = null
                        applied = false
                    }

                    "pokePart" -> {
                        val part = message.getJSONObject(1)
                        part.optJSONArray("mutationsPatch")?.let { patch ->
                            (0 until patch.length()).mapNotNull { patch.getJSONObject(it).optJSONObject("mutation") }.firstOrNull { it.optJSONObject("id")?.optString("clientID") == clientId }
                                ?.let { failure = it.optJSONObject("result") }
                        }
                        if ((part.optJSONObject("lastMutationIDChanges")?.optLong(clientId) ?: 0) >= 1) applied = true
                    }

                    // A cancelled poke is dropped whole.
                    "pokeEnd" -> if (applied && !message.getJSONObject(1).optBoolean("cancel")) return result(failure)
                }
                coroutineContext.ensureActive()
            }
        }

        private fun result(result: JSONObject?) {
            when (result?.optString("error")) {
                null, "", "alreadyProcessed" -> return
                "app" -> throw SyncProblem("margins_push_rejected")
                else -> throw HttpProblem(503, "push_failed", "margins")
            }
        }

        private fun applyRow(tables: MutableMap<String, MutableMap<String, JSONObject>>, patch: JSONObject) {
            if (patch.optString("op") == "clear") {
                tables.clear()
                return
            }
            val table = patch.optString("tableName")
            val keys = primaryKeys[table] ?: return
            val rows = tables.getOrPut(table) { mutableMapOf() }
            val source = patch.optJSONObject("value") ?: patch.optJSONObject("id") ?: return
            val key = keys.joinToString("\u0000") { source.opt(it)?.toString().orEmpty() }
            when (patch.optString("op")) {
                "put" -> rows[key] = source
                "update" -> rows[key]?.let { row -> patch.optJSONObject("merge")?.let { merge -> merge.keys().forEach { row.put(it, merge.get(it)) } } }
                "del" -> rows.remove(key)
            }
        }

        private suspend fun expect(kind: String) {
            while (next().getString(0) != kind) Unit
        }

        /**
         * The next protocol message; a server error, a closed socket, or the deadline ends the connection. The wait is
         * interruptible, so Off, Cancel, and Log out stop it at once.
         */
        private suspend fun next(): JSONArray {
            val wait = deadline - System.currentTimeMillis()
            val event = (if (wait > 0) runInterruptible(Dispatchers.IO) { events.poll(wait, TimeUnit.MILLISECONDS) } else null) ?: throw IOException("margins_sync_timeout")
            return when (event) {
                is ZeroEvent.Failed -> when (event.status) {
                    401, 403 -> throw HttpProblem(event.status, MARGINS_REJECTED_TOKEN, "margins")
                    null -> throw (event.error as? IOException ?: IOException(event.error))
                    else -> throw HttpProblem(event.status, null, "margins")
                }

                is ZeroEvent.Closed -> throw IOException("margins_sync_closed")

                is ZeroEvent.Text -> {
                    val message = runCatching { JSONArray(event.text) }.getOrNull() ?: throw SyncProblem("margins_invalid_message")
                    if (message.optString(0) == "error") serverError(message.optJSONObject(1)?.optString("kind").orEmpty())
                    message
                }
            }
        }

        private fun serverError(kind: String): Nothing = when (kind) {
            "Unauthorized", "AuthInvalidated" -> throw HttpProblem(401, MARGINS_REJECTED_TOKEN, "margins")
            "SchemaVersionNotSupported" -> throw SyncProblem("margins_schema_changed")
            "VersionNotSupported" -> throw SyncProblem("margins_protocol_unsupported")
            "ClientNotFound", "InvalidConnectionRequestBaseCookie", "InvalidConnectionRequestLastMutationID", "InvalidConnectionRequestClientDeleted" -> throw ZeroGroupReset()
            "MutationRateLimited" -> throw HttpProblem(429, "rate_limited", "margins")
            "Rebalance", "Rehome", "ServerOverloaded", "Internal", "PushFailed" -> throw HttpProblem(503, kind.lowercase(), "margins")
            "MutationFailed", "InvalidPush" -> throw SyncProblem("margins_push_rejected")
            else -> throw SyncProblem("margins_sync_error")
        }

        override fun close() {
            socket.close(1000, null)
        }
    }

    private companion object {
        const val GROUP_KEY = "margins.clientGroup"

        fun defaultClient() = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    }
}
