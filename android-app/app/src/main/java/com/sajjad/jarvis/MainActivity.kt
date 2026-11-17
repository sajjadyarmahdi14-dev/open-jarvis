package com.sajjad.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity(), JarvisVoiceService.VoiceEventListener {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineBanner: LinearLayout
    private lateinit var splashContainer: FrameLayout
    private lateinit var tvSplashStatus: TextView
    private lateinit var splashProgressBar: ProgressBar
    private lateinit var cardTermuxOffline: LinearLayout
    private lateinit var btnStartTermux: Button
    private lateinit var btnRetrySplash: Button

    private var bridge: JarvisAndroidBridge? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var healthJob: Job? = null
    private var isUrlLoaded = false

    private val targetUrl = "http://127.0.0.1:8765"
    private val permissionsCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initApp(savedInstanceState)
        } catch (t: Throwable) {
            Log.e("Jarvis", "Fatal crash during MainActivity initialization", t)
            renderCrashScreen(t)
        }
    }

    private fun initApp(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        applyEdgeToEdgeDarkTheme()

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        offlineBanner = findViewById(R.id.offlineBanner)
        splashContainer = findViewById(R.id.splashContainer)
        tvSplashStatus = findViewById(R.id.tvSplashStatus)
        splashProgressBar = findViewById(R.id.splashProgressBar)
        cardTermuxOffline = findViewById(R.id.cardTermuxOffline)
        btnStartTermux = findViewById(R.id.btnStartTermux)
        btnRetrySplash = findViewById(R.id.btnRetrySplash)

        requestRequiredPermissions()
        promptIgnoreBatteryOptimizations()
        startVoiceService()

        initWebView()
        setupSwipeRefresh()

        btnStartTermux.setOnClickListener { launchTermuxServer() }
        btnRetrySplash.setOnClickListener {
            cardTermuxOffline.visibility = View.GONE
            splashProgressBar.visibility = View.VISIBLE
            tvSplashStatus.text = getString(R.string.init_core_systems)
            startHealthCheckLoop()
        }

        handleIncomingIntent(intent)
        startHealthCheckLoop()
    }

    override fun onResume() {
        super.onResume()
        JarvisVoiceService.serviceListener = this
    }

    override fun onPause() {
        super.onPause()
        if (JarvisVoiceService.serviceListener === this) {
            JarvisVoiceService.serviceListener = null
        }
    }

    private fun applyEdgeToEdgeDarkTheme() {
        try {
            window?.statusBarColor = ContextCompat.getColor(this, R.color.bg_dark)
            window?.navigationBarColor = ContextCompat.getColor(this, R.color.bg_dark)

            window?.decorView?.post {
                try {
                    val decor = window?.decorView ?: return@post
                    val win = window ?: return@post
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowCompat.getInsetsController(win, decor)?.apply {
                            isAppearanceLightStatusBars = false
                            isAppearanceLightNavigationBars = false
                        }
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        @Suppress("DEPRECATION")
                        decor.systemUiVisibility = 0
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun renderCrashScreen(t: Throwable) {
        try {
            val sw = java.io.StringWriter()
            t.printStackTrace(java.io.PrintWriter(sw))
            val stackTrace = sw.toString()

            val scrollView = android.widget.ScrollView(this).apply {
                setBackgroundColor(0xFF080C14.toInt())
                setPadding(40, 60, 40, 60)
            }
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val titleView = TextView(this).apply {
                text = "⚠️ Jarvis Diagnostics Recovery"
                setTextColor(0xFF00F0FF.toInt())
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 20)
            }
            val msgView = TextView(this).apply {
                text = "Startup Exception:\n\n$stackTrace"
                setTextColor(0xFFF1F5F9.toInt())
                textSize = 13f
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
            }
            layout.addView(titleView)
            layout.addView(msgView)
            scrollView.addView(layout)
            setContentView(scrollView)
        } catch (_: Throwable) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(JarvisForegroundService.EXTRA_START_VOICE, false) == true) {
            startListeningVoice()
        }
    }

    // -----------------------------------------------------------------------
    // Always-On Foreground Service & Xiaomi HyperOS Battery Exemption
    // -----------------------------------------------------------------------

    fun startVoiceService() {
        try {
            val serviceIntent = Intent(this, JarvisVoiceService::class.java).apply {
                action = JarvisVoiceService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            Log.w("Jarvis", "startVoiceService error: ${t.message}")
        }
    }

    private fun promptIgnoreBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val prefs = getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    val prompted = prefs.getBoolean("battery_optimization_requested", false)
                    if (!prompted) {
                        prefs.edit().putBoolean("battery_optimization_requested", true).apply()
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("Jarvis", "Battery optimization prompt failed: ${e.message}")
        }
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        val checks = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.VIBRATE
        )
        for (perm in checks) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), permissionsCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            if (requestCode == permissionsCode) {
                startVoiceService()
            }
        } catch (_: Throwable) {}
    }

    // -----------------------------------------------------------------------
    // WebView & JavaScript Bridge Setup
    // -----------------------------------------------------------------------

    private fun initWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.allowContentAccess = true
        settings.allowFileAccess = true

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipeRefresh.isRefreshing = false
                val st = if (JarvisVoiceService.isServiceMuted) "paused" else "listening_wake"
                webView.evaluateJavascript("if (window.setWakeStatus) window.setWakeStatus('$st');", null)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    if (splashContainer.visibility != View.VISIBLE) {
                        offlineBanner.visibility = View.VISIBLE
                    }
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        bridge = JarvisAndroidBridge(
            activity = this,
            onStartVoice = { startListeningVoice() },
            onStopVoice = { stopListeningVoice() }
        )
        webView.addJavascriptInterface(bridge!!, "JarvisNative")
        webView.addJavascriptInterface(bridge!!, "JarvisAndroid")
        webView.addJavascriptInterface(bridge!!, "AndroidJarvis")
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.cyan_primary))
        swipeRefresh.setProgressBackgroundColorSchemeColor(ContextCompat.getColor(this, R.color.bg_card))
        swipeRefresh.setOnRefreshListener {
            scope.launch {
                val isOnline = withContext(Dispatchers.IO) { checkServerOnline() }
                if (isOnline) {
                    offlineBanner.visibility = View.GONE
                    isUrlLoaded = true
                    webView.reload()
                } else {
                    offlineBanner.visibility = View.VISIBLE
                    swipeRefresh.isRefreshing = false
                }
            }
        }

        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webView.scrollY > 0
        }
    }

    private fun startHealthCheckLoop() {
        healthJob?.cancel()
        val startTime = System.currentTimeMillis()
        var hasTriggeredAutoStart = false

        healthJob = scope.launch {
            while (isActive) {
                val isOnline = withContext(Dispatchers.IO) { checkServerOnline() }

                if (isOnline) {
                    offlineBanner.visibility = View.GONE
                    if (splashContainer.visibility == View.VISIBLE) {
                        splashContainer.animate()
                            .alpha(0f)
                            .setDuration(350)
                            .withEndAction {
                                splashContainer.visibility = View.GONE
                                splashContainer.alpha = 1f
                            }
                            .start()
                    }
                    if (!isUrlLoaded) {
                        isUrlLoaded = true
                        webView.loadUrl(targetUrl)
                    }
                    delay(4000)
                } else {
                    if (!hasTriggeredAutoStart) {
                        hasTriggeredAutoStart = true
                        tvSplashStatus.text = getString(R.string.init_core_systems)
                        launchTermuxServer()
                    }

                    if (splashContainer.visibility == View.VISIBLE) {
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed >= 15000) {
                            cardTermuxOffline.visibility = View.VISIBLE
                            splashProgressBar.visibility = View.GONE
                            tvSplashStatus.text = "ارتباط با هسته هوش مصنوعی برقرار نشد"
                        }
                    } else {
                        offlineBanner.visibility = View.VISIBLE
                    }
                    delay(800)
                }
            }
        }
    }

    private fun checkServerOnline(): Boolean {
        return try {
            val url = URL("$targetUrl/api/config")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 700
            conn.readTimeout = 700
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            try {
                val urlFallback = URL("$targetUrl/api/status")
                val connFallback = urlFallback.openConnection() as HttpURLConnection
                connFallback.connectTimeout = 700
                connFallback.readTimeout = 700
                val codeFallback = connFallback.responseCode
                connFallback.disconnect()
                codeFallback in 200..299
            } catch (_: Exception) {
                false
            }
        }
    }

    fun launchTermuxServer() {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/home/.openjarvis/start_server.sh")
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home/projects/open-jarvis")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            startService(intent)
        } catch (_: Exception) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.termux")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            } catch (_: Exception) {}
        }
    }

    // -----------------------------------------------------------------------
    // Voice Service Events -> Live HUD Sync
    // -----------------------------------------------------------------------

    override fun onWakeStateChanged(state: String) {
        runOnUiThread {
            webView.evaluateJavascript("if (window.setWakeStatus) window.setWakeStatus('$state');", null)
        }
    }

    override fun onSpeechStarted() {
        runOnUiThread {
            webView.evaluateJavascript("if (window.onSpeechStarted) window.onSpeechStarted();", null)
        }
    }

    override fun onSpeechEnded() {
        runOnUiThread {
            webView.evaluateJavascript("if (window.onSpeechEnded) window.onSpeechEnded();", null)
        }
    }

    override fun onPartialSpeech(text: String) {
        runOnUiThread {
            val escaped = escapeJs(text)
            webView.evaluateJavascript("if (window.onPartialSpeech) window.onPartialSpeech('$escaped');", null)
        }
    }

    override fun onFinalSpeech(userText: String) {
        runOnUiThread {
            val escaped = escapeJs(userText)
            webView.evaluateJavascript("if (window.appendUserMessage) window.appendUserMessage('$escaped'); else if (window.handleWakeCommand) window.handleWakeCommand('$escaped');", null)
        }
    }

    override fun onAssistantResponse(response: String) {
        runOnUiThread {
            val escaped = escapeJs(response)
            webView.evaluateJavascript("if (window.appendAssistantMessage) window.appendAssistantMessage('$escaped');", null)
        }
    }

    override fun onTtsStarted() {
        runOnUiThread {
            webView.evaluateJavascript("if (window.onTtsStarted) window.onTtsStarted();", null)
        }
    }

    override fun onTtsFinished() {
        runOnUiThread {
            webView.evaluateJavascript("if (window.onTtsFinished) window.onTtsFinished();", null)
        }
    }

    fun startListeningVoice() {
        JarvisVoiceService.forceStartListening(this)
    }

    fun stopListeningVoice() {
        // Managed by JarvisVoiceService
    }

    fun setWakeWordListening(enabled: Boolean) {
        if (JarvisVoiceService.isServiceMuted == enabled) {
            val intent = Intent(this, JarvisVoiceService::class.java).apply {
                action = JarvisVoiceService.ACTION_TOGGLE_MUTE
            }
            startService(intent)
        }
    }

    fun isWakeWordListening(): Boolean = !JarvisVoiceService.isServiceMuted

    fun launchApp(query: String): String = AppLauncher.launchApp(this, query)

    fun listInstalledApps(): String = AppLauncher.listInstalledApps(this)

    private fun escapeJs(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    override fun onDestroy() {
        try {
            healthJob?.cancel()
            bridge?.shutdown()
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
