package dev.otherguy.booxtracker

import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject

const val MARGINS_ACCOUNT = "00000000-0000-4000-8000-0000000000a1"
const val MARGINS_WORK = "00000000-0000-4000-8000-000000000101"
const val MARGINS_OTHER = "00000000-0000-4000-8000-000000000102"
const val MARGINS_ISBN = "9781398508255"
const val MARGINS_EBOOK_ISBN = "9781234567897"
const val MARGINS_OTHER_ISBN = "9780306406157"
const val MARGINS_GOODREADS = "58438630"
const val MARGINS_ASIN = "B0TESTASIN"
const val MARGINS_CODE = "123456"

/** A Supabase-shaped access token whose payload names [account]; only the claims matter to the app. */
fun marginsToken(account: String, generation: Int) = "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject().put("sub", account).put("n", generation).toString().toByteArray()) + ".signature"

/** A Margins read ("readthrough") with its reading sessions, in the row shapes Margins synced on 2026-10-09. */
class MarginsReadthrough(val id: String, val work: String, var status: String) {
    var startDate: String? = null
    var endDate: String? = null
    var isEbook = false
    var isPrint = false
    var isAudio = false
    var pages: Int? = null
    val sessions = mutableListOf<JSONObject>()

    fun row(account: String) = JSONObject().put("readthrough_id", id).put("user_id", account).put("work_id", work).put("status", status)
        .put("is_ebook", isEbook).put("is_print", isPrint).put("is_audio", isAudio).put("num_pages", pages ?: JSONObject.NULL).put("num_locations", JSONObject.NULL)
        .put("num_seconds", JSONObject.NULL).put("start_date", startDate ?: JSONObject.NULL).put("end_date", endDate ?: JSONObject.NULL)
        .put("touched_at", 1_758_741_058_388L).put("is_private", false).put("created_at", 1_758_741_058_514.0)

    fun session(id: String, start: Int, end: Int, unit: String, day: Long = 1_791_158_400_000L) = JSONObject().put("reading_session_id", id).put("readthrough_id", this.id)
        .put("session_date", day).put("created_at", day + sessions.size).put("start_position", start).put("end_position", end).put("progress", end - start).put("unit", unit)
        .put("is_audio", false).put("is_ebook", true).put("is_print", false).also { sessions.add(it) }
}

/** Fake Supabase Auth and Zero sync endpoints, with the message shapes Margins used on 2026-10-09. */
class MarginsServer : AutoCloseable {
    val requests = CopyOnWriteArrayList<JSONObject>()
    val connections = CopyOnWriteArrayList<JSONObject>()
    val pushes = CopyOnWriteArrayList<JSONObject>()
    var account = MARGINS_ACCOUNT
    val unknownEmails = mutableSetOf<String>()
    val revoked = mutableSetOf<String>()
    var invalidRefresh = false
    var schemaRejected = false
    var pushAppError = false
    var pushFailedOnce = false
    var rateLimitedOnce = false
    var dropOnce = false
    var confirmByResponse = false
    var rejectReads = false
    var ignoreStatus = false
    var ignoreProgress = false
    var signInRelease: java.util.concurrent.CountDownLatch? = null
    val reads = mutableListOf<MarginsReadthrough>()
    private var generation = 1
    private var nextRead = 0

    fun read(status: String, work: String = MARGINS_WORK): MarginsReadthrough = MarginsReadthrough("00000000-0000-4000-8000-%012d".format(900 + nextRead++), work, status).also(reads::add)

