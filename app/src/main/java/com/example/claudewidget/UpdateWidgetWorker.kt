package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
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
        private const val TAG = "UpdateWidgetWorker"

        /** Run once immediately */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWidgetWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun runNowClaude(context: Context) {
            runNow(context)
        }

        fun runNowChatGpt(context: Context) {
            runNow(context)
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

    private fun formatEpochResetTime(resetAt: Long, fallbackSeconds: Long): String {
        val nowSec = System.currentTimeMillis() / 1000
        val remaining = if (resetAt > nowSec) {
            resetAt - nowSec
        } else if (fallbackSeconds > 0) {
            fallbackSeconds
        } else {
            0L
        }

        if (remaining <= 0) return "Resets now"

        val days = remaining / 86400
        val hours = (remaining % 86400) / 3600
        val mins = (remaining % 3600) / 60

        return when {
            days > 0 -> "Resets in ${days}d ${hours}h"
            hours > 0 -> "Resets in ${hours}h ${mins}m"
            else -> "Resets in ${mins}m"
        }
    }

    private fun setClaudeErrorState(message: String) {
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

    private fun setChatGptErrorState(message: String) {
        val prefs = applicationContext.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("chatgpt_session_pct", "Error")
            .putString("chatgpt_session_reset", message)
            .putInt("chatgpt_session_prog", 0)
            .putString("chatgpt_weekly_pct", "Error")
            .putString("chatgpt_weekly_reset", message)
            .putInt("chatgpt_weekly_prog", 0)
            .apply()
        ChatGptWidgetProvider.updateAllWidgets(applicationContext)
    }

    private fun nowTimestamp(): String {
        return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    }

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        val defaultUa = prefs.getString("user_agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36")!!

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        // 1. Update Claude if configured
        val claudeCookies = prefs.getString("saved_cookies", null)
        if (!claudeCookies.isNullOrEmpty()) {
            updateClaude(client, prefs, defaultUa, claudeCookies)
        }

        // 2. Update ChatGPT if configured
        val chatGptToken = prefs.getString("chatgpt_access_token", null)
        val chatGptCookies = prefs.getString("chatgpt_saved_cookies", null)
        if (!chatGptToken.isNullOrEmpty() || !chatGptCookies.isNullOrEmpty()) {
            updateChatGpt(client, prefs, defaultUa, chatGptToken, chatGptCookies)
        }

        return Result.success()
    }

    private fun updateClaude(
        client: OkHttpClient,
        prefs: SharedPreferences,
        defaultUa: String,
        cookies: String
    ): Boolean {
        val ua = prefs.getString("user_agent", defaultUa) ?: defaultUa

        try {
            Log.d(TAG, "Fetching Claude organizations...")
            val orgRequest = Request.Builder()
                .url("https://claude.ai/api/organizations")
                .header("Cookie", cookies)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Referer", "https://claude.ai/")
                .build()

            val orgResponse = client.newCall(orgRequest).execute()
            if (orgResponse.code == 401 || orgResponse.code == 403) {
                setClaudeErrorState("Session expired — tap to log in")
                return false
            }

            val orgBody = orgResponse.body?.string()
            if (!orgResponse.isSuccessful || orgBody.isNullOrEmpty()) {
                setClaudeErrorState("Server error — tap refresh")
                return false
            }

            val orgArray = JSONArray(orgBody)
            if (orgArray.length() == 0) {
                setClaudeErrorState("No org found")
                return false
            }
            val orgId = orgArray.getJSONObject(0).getString("uuid")

            Log.d(TAG, "Fetching Claude usage for org $orgId...")
            val usageRequest = Request.Builder()
                .url("https://claude.ai/api/organizations/$orgId/usage")
                .header("Cookie", cookies)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Referer", "https://claude.ai/")
                .build()

            val usageResponse = client.newCall(usageRequest).execute()
            if (usageResponse.code == 401 || usageResponse.code == 403) {
                setClaudeErrorState("Session expired — tap to log in")
                return false
            }

            val usageBody = usageResponse.body?.string()
            if (!usageResponse.isSuccessful || usageBody.isNullOrEmpty()) {
                setClaudeErrorState("Server error — tap refresh")
                return false
            }

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
            Log.d(TAG, "Claude Widget updated successfully!")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Claude worker exception", e)
            setClaudeErrorState("Network error — tap refresh")
            return false
        }
    }

    private fun updateChatGpt(
        client: OkHttpClient,
        prefs: SharedPreferences,
        defaultUa: String,
        token: String?,
        cookies: String?
    ): Boolean {
        var currentToken = token
        val chatGptUa = prefs.getString("chatgpt_user_agent", defaultUa) ?: defaultUa

        // If no token or we have cookies, try fetching/refreshing token
        if (currentToken.isNullOrEmpty() && !cookies.isNullOrEmpty()) {
            currentToken = refreshChatGptToken(client, cookies, chatGptUa)
            if (!currentToken.isNullOrEmpty()) {
                prefs.edit().putString("chatgpt_access_token", currentToken).apply()
            }
        }

        if (currentToken.isNullOrEmpty()) {
            setChatGptErrorState("Tap widget to log in")
            return false
        }

        try {
            Log.d(TAG, "Fetching ChatGPT usage...")
            var usageRequest = buildChatGptRequest(currentToken, chatGptUa, cookies)
            var usageResponse = client.newCall(usageRequest).execute()

            // If 401 and we have cookies, attempt token refresh once
            if (usageResponse.code == 401 && !cookies.isNullOrEmpty()) {
                val refreshedToken = refreshChatGptToken(client, cookies, chatGptUa)
                if (!refreshedToken.isNullOrEmpty()) {
                    currentToken = refreshedToken
                    prefs.edit().putString("chatgpt_access_token", refreshedToken).apply()
                    usageRequest = buildChatGptRequest(currentToken, chatGptUa, cookies)
                    usageResponse = client.newCall(usageRequest).execute()
                }
            }

            if (usageResponse.code == 401 || usageResponse.code == 403) {
                setChatGptErrorState("Session expired — tap to log in")
                return false
            }

            val body = usageResponse.body?.string()
            if (!usageResponse.isSuccessful || body.isNullOrEmpty()) {
                setChatGptErrorState("Server error — tap refresh")
                return false
            }

            val json = JSONObject(body)
            val rateLimit = json.optJSONObject("rate_limit")

            var sessionPct = "0% used"
            var sessionProg = 0
            var sessionReset = "No limit"
            var weeklyPct = "0% used"
            var weeklyProg = 0
            var weeklyReset = "No limit"

            if (rateLimit != null) {
                val primary = rateLimit.optJSONObject("primary_window")
                if (primary != null) {
                    sessionProg = primary.optInt("used_percent", 0)
                    sessionPct = "$sessionProg% used"
                    val resetAt = primary.optLong("reset_at", 0L)
                    val resetSecs = primary.optLong("reset_after_seconds", 0L)
                    sessionReset = formatEpochResetTime(resetAt, resetSecs)
                }

                val secondary = rateLimit.optJSONObject("secondary_window")
                if (secondary != null) {
                    weeklyProg = secondary.optInt("used_percent", 0)
                    weeklyPct = "$weeklyProg% used"
                    val resetAt = secondary.optLong("reset_at", 0L)
                    val resetSecs = secondary.optLong("reset_after_seconds", 0L)
                    weeklyReset = formatEpochResetTime(resetAt, resetSecs)
                }
            }

            val timestamp = "Updated ${nowTimestamp()}"
            prefs.edit()
                .putString("chatgpt_session_pct", sessionPct)
                .putString("chatgpt_session_reset", sessionReset)
                .putInt("chatgpt_session_prog", sessionProg)
                .putString("chatgpt_weekly_pct", weeklyPct)
                .putString("chatgpt_weekly_reset", weeklyReset)
                .putInt("chatgpt_weekly_prog", weeklyProg)
                .putString("chatgpt_last_update", timestamp)
                .apply()

            ChatGptWidgetProvider.updateAllWidgets(applicationContext)
            Log.d(TAG, "ChatGPT Widget updated successfully: session=$sessionPct, weekly=$weeklyPct")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "ChatGPT worker exception", e)
            setChatGptErrorState("Network error — tap refresh")
            return false
        }
    }

    private fun buildChatGptRequest(token: String, ua: String, cookies: String?): Request {
        val builder = Request.Builder()
            .url("https://chatgpt.com/backend-api/wham/usage")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .header("Referer", "https://chatgpt.com/")
        if (!cookies.isNullOrEmpty()) {
            builder.header("Cookie", cookies)
        }
        return builder.build()
    }

    private fun refreshChatGptToken(client: OkHttpClient, cookies: String, ua: String): String? {
        try {
            val sessionReq = Request.Builder()
                .url("https://chatgpt.com/api/auth/session")
                .header("Cookie", cookies)
                .header("User-Agent", ua)
                .header("Accept", "application/json")
                .header("Referer", "https://chatgpt.com/")
                .build()
            val resp = client.newCall(sessionReq).execute()
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrEmpty()) {
                val json = JSONObject(body)
                val token = json.optString("accessToken", "")
                if (token.isNotEmpty()) {
                    return token
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh ChatGPT token", e)
        }
        return null
    }
}
