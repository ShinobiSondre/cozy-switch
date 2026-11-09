package com.cozyswitch.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cozyswitch.app.MainActivity
import com.cozyswitch.app.R
import com.cozyswitch.app.data.Plug
import com.cozyswitch.app.data.Plugs
import com.cozyswitch.app.data.RefreshWorker
import com.cozyswitch.app.data.SwitchWorker

/** Home-screen widget: one row per plug, tap a row to switch it. */
class PlugWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        draw(ctx)
        RefreshWorker.schedule(ctx)
        RefreshWorker.now(ctx, force = false)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                val did = intent.getStringExtra(EXTRA_DID) ?: return
                val plug = Plugs.load(ctx).firstOrNull { it.did == did } ?: return
                val want = plug.on != true
                busy += did
                draw(ctx) // show "…" right away
                SwitchWorker.start(ctx, did, want)
            }
            ACTION_REFRESH -> {
                refreshing = true
                draw(ctx)
                RefreshWorker.now(ctx, force = true)
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.cozyswitch.app.TOGGLE"
        const val ACTION_REFRESH = "com.cozyswitch.app.REFRESH"
        const val EXTRA_DID = "did"

        // Plugs being switched, with when it started; anything older than 60 s is treated as done,
        // so a lost switch can never leave the widget stuck on "…".
        private val busySince = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private val busy = object {
            operator fun plusAssign(did: String) { busySince[did] = System.currentTimeMillis() }
            operator fun minusAssign(did: String) { busySince.remove(did) }
            operator fun contains(did: String) =
                busySince[did]?.let { System.currentTimeMillis() - it < 60_000 } == true
        }
        @Volatile private var refreshingSince = 0L
        private var refreshing: Boolean
            get() = System.currentTimeMillis() - refreshingSince < 45_000
            set(v) { refreshingSince = if (v) System.currentTimeMillis() else 0L }

        fun updateAll(ctx: Context) = draw(ctx)

        /** Called when a switch from the widget has finished. */
        fun done(ctx: Context, did: String) { busy -= did; draw(ctx) }

        fun refreshed(ctx: Context) { refreshing = false; draw(ctx) }

        private fun draw(ctx: Context) {
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, PlugWidget::class.java))
            if (ids.isEmpty()) return
            val plugs = Plugs.load(app)
            val views = RemoteViews(app.packageName, R.layout.widget_plugs)

            val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
            plugs.forEach { items.addItem(it.did.hashCode().toLong(), row(app, it)) }
            views.setRemoteAdapter(R.id.list, items.build())
            views.setEmptyView(R.id.list, R.id.empty)

            val toggle = Intent(app, PlugWidget::class.java).setAction(ACTION_TOGGLE)
            views.setPendingIntentTemplate(
                R.id.list,
                PendingIntent.getBroadcast(app, 1, toggle, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            )
            val refresh = Intent(app, PlugWidget::class.java).setAction(ACTION_REFRESH)
            views.setOnClickPendingIntent(
                R.id.refresh,
                PendingIntent.getBroadcast(app, 2, refresh, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            val open = PendingIntent.getActivity(
                app, 3, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.title, open)
            views.setOnClickPendingIntent(R.id.empty, open)
            views.setTextViewText(R.id.refresh, if (refreshing) "…" else "↻")

            mgr.updateAppWidget(ids, views)
        }

        private fun row(ctx: Context, p: Plug): RemoteViews {
            val r = RemoteViews(ctx.packageName, R.layout.widget_row)
            r.setTextViewText(R.id.name, p.name)
            val (label, bg) = when {
                p.did in busy -> "…" to R.drawable.pill_busy
                !p.online -> "offline" to R.drawable.pill_busy
                p.on == true -> (when (p.via) { "cloud" -> "ON ☁"; "bluetooth" -> "ON ᛒ"; else -> "ON" }) to R.drawable.pill_on
                p.on == false -> (when (p.via) { "cloud" -> "OFF ☁"; "bluetooth" -> "OFF ᛒ"; else -> "OFF" }) to R.drawable.pill_off
                else -> "?" to R.drawable.pill_busy
            }
            r.setTextViewText(R.id.state, label)
            r.setInt(R.id.state, "setBackgroundResource", bg)
            r.setInt(R.id.dot, "setBackgroundResource", if (p.on == true) R.drawable.dot_on else R.drawable.dot_off)
            r.setOnClickFillInIntent(R.id.row, Intent().putExtra(EXTRA_DID, p.did))
            return r
        }
    }
}
