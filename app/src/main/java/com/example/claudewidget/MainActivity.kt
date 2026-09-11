package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPrefs: SharedPreferences
    private var loginDetected = false

    // Interval options: display label → minutes value
    private val intervalOptions = listOf(
        "Every 15 minutes" to 15L,
        "Every 30 minutes" to 30L,
        "Every 1 hour" to 60L,
        "Every 2 hours" to 120L,
        "Every 4 hours" to 240L,
        "Never (manual only)" to 0L
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPrefs = getSharedPreferences("ClaudeWidgetPrefs", Context.MODE_PRIVATE)
        webView = findViewById(R.id.webView)
        loginDetected = false

        // Check if user is already logged in (has cookies)
        val existingCookies = sharedPrefs.getString("saved_cookies", null)
        if (!existingCookies.isNullOrEmpty()) {
            // Show success/settings screen directly
            showSuccessScreen()
        } else {
            // Show login
            showLoginScreen()
        }
    }

    private fun showLoginScreen() {
        loginDetected = false
        findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
        findViewById<View>(R.id.successLayout).visibility = View.GONE
        setupWebView()
    }

    private fun showSuccessScreen() {
        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        findViewById<View>(R.id.successLayout).visibility = View.VISIBLE
        setupSettings()
    }

    private fun setupSettings() {
        // ---- Refresh Interval Spinner ----
        val spinner = findViewById<Spinner>(R.id.spinner_interval)
        val labels = intervalOptions.map { it.first }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter

        // Select the current saved interval
        val currentInterval = sharedPrefs.getLong("refresh_interval_minutes", 15L)
        val selectedIndex = intervalOptions.indexOfFirst { it.second == currentInterval }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newInterval = intervalOptions[position].second
                if (newInterval != currentInterval) {
                    UpdateWidgetWorker.rescheduleWork(this@MainActivity, newInterval)
                    Log.d("ClaudeWidget", "Interval changed to ${newInterval}m")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- Tap Action Spinner ----
        val tapOptions = listOf("Refresh Status" to "refresh", "Open App" to "open_app")
        val tapSpinner = findViewById<Spinner>(R.id.spinner_tap_action)
        val tapLabels = tapOptions.map { it.first }
        val tapAdapter = ArrayAdapter(this, R.layout.spinner_item, tapLabels)
        tapAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        tapSpinner.adapter = tapAdapter

        val currentTapAction = sharedPrefs.getString("tap_action", "refresh")
        val tapSelectedIndex = tapOptions.indexOfFirst { it.second == currentTapAction }.coerceAtLeast(0)
        tapSpinner.setSelection(tapSelectedIndex)

        tapSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newAction = tapOptions[position].second
                if (newAction != currentTapAction) {
                    sharedPrefs.edit().putString("tap_action", newAction).apply()
                    // Update widgets immediately so the new tap action is applied
                    ClaudeWidgetProvider.updateAllWidgets(this@MainActivity)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- Re-login button ----
        findViewById<View>(R.id.btn_relogin).setOnClickListener {
            // Clear cookies and show login again
            sharedPrefs.edit().remove("saved_cookies").remove("user_agent").apply()
            CookieManager.getInstance().removeAllCookies(null)
            showLoginScreen()
        }
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true

            val defaultAgent = userAgentString
            userAgentString = defaultAgent.replace("; wv", "")
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = WebChromeClient()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                if (loginDetected) return

                Log.d("ClaudeWidget", "Page finished: $url")

                val cookies = CookieManager.getInstance().getCookie("https://claude.ai")

                if (cookies != null
                    && cookies.contains("sessionKey=")
                    && !cookies.contains("sessionKey=;")
                    && !cookies.contains("sessionKey=deleted")) {

                    loginDetected = true
                    Log.d("ClaudeWidget", "Login detected! Saving cookies.")

                    sharedPrefs.edit()
                        .putString("saved_cookies", cookies)
                        .putString("user_agent", view?.settings?.userAgentString)
                        .apply()

                    runOnUiThread { showSuccessScreen() }

                    UpdateWidgetWorker.enqueueWork(this@MainActivity)
                }
            }
        }

        webView.loadUrl("https://claude.ai/login")
    }
}
