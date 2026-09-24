package com.example.claudewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews

class ClaudeWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "ClaudeWidget"
        const val ACTION_REFRESH = "com.example.claudewidget.ACTION_REFRESH"

        fun updateAllWidgets(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ClaudeWidgetProvider::class.java))
            for (id in ids) {
                updateAppWidget(context, mgr, id)
            }
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            // Choose layout based on widget height
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val layoutId = if (minWidth >= 180 || minHeight < 80) R.layout.widget_layout_wide else R.layout.widget_layout

            val views = RemoteViews(context.packageName, layoutId)
            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)

            // ---- Refresh button ----
            val refreshIntent = Intent(context, ClaudeWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, 0, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_refresh, refreshPending)

            // ---- Tap widget body ----
            val isError = prefs.getString("session_pct", "--") == "Error"
            val tapAction = prefs.getString("tap_action", "refresh")
            
            if (isError || tapAction == "open_app") {
                // Open app to Claude tab
                val openAppPending = PendingIntent.getActivity(
                    context, 1, MainActivity.openTabIntent(context, "claude"),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openAppPending)
            } else {
                // Refresh on body tap
                val refreshIntent2 = Intent(context, ClaudeWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                }
                val refreshPending2 = PendingIntent.getBroadcast(
                    context, 2, refreshIntent2,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, refreshPending2)
            }

            // ---- Bind data ----
            val showLeft = UsageDisplay.mode(prefs, "claude") == "left"

            UsageDisplay.bind(views, R.id.tv_session_pct, R.id.pb_session,
                prefs.getString("session_pct", "--"), prefs.getInt("session_prog", 0), showLeft)
            views.setTextViewText(R.id.tv_session_reset, prefs.getString("session_reset", "Tap refresh"))

            UsageDisplay.bind(views, R.id.tv_weekly_pct, R.id.pb_weekly,
                prefs.getString("weekly_pct", "--"), prefs.getInt("weekly_prog", 0), showLeft)
            views.setTextViewText(R.id.tv_weekly_reset, prefs.getString("weekly_reset", "Tap refresh"))

            views.setTextViewText(R.id.tv_last_update, prefs.getString("last_update", "Not yet refreshed"))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Show a "Refreshing..." state (or another [status], like "Waiting for network...") on the widget immediately */
        fun showRefreshingState(context: Context, status: String = "Refreshing...") {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ClaudeWidgetProvider::class.java))
            for (id in ids) {
                val options = mgr.getAppWidgetOptions(id)
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                val layoutId = if (minWidth >= 180 || minHeight < 80) R.layout.widget_layout_wide else R.layout.widget_layout

                val views = RemoteViews(context.packageName, layoutId)

                // Keep click handlers
                val refreshIntent = Intent(context, ClaudeWidgetProvider::class.java).apply {
                    action = ACTION_REFRESH
                }
                views.setOnClickPendingIntent(R.id.btn_refresh, PendingIntent.getBroadcast(
                    context, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                ))
                val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
                val isError = prefs.getString("session_pct", "--") == "Error"
                val tapAction = prefs.getString("tap_action", "refresh")

                if (isError || tapAction == "open_app") {
                    views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                        context, 1, MainActivity.openTabIntent(context, "claude"),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ))
                } else {
                    val refreshIntent2 = Intent(context, ClaudeWidgetProvider::class.java).apply {
                        action = ACTION_REFRESH
                    }
                    views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(
                        context, 2, refreshIntent2,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    ))
                }

                views.setTextViewText(R.id.tv_session_reset, status)
                views.setTextViewText(R.id.tv_weekly_reset, status)
                views.setTextViewText(R.id.tv_last_update, status)
                mgr.updateAppWidget(id, views)
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        // Re-render with the correct layout when the user resizes the widget
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            Log.d(TAG, "Refresh button tapped!")
            // The worker refreshes every service, so show the refreshing state on all widgets
            // When offline, the refresh waits for a connection instead of failing, so say that
            val status = if (UpdateWidgetWorker.hasUsableNetwork(context)) "Refreshing..." else "Waiting for network..."
            showRefreshingState(context, status)
            ChatGptWidgetProvider.showRefreshingState(context, status)
            UpdateWidgetWorker.runNow(context)
        }
    }
}
