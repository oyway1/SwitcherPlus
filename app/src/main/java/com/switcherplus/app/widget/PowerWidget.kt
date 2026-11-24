package com.switcherplus.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.switcherplus.app.R
import com.switcherplus.app.data.Device
import com.switcherplus.app.data.Switchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home-screen widget: one tap turns the plug on (with the auto-off limit) or off. */
class PowerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        Switchers.init(context)
        appWidgetIds.forEach { render(context, manager, it) }
        // Periodic refresh: fetch the real state of every device shown on a widget.
        val ids = appWidgetIds.mapNotNull { Switchers.device(deviceFor(context, it))?.config?.id }.distinct()
        if (ids.isEmpty()) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ids.forEach { Switchers.refresh(it) }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            Switchers.init(context)
            val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val deviceId = Switchers.device(deviceFor(context, widgetId))?.config?.id ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Switchers.toggleNow(deviceId)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val edit = prefs(context).edit()
        appWidgetIds.forEach { edit.remove(key(it)) }
        edit.apply()
    }

    companion object {
        private const val ACTION_TOGGLE = "com.switcherplus.app.widget.TOGGLE"
        private const val PREFS = "widgets"
        private const val COLOR_ON = 0xFFFFA000.toInt()
        private const val COLOR_OFF_TEXT = 0xFF37474F.toInt()

        private var lastSignature: String? = null

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private fun key(widgetId: Int) = "w$widgetId"

        fun deviceFor(ctx: Context, widgetId: Int): String? = prefs(ctx).getString(key(widgetId), null)

        fun assign(ctx: Context, widgetId: Int, deviceId: String) {
            prefs(ctx).edit().putString(key(widgetId), deviceId).apply()
            render(ctx, AppWidgetManager.getInstance(ctx), widgetId)
        }

        private fun widgetIds(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, PowerWidget::class.java))

        /** Called by the engine whenever device state changes; redraws only on visible changes. */
        @Synchronized
        fun onDevicesChanged(ctx: Context, devices: List<Device>) {
            val sig = devices.joinToString("|") { d ->
                val s = d.status
                val endsAt = if (s.on && s.timeLeftSec > 0) (s.updatedAtMs / 1000 + s.timeLeftSec) / 10 else 0
                "${d.config.id},${d.config.name},${s.on},${s.powerWatts / 5},${s.reachable},${s.busy},$endsAt"
            }
            if (sig == lastSignature) return
            lastSignature = sig
            val ids = widgetIds(ctx)
            if (ids.isEmpty()) return
            val manager = AppWidgetManager.getInstance(ctx)
            ids.forEach { render(ctx, manager, it) }
        }

        fun render(ctx: Context, manager: AppWidgetManager, widgetId: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_power)
            val device = Switchers.device(deviceFor(ctx, widgetId))

            val toggle = PendingIntent.getBroadcast(
                ctx, widgetId,
                Intent(ctx, PowerWidget::class.java)
                    .setAction(ACTION_TOGGLE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, toggle)

            if (device == null) {
                views.setTextViewText(R.id.widget_name, ctx.getString(R.string.app_name))
                views.setTextViewText(R.id.widget_status, ctx.getString(R.string.widget_no_device))
                views.setViewVisibility(R.id.widget_timer, View.GONE)
                applyColors(views, false)
                // Without a device, tapping opens the app so it can discover the plug.
                val open = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, com.switcherplus.app.ui.MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_root, open)
                manager.updateAppWidget(widgetId, views)
                return
            }

            val s = device.status
            views.setTextViewText(R.id.widget_name, device.config.name)
            val status = when {
                s.busy -> ctx.getString(R.string.widget_working)
                s.updatedAtMs == 0L && !s.reachable && s.error == null -> ctx.getString(R.string.widget_tap)
                !s.reachable -> ctx.getString(R.string.widget_unreachable)
                s.on -> ctx.getString(R.string.widget_on, s.powerWatts)
                else -> ctx.getString(R.string.widget_off)
            }
            views.setTextViewText(R.id.widget_status, status)
            applyColors(views, s.on)

            val leftMs = s.timeLeftSec * 1000 - (System.currentTimeMillis() - s.updatedAtMs)
            if (s.on && s.timeLeftSec > 0 && leftMs > 0) {
                views.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() + leftMs, null, true)
                views.setChronometerCountDown(R.id.widget_timer, true)
                views.setViewVisibility(R.id.widget_timer, View.VISIBLE)
            } else {
                views.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime(), null, false)
                views.setViewVisibility(R.id.widget_timer, View.GONE)
            }
            manager.updateAppWidget(widgetId, views)
        }

        private fun applyColors(views: RemoteViews, on: Boolean) {
            views.setInt(
                R.id.widget_root, "setBackgroundResource",
                if (on) R.drawable.widget_bg_on else R.drawable.widget_bg_off,
            )
            val text = if (on) Color.WHITE else COLOR_OFF_TEXT
            views.setTextColor(R.id.widget_name, text)
            views.setTextColor(R.id.widget_status, text)
            views.setTextColor(R.id.widget_timer, text)
            views.setInt(R.id.widget_icon, "setColorFilter", if (on) Color.WHITE else COLOR_ON)
        }
    }
}
