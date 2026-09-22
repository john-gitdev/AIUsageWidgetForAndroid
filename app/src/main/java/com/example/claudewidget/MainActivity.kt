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
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPrefs: SharedPreferences
    private var popupWebView: WebView? = null
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
        
        // Enable WebView debugging for Chrome DevTools
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)

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
        removePopupWebView()
        findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
        findViewById<View>(R.id.successLayout).visibility = View.GONE
        setupWebView()
    }

    private fun showSuccessScreen() {
        removePopupWebView()
        loginCheckHandler.removeCallbacks(loginCheckRunnable)
        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        findViewById<View>(R.id.successLayout).visibility = View.VISIBLE
        setupSettings()
    }

    private fun removePopupWebView() {
        popupWebView?.let {
            try {
                findViewById<android.widget.FrameLayout>(R.id.root_frame).removeView(it)
                it.destroy()
            } catch (e: Exception) {
                Log.w("ClaudeWidget", "Error removing popup WebView", e)
            }
            popupWebView = null
        }
    }

    private fun clearClaudeSession() {
        val cookieManager = CookieManager.getInstance()
        val targetUrls = listOf(
            "https://claude.ai",
            "https://claude.ai/",
            "https://api.claude.ai",
            "https://anthropic.com"
        )
        val targetDomains = listOf(
            "claude.ai",
            ".claude.ai",
            "anthropic.com",
            ".anthropic.com"
        )
        val knownClaudeCookieNames = setOf(
            "sessionKey",
            "cf_clearance",
            "__cf_bm",
            "anthropic-session",
            "claude-session",
            "ajs_user_id",
            "ajs_anonymous_id",
            "intercom-id",
            "intercom-session"
        )

        val cookieNamesToClear = mutableSetOf<String>()
        cookieNamesToClear.addAll(knownClaudeCookieNames)

        for (url in targetUrls) {
            val cookieStr = cookieManager.getCookie(url) ?: continue
            for (cookie in cookieStr.split(";")) {
                val name = cookie.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cookieNamesToClear.add(name)
                }
            }
        }

        for (url in targetUrls) {
            for (name in cookieNamesToClear) {
                // Clear host-only
                cookieManager.setCookie(url, "$name=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                // Clear across domain variations
                for (domain in targetDomains) {
                    cookieManager.setCookie(url, "$name=; Domain=$domain; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                }
            }
        }

        // Clear WebStorage (localStorage, IndexedDB) for Claude origins only (preserves Google storage)
        try {
            val webStorage = WebStorage.getInstance()
            webStorage.deleteOrigin("https://claude.ai")
            webStorage.deleteOrigin("https://api.claude.ai")
            webStorage.deleteOrigin("https://anthropic.com")
        } catch (e: Exception) {
            Log.w("ClaudeWidget", "Failed to clear web storage for Claude", e)
        }

        cookieManager.flush()
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

        // ---- Re-login button (clears Claude session, preserves Google account) ----
        findViewById<View>(R.id.btn_relogin).setOnClickListener {
            sharedPrefs.edit().remove("saved_cookies").remove("user_agent").apply()
            clearClaudeSession()
            showLoginScreen()
        }

        // ---- Full Logout button (clears all cookies including Google) ----
        findViewById<View>(R.id.btn_full_logout)?.setOnClickListener {
            sharedPrefs.edit().remove("saved_cookies").remove("user_agent").apply()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                runOnUiThread { showLoginScreen() }
            }
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
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            val defaultAgent = userAgentString
            userAgentString = defaultAgent.replace("; wv", "")
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                removePopupWebView()
                val newWebView = WebView(this@MainActivity)
                popupWebView = newWebView
                newWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    val defaultAgent = userAgentString
                    userAgentString = defaultAgent.replace("; wv", "")
                }
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(newWebView, true)
                
                newWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        removePopupWebView()
                    }
                }
                newWebView.webViewClient = WebViewClient() // Allows URLs to load inside the popup instead of external browser
                
                val params = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
                findViewById<android.widget.FrameLayout>(R.id.root_frame).addView(newWebView, params)
                
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                transport?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                val msg = "JS Console: ${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}"
                Log.e("ClaudeWidget", msg)
                val text = consoleMessage?.message() ?: ""
                if ((text.contains("error", ignoreCase = true) || text.contains("failed", ignoreCase = true)) 
                    && !text.contains("Permissions-Policy", ignoreCase = true)) {
                    logErrorToScreen(msg)
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                val msg = "WebView Error: code ${error?.errorCode}, description ${error?.description}, url ${request?.url}"
                Log.e("ClaudeWidget", msg)
                if (request?.isForMainFrame == true) {
                    logErrorToScreen(msg)
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val msg = "WebView HTTP Error: status ${errorResponse?.statusCode}, url ${request?.url}"
                Log.e("ClaudeWidget", msg)
                if (request?.isForMainFrame == true) {
                    logErrorToScreen(msg)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                checkLoginCookies(view)
            }
            
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                checkLoginCookies(view)
            }
        }

        webView.loadUrl("https://claude.ai/login")
        startCookiePolling()
    }

    private val loginCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val loginCheckRunnable = object : Runnable {
        override fun run() {
            if (!loginDetected) {
                checkLoginCookies(webView)
                loginCheckHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun startCookiePolling() {
        loginCheckHandler.removeCallbacks(loginCheckRunnable)
        loginCheckHandler.post(loginCheckRunnable)
    }

    private fun hasValidSessionKey(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        val regex = Regex("""(?:^|;\s*)sessionKey=([^;]+)""")
        val match = regex.find(cookies) ?: return false
        val value = match.groupValues[1].trim()
        return value.isNotEmpty() && value != "deleted" && value != "\"\"" && value != "null"
    }

    private fun checkLoginCookies(view: WebView?) {
        if (loginDetected) return
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (hasValidSessionKey(cookies)) {
            loginDetected = true
            Log.d("ClaudeWidget", "Login detected! Saving cookies.")
            sharedPrefs.edit()
                .putString("saved_cookies", cookies)
                .putString("user_agent", view?.settings?.userAgentString)
                .apply()
            CookieManager.getInstance().flush()
            runOnUiThread {
                removePopupWebView()
                showSuccessScreen()
            }
            UpdateWidgetWorker.enqueueWork(this@MainActivity)
        }
    }

    private fun logErrorToScreen(msg: String) {
        runOnUiThread {
            val errorText = findViewById<android.widget.TextView>(R.id.errorText)
            errorText.visibility = View.VISIBLE
            val currentText = errorText.text.toString()
            errorText.text = if (currentText.isEmpty()) msg else "$currentText\n$msg"
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        loginCheckHandler.removeCallbacks(loginCheckRunnable)
        removePopupWebView()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (popupWebView != null) {
            removePopupWebView()
            return
        }
        if (findViewById<View>(R.id.loadingLayout).visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

