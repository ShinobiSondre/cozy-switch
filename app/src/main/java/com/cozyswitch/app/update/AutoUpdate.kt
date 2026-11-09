package com.cozyswitch.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cozyswitch.app.MainActivity
import com.cozyswitch.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Hands a downloaded APK to Android's package installer. */
object Installer {
    /** True while the app is on screen, so the installer's confirm screen can be shown directly. */
    @Volatile var appVisible = false

    fun install(ctx: Context, file: File): Boolean = try {
        val app = ctx.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val result = PendingIntent.getBroadcast(app, id, Intent(app, InstallResultReceiver::class.java), flags)
            session.commit(result.intentSender)
        }
        true
    } catch (_: Exception) {
        false
    }
}

/** Android reports back here: installed, needs a tap to confirm, or failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (Installer.appVisible) {
                    try { ctx.startActivity(confirm); return } catch (_: Exception) { }
                }
                UpdateNotice.show(
                    ctx, "Cozy Switch update ready", "Tap to install the new version.",
                    PendingIntent.getActivity(ctx, 3, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                )
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts as the new version
            else -> if (Installer.appVisible) Updater.fail("The update didn't install. Try again later.")
        }
    }
}

object UpdateNotice {
    private const val CHANNEL = "updates"

    fun show(ctx: Context, title: String, text: String, tap: PendingIntent) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_plug)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try { nm.notify(77, n) } catch (_: SecurityException) { }
    }
}

/**
 * Checks GitHub for a new version every few hours in the background and installs it.
 * Android 12+ installs it silently after the first in-app update; otherwise a notification
 * asks for one tap.
 */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (Updater.stage != UpdateStage.Idle) return Result.success() // the app is already on it
        val r = try { Updater.latestRelease() } catch (_: Exception) { null } ?: return Result.retry()
        if (r.build <= Updater.currentBuild) return Result.success()
        if (!Updater.canInstall(ctx)) {
            UpdateNotice.show(
                ctx, "Cozy Switch update available", "Open the app to install it.",
                PendingIntent.getActivity(ctx, 4, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            return Result.success()
        }
        val file = download(ctx, r) ?: return Result.retry()
        Installer.install(ctx, file)
        return Result.success()
    }

    private suspend fun download(ctx: Context, r: Updater.Release): File? = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "cozyswitch-build-${r.build}.apk")
        if (file.exists() && file.length() == r.size) return@withContext file
        dir.listFiles()?.forEach { it.delete() }
        val part = File(dir, file.name + ".part")
        try {
            var u = URL(r.url)
            var conn: HttpURLConnection
            var hops = 0
            while (true) {
                conn = (u.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 10_000; readTimeout = 30_000
                    setRequestProperty("User-Agent", "CozySwitch")
                }
                val code = conn.responseCode
                if (code in 300..399 && hops++ < 5) {
                    u = URL(u, conn.getHeaderField("Location")); conn.disconnect()
                } else break
            }
            if (conn.responseCode != 200) { conn.disconnect(); return@withContext null }
            conn.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            if (r.size > 0 && part.length() != r.size) { part.delete(); return@withContext null }
            part.renameTo(file)
            file
        } catch (_: Exception) {
            part.delete(); null
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "auto-update", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(4, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }
}
