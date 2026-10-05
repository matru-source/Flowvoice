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
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
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
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source != null && isEditableField(source)) {
                    currentFocusedNode = source
                    Log.d(TAG, "Focused editable field: ${source.className} in pkg: ${event.packageName}")

                    if (canDrawOverlays()) {
                        val bubble = FloatingBubbleManager.getInstance(this)
                        bubble.onTextReadyListener = { textToInsert ->
                            injectTextIntoFocusedField(textToInsert)
                        }
                        bubble.showBubble()
                    }
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val source = event.source
                if (source != null && isEditableField(source)) {
                    currentFocusedNode = source
                    Log.d(TAG, "Clicked editable field: ${source.className} in pkg: ${event.packageName}")

                    if (canDrawOverlays()) {
                        val bubble = FloatingBubbleManager.getInstance(this)
                        bubble.onTextReadyListener = { textToInsert ->
                            injectTextIntoFocusedField(textToInsert)
                        }
                        bubble.showBubble()
                    }
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val activeNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (activeNode != null && isEditableField(activeNode)) {
                    currentFocusedNode = activeNode
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
     * Injects the polished English text directly into the target app's input field.
     * Uses ACTION_SET_TEXT with a reliable Clipboard + ACTION_PASTE fallback.
     */
    fun injectTextIntoFocusedField(text: String) {
        var targetNode = currentFocusedNode

        // If stored node is no longer valid, look up current input focus
        if (targetNode == null || !targetNode.refresh()) {
            targetNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode == null) {
            val root = rootInActiveWindow
            targetNode = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        }

        if (targetNode != null) {
            Log.d(TAG, "Injecting text into node: ${targetNode.className}")

            // Attempt 1: Direct ACTION_SET_TEXT
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val setSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            // Attempt 2: If direct set fails (some apps like WhatsApp/browsers), fallback to Clipboard + ACTION_PASTE
            if (!setSuccess) {
                copyToClipboard(text)
                val pasteSuccess = targetNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "Fallback paste result: $pasteSuccess")
            }
        } else {
            // Fallback: Copy to clipboard so user can paste with one tap
            copyToClipboard(text)
            Log.w(TAG, "No focused text field found to inject text into. Copied to clipboard.")
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
