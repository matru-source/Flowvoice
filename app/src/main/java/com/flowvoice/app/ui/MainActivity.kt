package com.flowvoice.app.ui

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.flowvoice.app.R
import com.flowvoice.app.data.PreferencesManager
import com.flowvoice.app.databinding.ActivityMainBinding
import com.flowvoice.app.service.FlowAccessibilityService
import com.flowvoice.app.utils.PermissionUtils

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice typing", Toast.LENGTH_LONG).show()
        }
        updatePermissionStatuses()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        setupListeners()
        loadPreferences()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatuses()
    }

    private fun setupListeners() {
        // Permissions actions
        binding.btnPermOverlay.setOnClickListener {
            PermissionUtils.openOverlaySettings(this)
        }

        binding.btnPermAccessibility.setOnClickListener {
            PermissionUtils.openAccessibilitySettings(this)
        }

        binding.btnPermMic.setOnClickListener {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Save AI configuration
        binding.btnSaveConfig.setOnClickListener {
            savePreferences()
        }

        // Manually trigger bubble for live test
        binding.btnTestBubble.setOnClickListener {
            if (!PermissionUtils.isOverlayPermissionGranted(this)) {
                Toast.makeText(this, "Please grant 'Display over other apps' permission first", Toast.LENGTH_SHORT).show()
                PermissionUtils.openOverlaySettings(this)
                return@setOnClickListener
            }

            FloatingBubbleManager.getInstance(this).showBubble { text ->
                binding.etTestPlayground.setText(text)
            }

            Toast.makeText(this, "Floating Bubble activated! Speak in Hindi/Hinglish.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadPreferences() {
        // Provider
        when (prefs.provider) {
            PreferencesManager.PROVIDER_SARVAM -> binding.rbSarvam.isChecked = true
            PreferencesManager.PROVIDER_OPENAI -> binding.rbOpenai.isChecked = true
            else -> binding.rbGroq.isChecked = true
        }

        // API Key
        binding.etApiKey.setText(prefs.apiKey)

        // Tone
        when (prefs.tone) {
            PreferencesManager.TONE_PROFESSIONAL -> binding.chipToneProfessional.isChecked = true
            PreferencesManager.TONE_CASUAL -> binding.chipToneCasual.isChecked = true
            PreferencesManager.TONE_CONCISE -> binding.chipToneConcise.isChecked = true
            else -> binding.chipToneNatural.isChecked = true
        }
    }

    private fun savePreferences() {
        val selectedProvider = when {
            binding.rbSarvam.isChecked -> PreferencesManager.PROVIDER_SARVAM
            binding.rbOpenai.isChecked -> PreferencesManager.PROVIDER_OPENAI
            else -> PreferencesManager.PROVIDER_GROQ
        }
        prefs.provider = selectedProvider

        val key = binding.etApiKey.text?.toString().orEmpty()
        prefs.apiKey = key

        val selectedTone = when {
            binding.chipToneProfessional.isChecked -> PreferencesManager.TONE_PROFESSIONAL
            binding.chipToneCasual.isChecked -> PreferencesManager.TONE_CASUAL
            binding.chipToneConcise.isChecked -> PreferencesManager.TONE_CONCISE
            else -> PreferencesManager.TONE_NATURAL
        }
        prefs.tone = selectedTone

        Toast.makeText(this, "Configuration saved successfully!", Toast.LENGTH_SHORT).show()
    }

    private fun updatePermissionStatuses() {
        // 1. Overlay
        val overlayGranted = PermissionUtils.isOverlayPermissionGranted(this)
        if (overlayGranted) {
            binding.btnPermOverlay.text = getString(R.string.status_enabled)
            binding.btnPermOverlay.isEnabled = false
            binding.btnPermOverlay.setBackgroundColor(ContextCompat.getColor(this, R.color.success_green_bg))
            binding.btnPermOverlay.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        } else {
            binding.btnPermOverlay.text = getString(R.string.status_action_grant)
            binding.btnPermOverlay.isEnabled = true
        }

        // 2. Accessibility
        val accessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            this,
            FlowAccessibilityService::class.java
        )
        if (accessibilityEnabled) {
            binding.btnPermAccessibility.text = getString(R.string.status_enabled)
            binding.btnPermAccessibility.isEnabled = false
            binding.btnPermAccessibility.setBackgroundColor(ContextCompat.getColor(this, R.color.success_green_bg))
            binding.btnPermAccessibility.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        } else {
            binding.btnPermAccessibility.text = getString(R.string.status_action_grant)
            binding.btnPermAccessibility.isEnabled = true
        }

        // 3. Microphone
        val micGranted = PermissionUtils.isRecordAudioGranted(this)
        if (micGranted) {
            binding.btnPermMic.text = getString(R.string.status_enabled)
            binding.btnPermMic.isEnabled = false
            binding.btnPermMic.setBackgroundColor(ContextCompat.getColor(this, R.color.success_green_bg))
            binding.btnPermMic.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        } else {
            binding.btnPermMic.text = getString(R.string.status_action_grant)
            binding.btnPermMic.isEnabled = true
        }
    }
}
