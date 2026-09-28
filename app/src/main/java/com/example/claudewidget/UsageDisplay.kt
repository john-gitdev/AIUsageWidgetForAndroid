package com.example.claudewidget

import android.content.SharedPreferences
import android.widget.RemoteViews

/** Renders usage rows as "% used" or "% left", per each service's "Show Usage As" setting. */
object UsageDisplay {

    /** Pref key holding the "Show Usage As" mode ("used" or "left") for "claude" or "chatgpt". */
    fun prefKey(service: String) = if (service == "chatgpt") "chatgpt_usage_display" else "usage_display"

    /** Claude defaults to showing used, ChatGPT to showing left. */
    fun defaultMode(service: String) = if (service == "chatgpt") "left" else "used"

    /** When true, changing "Show Usage As" on either tab applies to both services. Off by default. */
    const val LINKED_KEY = "usage_display_linked"

    fun otherService(service: String) = if (service == "chatgpt") "claude" else "chatgpt"

    fun mode(prefs: SharedPreferences, service: String): String =
        prefs.getString(prefKey(service), defaultMode(service)) ?: defaultMode(service)

    /**
     * Binds one usage row (percent text + progress bar).
     * [stored] is the text the worker saved, e.g. "12% used". Placeholders like "--" or "Error"
     * aren't readings, so they're shown as-is and never flipped.
     */
    fun text(stored: String?, usedPercent: Int, showLeft: Boolean): String {
        val value = stored ?: "--"
        return if (showLeft && value.endsWith("% used")) {
            val left = (100 - usedPercent).coerceIn(0, 100)
            "$left% left"
        } else {
            value
        }
    }

    fun bind(
        views: RemoteViews,
        pctId: Int,
        barId: Int,
        stored: String?,
        usedPercent: Int,
        showLeft: Boolean
    ) {
        val displayText = text(stored, usedPercent, showLeft)
        val progress = if (showLeft && (stored ?: "").endsWith("% used")) {
            (100 - usedPercent).coerceIn(0, 100)
        } else {
            usedPercent
        }
        views.setTextViewText(pctId, displayText)
        views.setProgressBar(barId, 100, progress, false)
    }
}
