package com.sajjad.jarvis

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class JarvisAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val TAG = "JarvisAccessibility"

        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "JarvisAccessibilityService connected and ready for deep automation.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active event stream receiver
    }

    override fun onInterrupt() {
        Log.w(TAG, "JarvisAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // 1. Text Input & Auto-Send
    // -------------------------------------------------------------------------

    fun typeText(text: String, autoSend: Boolean = false): Boolean {
        val cleanText = UtteranceStitcher.sanitizeLiteralText(text)
        val root = rootInActiveWindow ?: return false
        val targetNode = findEditableNode(root) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (targetNode == null) {
            Log.w(TAG, "No editable or focused input node found in active window.")
            return false
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.i(TAG, "typeText performed: success=$success, text='$cleanText'")

        if (autoSend) {
            serviceScope.launch {
                delay(350)
                clickSendButton()
            }
        }
        return success
    }

    fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        val cls = node.className?.toString() ?: ""
        if (node.isEditable || cls.contains("EditText", ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    fun clickSendButton(): Boolean {
        val root = rootInActiveWindow ?: return false
        val sendKeywords = listOf("send", "ارسال", "search", "جستجو", "submit", "تایید")

        val match = findNodeByPredicate(root) { n ->
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            val viewId = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            sendKeywords.any { k -> text.contains(k) || desc.contains(k) || viewId.contains(k) }
        }

        if (match != null) {
            return clickNodeOrParent(match)
        }
        return false
    }

    // -------------------------------------------------------------------------
    // 2. Click Elements by Target Text
    // -------------------------------------------------------------------------

    fun clickElement(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val cleanTarget = targetText.trim().lowercase(Locale.ROOT)
        if (cleanTarget.isBlank()) return false

        val match = findNodeByPredicate(root) { n ->
            val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
            val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
            text.contains(cleanTarget) || desc.contains(cleanTarget)
        }

        if (match != null) {
            val clicked = clickNodeOrParent(match)
            Log.i(TAG, "clickElement '$targetText' clicked=$clicked")
            return clicked
        }
        Log.w(TAG, "clickElement could not find element matching '$targetText'")
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        // If not clickable flag, try clicking direct node as fallback
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findNodeByPredicate(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByPredicate(child, predicate)
            if (found != null) return found
        }
        return null
    }

    // -------------------------------------------------------------------------
    // 3. Automated Photo Capture (Shutter Click)
    // -------------------------------------------------------------------------

    fun takePhoto(context: Context): Boolean {
        val cameraIntent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        AppLauncher.launchWithOverlayFallback(context, cameraIntent)

        serviceScope.launch {
            // Wait 1500ms for camera app to launch and autofocus
            delay(1500)
            val root = rootInActiveWindow ?: return@launch
            val shutterKeywords = listOf("shutter", "take picture", "capture", "عکس", "شاتر", "camera_shutter")

            var shutterNode = findNodeByPredicate(root) { n ->
                val desc = (n.contentDescription?.toString() ?: "").lowercase(Locale.ROOT)
                val text = (n.text?.toString() ?: "").lowercase(Locale.ROOT)
                val id = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                shutterKeywords.any { k -> desc.contains(k) || text.contains(k) || id.contains(k) }
            }

            // Fallback: If no explicit shutter label found, search for circular/prominent clickable button at screen bottom
            if (shutterNode == null) {
                val displayMetrics = resources.displayMetrics
                val bottomThreshold = (displayMetrics.heightPixels * 0.70).toInt()
                shutterNode = findNodeByPredicate(root) { n ->
                    if (!n.isClickable) return@findNodeByPredicate false
                    val rect = Rect()
                    n.getBoundsInScreen(rect)
                    rect.top >= bottomThreshold && rect.width() >= 120 && rect.height() >= 120
                }
            }

            if (shutterNode != null) {
                val clicked = clickNodeOrParent(shutterNode)
                Log.i(TAG, "Shutter button clicked: $clicked")
            } else {
                Log.w(TAG, "Could not find camera shutter button.")
            }
        }
        return true
    }

    // -------------------------------------------------------------------------
    // 4. Screen Reading (Extract On-Screen Text)
    // -------------------------------------------------------------------------

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Screen content is currently empty or protected."
        val textList = mutableListOf<String>()
        collectScreenText(root, textList)
        val fullContent = textList.distinct().joinToString("\n")
        return if (fullContent.isBlank()) "No visible text detected on screen." else fullContent
    }

    private fun collectScreenText(node: AccessibilityNodeInfo?, outList: MutableList<String>) {
        if (node == null || !node.isVisibleToUser) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrEmpty() && text.length > 1) {
            outList.add(text)
        }
        if (!desc.isNullOrEmpty() && desc != text && desc.length > 1) {
            outList.add(desc)
        }

        for (i in 0 until node.childCount) {
            collectScreenText(node.getChild(i), outList)
        }
    }

    // -------------------------------------------------------------------------
    // 5. Global Actions & Gestures
    // -------------------------------------------------------------------------

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    fun scrollDown(): Boolean = performScrollGesture(scrollDown = true)

    fun scrollUp(): Boolean = performScrollGesture(scrollDown = false)

    private fun performScrollGesture(scrollDown: Boolean): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = (displayMetrics.widthPixels / 2).toFloat()
        val startY = if (scrollDown) (displayMetrics.heightPixels * 0.75f) else (displayMetrics.heightPixels * 0.25f)
        val endY = if (scrollDown) (displayMetrics.heightPixels * 0.25f) else (displayMetrics.heightPixels * 0.75f)

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 300)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    // -------------------------------------------------------------------------
    // 6. Live Semantic Screen Serializer & Closed-Loop Autonomous UI Execution
    // -------------------------------------------------------------------------

    fun serializeCurrentWindow(): Pair<List<String>, Map<Int, AccessibilityNodeInfo>> {
        val root = rootInActiveWindow ?: return Pair(emptyList(), emptyMap())
        val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()
        val serialized = mutableListOf<String>()
        var counter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            val text = node.text?.toString()?.replace("\n", " ")?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.replace("\n", " ")?.trim() ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isCheckable = node.isCheckable
            val isChecked = node.isChecked
            val className = node.className?.toString()?.substringAfterLast(".") ?: "View"

            val isInformative = text.isNotEmpty() || desc.isNotEmpty() || isClickable || isEditable || isCheckable
            if (isInformative) {
                val id = counter++
                nodeMap[id] = node
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val line = "[$id] $className | text=\"$text\" | desc=\"$desc\" | clickable=$isClickable | editable=$isEditable | checked=$isChecked | bounds=(${rect.left},${rect.top})"
                serialized.add(line)
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return Pair(serialized, nodeMap)
    }

    fun executeAtomicAction(
        actionType: String,
        params: JSONObject,
        nodeMap: Map<Int, AccessibilityNodeInfo>
    ): Boolean {
        Log.i(TAG, "executeAtomicAction: type=$actionType, params=$params")
        return when (actionType.lowercase(Locale.ROOT)) {
            "tap" -> {
                val nodeId = params.optInt("node_id", -1)
                val node = if (nodeId >= 0) nodeMap[nodeId] else null
                if (node != null) {
                    clickNodeOrParent(node)
                } else {
                    val targetText = params.optString("target_text", "")
                    if (targetText.isNotBlank()) clickElement(targetText) else false
                }
            }
            "input_text" -> {
                val rawText = params.optString("text", "")
                val cleanText = UtteranceStitcher.sanitizeLiteralText(rawText)
                val submit = params.optBoolean("submit", true)
                val nodeId = params.optInt("node_id", -1)
                val node = if (nodeId >= 0) nodeMap[nodeId] else findEditableNode(rootInActiveWindow)
                if (node != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
                    }
                    val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (submit) {
                        serviceScope.launch {
                            delay(350)
                            clickSendButton()
                        }
                    }
                    ok
                } else {
                    typeText(cleanText, submit)
                }
            }
            "scroll" -> {
                val dir = params.optString("direction", "down").lowercase(Locale.ROOT)
                if (dir == "up") scrollUp() else scrollDown()
            }
            "wait_and_tap" -> {
                val delayMs = params.optLong("delay_ms", 1500L)
                val nodeId = params.optInt("node_id", -1)
                serviceScope.launch {
                    delay(delayMs)
                    val target = if (nodeId >= 0) nodeMap[nodeId] else null
                    if (target != null) {
                        clickNodeOrParent(target)
                    } else {
                        clickSendButton()
                    }
                }
                true
            }
            "media_key" -> {
                val key = params.optString("key", "PLAY").uppercase(Locale.ROOT)
                when (key) {
                    "PLAY" -> DeviceController.playMusic(this)
                    "PAUSE" -> DeviceController.pauseMusic(this)
                    "NEXT" -> DeviceController.nextTrack(this)
                    "PREVIOUS" -> DeviceController.previousTrack(this)
                }
                true
            }
            "local_audio_uri" -> {
                val query = params.optString("query", "")
                if (query.isNotBlank()) {
                    DeviceController.playSong(this, query)
                }
                true
            }
            "back" -> goBack()
            "home" -> goHome()
            "done" -> true
            else -> false
        }
    }

    private fun postAgentStep(serverUrl: String, payload: JSONObject): JSONObject? {
        return try {
            val cleanUrl = serverUrl.trim().removeSuffix("/")
            val url = URL("$cleanUrl/api/agent/step")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 6000
            conn.readTimeout = 12000
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            if (code in 200..299) {
                val respStr = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                JSONObject(respStr)
            } else {
                Log.w(TAG, "postAgentStep HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "postAgentStep error: ${e.message}")
            null
        }
    }

    fun dumpUiTree(): Pair<JSONArray, Map<String, AccessibilityNodeInfo>> {
        val root = rootInActiveWindow ?: return Pair(JSONArray(), emptyMap())
        val nodeMap = mutableMapOf<String, AccessibilityNodeInfo>()
        val array = JSONArray()
        var counter = 0

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null || !node.isVisibleToUser) return

            val text = node.text?.toString()?.replace("\n", " ")?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.replace("\n", " ")?.trim() ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable || (node.className?.toString()?.contains("EditText", ignoreCase = true) == true)
            val isCheckable = node.isCheckable
            val isChecked = node.isChecked
            val className = node.className?.toString()?.substringAfterLast(".") ?: "View"

            val isInformative = text.isNotEmpty() || desc.isNotEmpty() || isClickable || isEditable || isCheckable
            if (isInformative) {
                val nodeId = "node_$counter"
                nodeMap[nodeId] = node
                nodeMap["$counter"] = node
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val obj = JSONObject().apply {
                    put("node_id", nodeId)
                    put("class", className)
                    put("text", text)
                    put("desc", desc)
                    put("clickable", isClickable)
                    put("editable", isEditable)
                    put("checked", isChecked)
                    put("x", rect.left)
                    put("y", rect.top)
                }
                array.put(obj)
                counter++
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return Pair(array, nodeMap)
    }

    fun executeUiAction(
        actionPayload: JSONObject,
        nodeMap: Map<String, AccessibilityNodeInfo> = emptyMap()
    ): Boolean {
        val action = actionPayload.optString("action", actionPayload.optString("type", "")).lowercase(Locale.ROOT)
        Log.i(TAG, "executeUiAction: action='$action', payload=$actionPayload")
        return when (action) {
            "click", "tap" -> {
                val nodeIdStr = actionPayload.optString("node_id", "")
                val nodeIdInt = actionPayload.optInt("node_id", -1)
                val targetKey = if (nodeIdStr.isNotBlank()) nodeIdStr else if (nodeIdInt >= 0) "node_$nodeIdInt" else ""
                val targetNode = if (targetKey.isNotBlank()) {
                    nodeMap[targetKey] ?: (if (nodeIdInt >= 0) nodeMap["$nodeIdInt"] else null)
                } else null

                if (targetNode != null) {
                    clickNodeOrParent(targetNode)
                } else {
                    val targetText = actionPayload.optString("target_text", actionPayload.optString("keyword", actionPayload.optString("target", "")))
                    if (targetText.isNotBlank()) clickElement(targetText) else false
                }
            }
            "type", "input_text" -> {
                val rawText = actionPayload.optString("text", actionPayload.optString("message", ""))
                val cleanText = UtteranceStitcher.sanitizeLiteralText(rawText)
                val submit = actionPayload.optBoolean("submit", actionPayload.optBoolean("auto_send", true))
                val nodeIdStr = actionPayload.optString("node_id", "")
                val nodeIdInt = actionPayload.optInt("node_id", -1)
                val targetKey = if (nodeIdStr.isNotBlank()) nodeIdStr else if (nodeIdInt >= 0) "node_$nodeIdInt" else ""
                val targetNode = if (targetKey.isNotBlank()) {
                    nodeMap[targetKey] ?: (if (nodeIdInt >= 0) nodeMap["$nodeIdInt"] else null)
                } else findEditableNode(rootInActiveWindow)

                if (targetNode != null) {
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanText)
                    }
                    val ok = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (submit) {
                        serviceScope.launch {
                            delay(400)
                            clickSendButton()
                        }
                    }
                    ok
                } else {
                    typeText(cleanText, submit)
                }
            }
            "scroll" -> {
                val dir = actionPayload.optString("direction", "down").lowercase(Locale.ROOT)
                if (dir == "up") scrollUp() else scrollDown()
            }
            "global" -> {
                val sub = actionPayload.optString("target", actionPayload.optString("type", "")).lowercase(Locale.ROOT)
                when (sub) {
                    "back" -> goBack()
                    "home" -> goHome()
                    "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                    "notifications" -> openNotifications()
                    else -> goBack()
                }
            }
            "back" -> goBack()
            "home" -> goHome()
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "notifications" -> openNotifications()
            "wait_and_tap" -> {
                val delayMs = actionPayload.optLong("delay_ms", 1500L)
                val nodeIdInt = actionPayload.optInt("node_id", -1)
                val nodeIdStr = actionPayload.optString("node_id", "")
                val targetKey = if (nodeIdStr.isNotBlank()) nodeIdStr else if (nodeIdInt >= 0) "node_$nodeIdInt" else ""
                serviceScope.launch {
                    delay(delayMs)
                    val target = if (targetKey.isNotBlank()) {
                        nodeMap[targetKey] ?: (if (nodeIdInt >= 0) nodeMap["$nodeIdInt"] else null)
                    } else null
                    if (target != null) {
                        clickNodeOrParent(target)
                    } else {
                        clickSendButton()
                    }
                }
                true
            }
            "done" -> true
            else -> {
                val intMap = mutableMapOf<Int, AccessibilityNodeInfo>()
                nodeMap.forEach { (k, v) ->
                    val cleanK = k.removePrefix("node_").toIntOrNull()
                    if (cleanK != null) intMap[cleanK] = v
                }
                executeAtomicAction(action, actionPayload, intMap)
            }
        }
    }

    fun runAgentLoop(
        goal: String,
        serverUrl: String,
        onComplete: ((String) -> Unit)? = null
    ) {
        serviceScope.launch(Dispatchers.IO) {
            val maxSteps = 8
            var currentStep = 0
            var lastReply = ""

            while (currentStep < maxSteps) {
                if (currentStep == 0) delay(1000)

                val (serializedNodes, _) = withContext(Dispatchers.Main) {
                    serializeCurrentWindow()
                }

                val (dumpNodesArray, strNodeMap) = withContext(Dispatchers.Main) {
                    dumpUiTree()
                }

                val currentPkg = withContext(Dispatchers.Main) {
                    rootInActiveWindow?.packageName?.toString() ?: ""
                }

                val jsonArray = JSONArray()
                serializedNodes.forEach { jsonArray.put(it) }

                val payload = JSONObject().apply {
                    put("goal", goal)
                    put("package", currentPkg)
                    put("step", currentStep)
                    put("ui_tree", jsonArray)
                    put("ui_nodes", dumpNodesArray)
                }

                val stepResponse = postAgentStep(serverUrl, payload)
                if (stepResponse == null) {
                    Log.w(TAG, "Agent step $currentStep returned null response from server")
                    break
                }

                val actionType = stepResponse.optString("action", "done")
                lastReply = stepResponse.optString("reply", "")

                if (actionType.equals("done", ignoreCase = true)) {
                    Log.i(TAG, "Agent goal completed at step $currentStep: reply='$lastReply'")
                    break
                }

                withContext(Dispatchers.Main) {
                    executeUiAction(stepResponse, strNodeMap)
                }

                currentStep++
                delay(1500)
            }

            withContext(Dispatchers.Main) {
                val finalMsg = if (lastReply.isNotBlank()) lastReply else "Task completed, sir."
                onComplete?.invoke(finalMsg)
                JarvisVoiceService.instance?.speakBritish(finalMsg)
            }
        }
    }
}
