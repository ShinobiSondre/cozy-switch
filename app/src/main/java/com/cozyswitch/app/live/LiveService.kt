package com.cozyswitch.app.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cozyswitch.app.MainActivity
import com.cozyswitch.app.R
import com.cozyswitch.app.data.BleHub
import com.cozyswitch.app.data.LiveLink
import com.cozyswitch.app.data.Plugs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Always ready": keeps the plugs as available as the CozyLife app is while it's open, even when
 * Cozy Switch is closed. Every 10 seconds it refreshes every plug (and the widget), and it keeps
 * the standing cloud link and the Bluetooth links to plugs in range open.
 */
class LiveService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTE_ID, notice(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (job?.isActive != true) {
            job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                while (isActive) {
                    LiveLink.start(this@LiveService)
                    BleHub.start(this@LiveService)
                    try { withTimeoutOrNull(9_000) { Plugs.refresh(this@LiveService, force = true) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
                    delay(10_000)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        LiveLink.stopSoon(); BleHub.stopSoon()
        super.onDestroy()
    }

    private fun notice() = run {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Always ready", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps your plugs and the widget up to date in the background"
                setShowBadge(false)
            })
        }
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_plug)
            .setContentTitle("Keeping your plugs ready")
            .setContentText("Refreshing every 10 seconds. Turn off in Cozy Switch.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(PendingIntent.getActivity(this, 6, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    companion object {
        private const val CHANNEL = "always_ready"
        private const val NOTE_ID = 42
        private const val PREFS = "live"

        fun enabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

        fun setEnabled(ctx: Context, on: Boolean) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
            if (on) start(ctx) else ctx.stopService(Intent(ctx, LiveService::class.java))
        }

        /** Starts it if it's switched on (from the app, after a restart, or after an update). */
        fun start(ctx: Context) {
            if (!enabled(ctx)) return
            try { ctx.startForegroundService(Intent(ctx, LiveService::class.java)) } catch (_: Exception) { }
        }
    }
}
