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
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
            val layoutId = if (minHeight < 80) R.layout.widget_layout_wide else R.layout.widget_layout

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
                // Open app
                val openAppIntent = Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                val openAppPending = PendingIntent.getActivity(
                    context, 1, openAppIntent,
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
            views.setTextViewText(R.id.tv_session_pct, prefs.getString("session_pct", "--"))
            views.setTextViewText(R.id.tv_session_reset, prefs.getString("session_reset", "Tap refresh"))
            views.setProgressBar(R.id.pb_session, 100, prefs.getInt("session_prog", 0), false)

            views.setTextViewText(R.id.tv_weekly_pct, prefs.getString("weekly_pct", "--"))
            views.setTextViewText(R.id.tv_weekly_reset, prefs.getString("weekly_reset", "Tap refresh"))
            views.setProgressBar(R.id.pb_weekly, 100, prefs.getInt("weekly_prog", 0), false)

            views.setTextViewText(R.id.tv_last_update, prefs.getString("last_update", "Not yet refreshed"))

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** Show a "Refreshing..." state on the widget immediately */
        fun showRefreshingState(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ClaudeWidgetProvider::class.java))
            for (id in ids) {
                val options = mgr.getAppWidgetOptions(id)
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                val layoutId = if (minHeight < 80) R.layout.widget_layout_wide else R.layout.widget_layout

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
                    val openAppIntent = Intent(context, MainActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                        context, 1, openAppIntent,
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

                views.setTextViewText(R.id.tv_session_reset, "Refreshing...")
                views.setTextViewText(R.id.tv_weekly_reset, "Refreshing...")
                views.setTextViewText(R.id.tv_last_update, "Refreshing...")
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
            showRefreshingState(context)
            UpdateWidgetWorker.runNow(context)
        }
    }
}
