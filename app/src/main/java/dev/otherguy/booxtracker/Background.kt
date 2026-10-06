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
            app.sync("scheduled", "periodic", run)
            app.diagnostics.stopRun("scheduled", run, "completed")
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
        try {
            val retry = app.hardcover.drain("delivery")
            app.diagnostics.stopRun("delivery", run, if (retry) "retry_pending" else "completed")
            return if (retry) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { app.diagnostics.stopRun("delivery", run, "worker_cancelled", true) }
            throw error
        } catch (error: Exception) {
            app.diagnostics.event("delivery", "worker_failed", run, JSONObject().put("reason", failureReason(error)), !temporaryFailure(error))
            app.diagnostics.stopRun("delivery", run, "failed", true)
            return if (temporaryFailure(error)) Result.retry() else Result.success()
        }
    }
}
