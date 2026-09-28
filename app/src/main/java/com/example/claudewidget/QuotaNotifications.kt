package com.example.claudewidget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Optional persistent quota notifications, one per service.
 *
 * Android 14+ allows users to swipe away many notifications marked ongoing. A delete intent is
 * attached so an enabled quota notification is posted again if that happens. The setting in the
 * app is the authoritative way to turn a service's notification off.
 */
object QuotaNotifications {
    const val ACTION_REFRESH = "com.example.claudewidget.ACTION_NOTIFICATION_REFRESH"
    const val ACTION_REPOST = "com.example.claudewidget.ACTION_NOTIFICATION_REPOST"
    const val EXTRA_SERVICE = "service"

    private const val CHANNEL_ID = "quota_status"
    private const val CLAUDE_NOTIFICATION_ID = 4101
    private const val CHATGPT_NOTIFICATION_ID = 4102

    fun prefKey(service: String) = if (service == "chatgpt") {
        "chatgpt_notification_enabled"
    } else {
        "claude_notification_enabled"
    }

    fun isEnabled(context: Context, service: String): Boolean =
        prefs(context).getBoolean(prefKey(service), false)

    fun setEnabled(context: Context, service: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(prefKey(service), enabled).apply()
        if (enabled) updateService(context, service) else cancel(context, service)
    }

    /** Re-post enabled notifications after app start/update, if notification permission is available. */
    fun restoreEnabled(context: Context) {
        updateService(context, "claude")
        updateService(context, "chatgpt")
    }

    fun cancel(context: Context, service: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(service))
    }

    /**
     * Updates a service's notification from the same saved quota values used by its widget.
     * If the option is off, permission is missing, or the service is logged out, no notification
     * is posted.
     */
    fun updateService(context: Context, service: String) {
        if (!isEnabled(context, service)) return
        if (!canPostNotifications(context)) return

        val prefs = prefs(context)
        if (!isLoggedIn(prefs, service)) {
            cancel(context, service)
            return
        }

        ensureChannel(context)

        val prefix = if (service == "chatgpt") "chatgpt_" else ""
        val showLeft = UsageDisplay.mode(prefs, service) == "left"
        val sessionUsed = prefs.getInt("${prefix}session_prog", 0)
        val weeklyUsed = prefs.getInt("${prefix}weekly_prog", 0)
        val sessionText = UsageDisplay.text(
            prefs.getString("${prefix}session_pct", "--"),
            sessionUsed,
            showLeft
        )
        val weeklyText = UsageDisplay.text(
            prefs.getString("${prefix}weekly_pct", "--"),
            weeklyUsed,
            showLeft
        )
        val sessionReset = prefs.getString("${prefix}session_reset", "Tap refresh") ?: "Tap refresh"
        val weeklyReset = prefs.getString("${prefix}weekly_reset", "Tap refresh") ?: "Tap refresh"
        val refreshedAt = prefs.getString("${prefix}updated_at", null) ?: "Never"
        val name = if (service == "chatgpt") "ChatGPT" else "Claude"

        val compact = "Session $sessionText · Weekly $weeklyText"
        val expanded = buildString {
            append("Session: $sessionText — $sessionReset")
            append('\n')
            append("Weekly: $weeklyText — $weeklyReset")
        }

        NotificationManagerCompat.from(context).notify(
            notificationId(service),
            baseBuilder(context, service)
                .setContentTitle("$name Quota - Refreshed: $refreshedAt")
                .setContentText(compact)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
                .build()
        )
    }

    /** Keeps the old readings visible while clearly showing that a manual refresh is running. */
    fun showRefreshing(context: Context, service: String) {
        if (!isEnabled(context, service) || !canPostNotifications(context)) return
        val prefs = prefs(context)
        if (!isLoggedIn(prefs, service)) return

        ensureChannel(context)
        val name = if (service == "chatgpt") "ChatGPT" else "Claude"
        val prefix = if (service == "chatgpt") "chatgpt_" else ""
        val refreshedAt = prefs.getString("${prefix}updated_at", null) ?: "Never"
        NotificationManagerCompat.from(context).notify(
            notificationId(service),
            baseBuilder(context, service)
                .setContentTitle("$name Quota - Refreshed: $refreshedAt")
                .setContentText("Refreshing quota…")
                .build()
        )
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun baseBuilder(context: Context, service: String): NotificationCompat.Builder {
        val openPending = PendingIntent.getActivity(
            context,
            if (service == "chatgpt") 4402 else 4401,
            MainActivity.openTabIntent(context, service),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val refreshIntent = Intent(context, QuotaNotificationReceiver::class.java).apply {
            action = ACTION_REFRESH
            putExtra(EXTRA_SERVICE, service)
        }
        val refreshPending = PendingIntent.getBroadcast(
            context,
            if (service == "chatgpt") 4202 else 4201,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repostIntent = Intent(context, QuotaNotificationReceiver::class.java).apply {
            action = ACTION_REPOST
            putExtra(EXTRA_SERVICE, service)
        }
        val repostPending = PendingIntent.getBroadcast(
            context,
            if (service == "chatgpt") 4302 else 4301,
            repostIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(if (service == "chatgpt") R.drawable.ic_chatgpt else R.drawable.ic_claude)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openPending)
            .setDeleteIntent(repostPending)
            .addAction(R.drawable.ic_refresh, "Refresh", refreshPending)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quota status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent Claude and ChatGPT quota status"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationId(service: String) =
        if (service == "chatgpt") CHATGPT_NOTIFICATION_ID else CLAUDE_NOTIFICATION_ID

    private fun prefs(context: Context) =
        context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)

    private fun isLoggedIn(prefs: android.content.SharedPreferences, service: String): Boolean {
        return if (service == "chatgpt") {
            !prefs.getString("chatgpt_access_token", null).isNullOrEmpty() ||
                !prefs.getString("chatgpt_saved_cookies", null).isNullOrEmpty()
        } else {
            !prefs.getString("saved_cookies", null).isNullOrEmpty()
        }
    }
}
