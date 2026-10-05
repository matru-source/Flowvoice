package com.flowvoice.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.flowvoice.app.R
import com.flowvoice.app.audio.AudioRecorderManager
import com.flowvoice.app.data.PreferencesManager
import com.flowvoice.app.network.SpeechPolisherEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class FloatingBubbleManager private constructor(private val appContext: Context) {

    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val vibrator: Vibrator? =
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val prefs = PreferencesManager(appContext)
    private val audioRecorder = AudioRecorderManager(appContext)
    private val polisherEngine = SpeechPolisherEngine(appContext)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Layout State Views
    private var layoutIdle: LinearLayout? = null
    private var layoutRecording: LinearLayout? = null
    private var layoutProcessing: LinearLayout? = null
    private var layoutPreview: LinearLayout? = null

    private var tvRecordingStatus: TextView? = null
    private var tvPreviewText: TextView? = null

    var isBubbleAttached: Boolean = false
        private set

    var onTextReadyListener: ((String) -> Unit)? = null

    private var currentRecordedFile: File? = null
    private var lastPolishedText: String = ""

    @Synchronized
    fun showBubble(callback: ((String) -> Unit)? = null) {
        if (callback != null) {
            this.onTextReadyListener = callback
        }

        // If view already attached, ensure visible and return immediately (NEVER add duplicate!)
        if (isBubbleAttached && bubbleView != null) {
            bubbleView?.visibility = View.VISIBLE
            setIdleState()
            return
        }

        val inflater = LayoutInflater.from(appContext)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

        initViews(bubbleView!!)
        setupTouchListener(bubbleView!!)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels - 240
            y = metrics.heightPixels / 2
        }

        try {
            windowManager.addView(bubbleView, layoutParams)
            isBubbleAttached = true
            setIdleState()
            Log.d(TAG, "Floating bubble added to WindowManager (singleton enforced)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble to WindowManager", e)
        }
    }

    fun hideBubble() {
        if (isBubbleAttached && bubbleView != null) {
            bubbleView?.visibility = View.GONE
        }
    }

    @Synchronized
    fun removeBubble() {
        if (isBubbleAttached && bubbleView != null) {
            try {
                if (audioRecorder.isRecording) {
                    audioRecorder.cancelRecording()
                }
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove bubble view", e)
            } finally {
                isBubbleAttached = false
                bubbleView = null
            }
        }
    }

    private fun initViews(root: View) {
        layoutIdle = root.findViewById(R.id.layout_state_idle)
        layoutRecording = root.findViewById(R.id.layout_state_recording)
        layoutProcessing = root.findViewById(R.id.layout_state_processing)
        layoutPreview = root.findViewById(R.id.layout_state_preview)

        tvRecordingStatus = root.findViewById(R.id.tv_recording_status)
        tvPreviewText = root.findViewById(R.id.tv_preview_text)

        // Idle state click -> start recording
        layoutIdle?.setOnClickListener {
            startVoiceCapture()
        }

        // Dismiss idle bubble
        root.findViewById<ImageView>(R.id.btn_close_idle)?.setOnClickListener {
            hideBubble()
        }

        // Recording state stop button -> finish and process
        root.findViewById<ImageView>(R.id.btn_stop_recording)?.setOnClickListener {
            stopVoiceCapture()
        }

        // Cancel recording
        root.findViewById<ImageView>(R.id.btn_cancel_recording)?.setOnClickListener {
            cancelVoiceCapture()
        }

        // Preview state actions
        root.findViewById<Button>(R.id.btn_insert_text)?.setOnClickListener {
            onTextReadyListener?.invoke(lastPolishedText)
            setIdleState()
        }

        root.findViewById<ImageView>(R.id.btn_dismiss_preview)?.setOnClickListener {
            setIdleState()
        }
    }

    private fun startVoiceCapture() {
        vibratePhone(40)
        currentRecordedFile = audioRecorder.startRecording()
        if (currentRecordedFile == null) {
            Toast.makeText(appContext, "Microphone permission needed", Toast.LENGTH_SHORT).show()
            setIdleState()
            return
        }
        setRecordingState()
    }

    private fun stopVoiceCapture() {
        vibratePhone(40)
        currentRecordedFile = audioRecorder.stopRecording()
        setProcessingState()

        val file = currentRecordedFile
        if (file == null || !file.exists() || file.length() < 1000) {
            Toast.makeText(appContext, "Audio too short", Toast.LENGTH_SHORT).show()
            setIdleState()
            return
        }

        coroutineScope.launch {
            val result = polisherEngine.processAudio(file) { statusMsg ->
                // Live status update
            }

            if (result.isSuccess) {
                lastPolishedText = result.getOrNull().orEmpty()
                vibratePhone(60)

                if (prefs.autoInsert) {
                    onTextReadyListener?.invoke(lastPolishedText)
                    setIdleState()
                } else {
                    setPreviewState(lastPolishedText)
                }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Processing error"
                Toast.makeText(appContext, error, Toast.LENGTH_LONG).show()
                setIdleState()
            }
        }
    }

    private fun cancelVoiceCapture() {
        vibratePhone(30)
        audioRecorder.cancelRecording()
        setIdleState()
    }

    private fun setIdleState() {
        layoutIdle?.visibility = View.VISIBLE
        layoutRecording?.visibility = View.GONE
        layoutProcessing?.visibility = View.GONE
        layoutPreview?.visibility = View.GONE
    }

    private fun setRecordingState() {
        layoutIdle?.visibility = View.GONE
        layoutRecording?.visibility = View.VISIBLE
        layoutProcessing?.visibility = View.GONE
        layoutPreview?.visibility = View.GONE
    }

    private fun setProcessingState() {
        layoutIdle?.visibility = View.GONE
        layoutRecording?.visibility = View.GONE
        layoutProcessing?.visibility = View.VISIBLE
        layoutPreview?.visibility = View.GONE
    }

    private fun setPreviewState(text: String) {
        tvPreviewText?.text = text
        layoutIdle?.visibility = View.GONE
        layoutRecording?.visibility = View.GONE
        layoutProcessing?.visibility = View.GONE
        layoutPreview?.visibility = View.VISIBLE
    }

    private fun vibratePhone(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(view: View) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                            isDragging = true
                            params.x = initialX + deltaX
                            params.y = initialY + deltaY
                            try {
                                windowManager.updateViewLayout(bubbleView, params)
                            } catch (e: Exception) {
                                // Ignore
                            }
                            return true
                        }
                        return false
                    }

                    MotionEvent.ACTION_UP -> {
                        return isDragging
                    }
                }
                return false
            }
        })
    }

    companion object {
        private const val TAG = "FloatingBubbleManager"

        @Volatile
        private var instance: FloatingBubbleManager? = null

        fun getInstance(context: Context): FloatingBubbleManager {
            return instance ?: synchronized(this) {
                instance ?: FloatingBubbleManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
