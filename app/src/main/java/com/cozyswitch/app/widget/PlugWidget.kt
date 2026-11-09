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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Home-screen widget: one row per plug, tap a row to switch it. */
class PlugWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        draw(ctx)
        background { Plugs.refresh(ctx) }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                val did = intent.getStringExtra(EXTRA_DID) ?: return
                val plug = Plugs.load(ctx).firstOrNull { it.did == did } ?: return
                val want = plug.on != true
                busy += did
                draw(ctx) // show "…" right away
                background {
                    try { Plugs.switch(ctx, did, want) } finally { busy -= did; draw(ctx) }
                }
            }
            ACTION_REFRESH -> {
                refreshing = true
                draw(ctx)
                background {
                    try { Plugs.refresh(ctx) } finally { refreshing = false; draw(ctx) }
                }
            }
            else -> super.onReceive(ctx, intent)
        }
    }

    /** Runs network work after the tap; Android gives a receiver about 10 seconds. */
    private fun background(work: suspend () -> Unit) {
        val pending = goAsync()
        scope.launch {
            try { withTimeoutOrNull(9_000) { work() } } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.cozyswitch.app.TOGGLE"
        const val ACTION_REFRESH = "com.cozyswitch.app.REFRESH"
        const val EXTRA_DID = "did"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val busy = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        @Volatile private var refreshing = false

        fun updateAll(ctx: Context) = draw(ctx)

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
                p.on == true -> "ON" to R.drawable.pill_on
                p.on == false -> "OFF" to R.drawable.pill_off
                else -> "offline" to R.drawable.pill_busy
            }
            r.setTextViewText(R.id.state, label)
            r.setInt(R.id.state, "setBackgroundResource", bg)
            r.setInt(R.id.dot, "setBackgroundResource", if (p.on == true) R.drawable.dot_on else R.drawable.dot_off)
            r.setOnClickFillInIntent(R.id.row, Intent().putExtra(EXTRA_DID, p.did))
            return r
        }
    }
}
