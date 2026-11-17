package com.sajjad.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import android.widget.Toast
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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var offlineBanner: LinearLayout
    private lateinit var splashContainer: FrameLayout
    private lateinit var tvSplashStatus: TextView
    private lateinit var splashProgressBar: ProgressBar
    private lateinit var cardTermuxOffline: LinearLayout
    private lateinit var btnStartTermux: Button
    private lateinit var btnRetrySplash: Button

    private var speechRecognizer: SpeechRecognizer? = null
    private var bridge: JarvisAndroidBridge? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var healthJob: Job? = null
    private var restartListeningJob: Job? = null
    private var isUrlLoaded = false

    // Hands-Free Wake-Word ("Jarvis") Engine States
    enum class WakeState {
        WAITING_FOR_WAKE_WORD,
        WAITING_FOR_COMMAND,
        PROCESSING
    }

    private var currentWakeState = WakeState.WAITING_FOR_WAKE_WORD
    private var isWakeWordListeningEnabled = true
    private var isTtsSpeaking = false

    private val targetUrl = "http://127.0.0.1:8765"
    private val permissionsCode = 101

    // Smart Aliases for 100% App & Game Launching on Xiaomi HyperOS / Android 14
    private val appAliases = mapOf(
        "تلگرام" to "telegram",
        "واتساپ" to "whatsapp",
        "واتس اپ" to "whatsapp",
        "واتس‌اپ" to "whatsapp",
        "اینستاگرام" to "instagram",
        "اینستا" to "instagram",
        "یوتیوب" to "youtube",
        "یوتوب" to "youtube",
        "ماینکرفت" to "minecraft",
        "ماینکرافت" to "minecraft",
        "دوربین" to "camera",
        "عکس" to "camera",
        "تنظیمات" to "settings",
        "گالری" to "gallery",
        "جیمیل" to "gmail",
        "ایمیل" to "gmail",
        "کروم" to "chrome",
        "مرورگر" to "chrome",
        "ماشین حساب" to "calculator",
        "ساعت" to "clock",
        "آلارم" to "clock",
        "نقشه" to "maps",
        "مخاطبین" to "contacts",
        "پیامک" to "messages",
        "پیام‌ها" to "messages",
        "پیام ها" to "messages",
        "تلفن" to "phone",
        "تماس" to "phone",
        "ترموکس" to "termux",
        "فایل" to "files",
        "فایل‌ها" to "files",
        "مدیریت فایل" to "files",
        "موسیقی" to "spotify",
        "آهنگ" to "spotify"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            initApp(savedInstanceState)
        } catch (t: Throwable) {
            android.util.Log.e("Jarvis", "Fatal crash during MainActivity initialization", t)
            renderCrashScreen(t)
        }
    }

    private fun initApp(savedInstanceState: Bundle?) {
        // Fix 1: setContentView MUST be called BEFORE any window insets/decorView styling
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

        // Fix 2: Zero-touch background server auto-start & poll
        startHealthCheckLoop()
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
                val recordIndex = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
                if (recordIndex != -1 && grantResults.getOrNull(recordIndex) == PackageManager.PERMISSION_GRANTED) {
                    scheduleRestartListening(500L)
                }
                val postNotifIndex = permissions.indexOf(Manifest.permission.POST_NOTIFICATIONS)
                if (postNotifIndex != -1 && grantResults.getOrNull(postNotifIndex) == PackageManager.PERMISSION_GRANTED) {
                    startBackgroundService()
                }
            }
        } catch (_: Throwable) {}
    }

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
                if (isWakeWordListeningEnabled) {
                    scheduleRestartListening(600L)
                }
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
        // Expose bridge across all requested namespaces
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

    // Zero-Touch Server Health-Check & Auto-Boot Loop
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
                    // Server online: start wake word listening if enabled
                    if (isWakeWordListeningEnabled && !isTtsSpeaking) {
                        scheduleRestartListening(500L)
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

    // Wake Word ("Jarvis") Engine & Continuous Speech Recognizer
    fun setWakeWordListening(enabled: Boolean) {
        isWakeWordListeningEnabled = enabled
        if (enabled) {
            currentWakeState = WakeState.WAITING_FOR_WAKE_WORD
            scheduleRestartListening(200L)
        } else {
            stopListeningVoice()
        }
    }

    fun isWakeWordListening(): Boolean = isWakeWordListeningEnabled

    fun onTtsStarted() {
        isTtsSpeaking = true
        restartListeningJob?.cancel()
        runOnUiThread {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Throwable) {}
        }
    }

    fun onTtsFinished() {
        isTtsSpeaking = false
        if (isWakeWordListeningEnabled) {
            scheduleRestartListening(300L)
        }
    }

    private fun scheduleRestartListening(delayMs: Long = 300L) {
        if (!isWakeWordListeningEnabled || isTtsSpeaking || isFinishing) return
        restartListeningJob?.cancel()
        restartListeningJob = scope.launch {
            delay(delayMs)
            if (isActive && isWakeWordListeningEnabled && !isTtsSpeaking && !isFinishing) {
                startContinuousListening()
            }
        }
    }

    fun startListeningVoice() {
        currentWakeState = WakeState.WAITING_FOR_COMMAND
        startContinuousListening()
    }

    private fun startContinuousListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            return
        }
        if (isTtsSpeaking || isFinishing) {
            return
        }

        runOnUiThread {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            val st = if (currentWakeState == WakeState.WAITING_FOR_COMMAND) "listening_cmd" else "listening_wake"
                            webView.evaluateJavascript("if (window.setWakeStatus) window.setWakeStatus('$st');", null)
                        }

                        override fun onBeginningOfSpeech() {
                            webView.evaluateJavascript("if (window.onSpeechStarted) window.onSpeechStarted();", null)
                        }

                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}

                        override fun onEndOfSpeech() {
                            webView.evaluateJavascript("if (window.onSpeechEnded) window.onSpeechEnded();", null)
                        }

                        override fun onError(error: Int) {
                            webView.evaluateJavascript("if (window.onSpeechError) window.onSpeechError($error);", null)
                            if (isWakeWordListeningEnabled && !isTtsSpeaking) {
                                scheduleRestartListening(350L)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: return

                            if (currentWakeState == WakeState.WAITING_FOR_WAKE_WORD) {
                                if (containsWakeWord(text)) {
                                    val remainder = stripWakeWord(text)
                                    if (remainder.length > 2) {
                                        // Case A: Inline command caught in partial speech!
                                        currentWakeState = WakeState.PROCESSING
                                        playActivationChime()
                                        bridge?.vibrate(40)
                                        stopListeningVoice()
                                        val escaped = escapeJs(remainder)
                                        webView.evaluateJavascript("if (window.handleWakeCommand) window.handleWakeCommand('$escaped');", null)
                                    }
                                }
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()?.trim() ?: ""

                            if (text.isBlank()) {
                                scheduleRestartListening(250L)
                                return
                            }

                            if (currentWakeState == WakeState.WAITING_FOR_WAKE_WORD) {
                                if (containsWakeWord(text)) {
                                    val remainder = stripWakeWord(text)
                                    if (remainder.isNotBlank()) {
                                        // Case A: Wake word + inline command
                                        currentWakeState = WakeState.PROCESSING
                                        playActivationChime()
                                        bridge?.vibrate(40)
                                        val escaped = escapeJs(remainder)
                                        webView.evaluateJavascript("if (window.handleWakeCommand) window.handleWakeCommand('$escaped');", null)
                                    } else {
                                        // Case B: Only wake word
                                        currentWakeState = WakeState.WAITING_FOR_COMMAND
                                        playActivationChime()
                                        bridge?.vibrate(40)
                                        bridge?.speak("At your service, sir")
                                        webView.evaluateJavascript("if (window.setWakeStatus) window.setWakeStatus('listening_cmd');", null)
                                        scheduleRestartListening(1100L)
                                    }
                                } else {
                                    scheduleRestartListening(250L)
                                }
                            } else if (currentWakeState == WakeState.WAITING_FOR_COMMAND) {
                                currentWakeState = WakeState.WAITING_FOR_WAKE_WORD
                                bridge?.vibrate(30)
                                val escaped = escapeJs(text)
                                webView.evaluateJavascript("if (window.handleWakeCommand) window.handleWakeCommand('$escaped');", null)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "fa-IR"))
                }
                speechRecognizer?.startListening(intent)
            } catch (_: Throwable) {
                scheduleRestartListening(1500L)
            }
        }
    }

    fun stopListeningVoice() {
        runOnUiThread {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("jarvis") || text.contains("جارویس")
    }

    private fun stripWakeWord(text: String): String {
        var cleaned = text
        val patterns = arrayOf(
            "(?i)^\\s*(hey|hi|hello|ok|okay|dear)?\\s*jarvis[,.!?\\s]*",
            "(?i)[,.!?\\s]*jarvis[,.!?\\s]*",
            "^\\s*(سلام|هی|درود)?\\s*جارویس[,.!?\\s]*",
            "[,.!?\\s]*جارویس[,.!?\\s]*"
        )
        for (pattern in patterns) {
            cleaned = cleaned.replaceFirst(Regex(pattern), " ")
        }
        return cleaned.trim()
    }

    private fun playActivationChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            scope.launch {
                delay(300)
                try { tg.release() } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    // Bulletproof Universal App & Game Launcher (Foreground Context for Xiaomi HyperOS)
    fun launchApp(query: String): String {
        return try {
            var clean = query.trim().lowercase(Locale.ROOT)
            val stripPrefixes = arrayOf(
                "open", "launch", "start", "run", "play",
                "باز کن", "بازکن", "اجرا کن", "اجراکن", "برنامه", "بازی"
            )
            for (p in stripPrefixes) {
                if (clean.startsWith(p)) {
                    clean = clean.removePrefix(p).trim()
                }
            }
            val normalized = appAliases[clean] ?: appAliases[query.trim()] ?: clean

            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = packageManager.queryIntentActivities(mainIntent, 0)

            var matched = launchableApps.firstOrNull {
                val label = it.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
                label == normalized || label == clean
            }
            if (matched == null) {
                matched = launchableApps.firstOrNull {
                    val label = it.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
                    label.startsWith(normalized) || label.startsWith(clean)
                }
            }
            if (matched == null) {
                matched = launchableApps.firstOrNull {
                    val label = it.loadLabel(packageManager).toString().lowercase(Locale.ROOT)
                    label.contains(normalized) || label.contains(clean)
                }
            }
            if (matched == null) {
                matched = launchableApps.firstOrNull {
                    val pkg = it.activityInfo.packageName.lowercase(Locale.ROOT)
                    pkg.contains(normalized) || pkg.contains(clean)
                }
            }

            if (matched != null) {
                val pkg = matched.activityInfo.packageName
                val label = matched.loadLabel(packageManager).toString()
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    return """{"success": true, "package": "$pkg", "label": "$label"}"""
                }
            }
            """{"success": false, "error": "App not found for query: $query"}"""
        } catch (e: Exception) {
            """{"success": false, "error": "${e.message}"}"""
        }
    }

    fun listInstalledApps(): String {
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launchableApps = packageManager.queryIntentActivities(mainIntent, 0)
            val sb = StringBuilder("[")
            launchableApps.forEachIndexed { index, resolveInfo ->
                val label = resolveInfo.loadLabel(packageManager).toString().replace("\"", "\\\"")
                val pkg = resolveInfo.activityInfo.packageName
                sb.append("""{"label": "$label", "package": "$pkg"}""")
                if (index < launchableApps.size - 1) sb.append(",")
            }
            sb.append("]")
            sb.toString()
        } catch (e: Exception) {
            "[]"
        }
    }

    private fun startBackgroundService() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
            }
            val serviceIntent = Intent(this, JarvisForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (t: Throwable) {
            android.util.Log.w("Jarvis", "startBackgroundService failed: ${t.message}")
        }
    }

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
            restartListeningJob?.cancel()
            bridge?.shutdown()
            speechRecognizer?.destroy()
        } catch (_: Throwable) {}
        super.onDestroy()
    }
}
