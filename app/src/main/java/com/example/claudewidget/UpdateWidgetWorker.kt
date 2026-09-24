package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
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

        /** Unique name for tap-to-refresh runs, so repeated taps don't queue up behind each other. */
        private const val REFRESH_NOW_WORK = "ClaudeWidgetRefreshNow"

        /**
         * Tries per run, counting the first. A failure to reach the server (usually a Wi-Fi to
         * mobile handover) is retried with backoff; after the last try the widget keeps its
         * previous readings and waits for the next refresh.
         */
        private const val MAX_ATTEMPTS = 4
        private const val BACKOFF_SECONDS = 15L

        /**
         * Only run while the system reports a working connection that the app is allowed to use.
         * Without this, runs fired mid-handover or while the app's network was blocked in the
         * background, and every request failed with UnknownHostException.
         */
        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun periodicRequest(intervalMinutes: Long) =
            PeriodicWorkRequestBuilder<UpdateWidgetWorker>(intervalMinutes, TimeUnit.MINUTES)
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

        /** Run once now, or as soon as there is a connection. Replaces a run still waiting on one. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWidgetWorker>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_NOW_WORK, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Whether the device has a validated connection this app can use right now (the same test
         * [networkConstraints] applies). Used to tell a tap "Waiting for network" from "Refreshing".
         * getActiveNetwork() returns null when the app's access to the default network is blocked.
         */
        fun hasUsableNetwork(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
            schedulePeriodic(context)
        }

        /**
         * Registers the periodic refresh at the saved interval, without running one now. UPDATE
         * keeps an existing schedule's timing and only swaps in the current request, which is how
         * installs from before the network constraint pick it up when the app is opened.
         */
        fun schedulePeriodic(context: Context) {
            val prefs = context.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
            val intervalMin = prefs.getLong("refresh_interval_minutes", 15L)

            if (intervalMin > 0L) {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "ClaudeWidgetUpdate",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    periodicRequest(intervalMin)
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
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    "ClaudeWidgetUpdate",
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    periodicRequest(intervalMinutes)
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

    /** Shows [message] on the Claude widget and records [detail] in the in-app log. */
    private fun setClaudeErrorState(message: String, detail: String, error: Throwable? = null) {
        AppLog.e(applicationContext, "Claude", detail, error)
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

    /** Shows [message] on the ChatGPT widget and records [detail] in the in-app log. */
    private fun setChatGptErrorState(message: String, detail: String, error: Throwable? = null) {
        AppLog.e(applicationContext, "ChatGPT", detail, error)
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

    /** How one service's refresh went. OFFLINE (the server couldn't be reached) is worth retrying. */
    private enum class Outcome { UPDATED, FAILED, OFFLINE }

    /** Whether a failure now will be retried, so it's logged as a warning and no "Error" is shown yet. */
    private val willRetry get() = runAttemptCount < MAX_ATTEMPTS - 1

    /**
     * The server couldn't be reached (no connection, DNS failure, timeout). That's usually a
     * network handover that clears up by itself, so the last good readings stay on the widget
     * with "Offline" and the time they're from, instead of being replaced with "Error".
     * [name] is "Claude" or "ChatGPT"; [prefix] is the service's pref key prefix.
     */
    private fun setOfflineState(name: String, prefix: String, error: IOException): Outcome {
        val prefs = applicationContext.getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        val hasReading = prefs.getString("${prefix}session_pct", null)?.endsWith("% used") == true

        if (!willRetry && !hasReading) {
            // Out of retries and nothing worth keeping on the widget, so show the error
            if (prefix.isEmpty()) setClaudeErrorState("Offline — tap refresh", "Couldn't reach the server", error)
            else setChatGptErrorState("Offline — tap refresh", "Couldn't reach the server", error)
            return Outcome.OFFLINE
        }

        if (willRetry) AppLog.w(applicationContext, name, "Couldn't reach the server, will retry", error)
        else AppLog.e(applicationContext, name, "Couldn't reach the server, keeping the last readings", error)

        if (hasReading) {
            val updatedAt = prefs.getString("${prefix}updated_at", null)
            prefs.edit()
                .putString("${prefix}last_update", if (updatedAt != null) "Offline · $updatedAt" else "Offline")
                .apply()
        }
        // Redraw either way, so a tap's "Refreshing..." gives way to the saved readings
        if (prefix.isEmpty()) ClaudeWidgetProvider.updateAllWidgets(applicationContext)
        else ChatGptWidgetProvider.updateAllWidgets(applicationContext)
        return Outcome.OFFLINE
    }

    /** "HTTP 403: <start of body>" for the log. Only used for failed calls, whose bodies are error messages. */
    private fun httpSummary(code: Int, body: String?): String {
        val snippet = body?.replace(Regex("\\s+"), " ")?.trim()?.take(160)
        return if (snippet.isNullOrEmpty()) "HTTP $code" else "HTTP $code: $snippet"
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
        var claudeOutcome = Outcome.UPDATED
        val claudeCookies = prefs.getString("saved_cookies", null)
        if (!claudeCookies.isNullOrEmpty()) {
            claudeOutcome = updateClaude(client, prefs, defaultUa, claudeCookies)
        } else {
            // Not logged in — redraw anyway so the widget doesn't stay stuck on "Refreshing..."
            ClaudeWidgetProvider.updateAllWidgets(applicationContext)
        }

        // 2. Update ChatGPT if configured
        var chatGptOutcome = Outcome.UPDATED
        val chatGptToken = prefs.getString("chatgpt_access_token", null)
        val chatGptCookies = prefs.getString("chatgpt_saved_cookies", null)
        if (!chatGptToken.isNullOrEmpty() || !chatGptCookies.isNullOrEmpty()) {
            chatGptOutcome = updateChatGpt(client, prefs, defaultUa, chatGptToken, chatGptCookies)
        } else {
            ChatGptWidgetProvider.updateAllWidgets(applicationContext)
        }

        // Retrying refreshes both services, which is harmless for the one that already worked
        val offline = claudeOutcome == Outcome.OFFLINE || chatGptOutcome == Outcome.OFFLINE
        return if (offline && willRetry) Result.retry() else Result.success()
    }

    private fun updateClaude(
        client: OkHttpClient,
        prefs: SharedPreferences,
        defaultUa: String,
        cookies: String
    ): Outcome {
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
            val orgBody = orgResponse.body?.string()
            if (orgResponse.code == 401 || orgResponse.code == 403) {
                setClaudeErrorState("Session expired — tap to log in",
                    "Organizations request rejected (${httpSummary(orgResponse.code, orgBody)})")
                return Outcome.FAILED
            }
            if (!orgResponse.isSuccessful || orgBody.isNullOrEmpty()) {
                setClaudeErrorState("Server error — tap refresh",
                    "Organizations request failed (${httpSummary(orgResponse.code, orgBody)})")
                return Outcome.FAILED
            }

            val orgArray = JSONArray(orgBody)
            if (orgArray.length() == 0) {
                setClaudeErrorState("No org found", "Organizations request returned no organizations")
                return Outcome.FAILED
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
            val usageBody = usageResponse.body?.string()
            if (usageResponse.code == 401 || usageResponse.code == 403) {
                setClaudeErrorState("Session expired — tap to log in",
                    "Usage request rejected (${httpSummary(usageResponse.code, usageBody)})")
                return Outcome.FAILED
            }
            if (!usageResponse.isSuccessful || usageBody.isNullOrEmpty()) {
                setClaudeErrorState("Server error — tap refresh",
                    "Usage request failed (${httpSummary(usageResponse.code, usageBody)})")
                return Outcome.FAILED
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

            val updatedAt = nowTimestamp()
            prefs.edit()
                .putString("session_pct", sessionPct)
                .putString("session_reset", sessionReset)
                .putInt("session_prog", sessionProg)
                .putString("weekly_pct", weeklyPct)
                .putString("weekly_reset", weeklyReset)
                .putInt("weekly_prog", weeklyProg)
                .putString("last_update", "Updated $updatedAt")
                .putString("updated_at", updatedAt)
                .apply()

            ClaudeWidgetProvider.updateAllWidgets(applicationContext)
            AppLog.i(applicationContext, "Claude", "Updated: session $sessionPct, weekly $weeklyPct")
            return Outcome.UPDATED

        } catch (e: IOException) {
            return setOfflineState("Claude", "", e)
        } catch (e: Exception) {
            setClaudeErrorState("Unexpected response — tap refresh", "Refresh failed", e)
            return Outcome.FAILED
        }
    }

    private fun updateChatGpt(
        client: OkHttpClient,
        prefs: SharedPreferences,
        defaultUa: String,
        token: String?,
        cookies: String?
    ): Outcome {
        var currentToken = token
        val chatGptUa = prefs.getString("chatgpt_user_agent", defaultUa) ?: defaultUa

        try {
            // If no token or we have cookies, try fetching/refreshing token
            if (currentToken.isNullOrEmpty() && !cookies.isNullOrEmpty()) {
                currentToken = refreshChatGptToken(client, cookies, chatGptUa)
                if (!currentToken.isNullOrEmpty()) {
                    prefs.edit().putString("chatgpt_access_token", currentToken).apply()
                }
            }

            if (currentToken.isNullOrEmpty()) {
                setChatGptErrorState("Tap widget to log in", "No access token, and the saved session couldn't get a new one")
                return Outcome.FAILED
            }

            Log.d(TAG, "Fetching ChatGPT usage...")
            var usageRequest = buildChatGptRequest(currentToken, chatGptUa, cookies)
            var usageResponse = client.newCall(usageRequest).execute()

            // If 401 and we have cookies, attempt token refresh once
            if (usageResponse.code == 401 && !cookies.isNullOrEmpty()) {
                AppLog.i(applicationContext, "ChatGPT", "Access token rejected (HTTP 401), getting a new one")
                val refreshedToken = refreshChatGptToken(client, cookies, chatGptUa)
                if (!refreshedToken.isNullOrEmpty()) {
                    usageResponse.close()
                    currentToken = refreshedToken
                    prefs.edit().putString("chatgpt_access_token", refreshedToken).apply()
                    usageRequest = buildChatGptRequest(currentToken, chatGptUa, cookies)
                    usageResponse = client.newCall(usageRequest).execute()
                }
            }

            val body = usageResponse.body?.string()
            if (usageResponse.code == 401 || usageResponse.code == 403) {
                setChatGptErrorState("Session expired — tap to log in",
                    "Usage request rejected (${httpSummary(usageResponse.code, body)})")
                return Outcome.FAILED
            }
            if (!usageResponse.isSuccessful || body.isNullOrEmpty()) {
                setChatGptErrorState("Server error — tap refresh",
                    "Usage request failed (${httpSummary(usageResponse.code, body)})")
                return Outcome.FAILED
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

            val updatedAt = nowTimestamp()
            prefs.edit()
                .putString("chatgpt_session_pct", sessionPct)
                .putString("chatgpt_session_reset", sessionReset)
                .putInt("chatgpt_session_prog", sessionProg)
                .putString("chatgpt_weekly_pct", weeklyPct)
                .putString("chatgpt_weekly_reset", weeklyReset)
                .putInt("chatgpt_weekly_prog", weeklyProg)
                .putString("chatgpt_last_update", "Updated $updatedAt")
                .putString("chatgpt_updated_at", updatedAt)
                .apply()

            ChatGptWidgetProvider.updateAllWidgets(applicationContext)
            AppLog.i(applicationContext, "ChatGPT", "Updated: session $sessionPct, weekly $weeklyPct")
            return Outcome.UPDATED

        } catch (e: IOException) {
            return setOfflineState("ChatGPT", "chatgpt_", e)
        } catch (e: Exception) {
            setChatGptErrorState("Unexpected response — tap refresh", "Refresh failed", e)
            return Outcome.FAILED
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

    /**
     * A new access token from the saved session, or null when the session is logged out or
     * rejected. Throws IOException when the server can't be reached, which isn't a logged-out
     * session and shouldn't be shown as one.
     */
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
                    AppLog.i(applicationContext, "ChatGPT", "Got a new access token from the saved session")
                    return token
                }
                // A logged-out session answers 200 with no accessToken
                AppLog.w(applicationContext, "ChatGPT", "Saved session has no access token (logged out or expired)")
            } else {
                AppLog.w(applicationContext, "ChatGPT", "Session request failed (${httpSummary(resp.code, body)})")
            }
        } catch (e: JSONException) {
            AppLog.w(applicationContext, "ChatGPT", "Session request failed", e)
        }
        return null
    }
}
