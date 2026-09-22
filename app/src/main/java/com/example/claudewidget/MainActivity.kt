package com.example.claudewidget

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var sharedPrefs: SharedPreferences
    private var popupWebView: WebView? = null

    @Volatile private var currentTab: String = "claude" // "claude" or "chatgpt"
    private var claudeLoginDetected = false
    @Volatile private var chatGptLoginDetected = false

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
        
        // Enable WebView debugging for Chrome DevTools
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)

        setupTabButtons()

        val targetTab = intent.getStringExtra("target_tab")
        if (targetTab == "chatgpt") {
            switchTab("chatgpt")
        } else {
            // Default to Claude, or ChatGPT if Claude is connected and ChatGPT is not
            if (isClaudeLoggedIn() && !isChatGptLoggedIn()) {
                switchTab("chatgpt")
            } else {
                switchTab("claude")
            }
        }
    }

    private fun isClaudeLoggedIn(): Boolean {
        return !sharedPrefs.getString("saved_cookies", null).isNullOrEmpty()
    }

    private fun isChatGptLoggedIn(): Boolean {
        val token = sharedPrefs.getString("chatgpt_access_token", null)
        val cookies = sharedPrefs.getString("chatgpt_saved_cookies", null)
        return !token.isNullOrEmpty() || !cookies.isNullOrEmpty()
    }

    private fun setupTabButtons() {
        findViewById<TextView>(R.id.tab_claude).setOnClickListener {
            switchTab("claude")
        }
        findViewById<TextView>(R.id.tab_chatgpt).setOnClickListener {
            switchTab("chatgpt")
        }
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        val tabClaude = findViewById<TextView>(R.id.tab_claude)
        val tabChatGpt = findViewById<TextView>(R.id.tab_chatgpt)

        if (tab == "claude") {
            tabClaude.setBackgroundColor(0xFFD4511E.toInt())
            tabClaude.setTextColor(0xFFFFFFFF.toInt())
            tabChatGpt.setBackgroundColor(0xFF222222.toInt())
            tabChatGpt.setTextColor(0xFF888888.toInt())

            if (isClaudeLoggedIn()) {
                showSuccessScreen()
            } else {
                showLoginScreen()
            }
        } else {
            tabChatGpt.setBackgroundColor(0xFF10A37F.toInt())
            tabChatGpt.setTextColor(0xFFFFFFFF.toInt())
            tabClaude.setBackgroundColor(0xFF222222.toInt())
            tabClaude.setTextColor(0xFF888888.toInt())

            if (isChatGptLoggedIn()) {
                showSuccessScreen()
            } else {
                showLoginScreen()
            }
        }
    }

    private fun showLoginScreen() {
        removePopupWebView()
        findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
        findViewById<View>(R.id.successLayout).visibility = View.GONE

        val instructions = findViewById<TextView>(R.id.tv_login_instructions)
        if (currentTab == "claude") {
            claudeLoginDetected = false
            instructions.text = "Please log in to Claude below. The app will automatically save your session for the widget."
        } else {
            chatGptLoginDetected = false
            instructions.text = "Please log in to ChatGPT below. The app will automatically save your session for the widget."
        }

        setupWebView()
    }

    private fun showSuccessScreen() {
        removePopupWebView()
        loginCheckHandler.removeCallbacks(loginCheckRunnable)
        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        findViewById<View>(R.id.successLayout).visibility = View.VISIBLE

        val title = findViewById<TextView>(R.id.tv_success_title)
        val subtitle = findViewById<TextView>(R.id.tv_success_subtitle)
        val reloginBtn = findViewById<TextView>(R.id.btn_relogin)

        if (currentTab == "claude") {
            title.text = "Claude Connected!"
            subtitle.text = "Add the Claude Widget to your home screen and tap refresh."
            reloginBtn.text = "Re-login to Claude"
            reloginBtn.setBackgroundColor(0xFFD4511E.toInt())
        } else {
            title.text = "ChatGPT Connected!"
            subtitle.text = "Add the ChatGPT Widget to your home screen and tap refresh."
            reloginBtn.text = "Re-login to ChatGPT"
            reloginBtn.setBackgroundColor(0xFF10A37F.toInt())
        }

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
                cookieManager.setCookie(url, "$name=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                for (domain in targetDomains) {
                    cookieManager.setCookie(url, "$name=; Domain=$domain; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                }
            }
        }

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

    private fun clearChatGptSession() {
        val cookieManager = CookieManager.getInstance()
        val targetUrls = listOf(
            "https://chatgpt.com",
            "https://chatgpt.com/",
            "https://oaistatic.com",
            "https://openai.com"
        )
        val targetDomains = listOf(
            "chatgpt.com",
            ".chatgpt.com",
            "oaistatic.com",
            ".oaistatic.com",
            "openai.com",
            ".openai.com"
        )
        val knownNames = setOf(
            "__Secure-next-auth.session-token",
            "next-auth.session-token",
            "__Host-next-auth.csrf-token",
            "cf_clearance",
            "__cf_bm",
            "oai-did",
            "oai-nav-state"
        )

        val cookieNamesToClear = mutableSetOf<String>()
        cookieNamesToClear.addAll(knownNames)

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
                cookieManager.setCookie(url, "$name=; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                for (domain in targetDomains) {
                    cookieManager.setCookie(url, "$name=; Domain=$domain; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0")
                }
            }
        }

        try {
            val webStorage = WebStorage.getInstance()
            webStorage.deleteOrigin("https://chatgpt.com")
            webStorage.deleteOrigin("https://openai.com")
        } catch (e: Exception) {
            Log.w("ChatGptWidget", "Failed to clear web storage for ChatGPT", e)
        }

        cookieManager.flush()
    }

    private fun updateWidgetsFor(service: String) {
        if (service == "claude") {
            ClaudeWidgetProvider.updateAllWidgets(this)
        } else {
            ChatGptWidgetProvider.updateAllWidgets(this)
        }
    }

    private fun setupSettings() {
        // ---- Refresh Interval Spinner ----
        val spinner = findViewById<Spinner>(R.id.spinner_interval)
        val labels = intervalOptions.map { it.first }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter

        val currentInterval = sharedPrefs.getLong("refresh_interval_minutes", 15L)
        val selectedIndex = intervalOptions.indexOfFirst { it.second == currentInterval }.coerceAtLeast(0)
        spinner.setSelection(selectedIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newInterval = intervalOptions[position].second
                // Compare against the saved value (not currentInterval) so switching back and forth works
                if (newInterval != sharedPrefs.getLong("refresh_interval_minutes", 15L)) {
                    UpdateWidgetWorker.rescheduleWork(this@MainActivity, newInterval)
                    Log.d("ClaudeWidget", "Interval changed to ${newInterval}m")
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- Usage Display Spinner (per service: applies to this tab's widget only) ----
        val service = currentTab
        findViewById<TextView>(R.id.tv_usage_display_title).text =
            if (service == "claude") "Show Claude Usage As" else "Show ChatGPT Usage As"

        val displayOptions = listOf("Percent used" to "used", "Percent left" to "left")
        val displaySpinner = findViewById<Spinner>(R.id.spinner_usage_display)
        val displayLabels = displayOptions.map { it.first }
        val displayAdapter = ArrayAdapter(this, R.layout.spinner_item, displayLabels)
        displayAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        displaySpinner.adapter = displayAdapter

        val currentDisplay = UsageDisplay.mode(sharedPrefs, service)
        val displaySelectedIndex = displayOptions.indexOfFirst { it.second == currentDisplay }.coerceAtLeast(0)
        displaySpinner.setSelection(displaySelectedIndex)

        displaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newDisplay = displayOptions[position].second
                // Compare against the saved value (not currentDisplay) so switching back and forth works
                if (newDisplay != UsageDisplay.mode(sharedPrefs, service)) {
                    val linked = sharedPrefs.getBoolean(UsageDisplay.LINKED_KEY, false)
                    val services = if (linked) listOf(service, UsageDisplay.otherService(service)) else listOf(service)
                    val editor = sharedPrefs.edit()
                    services.forEach { editor.putString(UsageDisplay.prefKey(it), newDisplay) }
                    editor.apply()
                    // Widgets format from saved data, so no re-fetch is needed
                    services.forEach { updateWidgetsFor(it) }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- "Use the same option for <other service>" checkbox ----
        // Both tabs read and write the same pref, so checking/unchecking on one tab is reflected on the other.
        val linkBox = findViewById<CheckBox>(R.id.cb_usage_display_linked)
        linkBox.text = if (service == "claude") "Use the same option for ChatGPT" else "Use the same option for Claude"
        val accent = if (service == "claude") 0xFFD4511E.toInt() else 0xFF10A37F.toInt()
        linkBox.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, 0xFF808080.toInt())
        )
        // setupSettings runs on every tab switch, so detach the old listener before restoring the state
        linkBox.setOnCheckedChangeListener(null)
        linkBox.isChecked = sharedPrefs.getBoolean(UsageDisplay.LINKED_KEY, false)
        linkBox.setOnCheckedChangeListener { _, checked ->
            val editor = sharedPrefs.edit().putBoolean(UsageDisplay.LINKED_KEY, checked)
            if (checked) {
                // Apply this tab's choice to the other service right away
                val other = UsageDisplay.otherService(service)
                editor.putString(UsageDisplay.prefKey(other), UsageDisplay.mode(sharedPrefs, service))
                editor.apply()
                updateWidgetsFor(other)
            } else {
                // Unlinking keeps each service's current value; they just stop syncing
                editor.apply()
            }
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
                // Compare against the saved value (not currentTapAction) so switching back and forth works
                if (newAction != sharedPrefs.getString("tap_action", "refresh")) {
                    sharedPrefs.edit().putString("tap_action", newAction).apply()
                    ClaudeWidgetProvider.updateAllWidgets(this@MainActivity)
                    ChatGptWidgetProvider.updateAllWidgets(this@MainActivity)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ---- Re-login button (clears current service session, preserves Google account) ----
        findViewById<View>(R.id.btn_relogin).setOnClickListener {
            if (currentTab == "claude") {
                sharedPrefs.edit().remove("saved_cookies").remove("user_agent").apply()
                clearClaudeSession()
            } else {
                sharedPrefs.edit()
                    .remove("chatgpt_access_token")
                    .remove("chatgpt_saved_cookies")
                    .remove("chatgpt_user_agent")
                    .apply()
                clearChatGptSession()
            }
            showLoginScreen()
        }

        // ---- Full Logout button (clears all cookies across everything including Google) ----
        findViewById<View>(R.id.btn_full_logout)?.setOnClickListener {
            sharedPrefs.edit()
                .remove("saved_cookies")
                .remove("user_agent")
                .remove("chatgpt_access_token")
                .remove("chatgpt_saved_cookies")
                .remove("chatgpt_user_agent")
                .apply()
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

        // Add JS Bridge for ChatGPT token extraction
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onChatGptToken(token: String?) {
                if (!token.isNullOrEmpty() && currentTab == "chatgpt") {
                    handleChatGptTokenReceived(token)
                }
            }
        }, "AndroidBridge")

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
                newWebView.webViewClient = WebViewClient()

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
                Log.d("ClaudeWidget", msg)
                return super.onConsoleMessage(consoleMessage)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (currentTab == "chatgpt" && !chatGptLoginDetected) {
                    val urlStr = request?.url?.toString() ?: ""
                    if (urlStr.contains("chatgpt.com")) {
                        val auth = request?.requestHeaders?.get("Authorization")
                            ?: request?.requestHeaders?.get("authorization")
                        if (auth != null && auth.startsWith("Bearer ey")) {
                            val token = auth.removePrefix("Bearer ").trim()
                            handleChatGptTokenReceived(token)
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (currentTab == "claude") {
                    checkClaudeLoginCookies(view)
                } else {
                    attemptChatGptSessionExtraction(view)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (currentTab == "claude") {
                    checkClaudeLoginCookies(view)
                } else {
                    attemptChatGptSessionExtraction(view)
                }
            }
        }

        val targetUrl = if (currentTab == "claude") "https://claude.ai/login" else "https://chatgpt.com/auth/login"
        webView.loadUrl(targetUrl)
        startCookiePolling()
    }

    private val loginCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val loginCheckRunnable = object : Runnable {
        override fun run() {
            if (currentTab == "claude" && !claudeLoginDetected) {
                checkClaudeLoginCookies(webView)
                loginCheckHandler.postDelayed(this, 1000)
            } else if (currentTab == "chatgpt" && !chatGptLoginDetected) {
                attemptChatGptSessionExtraction(webView)
                loginCheckHandler.postDelayed(this, 1500)
            }
        }
    }

    private fun startCookiePolling() {
        loginCheckHandler.removeCallbacks(loginCheckRunnable)
        loginCheckHandler.post(loginCheckRunnable)
    }

    private fun hasValidClaudeSessionKey(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        val regex = Regex("""(?:^|;\s*)sessionKey=([^;]+)""")
        val match = regex.find(cookies) ?: return false
        val value = match.groupValues[1].trim()
        return value.isNotEmpty() && value != "deleted" && value != "\"\"" && value != "null"
    }

    private fun checkClaudeLoginCookies(view: WebView?) {
        if (claudeLoginDetected) return
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (hasValidClaudeSessionKey(cookies)) {
            claudeLoginDetected = true
            Log.d("ClaudeWidget", "Claude login detected! Saving cookies.")
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

    private fun attemptChatGptSessionExtraction(view: WebView?) {
        if (chatGptLoginDetected) return
        view?.post {
            view.evaluateJavascript("""
                (function() {
                    try {
                        fetch('/api/auth/session')
                            .then(function(r) { return r.json(); })
                            .then(function(d) {
                                if (d && d.accessToken) {
                                    window.AndroidBridge.onChatGptToken(d.accessToken);
                                }
                            })
                            .catch(function(e) {});
                    } catch(e) {}
                })();
            """.trimIndent(), null)
        }
    }

    private fun handleChatGptTokenReceived(token: String) {
        if (token.length < 20) return

        // Called from the JS bridge and shouldInterceptRequest, which both run off the main
        // thread. WebView methods throw there, so do everything on the UI thread.
        runOnUiThread {
            if (chatGptLoginDetected || currentTab != "chatgpt") return@runOnUiThread
            chatGptLoginDetected = true
            Log.d("ChatGptWidget", "ChatGPT token captured! Saving session.")

            val cookies = CookieManager.getInstance().getCookie("https://chatgpt.com")
            sharedPrefs.edit()
                .putString("chatgpt_access_token", token)
                .putString("chatgpt_saved_cookies", cookies)
                .putString("chatgpt_user_agent", webView.settings.userAgentString)
                .apply()
            CookieManager.getInstance().flush()

            removePopupWebView()
            showSuccessScreen()
            UpdateWidgetWorker.enqueueWork(this@MainActivity)
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
