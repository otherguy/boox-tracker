package dev.otherguy.booxtracker

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

open class ReadingSyncApp :
    Application(),
    Application.ActivityLifecycleCallbacks {
    lateinit var diagnostics: Diagnostics
    lateinit var hardcover: HardcoverConnection
    lateinit var goodreads: GoodreadsConnection
    lateinit var fable: FableConnection
    lateinit var storygraph: StoryGraphConnection
    val connections: List<TrackerConnection> get() = listOf(hardcover, goodreads, storygraph, fable)

    @Volatile var visible = false

    private val syncMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics(this)
        hardcover = HardcoverConnection(this)
        goodreads = GoodreadsConnection(this)
        fable = FableConnection(this)
        storygraph = StoryGraphConnection(this)
        registerActivityLifecycleCallbacks(this)
        diagnostics.scope.launch {
            diagnostics.ensureRecovered()
            try {
                scheduleCollection(this@ReadingSyncApp)
                if (goodreads.session.connected()) scheduleGoodreadsRenewal(this@ReadingSyncApp)
            } catch (error: Exception) {
                diagnostics.event("system", "schedule_failed", detail = errorDetails(error, "schedule collection"), issue = true)
            }
        }
    }

    /** Collects fresh state and sends it to every enabled service. Returns true when a service send failed. */
    suspend fun sync(source: String, trigger: String, runId: String? = null): Boolean = syncMutex.withLock {
        requireEbookFolder(this, diagnostics.store)
        val fresh = diagnostics.collect(source, trigger, runId)
        var failed = false
        // Each service records its own failure so one service cannot stop delivery to another.
        for (connection in connections) {
            try {
                connection.send(source, fresh, runId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                connection.recordFailure(source, "${connection.service}_sync", error, runId)
                failed = true
            }
        }
        failed
    }

    override fun onActivityStarted(activity: Activity) {
        visible = true
    }

    override fun onActivityStopped(activity: Activity) {
        visible = false
    }

    override fun onActivityCreated(
        activity: Activity,
        state: Bundle?
    ) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(
        activity: Activity,
        state: Bundle
    ) {}

    override fun onActivityDestroyed(activity: Activity) {}
}

private const val LOGGED_CHANGES = 25

/**
 * The check as the event log keeps it: without the provider's column list, with a summary of the selected book, and
 * with only the first [LOGGED_CHANGES] library changes plus their count. The full check stays in `lastCheck`.
 */
fun loggedCheck(detail: JSONObject): JSONObject = JSONObject(detail.toString()).apply {
    remove("columns")
    // An issue check keeps the full record, so the log still shows why progress was unusable.
    optJSONObject("selected")?.takeIf { !optBoolean("issue") }?.let { book ->
        val summary = JSONObject()
        listOf(
            "key" to book.opt("key"),
            "title" to book.raw("title"),
            "authors" to book.raw("authors"),
            "percentage" to book.opt("percentage"),
            "readingStatus" to book.raw("readingStatus"),
            "lastAccess" to book.raw("lastAccess")
        ).forEach { (name, value) -> summary.put(name, value ?: JSONObject.NULL) }
        put("selected", summary)
    }
    optJSONArray("changes")?.let { all ->
        put("changeCount", all.length())
        if (all.length() > LOGGED_CHANGES) put("changes", JSONArray((0 until LOGGED_CHANGES).map { all.get(it) }))
    }
}

fun deviceInfo(): JSONObject = JSONObject()
    .put("manufacturer", Build.MANUFACTURER)
    .put("model", Build.MODEL)
    .put("android", Build.VERSION.RELEASE)
    .put("api", Build.VERSION.SDK_INT)
    .put("display", Build.DISPLAY)
    .put("buildId", Build.ID)
    .put("fingerprint", Build.FINGERPRINT)
    .put("incremental", Build.VERSION.INCREMENTAL)
    .put("securityPatch", Build.VERSION.SECURITY_PATCH)
    .put("appVersion", BuildConfig.VERSION_NAME)
    .put("versionCode", BuildConfig.VERSION_CODE)
    .put("variant", BuildConfig.BUILD_TYPE)

