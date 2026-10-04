package com.flowvoice.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
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

class FloatingBubbleManager(
    private val context: Context,
    private val onTextReadyToInsert: (String) -> Unit
) {

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val vibrator: Vibrator? =
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val prefs = PreferencesManager(context)
    private val audioRecorder = AudioRecorderManager(context)
    private val polisherEngine = SpeechPolisherEngine(context)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

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

    private var currentRecordedFile: File? = null
    private var lastPolishedText: String = ""

    fun showBubble() {
        if (isBubbleAttached) {
            bubbleView?.visibility = View.VISIBLE
            return
        }

        val inflater = LayoutInflater.from(context)
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
            Log.d(TAG, "Floating bubble added to WindowManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating bubble to WindowManager", e)
        }
    }

    fun hideBubble() {
        if (isBubbleAttached && bubbleView != null) {
            bubbleView?.visibility = View.GONE
        }
    }

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
            onTextReadyToInsert(lastPolishedText)
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
            Toast.makeText(context, "Microphone permission needed", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Audio too short", Toast.LENGTH_SHORT).show()
            setIdleState()
            return
        }

        coroutineScope.launch {
            val result = polisherEngine.processAudio(file) { statusMsg ->
                // Update live status if needed
            }

            if (result.isSuccess) {
                lastPolishedText = result.getOrNull().orEmpty()
                vibratePhone(60)

                if (prefs.autoInsert) {
                    // Superflow default: instantly insert into the active input!
                    onTextReadyToInsert(lastPolishedText)
                    setIdleState()
                } else {
                    // Show preview card
                    setPreviewState(lastPolishedText)
                }
            } else {
                val error = result.exceptionOrNull()?.message ?: "Processing error"
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
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
    }
}
