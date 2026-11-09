package com.cozyswitch.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cozyswitch.app.widget.PlugWidget
import java.util.concurrent.TimeUnit

/**
 * Switches a plug from the widget. Running as work (not inside the widget's 10-second window)
 * gives it time for every fallback: Wi-Fi, finding a moved plug, the cloud, a fresh relay address.
 */
class SwitchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val did = inputData.getString("did") ?: return Result.success()
        val on = inputData.getBoolean("on", true)
        try { Plugs.switch(applicationContext, did, on) } finally { PlugWidget.done(applicationContext, did) }
        return Result.success()
    }

    companion object {
        fun start(ctx: Context, did: String, on: Boolean) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "switch-$did", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SwitchWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(Data.Builder().putString("did", did).putBoolean("on", on).build())
                    .build()
            )
        }
    }
}

/** Keeps every plug's state fresh in the background (and the widget with it), every 15 minutes. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { Plugs.refresh(applicationContext, force = inputData.getBoolean("force", false)) }
        finally { PlugWidget.refreshed(applicationContext) }
        return Result.success()
    }

    companion object {
        fun now(ctx: Context, force: Boolean) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "refresh-now", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(Data.Builder().putBoolean("force", force).build())
                    .build()
            )
        }

        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "refresh-every-15", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                    .setInputData(Data.Builder().putBoolean("force", true).build())
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
