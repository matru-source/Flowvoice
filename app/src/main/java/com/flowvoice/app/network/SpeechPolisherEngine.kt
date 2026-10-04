package com.flowvoice.app.network

import android.content.Context
import android.util.Log
import com.flowvoice.app.data.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SpeechPolisherEngine(context: Context) {

    private val prefs = PreferencesManager(context)

    suspend fun processAudio(
        audioFile: File,
        onStatusUpdate: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                Exception("API Key is missing. Please set your API Key in FlowVoice settings.")
            )
        }

        val provider = prefs.provider
        val tone = prefs.tone

        Log.d(TAG, "Processing audio with provider: $provider, tone: $tone")

        try {
            when (provider) {
                PreferencesManager.PROVIDER_GROQ,
                PreferencesManager.PROVIDER_OPENAI -> {
                    val client = GroqClient(apiKey)

                    withContext(Dispatchers.Main) { onStatusUpdate("Transcribing voice...") }
                    val asrResult = client.transcribeAudio(audioFile)
                    if (asrResult.isFailure) {
                        return@withContext Result.failure(asrResult.exceptionOrNull()!!)
                    }

                    val rawTranscript = asrResult.getOrNull().orEmpty()
                    Log.d(TAG, "Raw transcript: $rawTranscript")

                    if (rawTranscript.isBlank()) {
                        return@withContext Result.failure(Exception("No speech detected"))
                    }

                    withContext(Dispatchers.Main) { onStatusUpdate("Polishing English...") }
                    val polishResult = client.polishEnglish(rawTranscript, tone)
                    if (polishResult.isFailure) {
                        // Fallback to raw transcript if LLM step fails
                        return@withContext Result.success(rawTranscript)
                    }

                    val polishedText = polishResult.getOrNull().orEmpty()
                    Log.d(TAG, "Polished text: $polishedText")
                    Result.success(polishedText)
                }

                PreferencesManager.PROVIDER_SARVAM -> {
                    val client = SarvamClient(apiKey)

                    withContext(Dispatchers.Main) { onStatusUpdate("Transcribing Indic speech...") }
                    val asrResult = client.transcribeAudio(audioFile)
                    if (asrResult.isFailure) {
                        return@withContext Result.failure(asrResult.exceptionOrNull()!!)
                    }

                    val rawTranscript = asrResult.getOrNull().orEmpty()
                    if (rawTranscript.isBlank()) {
                        return@withContext Result.failure(Exception("No speech detected"))
                    }

                    withContext(Dispatchers.Main) { onStatusUpdate("Translating to English...") }
                    val polishResult = client.translateAndPolish(rawTranscript, tone)
                    Result.success(polishResult.getOrDefault(rawTranscript))
                }

                else -> Result.failure(Exception("Unsupported AI provider: $provider"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process audio failed", e)
            Result.failure(e)
        } finally {
            // Clean up temporary audio file
            try {
                if (audioFile.exists()) {
                    audioFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp audio file", e)
            }
        }
    }

    companion object {
        private const val TAG = "SpeechPolisherEngine"
    }
}
