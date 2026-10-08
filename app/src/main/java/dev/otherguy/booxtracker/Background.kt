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
const val GOODREADS_RENEWAL_WORK_NAME = "boox-tracker.goodreads-renewal"

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

/** The first run comes one interval after the last renewal [renewedAt], so a sign-in, which renews, is not repeated at once. */
fun goodreadsRenewalRequest(renewedAt: Long?) = PeriodicWorkRequestBuilder<GoodreadsRenewalWorker>(GOODREADS_RENEWAL_MS, TimeUnit.MILLISECONDS)
    .setInitialDelay((GOODREADS_RENEWAL_MS - (System.currentTimeMillis() - (renewedAt ?: 0))).coerceIn(0, GOODREADS_RENEWAL_MS), TimeUnit.MILLISECONDS)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()

/** Renews the Goodreads session in a hidden browser every six hours while a network is available. */
fun scheduleGoodreadsRenewal(app: ReadingSyncApp) {
    val request = goodreadsRenewalRequest(app.goodreads.session.renewedAt())
    WorkManager.getInstance(app).enqueueUniquePeriodicWork(GOODREADS_RENEWAL_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
}

fun cancelGoodreadsRenewal(app: ReadingSyncApp) {
    WorkManager.getInstance(app).cancelUniqueWork(GOODREADS_RENEWAL_WORK_NAME)
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

/** Loads Goodreads in a hidden browser so its bot check and session cookies stay current between sends. */
class GoodreadsRenewalWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ReadingSyncApp
        if (!app.goodreads.renewable()) return Result.success()
        val run = "$id:$runAttemptCount:${UUID.randomUUID()}"
        app.diagnostics.startRun("renewal", run)
        try {
            // A failed renewal records its own issue; the run event only marks that the worker ran.
            app.diagnostics.stopRun("renewal", run, app.goodreads.renew("renewal").name.lowercase())
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { app.diagnostics.stopRun("renewal", run, "worker_cancelled", true) }
            throw error
        } catch (error: Exception) {
            app.diagnostics.event("renewal", "worker_failed", run, JSONObject().put("service", "goodreads").put("reason", failureReason(error)), true)
            app.diagnostics.stopRun("renewal", run, "failed", true)
        }
        return Result.success()
    }
}
