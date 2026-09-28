package com.example.claudewidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Handles taps/actions and restores an enabled quota notification if the user swipes it away. */
class QuotaNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = intent.getStringExtra(QuotaNotifications.EXTRA_SERVICE)
            ?.takeIf { it == "claude" || it == "chatgpt" }
            ?: return

        when (intent.action) {
            QuotaNotifications.ACTION_REFRESH -> {
                QuotaNotifications.showRefreshing(context, service)
                // Manual work intentionally runs immediately rather than waiting on WorkManager's
                // network constraint, which can lag behind a Wi-Fi -> cellular handoff.
                UpdateWidgetWorker.runNow(context)
            }
            QuotaNotifications.ACTION_REPOST -> {
                // Android 14+ can allow ongoing notifications to be swiped away. Give System UI a
                // moment to finish the dismissal, then put it back if the option is still enabled.
                val result = goAsync()
                val appContext = context.applicationContext
                Handler(Looper.getMainLooper()).postDelayed({
                    QuotaNotifications.updateService(appContext, service)
                    result.finish()
                }, 500L)
            }
        }
    }
}
