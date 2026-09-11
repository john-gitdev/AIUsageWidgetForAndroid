package com.example.claudewidget

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateWidgetWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ClaudeWidget"

        /** Run once immediately */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWidgetWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /** Schedule periodic updates AND run once now */
        fun enqueueWork(context: Context) {
            runNow(context)

            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            val intervalMin = prefs.getLong("refresh_interval_minutes", 15L)

            if (intervalMin > 0L) {
                val periodic = PeriodicWorkRequestBuilder<UpdateWidgetWorker>(intervalMin, TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "ClaudeWidgetUpdate",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodic
                )
            }
        }

        /** Re-schedule periodic work with a new interval */
        fun rescheduleWork(context: Context, intervalMinutes: Long) {
            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            prefs.edit().putLong("refresh_interval_minutes", intervalMinutes).apply()

            if (intervalMinutes <= 0L) {
                // "Never" — cancel any existing periodic work
                WorkManager.getInstance(context).cancelUniqueWork("ClaudeWidgetUpdate")
                Log.d(TAG, "Cancelled periodic work (manual only)")
            } else {
                val periodic = PeriodicWorkRequestBuilder<UpdateWidgetWorker>(intervalMinutes, TimeUnit.MINUTES).build()
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "ClaudeWidgetUpdate",
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    periodic
                )
                Log.d(TAG, "Rescheduled periodic work to every ${intervalMinutes}m")
            }
        }
    }

    private fun formatResetTime(isoString: String): String {
        if (isoString.isEmpty() || isoString == "null") return "Unknown"
        try {
            val resetTime = ZonedDateTime.parse(isoString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val now = ZonedDateTime.now(java.time.ZoneOffset.UTC)
            var duration = Duration.between(now, resetTime)

            if (duration.isNegative) return "Resets now"

            val days = duration.toDays()
            duration = duration.minusDays(days)
            val hours = duration.toHours()
            duration = duration.minusHours(hours)
            val minutes = duration.toMinutes()

            return if (days > 0) "Resets in ${days}d ${hours}h"
            else "Resets in ${hours}h ${minutes}m"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse reset time: $isoString", e)
            return "Unknown"
        }
    }

    private fun setErrorState(message: String) {
        val prefs = applicationContext.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("session_pct", "Error")
            .putString("session_reset", message)
            .putInt("session_prog", 0)
            .putString("weekly_pct", "Error")
            .putString("weekly_reset", message)
            .putInt("weekly_prog", 0)
            .apply()
        ClaudeWidgetProvider.updateAllWidgets(applicationContext)
    }

    private fun nowTimestamp(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        val cookies = prefs.getString("saved_cookies", null)

        if (cookies.isNullOrEmpty()) {
            Log.w(TAG, "No cookies saved.")
            setErrorState("Tap widget to log in")
            return Result.failure()
        }

        val ua = prefs.getString("user_agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36")!!

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            // ---------- Step 1: Get org UUID ----------
            Log.d(TAG, "Fetching organizations...")
            val orgRequest = Request.Builder()
                .url("https://claude.ai/api/organizations")
                .header("Cookie", cookies)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Referer", "https://claude.ai/")
                .build()

            val orgResponse = client.newCall(orgRequest).execute()
            Log.d(TAG, "Org response: ${orgResponse.code}")

            if (orgResponse.code == 401 || orgResponse.code == 403) {
                setErrorState("Session expired — tap to log in")
                return Result.failure()
            }

            val orgBody = orgResponse.body?.string()
            if (!orgResponse.isSuccessful || orgBody.isNullOrEmpty()) {
                setErrorState("Server error — tap refresh")
                return Result.retry()
            }

            val orgArray = JSONArray(orgBody)
            if (orgArray.length() == 0) {
                setErrorState("No org found")
                return Result.failure()
            }
            val orgId = orgArray.getJSONObject(0).getString("uuid")

            // ---------- Step 2: Fetch usage ----------
            Log.d(TAG, "Fetching usage for org $orgId...")
            val usageRequest = Request.Builder()
                .url("https://claude.ai/api/organizations/$orgId/usage")
                .header("Cookie", cookies)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Referer", "https://claude.ai/")
                .build()

            val usageResponse = client.newCall(usageRequest).execute()
            Log.d(TAG, "Usage response: ${usageResponse.code}")

            if (usageResponse.code == 401 || usageResponse.code == 403) {
                setErrorState("Session expired — tap to log in")
                return Result.failure()
            }

            val usageBody = usageResponse.body?.string()
            if (!usageResponse.isSuccessful || usageBody.isNullOrEmpty()) {
                setErrorState("Server error — tap refresh")
                return Result.retry()
            }

            // ---------- Step 3: Parse ----------
            val json = JSONObject(usageBody)
            val limits = json.optJSONArray("limits")

            var sessionPct = "0% used"
            var sessionProg = 0
            var sessionReset = "No limit"
            var weeklyPct = "0% used"
            var weeklyProg = 0
            var weeklyReset = "No limit"

            if (limits != null) {
                for (i in 0 until limits.length()) {
                    val limit = limits.getJSONObject(i)
                    val group = limit.optString("group", "")
                    val percent = limit.optInt("percent", 0)
                    val resetsAt = limit.optString("resets_at", "")

                    if (group == "session") {
                        sessionProg = percent
                        sessionPct = "$percent% used"
                        sessionReset = formatResetTime(resetsAt)
                    } else if (group == "weekly") {
                        weeklyProg = percent
                        weeklyPct = "$percent% used"
                        weeklyReset = formatResetTime(resetsAt)
                    }
                }
            }

            val timestamp = "Updated ${nowTimestamp()}"
            Log.d(TAG, "Saving: session=$sessionPct, weekly=$weeklyPct, timestamp=$timestamp")

            prefs.edit()
                .putString("session_pct", sessionPct)
                .putString("session_reset", sessionReset)
                .putInt("session_prog", sessionProg)
                .putString("weekly_pct", weeklyPct)
                .putString("weekly_reset", weeklyReset)
                .putInt("weekly_prog", weeklyProg)
                .putString("last_update", timestamp)
                .apply()

            ClaudeWidgetProvider.updateAllWidgets(applicationContext)
            Log.d(TAG, "Widget updated successfully!")
            return Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Worker exception", e)
            setErrorState("Network error — tap refresh")
            return Result.retry()
        }
    }
}
