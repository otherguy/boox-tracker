package org.readingsync.diagnostic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val CHANNEL = "observation"
const val WORK_NAME = "reading-sync.background-checks"
const val SESSION_MS = 600_000L

fun expired(
    deadline: Long,
    now: Long
) = now >= deadline

fun createChannel(context: android.content.Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Observation session", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }
    )
}

fun notificationAllowed(context: android.content.Context): Boolean {
    createChannel(context)
    val manager = context.getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE &&
        (
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            )
}

class ObservationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var diagnostics: Diagnostics
    private var observer: ContentObserver? = null
    private var deadline = 0L
    private var runId: String? = null
    private var stopping = false
    private var observerQuery: Job? = null

    override fun onCreate() {
        super.onCreate()
        diagnostics = (application as ReadingSyncApp).diagnostics
        createChannel(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent?.action == "stop") {
            finish("user_stop")
            return START_NOT_STICKY
        }
        if (runId != null || stopping) return START_NOT_STICKY
        val id = UUID.randomUUID().toString()
        runId = id
        if (!notificationAllowed(this)) {
            finish("notification_unavailable", true)
            return START_NOT_STICKY
        }
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ObservationService::class.java).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        val open =
            PendingIntent.getActivity(
                this,
                2,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        val notification =
            NotificationCompat
                .Builder(this, CHANNEL)
                .setSmallIcon(org.readingsync.diagnostic.R.drawable.ic_book)
                .setContentTitle("Reading Sync observation")
                .setContentText("10-minute local observation session")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "Stop", stop)
                .build()
        try {
            if (Build.VERSION.SDK_INT >=
                29
            ) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }
        } catch (
            error: Exception
        ) {
            scope.launch {
                diagnostics.event("observation", "start_failed", id, errorDetails(error, "start foreground"), true)
                finish("start_failed", true)
            }
            return START_NOT_STICKY
        }
        (application as ReadingSyncApp).sessionActive = true
        deadline = SystemClock.elapsedRealtime() + SESSION_MS
        scope.launch {
            diagnostics.startRun("observation", id)
            observer =
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        if (stopping) return
                        if (expired(deadline, SystemClock.elapsedRealtime())) {
                            finish("deadline_after_gap")
                            return
                        }
                        if (observerQuery?.isActive !=
                            true
                        ) {
                            observerQuery = scope.launch { diagnostics.collect("observation", "observer", id, deadline) }
                        }
                    }
                }
            try {
                val registered = withContext(Dispatchers.Main) {
                    if (stopping) {
                        false
                    } else {
                        contentResolver.registerContentObserver(METADATA_URI, true, observer!!)
                        true
                    }
                }
                if (registered) diagnostics.event("observation", "observer_registered", id)
            } catch (
                error: Exception
            ) {
                diagnostics.event("observation", "observer_failed", id, errorDetails(error, "register observer"), true)
            }
            diagnostics.collect("observation", "initial", id, deadline)
            var nextPoll = SystemClock.elapsedRealtime() + 5000
            while (isActive && !stopping) {
                delay((nextPoll - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (expired(deadline, SystemClock.elapsedRealtime())) {
                    finish("deadline")
                    break
                }
                diagnostics.collect("observation", "poll", id, deadline)
                do {
                    nextPoll += 5000
                } while (nextPoll <= SystemClock.elapsedRealtime())
            }
        }
        handler.postDelayed({ finish("deadline") }, SESSION_MS)
        return START_NOT_STICKY
    }

    private fun finish(
        reason: String,
        issue: Boolean = false
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { finish(reason, issue) }
            return
        }
        synchronized(this) {
            if (stopping) return
            stopping = true
        }
        val cleanupScope = CoroutineScope(Dispatchers.IO)
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        observer?.let { runCatching { contentResolver.unregisterContentObserver(it) } }
        observer = null
        (application as ReadingSyncApp).sessionActive = false
        cleanupScope.launch {
            runId?.let { id ->
                if (deadline > 0) {
                    diagnostics.event(
                        "observation",
                        "deadline_detail",
                        id,
                        JSONObject().put("deadlineElapsedMs", deadline).put("lateByMs", (SystemClock.elapsedRealtime() - deadline).coerceAtLeast(0))
                    )
                }
                diagnostics.stopRun("observation", id, reason, issue)
            }
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        if (!stopping) finish("service_destroyed", true)
        super.onDestroy()
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int
    ) {
        finish("android_service_timeout", true)
    }

    override fun onBind(intent: Intent?) = null
}

class ScheduledCheckWorker(
    context: android.content.Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val diagnostics = (applicationContext as ReadingSyncApp).diagnostics
        if (diagnostics.store.get("background") != "true") return Result.success()
        val runId = "$id:$runAttemptCount:${UUID.randomUUID()}"
        diagnostics.startRun("scheduled", runId)
        try {
            diagnostics.collect("scheduled", "periodic", runId)
            diagnostics.stopRun("scheduled", runId, "completed")
            return Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { diagnostics.stopRun("scheduled", runId, "worker_cancelled", true) }
            throw error
        } catch (error: Exception) {
            diagnostics.event("scheduled", "worker_failed", runId, errorDetails(error, "worker"), true)
            diagnostics.stopRun("scheduled", runId, "failed", true)
            return Result.failure()
        }
    }
}

fun periodicRequest() = PeriodicWorkRequestBuilder<ScheduledCheckWorker>(15, TimeUnit.MINUTES).setInitialDelay(15, TimeUnit.MINUTES).build()

suspend fun setBackground(
    app: ReadingSyncApp,
    enabled: Boolean
) {
    val diagnostics = app.diagnostics
    diagnostics.store.put("background", enabled.toString())
    try {
        val manager = WorkManager.getInstance(app)
        val operation =
            if (enabled) {
                manager.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest()
                )
            } else {
                manager.cancelUniqueWork(WORK_NAME)
            }
        withContext(Dispatchers.IO) { operation.result.get() }
        diagnostics.event("manual", "background_setting", detail = JSONObject().put("enabled", enabled))
    } catch (error: Exception) {
        if (enabled) diagnostics.store.put("background", "false")
        diagnostics.event("manual", "schedule_failed", detail = errorDetails(error, "set background"), issue = true)
    }
}
