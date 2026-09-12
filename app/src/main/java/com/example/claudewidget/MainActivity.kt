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
        
        // Enable WebView debugging for Chrome DevTools
        WebView.setWebContentsDebuggingEnabled(true)

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
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            val defaultAgent = userAgentString
            userAgentString = defaultAgent.replace("; wv", "")
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportMultipleWindows(true)
                    javaScriptCanOpenWindowsAutomatically = true
                    val defaultAgent = userAgentString
                    userAgentString = defaultAgent.replace("; wv", "")
                }
                
                newWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(window: WebView?) {
                        findViewById<android.widget.FrameLayout>(R.id.root_frame).removeView(newWebView)
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

    private fun checkLoginCookies(view: WebView?) {
        if (loginDetected) return
        val cookies = CookieManager.getInstance().getCookie("https://claude.ai")
        if (cookies != null && cookies.contains("sessionKey=") && !cookies.contains("sessionKey=;") && !cookies.contains("sessionKey=deleted")) {
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

    private fun logErrorToScreen(msg: String) {
        runOnUiThread {
            val errorText = findViewById<android.widget.TextView>(R.id.errorText)
            errorText.visibility = View.VISIBLE
            val currentText = errorText.text.toString()
            errorText.text = if (currentText.isEmpty()) msg else "$currentText\n$msg"
        }
    }
}
