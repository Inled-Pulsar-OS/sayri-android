package es.inled.sayri.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import es.inled.sayri.data.MobileScreenSnapshot
import es.inled.sayri.data.UiElementInfo

class SayriAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SayriAccessibilityService? = null
            private set

        val isRunning: Boolean
            get() = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Event processing if continuous monitoring is enabled
    }

    override fun onInterrupt() {
        // Interrupted by system
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Dumps the current active window UI hierarchy into a structured snapshot.
     */
    fun dumpScreenHierarchy(userIntent: String = ""): MobileScreenSnapshot {
        val root = rootInActiveWindow
        val elements = mutableListOf<UiElementInfo>()
        var pkgName = "unknown"

        if (root != null) {
            pkgName = root.packageName?.toString() ?: "unknown"
            traverseNode(root, elements)
        }

        return MobileScreenSnapshot(
            packageName = pkgName,
            elements = elements,
            intent = userIntent
        )
    }

    private fun traverseNode(node: AccessibilityNodeInfo?, list: MutableList<UiElementInfo>) {
        if (node == null) return

        val isInteractive = node.isClickable || node.isScrollable || node.isEditable || node.isCheckable
        val hasContent = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

        if (node.isVisibleToUser && (isInteractive || hasContent)) {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Only consider elements that have non-zero area
            if (rect.width() > 0 && rect.height() > 0) {
                list.add(
                    UiElementInfo(
                        id = node.viewIdResourceName,
                        type = node.className?.toString(),
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        bounds = listOf(rect.left, rect.top, rect.right, rect.bottom),
                        clickable = node.isClickable,
                        scrollable = node.isScrollable,
                        editable = node.isEditable
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChild(i), list)
        }
    }

    /**
     * Dispatches a tap gesture at coordinate (x, y).
     */
    fun clickCoordinates(x: Float, y: Float, onComplete: ((Boolean) -> Unit)? = null) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onComplete?.invoke(false)
            }
        }, null)
    }

    /**
     * Finds a node by text or ID and triggers a click action.
     */
    fun clickElement(targetTextOrId: String): Boolean {
        val root = rootInActiveWindow ?: return false

        // Search by view ID
        val nodesById = root.findAccessibilityNodeInfosByViewId(targetTextOrId)
        if (!nodesById.isNullOrEmpty()) {
            for (node in nodesById) {
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        }

        // Search by text
        val nodesByText = root.findAccessibilityNodeInfosByText(targetTextOrId)
        if (!nodesByText.isNullOrEmpty()) {
            for (node in nodesByText) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                // Try parent if parent is clickable
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    parent = parent.parent
                }
            }
        }
        return false
    }

    /**
     * Sets text in an editable field.
     */
    fun setTextInField(targetTextOrId: String, textToSet: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(targetTextOrId)
            ?: root.findAccessibilityNodeInfosByText(targetTextOrId)

        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (node.isEditable) {
                    val arguments = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToSet)
                    }
                    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                }
            }
        }
        return false
    }

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /**
     * Captures a real screenshot of the screen for Vision and Circle to Search.
     */
    fun captureScreenshot(onResult: (android.graphics.Bitmap?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: ScreenshotResult) {
                            try {
                                val hardwareBuffer = screenshotResult.hardwareBuffer
                                val colorSpace = screenshotResult.colorSpace
                                val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                                val copy = bitmap?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                hardwareBuffer.close()
                                onResult(copy ?: bitmap)
                            } catch (e: Exception) {
                                onResult(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            onResult(null)
                        }
                    }
                )
            } catch (e: Throwable) {
                android.util.Log.e("SayriAccessibility", "Failed to take screenshot", e)
                onResult(null)
            }
        } else {
            onResult(null)
        }
    }
}
