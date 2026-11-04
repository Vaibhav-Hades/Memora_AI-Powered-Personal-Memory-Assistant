package com.memora.app.local

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.memora.app.data.MemoraApi
import com.memora.app.data.ServerSettings
import java.util.concurrent.TimeUnit

/** The user's background-sync choice and the last outcome, kept on the phone. Off by default. */
class SyncPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("memora-sync", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit { putBoolean("enabled", v) }
    val lastRunAt: Long get() = prefs.getLong("last_run_at", 0L)
    val lastMessage: String get() = prefs.getString("last_message", "") ?: ""
    val pendingRetry: Boolean get() = prefs.getBoolean("pending_retry", false)

    fun record(report: SyncReport, now: Long) = prefs.edit {
        putLong("last_run_at", now); putString("last_message", report.message); putBoolean("pending_retry", report.shouldRetry)
    }
}

object SyncScheduler {
    private const val PERIODIC = "memora-sync-periodic"
    private const val NOW = "memora-sync-now"

    /** Every 6 hours, only on an unmetered network and not on a low battery. WorkManager keeps this across reboots. */
    fun enable(context: Context) {
        val c = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(c)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(NOW)
    }

    /** User pressed "Sync now": any network, runs once, retries with backoff if the laptop is unreachable. */
    fun syncNow(context: Context) {
        val c = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(c)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }
}

/** Runs [SyncRunner] in the background. In Phone only mode, or with no laptop set up, it does nothing and never touches the network. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (ModePrefs(ctx).mode == OperatingMode.PHONE_ONLY) return Result.success()
        val config = ServerSettings(ctx).load() ?: return Result.success()
        val memory = LocalMemory(ctx)
        val report = MemoraApi(config).let { api -> SyncRunner(api, memory.library, memory.downloader(api)).run() }
        SyncPrefs(ctx).record(report, System.currentTimeMillis())
        return if (report.shouldRetry && runAttemptCount < 5) Result.retry() else Result.success()
    }
}