    private val works = mapOf(
        MARGINS_WORK to Triple("Synthetic Book", "Test Author", 480),
        MARGINS_OTHER to Triple("Other Book", "Other Author", 200)
    )
    private val isbns = mapOf(MARGINS_ISBN to MARGINS_WORK, MARGINS_EBOOK_ISBN to MARGINS_WORK, MARGINS_OTHER_ISBN to MARGINS_OTHER)

    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                if (url.encodedPath == "/sync/v51/connect") {
                    val protocol = java.net.URLDecoder.decode(request.getHeader("Sec-WebSocket-Protocol").orEmpty(), "UTF-8")
                    val token = JSONObject(String(Base64.getDecoder().decode(protocol))).getString("authToken")
                    check(request.getHeader("Origin") == "https://margins.app")
                    val connection = JSONObject().put("clientID", url.queryParameter("clientID")).put("clientGroupID", url.queryParameter("clientGroupID")).put("userID", url.queryParameter("userID")).put("token", token)
                    connections.add(connection)
                    return MockResponse().withWebSocketUpgrade(Peer(connection))
                }
                val text = request.body.readUtf8()
                val body = runCatching { JSONObject(text) }.getOrNull()
                requests.add(JSONObject().put("method", request.method).put("path", url.encodedPath).put("query", url.encodedQuery ?: JSONObject.NULL).put("auth", request.getHeader("Authorization") ?: JSONObject.NULL).put("apikey", request.getHeader("apikey") ?: JSONObject.NULL).put("body", body ?: text))
                val (status, response) = auth(url.encodedPath, body, request.getHeader("Authorization")?.removePrefix("Bearer "))
                return MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(response?.toString().orEmpty())
            }
        }
        start()
    }
    private val origin = server.url("/").toString().removeSuffix("/")
    val http = MarginsHttp(origin, "test-key")
    val zeroUrl = origin.replace("http://", "ws://")

    private fun supabaseError(status: Int, code: String) = status to JSONObject().put("code", status).put("error_code", code).put("msg", code)

    private fun session(): JSONObject {
        val now = System.currentTimeMillis() / 1000
        return JSONObject().put("access_token", marginsToken(account, generation)).put("refresh_token", "refresh-$generation").put("token_type", "bearer")
            .put("expires_in", 604_800).put("expires_at", now + 604_800).put("user", JSONObject().put("id", account))
    }

    private fun valid(token: String?) = token != null && token.startsWith("header.") && token !in revoked

    private fun auth(path: String, body: JSONObject?, token: String?): Pair<Int, Any?> = when (path) {
        "/auth/v1/otp" -> if (body!!.getString("email") in unknownEmails) supabaseError(422, "otp_disabled") else 200 to JSONObject()

        "/auth/v1/verify" -> {
            signInRelease?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (body!!.getString("token") != MARGINS_CODE) supabaseError(403, "otp_expired") else 200 to session()
        }

        "/auth/v1/token" -> if (invalidRefresh) {
            supabaseError(400, "refresh_token_not_found")
        } else {
            generation++
            200 to session()
        }

        "/auth/v1/user" -> if (!valid(token)) supabaseError(401, "bad_jwt") else 200 to JSONObject().put("id", account).put("email", "reader@example.com").put("created_at", "2025-09-22T21:17:00Z")

        "/auth/v1/logout" -> 204 to null

        else -> 404 to null
    }

    private fun rows(name: String, arg: Any?, connection: JSONObject): List<Pair<String, JSONObject>>? {
        fun work(id: String): List<Pair<String, JSONObject>> {
            val (title, author, pages) = works[id] ?: return emptyList()
            return listOf(
                "catalog.works" to JSONObject().put("work_id", id).put("original_title", title).put("num_pages", pages).put("num_locations", pages * 10),
                "catalog.work_titles" to JSONObject().put("title_id", "$id-title").put("work_id", id).put("title", title).put("language", "en-US").put("is_primary", true),
                "catalog.work_contributors" to JSONObject().put("work_contributor_id", "$id-author").put("work_id", id).put("contributor_id", "$id-person").put("position", 0),
                "catalog.contributor_names" to JSONObject().put("name_id", "$id-name").put("contributor_id", "$id-person").put("name", author).put("language", "en").put("is_primary", true)
            )
        }
        return when (name) {
            "workById" -> work(arg as String)

            "workByISBN13" -> isbns[arg as String]?.let { listOf("catalog.work_isbn13s" to JSONObject().put("isbn13", "${arg.take(3)}-${arg.drop(3)}").put("work_id", it).put("cover_id", JSONObject.NULL)) }.orEmpty()

            "workByGoodreadsEditionID" -> if (arg.toString() == MARGINS_GOODREADS) listOf("catalog.goodreads_edition_ids" to JSONObject().put("goodreads_edition_id", MARGINS_GOODREADS.toLong()).put("goodreads_work_id", 91709220).put("work_id", MARGINS_WORK)) else emptyList()

            "workByASIN" -> if (arg == MARGINS_ASIN) listOf("catalog.work_asins" to JSONObject().put("asin", MARGINS_ASIN).put("work_id", MARGINS_WORK)) else emptyList()

            "libraryMembershipReadthroughs" -> if (rejectReads) {
                null
            } else {
                val wanted = (arg as JSONObject).getJSONArray("workIds").let { ids -> (0 until ids.length()).map(ids::getString) }
                check(arg.getString("userId") == connection.getString("userID"))
                reads.filter { it.work in wanted }.flatMap { read -> listOf("userspace.readthroughs" to read.row(account)) + read.sessions.map { "userspace.reading_sessions_v2" to JSONObject(it.toString()).put("user_id", account) } }
            }

            "profile" -> listOf("userspace.profiles" to JSONObject().put("user_id", account).put("display_name", "Reader").put("followers_count", 0).put("following_count", 1).put("joined_at", 1_758_575_820_010.9).put("created_at", 1_758_575_820_010.9))

            else -> null
        }
    }

    /** Applies a Margins mutator the way Margins' server does for the cases the app uses. */
    private fun mutate(name: String, args: JSONObject) {
        when (name) {
            "librarySetReadthroughStatus" -> {
                check(!args.getBoolean("isInLibrary"))
                if (ignoreStatus) return
                val status = args.getString("status")
                val read = if (args.getBoolean("startsNewReadthrough")) {
                    reads.firstOrNull { it.work == args.getString("workId") && it.status in setOf("unread", "want_to_read") }
                        ?: MarginsReadthrough(args.getString("newReadthroughId"), args.getString("workId"), status).also(reads::add)
                } else {
                    reads.first { it.id == args.getString("readthroughId") }
                }
                if (read.status != "in_progress") read.startDate = args.getString("todayDate")
                if (status == "finished") {
                    read.endDate = args.getString("todayDate")
                    read.sessions.lastOrNull()?.let { last -> read.session(args.getString("newReadthroughId"), last.getInt("end_position"), 100, "percentages") }
                }
                read.status = status
            }

            "libraryAddReadingProgress" -> {
                val read = reads.first { it.id == args.getString("readthroughId") }
                if (ignoreProgress) return
                args.optJSONObject("readthroughUpdate")?.let {
                    read.isEbook = it.getBoolean("isEbook")
                    read.isPrint = it.getBoolean("isPrint")
                    read.isAudio = it.getBoolean("isAudio")
                }
                read.session(args.getString("id"), args.getInt("startPosition"), args.getInt("endPosition"), args.getString("unit"))
            }

            else -> error("unknown mutator $name")
        }
    }

    private inner class Peer(private val connection: JSONObject) : WebSocketListener() {
        private fun WebSocket.message(vararg parts: Any) = send(JSONArray(parts.toList()).toString())

        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.message("connected", JSONObject().put("wsid", "w"))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONArray(text)
            val body = message.getJSONObject(1)
            if (!valid(connection.getString("token"))) {
                webSocket.message("error", JSONObject().put("kind", "Unauthorized").put("message", "invalid token"))
                return
            }
            when (message.getString(0)) {
                "initConnection" -> {
                    check(body.getJSONObject("clientSchema").getJSONObject("tables").has("userspace.readthroughs"))
                    if (schemaRejected) {
                        webSocket.message("error", JSONObject().put("kind", "SchemaVersionNotSupported").put("message", "The \"public.languages\" table does not exist"))
                        return
                    }
                    val queries = body.getJSONArray("desiredQueriesPatch").let { patch -> (0 until patch.length()).map(patch::getJSONObject) }
                    val failed = JSONArray()
                    val got = JSONArray()
                    val patch = JSONArray()
                    queries.forEach { query ->
                        val found = rows(query.getString("name"), query.getJSONArray("args").opt(0), connection)
                        if (found == null) {
                            failed.put(JSONObject().put("error", "app").put("id", query.getString("hash")).put("name", query.getString("name")).put("message", "unknown query"))
                        } else {
                            got.put(JSONObject().put("op", "put").put("hash", query.getString("hash")))
                            found.forEach { (table, row) -> patch.put(JSONObject().put("op", "put").put("tableName", table).put("value", row)) }
                        }
                    }
                    if (failed.length() > 0) webSocket.message("transformError", failed)
                    webSocket.message("pokeStart", JSONObject().put("pokeID", "p1").put("baseCookie", JSONObject.NULL))
                    webSocket.message("pokePart", JSONObject().put("pokeID", "p1").put("gotQueriesPatch", got).put("rowsPatch", patch))
                    webSocket.message("pokeEnd", JSONObject().put("pokeID", "p1").put("cookie", "c1"))
                }

                "push" -> {
                    val mutation = body.getJSONArray("mutations").getJSONObject(0)
                    check(mutation.getString("type") == "custom" && mutation.getString("clientID") == connection.getString("clientID") && body.getString("clientGroupID") == connection.getString("clientGroupID"))
                    pushes.add(JSONObject().put("name", mutation.getString("name")).put("args", mutation.getJSONArray("args").getJSONObject(0)))
                    val id = JSONObject().put("clientID", mutation.getString("clientID")).put("id", mutation.getInt("id"))
                    when {
                        pushFailedOnce -> {
                            pushFailedOnce = false
                            webSocket.message("error", JSONObject().put("kind", "PushFailed").put("message", "database").put("mutationIDs", JSONArray().put(id)))
                        }

                        rateLimitedOnce -> {
                            rateLimitedOnce = false
                            webSocket.message("error", JSONObject().put("kind", "MutationRateLimited").put("message", "slow down"))
                        }

                        // The connection drops after the push arrived and before Margins applied it.
                        dropOnce -> {
                            dropOnce = false
                            webSocket.close(1001, null)
                        }

                        pushAppError -> webSocket.message("pushResponse", JSONObject().put("mutations", JSONArray().put(JSONObject().put("id", id).put("result", JSONObject().put("error", "app").put("message", "validation failed")))))

                        else -> {
                            mutate(mutation.getString("name"), mutation.getJSONArray("args").getJSONObject(0))
                            if (confirmByResponse) webSocket.message("pushResponse", JSONObject().put("mutations", JSONArray().put(JSONObject().put("id", id).put("result", JSONObject()))))
                            webSocket.message("pokeStart", JSONObject().put("pokeID", "p2").put("baseCookie", "c1"))
                            webSocket.message("pokePart", JSONObject().put("pokeID", "p2").put("lastMutationIDChanges", JSONObject().put(mutation.getString("clientID"), mutation.getInt("id"))))
                            webSocket.message("pokeEnd", JSONObject().put("pokeID", "p2").put("cookie", "c2"))
                        }
                    }
                }
            }
        }
    }

    fun pushNames(): List<String> = pushes.map { it.getString("name") }
    fun sessionPushes(): List<JSONObject> = pushes.filter { it.getString("name") == "libraryAddReadingProgress" }.map { it.getJSONObject("args") }
    override fun close() = server.shutdown()
}

fun marginsVault(app: ReadingSyncApp) = TokenVault(app, "margins") { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
