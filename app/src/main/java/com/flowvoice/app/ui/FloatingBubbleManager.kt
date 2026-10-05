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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.flowvoice.app.R
import com.flowvoice.app.audio.AudioRecorderManager
import com.flowvoice.app.data.PreferencesManager
import com.flowvoice.app.data.ToneVariants
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
    private val autoStopHandler = Handler(Looper.getMainLooper())

    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Layout State Views
    private var layoutIdle: LinearLayout? = null
    private var layoutRecording: LinearLayout? = null
    private var layoutProcessing: LinearLayout? = null
    private var layoutPreview: LinearLayout? = null

    // Tone Tabs & Text
    private var tabCasual: TextView? = null
    private var tabSemiFormal: TextView? = null
    private var tabFormal: TextView? = null
    private var tvPreviewText: TextView? = null
    private var chipLanguage: TextView? = null

    private var currentTones: ToneVariants? = null
    private var activeToneMode: ToneMode = ToneMode.SEMI_FORMAL
    private var isShowingOriginal: Boolean = false

    var isBubbleAttached: Boolean = false
        private set

    var onTextReadyListener: ((String) -> Unit)? = null

    private var currentRecordedFile: File? = null

    enum class ToneMode {
        CASUAL, SEMI_FORMAL, FORMAL
    }

    @Synchronized
    fun showBubble(callback: ((String) -> Unit)? = null) {
        if (callback != null) {
            this.onTextReadyListener = callback
        }

        // GUARD 1: If bubble is ALREADY attached and displaying, DO NOT destroy and re-add it!
        // Re-adding the overlay on every focus/click event is what causes rapid flickering.
        if (isBubbleAttached && bubbleView != null) {
            if (bubbleView?.visibility != View.VISIBLE) {
                bubbleView?.visibility = View.VISIBLE
            }
            return
        }

        try {
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(appContext, R.style.Theme_FlowVoice)
            val inflater = LayoutInflater.from(themedContext)
            bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)

            initViews(bubbleView!!)
            setupIdleDragListener()

            val metrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(metrics)

            // Position bubble reliably on the right edge, center height
            val startX = (metrics.widthPixels - 260).coerceAtLeast(60)
            val startY = (metrics.heightPixels / 2).coerceAtLeast(150)

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = startX
                y = startY
            }

            windowManager.addView(bubbleView, layoutParams)
            isBubbleAttached = true
            setIdleState()
            Log.d(TAG, "Floating bubble added cleanly to WindowManager at ($startX, $startY)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add floating bubble to WindowManager", e)
        }
    }

    @Synchronized
    fun hideBubble() {
        if (bubbleView != null) {
            try {
                autoStopHandler.removeCallbacksAndMessages(null)
                if (audioRecorder.isRecording) {
                    audioRecorder.cancelRecording()
                }
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {
                Log.w(TAG, "hideBubble error", e)
            } finally {
                bubbleView = null
                isBubbleAttached = false
            }
        }
    }

    @Synchronized
    fun removeBubble() {
        hideBubble()
    }

    private fun initViews(root: View) {
        layoutIdle = root.findViewById(R.id.layout_state_idle)
        layoutRecording = root.findViewById(R.id.layout_state_recording)
        layoutProcessing = root.findViewById(R.id.layout_state_processing)
        layoutPreview = root.findViewById(R.id.layout_state_preview)

        // Tone tabs
        tabCasual = root.findViewById(R.id.tab_tone_casual)
        tabSemiFormal = root.findViewById(R.id.tab_tone_semi_formal)
        tabFormal = root.findViewById(R.id.tab_tone_formal)
        tvPreviewText = root.findViewById(R.id.tv_preview_text)
        chipLanguage = root.findViewById(R.id.chip_language)

        // RECORDING STATE ACTIONS:
        root.findViewById<ImageView>(R.id.img_recording_indicator)?.setOnClickListener {
            stopVoiceCapture()
        }

        root.findViewById<ImageView>(R.id.btn_stop_recording)?.setOnClickListener {
            stopVoiceCapture()
        }

        root.findViewById<ImageView>(R.id.btn_cancel_recording)?.setOnClickListener {
            cancelVoiceCapture()
        }

        // PREVIEW ACTIONS:
        tabCasual?.setOnClickListener {
            selectTone(ToneMode.CASUAL)
        }

        tabSemiFormal?.setOnClickListener {
            selectTone(ToneMode.SEMI_FORMAL)
        }

        tabFormal?.setOnClickListener {
            selectTone(ToneMode.FORMAL)
        }

        // Language Chip Toggle (English <-> Original Indic Speech)
        chipLanguage?.setOnClickListener {
            toggleLanguageView()
        }

        // Insert Button (↵)
        root.findViewById<View>(R.id.btn_insert_text)?.setOnClickListener {
            val textToInsert = if (isShowingOriginal) {
                currentTones?.original?.ifBlank { getActiveToneText() } ?: getActiveToneText()
            } else {
                getActiveToneText()
            }
            if (textToInsert.isNotBlank()) {
                onTextReadyListener?.invoke(textToInsert)
                vibratePhone(40)
                Toast.makeText(appContext, "Polished text inserted!", Toast.LENGTH_SHORT).show()
            }
            setIdleState()
        }

        // Dismiss Preview Dialog
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

        // 40-Second Auto-Timeout: automatically stop if user forgets or sound finishes
        autoStopHandler.removeCallbacksAndMessages(null)
        autoStopHandler.postDelayed({
            if (audioRecorder.isRecording) {
                Log.d(TAG, "40s timeout reached, automatically stopping recording")
                stopVoiceCapture()
            }
        }, 40_000L)
    }

    private fun stopVoiceCapture() {
        autoStopHandler.removeCallbacksAndMessages(null)
        vibratePhone(40)
        currentRecordedFile = audioRecorder.stopRecording()

        val file = currentRecordedFile
        if (file == null || !file.exists() || file.length() < 2200) {
            Toast.makeText(appContext, "No speech detected. Please speak closer to the mic.", Toast.LENGTH_SHORT).show()
            setIdleState()
            return
        }

        setProcessingState()

        coroutineScope.launch {
            val result = polisherEngine.processAudioForTones(file) { statusMsg ->
                // Live status update
            }

            if (result.isSuccess) {
                currentTones = result.getOrNull()
                vibratePhone(60)

                // Show Tone Selection Card
                setPreviewState()
            } else {
                val error = result.exceptionOrNull()?.message ?: "No speech detected"
                Toast.makeText(appContext, error, Toast.LENGTH_LONG).show()
                setIdleState()
            }
        }
    }

    private fun cancelVoiceCapture() {
        autoStopHandler.removeCallbacksAndMessages(null)
        vibratePhone(30)
        audioRecorder.cancelRecording()
        setIdleState()
    }

    private fun selectTone(mode: ToneMode) {
        vibratePhone(25)
        isShowingOriginal = false
        chipLanguage?.text = "🌐 English"
        chipLanguage?.background = ContextCompat.getDrawable(appContext, R.drawable.bg_tone_unselected)
        chipLanguage?.setTextColor(ContextCompat.getColor(appContext, R.color.primary_light))
        activeToneMode = mode
        updateToneTabStyles()
        tvPreviewText?.text = getActiveToneText()
    }

    private fun toggleLanguageView() {
        vibratePhone(30)
        val tones = currentTones
        if (tones == null) {
            Toast.makeText(appContext, "No text available", Toast.LENGTH_SHORT).show()
            return
        }

        isShowingOriginal = !isShowingOriginal

        if (isShowingOriginal) {
            val originalText = tones.original.ifBlank { getActiveToneText() }
            tvPreviewText?.text = originalText
            chipLanguage?.text = "🗣️ Original"
            chipLanguage?.background = ContextCompat.getDrawable(appContext, R.drawable.bg_tone_selected)
            chipLanguage?.setTextColor(ContextCompat.getColor(appContext, R.color.white))
            dimToneTabs()
            Toast.makeText(appContext, "Showing original spoken speech", Toast.LENGTH_SHORT).show()
        } else {
            tvPreviewText?.text = getActiveToneText()
            chipLanguage?.text = "🌐 English"
            chipLanguage?.background = ContextCompat.getDrawable(appContext, R.drawable.bg_tone_unselected)
            chipLanguage?.setTextColor(ContextCompat.getColor(appContext, R.color.primary_light))
            updateToneTabStyles()
            Toast.makeText(appContext, "Translated to English (${activeToneMode.name.lowercase().replace('_', ' ')})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getActiveToneText(): String {
        val tones = currentTones ?: return ""
        return when (activeToneMode) {
            ToneMode.CASUAL -> tones.casual
            ToneMode.SEMI_FORMAL -> tones.semiFormal
            ToneMode.FORMAL -> tones.formal
        }
    }

    private fun updateToneTabStyles() {
        tabCasual?.background = ContextCompat.getDrawable(
            appContext,
            if (activeToneMode == ToneMode.CASUAL) R.drawable.bg_tone_selected else R.drawable.bg_tone_unselected
        )
        tabCasual?.setTextColor(if (activeToneMode == ToneMode.CASUAL) ContextCompat.getColor(appContext, R.color.white) else ContextCompat.getColor(appContext, R.color.text_secondary))

        tabSemiFormal?.background = ContextCompat.getDrawable(
            appContext,
            if (activeToneMode == ToneMode.SEMI_FORMAL) R.drawable.bg_tone_selected else R.drawable.bg_tone_unselected
        )
        tabSemiFormal?.setTextColor(if (activeToneMode == ToneMode.SEMI_FORMAL) ContextCompat.getColor(appContext, R.color.white) else ContextCompat.getColor(appContext, R.color.text_secondary))

        tabFormal?.background = ContextCompat.getDrawable(
            appContext,
            if (activeToneMode == ToneMode.FORMAL) R.drawable.bg_tone_selected else R.drawable.bg_tone_unselected
        )
        tabFormal?.setTextColor(if (activeToneMode == ToneMode.FORMAL) ContextCompat.getColor(appContext, R.color.white) else ContextCompat.getColor(appContext, R.color.text_secondary))
    }

    private fun dimToneTabs() {
        tabCasual?.background = ContextCompat.getDrawable(appContext, R.drawable.bg_tone_unselected)
        tabCasual?.setTextColor(ContextCompat.getColor(appContext, R.color.text_secondary))
        tabSemiFormal?.background = ContextCompat.getDrawable(appContext, R.drawable.bg_tone_unselected)
        tabSemiFormal?.setTextColor(ContextCompat.getColor(appContext, R.color.text_secondary))
        tabFormal?.background = ContextCompat.getDrawable(appContext, R.drawable.bg_tone_unselected)
        tabFormal?.setTextColor(ContextCompat.getColor(appContext, R.color.text_secondary))
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

    private fun setPreviewState() {
        isShowingOriginal = false
        activeToneMode = ToneMode.SEMI_FORMAL
        chipLanguage?.text = "🌐 English"
        chipLanguage?.background = ContextCompat.getDrawable(appContext, R.drawable.bg_tone_unselected)
        chipLanguage?.setTextColor(ContextCompat.getColor(appContext, R.color.primary_light))
        updateToneTabStyles()
        tvPreviewText?.text = getActiveToneText()

        // Reposition preview card cleanly so it centers comfortably on screen
        layoutParams?.let { params ->
            val metrics = appContext.resources.displayMetrics
            val cardWidthPx = (330 * metrics.density).toInt()
            val centeredX = ((metrics.widthPixels - cardWidthPx) / 2).coerceAtLeast(16)
            params.x = centeredX
            try {
                windowManager.updateViewLayout(bubbleView, params)
            } catch (e: Exception) {
                Log.w(TAG, "Update preview layout position error", e)
            }
        }

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

    /**
     * Touch & Drag handling ONLY on layoutIdle:
     * Prevents any touch event conflicts with recording or tone selection cards!
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupIdleDragListener() {
        val touchSlop = android.view.ViewConfiguration.get(appContext).scaledTouchSlop.coerceAtLeast(24)

        layoutIdle?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false
            private var touchDownTime = 0L

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = layoutParams ?: return false

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        touchDownTime = System.currentTimeMillis()
                        isDragging = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()

                        if (Math.hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlop) {
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
                        if (isDragging) {
                            isDragging = false
                            return true
                        }

                        // It's a TAP!
                        val duration = System.currentTimeMillis() - touchDownTime
                        if (duration < 500) {
                            val closeBtn = bubbleView?.findViewById<View>(R.id.btn_close_idle)
                            val isCloseTapped = closeBtn != null &&
                                    event.x >= (closeBtn.left - 12) &&
                                    event.x <= (closeBtn.right + 12) &&
                                    event.y >= (closeBtn.top - 12) &&
                                    event.y <= (closeBtn.bottom + 12)

                            if (isCloseTapped) {
                                hideBubble()
                            } else {
                                startVoiceCapture()
                            }
                        }
                        return true
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
