package com.sajjad.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var offlineLayout: View
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnRetry: Button

    private var speechRecognizer: SpeechRecognizer? = null
    private var bridge: JarvisBridge? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val targetUrl = "http://127.0.0.1:8765"
    private val permissionsCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        offlineLayout = findViewById(R.id.offlineContainer)
        progressBar = findViewById(R.id.progressBar)
        statusText = findViewById(R.id.tvStatus)
        btnStart = findViewById(R.id.btnStartServer)
        btnRetry = findViewById(R.id.btnRetry)

        requestRequiredPermissions()
        initWebView()
        initSpeech()
        startBackgroundService()

        btnRetry.setOnClickListener { checkAndLoadServer() }
        btnStart.setOnClickListener { launchTermuxServer() }

        checkAndLoadServer()
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
            Manifest.permission.READ_CONTACTS
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                request.grant(request.resources)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    showOfflineScreen("Connection error: ${error?.description}")
                }
            }
        }

        bridge = JarvisBridge(this) { startListeningVoice() }
        webView.addJavascriptInterface(bridge!!, "AndroidJarvis")
    }

    private fun initSpeech() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        Toast.makeText(this@MainActivity, "Listening...", Toast.LENGTH_SHORT).show()
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            val text = matches[0].replace("'", "\\'")
                            webView.evaluateJavascript("sendMessage('$text');", null)
                        }
                    }
                    override fun onError(error: Int) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun startListeningVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer?.startListening(intent)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), permissionsCode)
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

    private fun checkAndLoadServer() {
        progressBar.visibility = View.VISIBLE
        statusText.text = getString(R.string.connecting)

        scope.launch {
            val isOnline = withContext(Dispatchers.IO) {
                try {
                    val conn = URL("$targetUrl/api/health").openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500
                    conn.readTimeout = 1500
                    conn.responseCode in 200..299
                } catch (_: Exception) {
                    false
                }
            }

            progressBar.visibility = View.GONE
            if (isOnline) {
                offlineLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                webView.loadUrl(targetUrl)
            } else {
                showOfflineScreen(getString(R.string.daemon_offline))
            }
        }
    }

    private fun showOfflineScreen(msg: String) {
        webView.visibility = View.GONE
        offlineLayout.visibility = View.VISIBLE
        statusText.text = msg
    }

    fun launchTermuxServer() {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
                putExtra(
                    "com.termux.RUN_COMMAND_ARGUMENTS",
                    arrayOf("-m", "openjarvis.web.server")
                )
                putExtra(
                    "com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home/projects/open-jarvis"
                )
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            startService(intent)
            Toast.makeText(this, "Starting server in Termux...", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.termux")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Termux not found", Toast.LENGTH_SHORT).show()
            }
        }

        scope.launch {
            delay(3000)
            checkAndLoadServer()
        }
    }

    override fun onDestroy() {
        bridge?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