fun accessTime(book: JSONObject): Long? {
    val value = book.raw("lastAccess") ?: return null
    value.toLongOrNull()?.let {
        return when {
            it in 1_000_000_000L..9_999_999_999L -> it * 1000
            it in 1_000_000_000_000L..9_999_999_999_999L -> it
            else -> null
        }
    }
    return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

/** The local day the user last read the book: NeoReader's saved access time, else the queued read time, else now. */
fun readingDay(book: JSONObject, readAt: String?): LocalDate {
    val millis = accessTime(book) ?: readAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: System.currentTimeMillis()
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
}

fun books(snapshot: JSONObject?): List<JSONObject> = snapshot?.optJSONArray("books")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

fun mostRecentlyAccessedBook(snapshot: JSONObject?): JSONObject? {
    val records = books(snapshot).filter { accessTime(it) != null }
    val latest = records.maxOfOrNull { accessTime(it)!! } ?: return null
    return records.filter { accessTime(it) == latest }.singleOrNull()
}

fun changes(
    previous: JSONObject?,
    current: JSONObject
): JSONArray {
    fun indexed(snapshot: JSONObject?) = books(snapshot)
        .filter { !it.isNull("key") }
        .groupBy { it.getString("key") }
        .filterValues {
            it.size ==
                1
        }.mapValues { it.value.single() }
    val before = indexed(previous)
    val after = indexed(current)
    val result = JSONArray()
    if (previous == null) return result
    (before.keys + after.keys).sorted().forEach { key ->
        val old = before[key]
        val new = after[key]
        if (old == null || new == null) {
            result.put(JSONObject().put("key", key).put("kind", if (old == null) "added" else "removed"))
        } else {
            val fields = listOf("name", "progress", "readingStatus", "lastAccess", "extraAttributes")
            fields.forEach { field ->
                if (old.optJSONObject(field)?.toString() !=
                    new.optJSONObject(field)?.toString()
                ) {
                    result.put(
                        JSONObject()
                            .put("key", key)
                            .put("field", field)
                            .put("before", old.opt(field))
                            .put("after", new.opt(field))
                    )
                }
            }
        }
    }
    return result
}

class Diagnostics(
    private val app: ReadingSyncApp
) {
    val store = DiagnosticsStore(app)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val updates = MutableStateFlow(0L)
    val repository = NeoReaderRepository(app)
    private val mutex = Mutex()
    private val recovery = Mutex()
    private var recovered = false
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    suspend fun ensureRecovered() = recovery.withLock {
        if (!recovered) {
            recover()
            recovered = true
        }
    }
    private val process = UUID.randomUUID().toString()

    fun event(
        source: String,
        kind: String,
        runId: String? = null,
        detail: JSONObject = JSONObject(),
        issue: Boolean = false
    ) {
        val event =
            detail
                .put(
                    "timestamp",
                    Instant.now().toString()
                ).put("wallMs", System.currentTimeMillis())
                .put("elapsedMs", SystemClock.elapsedRealtime())
                .put("source", source)
                .put("kind", kind)
                .put("runId", runId ?: JSONObject.NULL)
                .put("process", process)
                .put("appVisible", app.visible)
                .put("issue", issue)
        store.append(event)
        updates.value++
    }

    fun recover() {
        listOf("hardcover", "goodreads", "fable", "storygraph").forEach { service ->
            store.get("$service.active")?.takeIf { it.isNotEmpty() }?.let { value ->
                val active = JSONObject(value)
                val detail = JSONObject().put("reason", "Previous send has no recorded finish; queued updates will reconcile remote state before retry.")
                detail.putOpt("key", active.text("key")).putOpt("startedAt", active.text("startedAt"))
                event(active.getString("source"), "${service}_interruption_detected", active.getString("runId"), detail, true)
                store.put("$service.active", "")
            }
        }
        listOf("scheduled", "delivery", "renewal").forEach { source ->
            store.get("active.$source")?.takeIf { it.isNotEmpty() }?.let { value ->
                // Older versions stored only the run id.
                val active = if (value.startsWith("{")) JSONObject(value) else JSONObject().put("id", value)
                val detail = JSONObject().put("reason", "Previous run has no recorded stop; termination time and cause are unknown")
                detail.putOpt("startedAt", active.text("startedAt"))
                event(source, "interruption_detected", active.getString("id"), detail, true)
                store.put("active.$source", "")
            }
        }
        prune()
    }

    @Volatile private var pruneFailed = false

    /** Applies the event retention limits, measured from the last successful sync. A failure is logged once per process. */
    fun prune() {
        try {
            if (store.prune(System.currentTimeMillis(), store.get("lastSyncMs")?.toLongOrNull()) > 0) updates.value++
        } catch (error: Exception) {
            if (!pruneFailed) {
                pruneFailed = true
                event("system", "prune_failed", detail = errorDetails(error, "prune"), issue = true)
            }
        }
    }

    suspend fun startRun(
        source: String,
        id: String
    ) {
        ensureRecovered()
        store.put(
            "active.$source",
            JSONObject().put("id", id).put("startedAt", Instant.now().toString()).put("startedElapsedMs", SystemClock.elapsedRealtime())
                .put("appVisible", app.visible).toString()
        )
    }

    /** Records one `run` event for the worker run that [startRun] began, then applies the retention limits. */
    fun stopRun(
        source: String,
        id: String,
        reason: String,
        issue: Boolean = false
    ) {
        val marker = store.get("active.$source")
        val active = marker?.takeIf { it.startsWith("{") }?.let(::JSONObject)?.takeIf { it.optString("id") == id }
        val detail = JSONObject().put("reason", reason)
        active?.let {
            detail.put("startedAt", it.getString("startedAt")).put("appVisibleAtStart", it.getBoolean("appVisible"))
                .put("durationMs", SystemClock.elapsedRealtime() - it.getLong("startedElapsedMs"))
        }
        event(source, "run", id, detail, issue)
        // A newer run may have started meanwhile; only this run's marker is cleared. Older versions stored the bare id.
        if (active != null || marker == id) store.put("active.$source", "")
        prune()
    }

    fun snapshot(): JSONObject? = store.get("snapshot")?.let(::JSONObject)

    suspend fun collect(
        source: String,
        trigger: String,
        runId: String? = null
    ): JSONObject? = mutex.withLock {
        ensureRecovered()
        if (!inFlight.compareAndSet(false, true)) {
            event(source, "query_skipped", runId, JSONObject().put("reason", "Provider query still running").put("trigger", trigger), true)
            return@withLock null
        }
        val start = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val signal = CancellationSignal()
        val visibleAtStart = app.visible
        val query = scope.async {
            try {
                repository.read(signal)
            } finally {
                inFlight.set(false)
            }
        }
        val result =
            try {
                withTimeout(15_000) { query.await() }
            } catch (error: Exception) {
                signal.cancel()
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                JSONObject().put("outcome", "query_failed").put("error", errorDetails(error, "query timeout"))
            }
        val previous = snapshot()
        val selected = if (result.optString("outcome") == "success") mostRecentlyAccessedBook(result) else null
        val changes = if (result.optString("outcome") == "success") changes(previous, result) else JSONArray()
        val detail =
            JSONObject()
                .put("trigger", trigger)
                .put("appVisibleAtStart", visibleAtStart)
                .put("readStartedWallMs", wall)
                .put(
                    "durationMs",
                    SystemClock.elapsedRealtime() - start
                ).put("outcome", result.getString("outcome"))
                .put("recordCount", result.opt("recordCount") ?: JSONObject.NULL)
                .put(
                    "columns",
                    result.optJSONArray("columns") ?: JSONArray()
                ).put("selected", selected ?: JSONObject.NULL)
                .put("changes", changes)
        result.optJSONObject("error")?.let { detail.put("error", it) }
        val last = store.get("lastElapsed.$source")?.toLongOrNull()
        val lastProcess = store.get("lastProcess.$source")
        if (last != null && lastProcess == process) detail.put("gapMs", start - last)
        store.put("lastElapsed.$source", start.toString())
        store.put("lastProcess.$source", process)
        val issue =
            result.optString("outcome") != "success" || selected?.isNull("percentage") == true ||
                (result.optInt("recordCount") > 0 && selected == null)
        detail.put("unchanged", previous != null && changes.length() == 0 && result.optString("outcome") == "success")
        detail.put("issue", issue)
        if (result.optString("outcome") ==
            "success"
        ) {
            result.put("readAt", Instant.now().toString())
            store.put("snapshot", result.toString())
        }
        store.put("lastCheck", detail.toString())
        val logged = loggedCheck(detail)
        event(source, "query", runId, logged, issue)
        // Callers read the event's timestamp and source from the full check.
        logged.keys().forEach { if (!detail.has(it)) detail.put(it, logged.get(it)) }
        detail
    }
}
