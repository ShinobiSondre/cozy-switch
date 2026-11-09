package com.cozyswitch.app.auto

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cozyswitch.app.MainActivity
import com.cozyswitch.app.R
import com.cozyswitch.app.data.Automation
import com.cozyswitch.app.data.Automations
import com.cozyswitch.app.data.Plugs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.ZonedDateTime

/** Puts each automation's next run into Android's alarm clock. */
object Scheduler {
    private const val ACTION_RUN = "com.cozyswitch.app.RUN_AUTOMATION"
    const val EXTRA_ID = "id"

    fun exactAllowed(ctx: Context) =
        ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Opens Android's "Alarms & reminders" switch for Cozy Switch. */
    fun askExact(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun pending(ctx: Context, id: String, create: Boolean): PendingIntent? {
        val i = Intent(ctx, AutomationReceiver::class.java).setAction(ACTION_RUN)
            .setData(android.net.Uri.parse("cozyswitch://automation/$id")) // one alarm per automation
            .putExtra(EXTRA_ID, id)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(ctx, 0, i, flags)
    }

    fun schedule(ctx: Context, a: Automation) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AlarmManager::class.java)
        val at = a.next(ZonedDateTime.now())
        if (at == null) { cancel(app, a.id); return }
        val pi = pending(app, a.id, true)!!
        val ms = at.toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else am.setWindow(AlarmManager.RTC_WAKEUP, ms, 60_000, pi) // within a minute, usually
    }

    fun cancel(ctx: Context, id: String) {
        val pi = pending(ctx.applicationContext, id, false) ?: return
        ctx.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    fun rescheduleAll(ctx: Context) = Automations.load(ctx).forEach { schedule(ctx, it) }

    /** Runs an automation now (from its alarm, or the "Run now" button). */
    fun runNow(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "automation-$id", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutomationWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().putString(EXTRA_ID, id).build())
                .build()
        )
    }
}

/** Woken by the alarm at the automation's time. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Scheduler.EXTRA_ID) ?: return
        val a = Automations.get(ctx, id) ?: return
        if (a.days.isEmpty()) Automations.put(ctx, a.copy(enabled = false)) // "once" is done after this
        else Scheduler.schedule(ctx, a) // next week's / tomorrow's run
        Scheduler.runNow(ctx, id)
    }
}

/** Keeps automations scheduled after a restart, an app update, or a clock change. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.rescheduleAll(ctx)
}

/** Switches the automation's plugs, retrying a few times if one doesn't answer. */
class AutomationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun getForegroundInfo() = androidx.work.ForegroundInfo(
        91, Notices.builder(applicationContext, "Running automation…").build()
    )

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getString(Scheduler.EXTRA_ID) ?: return Result.success()
        val a = Automations.get(ctx, id) ?: return Result.success()
        val plugs = Plugs.load(ctx).filter { it.did in a.plugs }
        // Only the plugs not already in the wanted state, so a retry doesn't flick anything.
        val todo = plugs.filter { it.on != a.on || runAttemptCount == 0 }
        val failed = coroutineScope {
            todo.map { p -> async { if (Plugs.switch(ctx, p.did, a.on) == a.on) null else p.name } }.awaitAll()
        }.filterNotNull()
        if (failed.isEmpty()) return Result.success()
        if (runAttemptCount < 3) return Result.retry() // WorkManager waits ~30 s, then longer
        Notices.show(
            ctx, a.id.hashCode(),
            "${a.name.ifBlank { "Automation" }} didn't finish",
            "Couldn't turn ${if (a.on) "on" else "off"}: ${failed.joinToString()}. " +
                "Check the plug, or unplug it for 10 seconds."
        )
        return Result.success()
    }
}

object Notices {
    private const val CHANNEL = "automations"

    fun builder(ctx: Context, text: String): NotificationCompat.Builder {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Automations", NotificationManager.IMPORTANCE_DEFAULT))
        }
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_plug)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
    }

    fun show(ctx: Context, id: Int, title: String, text: String) {
        val n = builder(ctx, text).setContentTitle(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (_: SecurityException) { }
    }
}
