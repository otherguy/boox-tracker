package dev.otherguy.booxtracker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val WORK_NAME = "boox-tracker.collection"
const val DELIVERY_WORK_NAME = "boox-tracker.delivery"

fun periodicRequest() = PeriodicWorkRequestBuilder<ScheduledCheckWorker>(15, TimeUnit.MINUTES).setInitialDelay(15, TimeUnit.MINUTES).build()

suspend fun scheduleCollection(app: ReadingSyncApp) {
    val operation = WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, periodicRequest())
    withContext(Dispatchers.IO) { operation.result.get() }
}

fun scheduleDelivery(app: ReadingSyncApp) {
    WorkManager.getInstance(app).enqueueUniqueWork(
        DELIVERY_WORK_NAME,
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<DeliveryWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    )
}

class ScheduledCheckWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ReadingSyncApp
        val run = "$id:$runAttemptCount:${UUID.randomUUID()}"
        app.diagnostics.startRun("scheduled", run)
        try {
            val failed = app.sync("scheduled", "periodic", run)
            app.diagnostics.stopRun("scheduled", run, if (failed) "failed" else "completed", failed)
            return Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { app.diagnostics.stopRun("scheduled", run, "worker_cancelled", true) }
            throw error
        } catch (error: Exception) {
            app.diagnostics.event("scheduled", "worker_failed", run, JSONObject().put("reason", failureReason(error)), true)
            app.diagnostics.stopRun("scheduled", run, "failed", true)
            return Result.success()
        }
    }
}

class DeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ReadingSyncApp
        val run = "$id:$runAttemptCount:${UUID.randomUUID()}"
        app.diagnostics.startRun("delivery", run)
        var retry = false
        var failed = false
        val reasons = mutableSetOf<String>()
        try {
            for (connection in app.connections) {
                try {
                    retry = connection.drain("delivery") || retry
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val temporary = temporaryFailure(error)
                    // A shared cause such as a revoked ebook folder fails every service; record it once.
                    if (reasons.add(failureReason(error))) app.diagnostics.event("delivery", "worker_failed", run, JSONObject().put("service", connection.service).put("reason", failureReason(error)), !temporary)
                    retry = retry || temporary
                    failed = true
                }
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { app.diagnostics.stopRun("delivery", run, "worker_cancelled", true) }
            throw error
        }
        val outcome = when {
            retry -> "retry_pending"
            failed -> "failed"
            else -> "completed"
        }
        app.diagnostics.stopRun("delivery", run, outcome, failed)
        return if (retry) Result.retry() else Result.success()
    }
}
