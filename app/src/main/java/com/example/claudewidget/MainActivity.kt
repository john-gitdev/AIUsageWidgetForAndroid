package com.example.claudewidget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_TARGET_TAB = "target_tab"

        /**
         * Opens the app on [tab] ("claude" or "chatgpt"). If the app is already open, CLEAR_TOP + SINGLE_TOP
         * deliver this to onNewIntent so the tab still switches, instead of the app just coming to the front.
         */
        fun openTabIntent(context: Context, tab: String): Intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_TARGET_TAB, tab)
            }
    }

    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var claudeBrowser: LoginBrowser
    private lateinit var chatGptBrowser: LoginBrowser

    private var currentTab: String = "claude" // "claude" or "chatgpt"

    private val loginCheckHandler = Handler(Looper.getMainLooper())

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

        // Keep the periodic refresh on the current request (constraints, backoff) after an update.
        // Logging in schedules it the first time.
        if (isClaudeLoggedIn() || isChatGptLoggedIn()) {
            UpdateWidgetWorker.schedulePeriodic(this)
        }

        // Enable WebView debugging for Chrome DevTools
        WebView.setWebContentsDebuggingEnabled(true)
        CookieManager.getInstance().setAcceptCookie(true)

        claudeBrowser = LoginBrowser("claude", findViewById(R.id.browser_claude), findViewById(R.id.webView_claude))
        chatGptBrowser = LoginBrowser("chatgpt", findViewById(R.id.browser_chatgpt), findViewById(R.id.webView_chatgpt))

        setupTabButtons()

        val targetTab = targetTabFrom(intent)
        if (targetTab != null) {
            switchTab(targetTab)
        } else {
            // Default to Claude, or ChatGPT if Claude is connected and ChatGPT is not
            if (isClaudeLoggedIn() && !isChatGptLoggedIn()) {
                switchTab("chatgpt")
            } else {
                switchTab("claude")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        targetTabFrom(intent)?.let { switchTab(it) }
    }

    override fun onResume() {
        super.onResume()
        // A refresh may have failed while the app was in the background
        if (findViewById<View>(R.id.successLayout).visibility == View.VISIBLE) {
            showConnectionStatus()
        }
    }

    /** The tab a widget asked to open, or null when the app was opened some other way. */
    private fun targetTabFrom(intent: Intent?): String? {
        val tab = intent?.getStringExtra(EXTRA_TARGET_TAB) ?: return null
        val service = if (tab == "chatgpt") "chatgpt" else "claude"
        AppLog.i(this, "App", "Opened from the ${serviceName(service)} widget")
        return service
    }

    private fun serviceName(service: String) = if (service == "chatgpt") "ChatGPT" else "Claude"

    private fun accentColor(service: String) = if (service == "chatgpt") 0xFF10A37F.toInt() else 0xFFD4511E.toInt()

    /** Claude's widget data is saved under unprefixed keys ("session_pct"), ChatGPT's under "chatgpt_". */
    private fun keyPrefix(service: String) = if (service == "chatgpt") "chatgpt_" else ""

    private fun browserFor(service: String) = if (service == "chatgpt") chatGptBrowser else claudeBrowser

    private fun isClaudeLoggedIn(): Boolean {
        return !sharedPrefs.getString("saved_cookies", null).isNullOrEmpty()
    }

    private fun isChatGptLoggedIn(): Boolean {
        val token = sharedPrefs.getString("chatgpt_access_token", null)
        val cookies = sharedPrefs.getString("chatgpt_saved_cookies", null)
        return !token.isNullOrEmpty() || !cookies.isNullOrEmpty()
    }

    private fun isLoggedIn(service: String) = if (service == "chatgpt") isChatGptLoggedIn() else isClaudeLoggedIn()

    private fun setupTabButtons() {
        findViewById<TextView>(R.id.tab_claude).setOnClickListener {
            switchTab("claude")
        }
        findViewById<TextView>(R.id.tab_chatgpt).setOnClickListener {
            switchTab("chatgpt")
        }
        findViewById<TextView>(R.id.tab_log).setOnClickListener {
            showLogDialog()
        }
    }

    private fun switchTab(tab: String) {
        currentTab = tab
        val selected = findViewById<TextView>(if (tab == "claude") R.id.tab_claude else R.id.tab_chatgpt)
        val other = findViewById<TextView>(if (tab == "claude") R.id.tab_chatgpt else R.id.tab_claude)
        selected.setBackgroundColor(accentColor(tab))
        selected.setTextColor(0xFFFFFFFF.toInt())
        other.setBackgroundColor(0xFF222222.toInt())
        other.setTextColor(0xFF888888.toInt())

        if (isLoggedIn(tab)) {
            showSuccessScreen()
        } else {
            showLoginScreen()
        }
    }

    /**
     * Shows the current tab's own browser. Its page is kept when switching tabs, so a half-finished login
     * is still there when you come back. [restart] reloads the login page.
     */
    private fun showLoginScreen(restart: Boolean = false) {
        findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
        findViewById<View>(R.id.successLayout).visibility = View.GONE

        val browser = browserFor(currentTab)
        claudeBrowser.frame.visibility = if (browser === claudeBrowser) View.VISIBLE else View.GONE
        chatGptBrowser.frame.visibility = if (browser === chatGptBrowser) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.tv_login_instructions).text =
            "Please log in to ${serviceName(currentTab)} below. The app will automatically save your session for the widget."

        if (restart || !browser.loginInProgress) {
            browser.startLogin()
        }
    }

    private fun showSuccessScreen() {
        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        findViewById<View>(R.id.successLayout).visibility = View.VISIBLE

        val reloginBtn = findViewById<TextView>(R.id.btn_relogin)
        reloginBtn.text = "Re-login to ${serviceName(currentTab)}"
        reloginBtn.setBackgroundColor(accentColor(currentTab))

        showConnectionStatus()
        setupSettings()
    }

    /**
     * Shows "Connected!", or the last refresh error if there is one. An error is usually why a widget opened
     * the app, so the screen shouldn't claim everything is fine.
     */
    private fun showConnectionStatus() {
        val name = serviceName(currentTab)
        val prefix = keyPrefix(currentTab)
        val failed = sharedPrefs.getString("${prefix}session_pct", "--") == "Error"
        // Error messages are written for the widget ("Session expired — tap to log in"), so drop the tap hint
        val reason = (sharedPrefs.getString("${prefix}session_reset", null) ?: "").substringBefore(" — ")

        val icon = findViewById<TextView>(R.id.tv_success_icon)
        val title = findViewById<TextView>(R.id.tv_success_title)
        val subtitle = findViewById<TextView>(R.id.tv_success_subtitle)

        if (!failed) {
            icon.text = "✓"
            icon.setTextColor(0xFF4CAF50.toInt())
            title.text = "$name Connected!"
            subtitle.text = "Add the $name Widget to your home screen and tap refresh."
            return
        }

        icon.text = "!"
        icon.setTextColor(0xFFFFB300.toInt())
        if (reason.contains("expired", ignoreCase = true) || reason.contains("log in", ignoreCase = true)) {
            title.text = "$name Session Expired"
            subtitle.text = "Tap Re-login below to sign in again. Tap Log at the top for details."
        } else {
            title.text = "$name Refresh Failed"
            subtitle.text = "$reason. Tap refresh on the widget to try again, or tap Log at the top for details."
        }
    }

    private fun showLogDialog() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        val header = "AI Usage Widget $version, Android ${Build.VERSION.RELEASE}. Newest first."
        val log = AppLog.read(this)
        val logText = if (log.isEmpty()) "$header\n\nNothing logged yet." else "$header\n\n$log"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val textView = TextView(this).apply {
            text = logText
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(pad, pad, pad, pad)
        }

        AlertDialog.Builder(this)
            .setTitle("Log")
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Copy") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("AI Usage Widget log", logText))
                // Android 13+ shows its own "Copied" confirmation
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Close", null)
            .setNeutralButton("Clear") { _, _ ->
                AppLog.clear(this)
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** Forgets the session the widget uses for [service]. The other service is untouched. */
    private fun clearSavedSession(service: String) {
        val editor = sharedPrefs.edit()
        if (service == "claude") {
            editor.remove("saved_cookies").remove("user_agent")
        } else {
            editor.remove("chatgpt_access_token").remove("chatgpt_saved_cookies").remove("chatgpt_user_agent")
        }
        editor.apply()
    }

    /** Deletes [service]'s cookies and web storage, leaving the other service and the Google sign-in alone. */
    private fun clearSiteData(service: String) {
        if (service == "claude") {
            clearSiteData(
                urls = listOf("https://claude.ai", "https://claude.ai/", "https://api.claude.ai", "https://anthropic.com"),
                domains = listOf("claude.ai", ".claude.ai", "anthropic.com", ".anthropic.com"),
                knownNames = setOf(
                    "sessionKey",
                    "cf_clearance",
                    "__cf_bm",
                    "anthropic-session",
                    "claude-session",
                    "ajs_user_id",
                    "ajs_anonymous_id",
                    "intercom-id",
                    "intercom-session"
                ),
                origins = listOf("https://claude.ai", "https://api.claude.ai", "https://anthropic.com")
            )
        } else {
            clearSiteData(
                urls = listOf("https://chatgpt.com", "https://chatgpt.com/", "https://oaistatic.com", "https://openai.com"),
                domains = listOf("chatgpt.com", ".chatgpt.com", "oaistatic.com", ".oaistatic.com", "openai.com", ".openai.com"),
                knownNames = setOf(
                    "__Secure-next-auth.session-token",
                    "next-auth.session-token",
                    "__Host-next-auth.csrf-token",
                    "cf_clearance",
                    "__cf_bm",
                    "oai-did",
                    "oai-nav-state"
                ),
                origins = listOf("https://chatgpt.com", "https://openai.com")
            )
        }
    }

    private fun clearSiteData(urls: List<String>, domains: List<String>, knownNames: Set<String>, origins: List<String>) {
        val cookieManager = CookieManager.getInstance()

        val cookieNamesToClear = mutableSetOf<String>()
        cookieNamesToClear.addAll(knownNames)

        for (url in urls) {
            val cookieStr = cookieManager.getCookie(url) ?: continue
            for (cookie in cookieStr.split(";")) {
                val name = cookie.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    cookieNamesToClear.add(name)
                }
            }
        }

        // Secure is required to delete __Secure- and __Host- cookies (like ChatGPT's session token);
        // without it the deletion is silently ignored. Every URL here is https, so it's fine for all of them.
        val expired = "Path=/; Secure; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0"
        for (url in urls) {
            for (name in cookieNamesToClear) {
                cookieManager.setCookie(url, "$name=; $expired")
                for (domain in domains) {
                    cookieManager.setCookie(url, "$name=; Domain=$domain; $expired")
                }
            }
        }

        try {
            val webStorage = WebStorage.getInstance()
            origins.forEach { webStorage.deleteOrigin(it) }
        } catch (e: Exception) {
            Log.w("ClaudeWidget", "Failed to clear web storage for $origins", e)
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
        findViewById<TextView>(R.id.tv_usage_display_title).text = "Show ${serviceName(service)} Usage As"

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
        linkBox.text = "Use the same option for ${serviceName(UsageDisplay.otherService(service))}"
        linkBox.buttonTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accentColor(service), 0xFF808080.toInt())
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

        // ---- Re-login button (clears this service's session only, preserves Google account) ----
        findViewById<View>(R.id.btn_relogin).setOnClickListener {
            clearSavedSession(service)
            clearSiteData(service)
            AppLog.i(this, serviceName(service), "Re-login: cleared the saved session")
            showLoginScreen(restart = true)
        }

        // ---- Full Logout button (this service plus the Google sign-in) ----
        val fullLogoutBtn = findViewById<TextView>(R.id.btn_full_logout)
        fullLogoutBtn.text = "Log out of ${serviceName(service)} completely (clear Google session)"
        fullLogoutBtn.setOnClickListener {
            clearSavedSession(service)
            clearSiteData(service)
            AppLog.i(this, serviceName(service), "Full logout: cleared the saved session and all cookies")
            // Clearing the Google sign-in reliably takes removing every cookie. The other service stays
            // logged in: its widget and tab use the session saved in prefs, not these cookies.
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                runOnUiThread {
                    if (currentTab == service) showLoginScreen(restart = true)
                }
            }
        }
    }

    /**
     * The login browser for one service. Each service has its own WebView, popup and login check, tied to
     * that service rather than to whichever tab is showing, so the two logins can't affect each other.
     * (Cookies are shared app-wide per domain, which is what lets the Google sign-in carry over.)
     */
    private inner class LoginBrowser(val service: String, val frame: FrameLayout, val webView: WebView) {
        private val name = serviceName(service)
        private val loginUrl = if (service == "claude") "https://claude.ai/login" else "https://chatgpt.com/auth/login"
        private val pollIntervalMs = if (service == "claude") 1000L else 1500L

        /** True from opening the login page until the login is detected. Read from WebView background threads. */
        @Volatile var loginInProgress = false
            private set

        private var popup: WebView? = null
        private var clearHistoryOnNextPage = false

        private val pollRunnable = object : Runnable {
            override fun run() {
                if (!loginInProgress) return
                checkForLogin()
                loginCheckHandler.postDelayed(this, pollIntervalMs)
            }
        }

        init {
            applyBrowserSettings(webView)
            webView.settings.apply {
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            if (service == "chatgpt") {
                // JS bridge for ChatGPT token extraction
                webView.addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onChatGptToken(token: String?) {
                        if (!token.isNullOrEmpty()) {
                            handleChatGptTokenReceived(token)
                        }
                    }
                }, "AndroidBridge")
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    openPopup(resultMsg)
                    return true
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d("ClaudeWidget", "$name JS Console: ${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    if (service == "chatgpt" && loginInProgress) {
                        val urlStr = request?.url?.toString() ?: ""
                        if (urlStr.contains("chatgpt.com")) {
                            val auth = request?.requestHeaders?.get("Authorization")
                                ?: request?.requestHeaders?.get("authorization")
                            if (auth != null && auth.startsWith("Bearer ey")) {
                                handleChatGptTokenReceived(auth.removePrefix("Bearer ").trim())
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (clearHistoryOnNextPage) {
                        // So Back from a restarted login page doesn't return to the previous session's pages
                        clearHistoryOnNextPage = false
                        view?.clearHistory()
                    }
                    if (loginInProgress) checkForLogin()
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    if (loginInProgress) checkForLogin()
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && loginInProgress) {
                        AppLog.w(this@MainActivity, name,
                            "Login page failed to load: ${error?.description} (${pageName(request.url)})")
                    }
                }

                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                    super.onReceivedHttpError(view, request, response)
                    if (request?.isForMainFrame == true && loginInProgress) {
                        AppLog.w(this@MainActivity, name,
                            "Login page returned HTTP ${response?.statusCode} (${pageName(request.url)})")
                    }
                }
            }
        }

        fun startLogin() {
            AppLog.i(this@MainActivity, name, "Opening the login page")
            loginInProgress = true
            clearHistoryOnNextPage = true
            closePopup()
            webView.loadUrl(loginUrl)
            loginCheckHandler.removeCallbacks(pollRunnable)
            loginCheckHandler.post(pollRunnable)
        }

        /** Stops the login check and unloads the site, so its scripts don't keep running hidden. */
        fun finish() {
            loginInProgress = false
            loginCheckHandler.removeCallbacks(pollRunnable)
            closePopup()
            webView.stopLoading()
            webView.loadUrl("about:blank")
        }

        private fun checkForLogin() {
            if (service == "claude") checkClaudeLoginCookies(this) else attemptChatGptSessionExtraction(this)
        }

        /** Returns true if there was a popup to close. */
        fun closePopup(): Boolean {
            val view = popup ?: return false
            popup = null
            try {
                frame.removeView(view)
                view.destroy()
            } catch (e: Exception) {
                Log.w("ClaudeWidget", "Error removing $name popup WebView", e)
            }
            return true
        }

        /** Google sign-in opens in a popup window, shown on top of this service's page only. */
        private fun openPopup(resultMsg: Message?) {
            closePopup()
            val newWebView = WebView(this@MainActivity)
            popup = newWebView
            applyBrowserSettings(newWebView)

            newWebView.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    if (window === popup) closePopup()
                }
            }
            newWebView.webViewClient = WebViewClient()

            frame.addView(newWebView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = newWebView
            resultMsg?.sendToTarget()
        }
    }

    /** Settings shared by the login pages and their Google sign-in popups. */
    private fun applyBrowserSettings(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            val defaultAgent = userAgentString
            userAgentString = defaultAgent.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
    }

    /** Host and path only: login URLs can carry one-time codes in the query string. */
    private fun pageName(url: Uri?) = "${url?.host}${url?.path}"

    private fun hasValidClaudeSessionKey(cookies: String?): Boolean {
        if (cookies.isNullOrEmpty()) return false
        val regex = Regex("""(?:^|;\s*)sessionKey=([^;]+)""")
        val match = regex.find(cookies) ?: return false
        val value = match.groupValues[1].trim()
        return value.isNotEmpty() && value != "deleted" && value != "\"\"" && value != "null"
    }

    private fun checkClaudeLoginCookies(browser: LoginBrowser) {
        if (!browser.loginInProgress) return
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (hasValidClaudeSessionKey(cookies)) {
            val userAgent = browser.webView.settings.userAgentString
            completeLogin(browser) {
                putString("saved_cookies", cookies)
                putString("user_agent", userAgent)
            }
        }
    }

    private fun attemptChatGptSessionExtraction(browser: LoginBrowser) {
        if (!browser.loginInProgress) return
        browser.webView.evaluateJavascript("""
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

    private fun handleChatGptTokenReceived(token: String) {
        if (token.length < 20) return

        // Called from the JS bridge and shouldInterceptRequest, which both run off the main
        // thread. WebView methods throw there, so do everything on the UI thread.
        runOnUiThread {
            if (!chatGptBrowser.loginInProgress) return@runOnUiThread
            val cookies = CookieManager.getInstance().getCookie("https://chatgpt.com")
            val userAgent = chatGptBrowser.webView.settings.userAgentString
            completeLogin(chatGptBrowser) {
                putString("chatgpt_access_token", token)
                putString("chatgpt_saved_cookies", cookies)
                putString("chatgpt_user_agent", userAgent)
            }
        }
    }

    /**
     * Saves a detected login and starts a refresh. Also drops the service's last error, so the new login
     * doesn't show as failed (on the widget or the Connected screen) before that refresh finishes.
     */
    private fun completeLogin(browser: LoginBrowser, save: SharedPreferences.Editor.() -> Unit) {
        browser.finish()

        val prefix = keyPrefix(browser.service)
        val editor = sharedPrefs.edit()
        editor.save()
        if (sharedPrefs.getString("${prefix}session_pct", null) == "Error") {
            editor.remove("${prefix}session_pct")
                .remove("${prefix}session_reset")
                .remove("${prefix}weekly_pct")
                .remove("${prefix}weekly_reset")
        }
        editor.apply()
        CookieManager.getInstance().flush()
        AppLog.i(this, serviceName(browser.service), "Login detected, session saved")

        if (currentTab == browser.service) {
            showSuccessScreen()
        }
        UpdateWidgetWorker.enqueueWork(this)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
        loginCheckHandler.removeCallbacksAndMessages(null)
        claudeBrowser.closePopup()
        chatGptBrowser.closePopup()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val browser = browserFor(currentTab)
        if (browser.closePopup()) {
            return
        }
        if (findViewById<View>(R.id.loadingLayout).visibility == View.VISIBLE && browser.webView.canGoBack()) {
            browser.webView.goBack()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}
