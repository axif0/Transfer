package com.matanh.transfer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.matanh.transfer.R
import com.matanh.transfer.util.ServerRemoteControl

class ServerAppWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> {
                ServerRemoteControl.toggle(context)
                // Service updates prefs async; refresh shortly.
                android.os.Handler(context.mainLooper).postDelayed({
                    requestUpdate(context)
                }, 600)
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.matanh.transfer.widget.ACTION_TOGGLE"

        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ServerAppWidget::class.java))
            for (id in ids) {
                updateWidget(context, mgr, id)
            }
        }

        fun updateWidget(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val active = ServerRemoteControl.isServerActive(context)
            val views = RemoteViews(context.packageName, R.layout.widget_server_control)
            views.setTextViewText(
                R.id.tvWidgetStatus,
                context.getString(
                    if (active) R.string.widget_status_running else R.string.widget_status_stopped
                )
            )
            views.setTextViewText(
                R.id.btnWidgetToggle,
                context.getString(
                    if (active) R.string.stop_server else R.string.start_server
                )
            )
            val toggleIntent = Intent(context, ServerAppWidget::class.java).apply {
                action = ACTION_TOGGLE
            }
            val pending = PendingIntent.getBroadcast(
                context,
                0,
                toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetToggle, pending)
            mgr.updateAppWidget(widgetId, views)
        }
    }
}
