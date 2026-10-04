package org.readingsync.diagnostic

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import java.time.Instant
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

class ReadingSyncApp :
    Application(),
    Application.ActivityLifecycleCallbacks {
    lateinit var diagnostics: Diagnostics

    @Volatile var visible = false

    @Volatile var sessionActive = false

    override fun onCreate() {
        super.onCreate()
        diagnostics = Diagnostics(this)
        registerActivityLifecycleCallbacks(this)
        diagnostics.scope.launch { diagnostics.ensureRecovered() }
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

fun books(snapshot: JSONObject?): List<JSONObject> = snapshot?.optJSONArray("books")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()

fun selectBook(
    snapshot: JSONObject?,
    key: String?
): JSONObject? {
    val records = books(snapshot)
    if (key != null) return records.firstOrNull { !it.isNull("key") && it.optString("key") == key }
    return records.filter { accessTime(it) != null }.maxByOrNull { accessTime(it)!! }
        ?: records.sortedBy { it.raw("name") ?: it.raw("uuid") ?: "" }.firstOrNull()
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
                .put("observationActive", app.sessionActive)
                .put("issue", issue)
        store.append(event)
        updates.value++
    }

    fun recover() {
        listOf("observation", "scheduled").forEach { source ->
            store.get("active.$source")?.takeIf { it.isNotEmpty() }?.let {
                event(
                    source,
                    "interruption_detected",
                    it,
                    JSONObject().put("reason", "Previous run has no recorded stop; termination time and cause are unknown"),
                    true
                )
                store.put("active.$source", "")
            }
        }
    }

    suspend fun startRun(
        source: String,
        id: String
    ) {
        ensureRecovered()
        store.put("active.$source", id)
        event(source, "start", id)
    }

    fun stopRun(
        source: String,
        id: String,
        reason: String,
        issue: Boolean = false
    ) {
        event(source, "stop", id, JSONObject().put("reason", reason), issue)
        store.put("active.$source", "")
    }

    fun snapshot(): JSONObject? = store.get("snapshot")?.let(::JSONObject)

    fun selection(): String? = store.get("selection")?.takeIf { it.isNotEmpty() }

    suspend fun collect(
        source: String,
        trigger: String,
        runId: String? = null,
        deadline: Long? = null
    ) = mutex.withLock {
        ensureRecovered()
        if (deadline != null && expired(deadline, SystemClock.elapsedRealtime())) return@withLock
        if (!inFlight.compareAndSet(false, true)) {
            event(source, "query_skipped", runId, JSONObject().put("reason", "Provider query still running").put("trigger", trigger), true)
            return@withLock
        }
        val start = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val signal = CancellationSignal()
        val visibleAtStart = app.visible
        val sessionAtStart = app.sessionActive
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
        val selected = if (result.optString("outcome") == "success") selectBook(result, selection()) else null
        val changes = if (result.optString("outcome") == "success") changes(previous, result) else JSONArray()
        val detail =
            JSONObject()
                .put("trigger", trigger)
                .put("appVisibleAtStart", visibleAtStart)
                .put("observationActiveAtStart", sessionAtStart)
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
                (source == "observation" && detail.optLong("gapMs") > 15_000)
        detail.put("unchanged", previous != null && changes.length() == 0 && result.optString("outcome") == "success")
        if (result.optString("outcome") ==
            "success"
        ) {
            result.put("readAt", Instant.now().toString())
            store.put("snapshot", result.toString())
        }
        store.put("lastCheck", detail.toString())
        event(source, "query", runId, detail, issue)
    }
}
