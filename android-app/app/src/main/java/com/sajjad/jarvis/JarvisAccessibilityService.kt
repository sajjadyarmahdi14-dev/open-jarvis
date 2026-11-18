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
        val root = rootInActiveWindow ?: return false
        val targetNode = findEditableNode(root) ?: root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (targetNode == null) {
            Log.w(TAG, "No editable or focused input node found in active window.")
            return false
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.i(TAG, "typeText performed: success=$success, text='$text'")

        if (autoSend) {
            serviceScope.launch {
                delay(350)
                clickSendButton()
            }
        }
        return success
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
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
}
