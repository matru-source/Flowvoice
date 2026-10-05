package com.flowvoice.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.flowvoice.app.ui.FloatingBubbleManager

class FlowAccessibilityService : AccessibilityService() {

    private var currentFocusedNode: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val config = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_FOCUSED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        serviceInfo = config

        // Connect global singleton bubble listener
        if (canDrawOverlays()) {
            val bubble = FloatingBubbleManager.getInstance(this)
            bubble.onTextReadyListener = { textToInsert ->
                injectTextIntoFocusedField(textToInsert)
            }
        }

        Log.d(TAG, "FlowAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Ignore events originating from FlowVoice itself to prevent recursive loops
        if (event.packageName == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                if (source != null && isEditableField(source)) {
                    currentFocusedNode = AccessibilityNodeInfo.obtain(source)
                    Log.d(TAG, "Input focused: ${source.className} in pkg: ${event.packageName}")

                    if (canDrawOverlays()) {
                        val bubble = FloatingBubbleManager.getInstance(this)
                        bubble.onTextReadyListener = { textToInsert ->
                            injectTextIntoFocusedField(textToInsert)
                        }
                        bubble.showBubble()
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Whenever keyboard opens or app changes, verify if an editable field is present
                val target = findTargetInputNode()
                if (target != null) {
                    currentFocusedNode = AccessibilityNodeInfo.obtain(target)
                    if (canDrawOverlays()) {
                        val bubble = FloatingBubbleManager.getInstance(this)
                        bubble.onTextReadyListener = { textToInsert ->
                            injectTextIntoFocusedField(textToInsert)
                        }
                        bubble.showBubble()
                    }
                }
            }
        }
    }

    private fun isEditableField(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isEditable) return true
        val className = node.className?.toString().orEmpty()
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("TextInput", ignoreCase = true)
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null && (child.isEditable || child.className?.toString()?.contains("EditText", true) == true)) {
                return true
            }
        }
        return false
    }

    fun triggerBubbleManually() {
        if (canDrawOverlays()) {
            val bubble = FloatingBubbleManager.getInstance(this)
            bubble.onTextReadyListener = { textToInsert ->
                injectTextIntoFocusedField(textToInsert)
            }
            bubble.showBubble()
        }
    }

    /**
     * Finds the active editable input node across ALL interactive windows (WhatsApp, Slack, Gmail, etc.)
     */
    fun findTargetInputNode(): AccessibilityNodeInfo? {
        // 1. Try refreshed currentFocusedNode if still valid
        currentFocusedNode?.let { node ->
            try {
                if (node.refresh() && isEditableField(node)) {
                    return node
                }
            } catch (e: Exception) {
                // Node might be invalidated
            }
        }

        // 2. Search across ALL interactive windows
        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    val root = window.root ?: continue
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && isEditableField(focused)) {
                        return focused
                    }
                }

                // Deep search across windows for WhatsApp entry or editable views
                for (window in windowList) {
                    val root = window.root ?: continue
                    val node = findEditableNodeDeep(root)
                    if (node != null) return node
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Window traversal error", e)
        }

        // 3. Fallback to active window
        try {
            val root = rootInActiveWindow
            if (root != null) {
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditableField(focused)) {
                    return focused
                }
                return findEditableNodeDeep(root)
            }
        } catch (e: Exception) {
            Log.w(TAG, "rootInActiveWindow error", e)
        }

        return null
    }

    private fun findEditableNodeDeep(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableField(node)) {
            if (node.isFocused || node.viewIdResourceName?.endsWith(":id/entry") == true) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNodeDeep(child)
            if (found != null) return found
        }
        return null
    }

    /**
     * Injects the polished English text directly into the target app's input field.
     * Uses ACTION_FOCUS + ACTION_SET_TEXT with a reliable Clipboard + ACTION_PASTE fallback.
     */
    fun injectTextIntoFocusedField(text: String) {
        // 1. Always copy to clipboard as guaranteed fallback
        copyToClipboard(text)

        val target = findTargetInputNode()
        if (target != null) {
            Log.d(TAG, "Injecting text into node: ${target.className} (id=${target.viewIdResourceName})")

            // Ensure focus is on target
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

            // Attempt 1: Direct ACTION_SET_TEXT
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            var setSuccess = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.d(TAG, "ACTION_SET_TEXT result: $setSuccess")

            // Attempt 2: If direct set fails (some apps like WhatsApp/browsers), fallback to ACTION_PASTE
            if (!setSuccess) {
                setSuccess = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "ACTION_PASTE result: $setSuccess")
            }

            // Move cursor to end of text
            try {
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
            } catch (e: Exception) {
                // Ignore
            }
        } else {
            Log.w(TAG, "No focused text field found across windows. Text is copied to clipboard.")
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("FlowVoice English", text)
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to clipboard", e)
        }
    }

    private fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "FlowAccessibilityService onInterrupt")
    }

    override fun onDestroy() {
        super.onDestroy()
        FloatingBubbleManager.getInstance(this).removeBubble()
        instance = null
        Log.d(TAG, "FlowAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "FlowAccessibility"
        var instance: FlowAccessibilityService? = null
            private set
    }
}
