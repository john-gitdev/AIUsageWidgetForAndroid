package com.example.claudewidget

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Registers the screen-on listener for as long as Android keeps this app process alive.
 * ACTION_SCREEN_ON cannot be delivered to a manifest-declared receiver, so this has to
 * be registered dynamically. Android may still kill an idle process, so this feature is
 * intentionally best-effort rather than a foreground service.
 */
class AIUsageWidgetApplication : Application() {

    companion object {
        private const val SCREEN_ON_COOLDOWN_MS = 2 * 60 * 1000L
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return

            val prefs = getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("refresh_on_screen_on", false)) return

            val hasClaude = !prefs.getString("saved_cookies", null).isNullOrEmpty()
            val hasChatGpt = !prefs.getString("chatgpt_access_token", null).isNullOrEmpty() ||
                !prefs.getString("chatgpt_saved_cookies", null).isNullOrEmpty()
            if (!hasClaude && !hasChatGpt) return

            val now = System.currentTimeMillis()
            val lastSuccessfulRefresh = prefs.getLong("last_successful_refresh_epoch_ms", 0L)
            val lastScreenOnRefresh = prefs.getLong("last_screen_on_refresh_epoch_ms", 0L)
            val mostRecent = maxOf(lastSuccessfulRefresh, lastScreenOnRefresh)
            if (mostRecent > 0L && now - mostRecent < SCREEN_ON_COOLDOWN_MS) {
                return
            }

            // Save before enqueueing so repeated screen events cannot stack refreshes.
            prefs.edit().putLong("last_screen_on_refresh_epoch_ms", now).apply()
            AppLog.i(this@AIUsageWidgetApplication, "Settings", "Screen turned on; refreshing quota")
            UpdateWidgetWorker.runNow(this@AIUsageWidgetApplication)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // ACTION_SCREEN_ON is a protected system-only broadcast. Android 14's dynamic-receiver
        // export-flag requirement explicitly exempts receivers that listen only for system broadcasts.
        @Suppress("DEPRECATION")
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }
}
