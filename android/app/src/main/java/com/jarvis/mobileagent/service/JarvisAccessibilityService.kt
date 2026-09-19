package com.jarvis.mobileagent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisA11y"
        var instance: JarvisAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Jarvis Accessibility Service connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Can be used to track active package or window changes
    }

    override fun onInterrupt() {
        Log.w(TAG, "Jarvis Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        Log.d(TAG, "Jarvis Accessibility Service destroyed")
    }

    // ── Global System Navigation ─────────────────────────────────────────────

    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun pressLock(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            false
        }
    }

    // ── Gesture / Tap Execution ──────────────────────────────────────────────

    suspend fun clickCoordinate(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)
        return deferred.await()
    }

    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(50))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val deferred = CompletableDeferred<Boolean>()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                deferred.complete(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                deferred.complete(false)
            }
        }, null)
        return deferred.await()
    }

    // ── Semantic Node Tree Interaction ───────────────────────────────────────

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                return ok
            }
            // Check parent if clickable
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    val ok = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    parent.recycle()
                    node.recycle()
                    return ok
                }
                val nextParent = parent.parent
                parent.recycle()
                parent = nextParent
            }
            node.recycle()
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)

        if (focusedNode != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focusedNode.recycle()
            return ok
        }
        return false
    }

    fun getUiHierarchy(): String {
        val root = rootInActiveWindow ?: return "{}"
        val rootJson = JSONObject()
        try {
            inspectNode(root, rootJson)
        } catch (e: Exception) {
            Log.e(TAG, "Error dumping UI hierarchy: ${e.message}")
        }
        return rootJson.toString()
    }

    private fun inspectNode(node: AccessibilityNodeInfo?, json: JSONObject) {
        if (node == null) return
        val rect = Rect()
        node.getBoundsInScreen(rect)

        json.put("class", node.className?.toString())
        json.put("text", node.text?.toString())
        json.put("desc", node.contentDescription?.toString())
        json.put("id", node.viewIdResourceName)
        json.put("clickable", node.isClickable)
        json.put("bounds", JSONObject().apply {
            put("left", rect.left)
            put("top", rect.top)
            put("right", rect.right)
            put("bottom", rect.bottom)
        })

        if (node.childCount > 0) {
            val childrenArray = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    val childJson = JSONObject()
                    inspectNode(child, childJson)
                    childrenArray.put(childJson)
                    child.recycle()
                }
            }
            json.put("children", childrenArray)
        }
    }
}
