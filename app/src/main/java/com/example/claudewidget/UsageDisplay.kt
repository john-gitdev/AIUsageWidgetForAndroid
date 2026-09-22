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
    fun bind(
        views: RemoteViews,
        pctId: Int,
        barId: Int,
        stored: String?,
        usedPercent: Int,
        showLeft: Boolean
    ) {
        val text = stored ?: "--"
        if (showLeft && text.endsWith("% used")) {
            val left = (100 - usedPercent).coerceIn(0, 100)
            views.setTextViewText(pctId, "$left% left")
            views.setProgressBar(barId, 100, left, false)
        } else {
            views.setTextViewText(pctId, text)
            views.setProgressBar(barId, 100, usedPercent, false)
        }
    }
}
