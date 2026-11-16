package com.sajjad.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowInsetsController
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
    private var isUrlLoaded = false

    private val targetUrl = "http://127.0.0.1:8765"
    private val permissionsCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyEdgeToEdgeDarkTheme()
        setContentView(R.layout.activity_main)

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
        startBackgroundService()

        btnStartTermux.setOnClickListener { launchTermuxServer() }
        btnRetrySplash.setOnClickListener {
            cardTermuxOffline.visibility = View.GONE
            splashProgressBar.visibility = View.VISIBLE
            tvSplashStatus.text = getString(R.string.connecting_core)
            startHealthCheckLoop()
        }

        handleIncomingIntent(intent)
        startHealthCheckLoop()
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

    private fun applyEdgeToEdgeDarkTheme() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.bg_dark)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.bg_dark)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
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
                } else {
                    if (splashContainer.visibility == View.VISIBLE) {
                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed >= 5000) {
                            cardTermuxOffline.visibility = View.VISIBLE
                            splashProgressBar.visibility = View.GONE
                            tvSplashStatus.text = "ارتباط با هسته هوش مصنوعی برقرار نشد"
                        }
                    } else {
                        offlineBanner.visibility = View.VISIBLE
                    }
                }

                delay(1500)
            }
        }
    }

    private fun checkServerOnline(): Boolean {
        return try {
            val url = URL("$targetUrl/api/status")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1200
            conn.readTimeout = 1200
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (_: Exception) {
            try {
                val urlFallback = URL("$targetUrl/api/health")
                val connFallback = urlFallback.openConnection() as HttpURLConnection
                connFallback.connectTimeout = 1200
                connFallback.readTimeout = 1200
                val codeFallback = connFallback.responseCode
                connFallback.disconnect()
                codeFallback in 200..299
            } catch (_: Exception) {
                false
            }
        }
    }

    fun launchTermuxServer() {
        tvSplashStatus.text = getString(R.string.server_starting)
        splashProgressBar.visibility = View.VISIBLE
        cardTermuxOffline.visibility = View.GONE

        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra(
                    "com.termux.RUN_COMMAND_ARGUMENTS",
                    arrayOf(
                        "-c",
                        "cd /data/data/com.termux/files/home/projects/open-jarvis && PYTHONPATH=src python -m openjarvis.web.server --port 8765"
                    )
                )
                putExtra(
                    "com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home/projects/open-jarvis"
                )
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            startService(intent)
            Toast.makeText(this, "دستور اجرای سرور به ترموکس ارسال شد", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.termux")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "برنامه ترموکس یافت نشد", Toast.LENGTH_LONG).show()
            }
        }

        startHealthCheckLoop()
    }

    fun startListeningVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), permissionsCode)
            return
        }

        runOnUiThread {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            webView.evaluateJavascript("if (window.onSpeechReady) window.onSpeechReady();", null)
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
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                            if (!partial.isNullOrBlank()) {
                                val escaped = escapeJs(partial)
                                webView.evaluateJavascript("if (window.setChatInput) window.setChatInput('$escaped');", null)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val text = matches?.firstOrNull()
                            if (!text.isNullOrBlank()) {
                                val escaped = escapeJs(text)
                                webView.evaluateJavascript(
                                    "if (window.setChatInput) window.setChatInput('$escaped'); if (window.onSpeechResults) window.onSpeechResults('$escaped');",
                                    null
                                )
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
                    putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("fa-IR", "en-US"))
                }
                speechRecognizer?.startListening(intent)
                Toast.makeText(this, "در حال گوش دادن به فرمان صوتی...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "خطای فعال‌سازی میکروفون: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopListeningVoice() {
        runOnUiThread {
            try {
                speechRecognizer?.stopListening()
            } catch (_: Exception) {}
        }
    }

    private fun startBackgroundService() {
        try {
            val serviceIntent = Intent(this, JarvisForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (_: Exception) {}
    }

    private fun escapeJs(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    override fun onDestroy() {
        healthJob?.cancel()
        bridge?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
