package com.flowvoice.app.network

import android.content.Context
import android.util.Log
import com.flowvoice.app.data.PreferencesManager
import com.flowvoice.app.data.ToneVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SpeechPolisherEngine(context: Context) {

    private val prefs = PreferencesManager(context)

    suspend fun processAudioForTones(
        audioFile: File,
        onStatusUpdate: (String) -> Unit
    ): Result<ToneVariants> = withContext(Dispatchers.IO) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                Exception("API Key is missing. Please set your API Key in FlowVoice settings.")
            )
        }

        val provider = prefs.provider

        try {
            when (provider) {
                PreferencesManager.PROVIDER_GROQ,
                PreferencesManager.PROVIDER_OPENAI -> {
                    val client = GroqClient(apiKey)

                    withContext(Dispatchers.Main) { onStatusUpdate("Transcribing voice...") }
                    val asrResult = client.transcribeAudio(audioFile)
                    if (asrResult.isFailure) {
                        val err = asrResult.exceptionOrNull() ?: Exception("Transcription failed")
                        return@withContext Result.failure(err)
                    }

                    val rawTranscript = asrResult.getOrNull().orEmpty()
                    if (rawTranscript.isBlank()) {
                        return@withContext Result.failure(Exception("No speech detected"))
                    }

                    withContext(Dispatchers.Main) { onStatusUpdate("Polishing tones...") }
                    val tonesResult = client.polishAllTones(rawTranscript)
                    if (tonesResult.isFailure) {
                        return@withContext Result.success(
                            ToneVariants(casual = rawTranscript, semiFormal = rawTranscript, formal = rawTranscript)
                        )
                    }

                    val variants = tonesResult.getOrNull() ?: ToneVariants(rawTranscript, rawTranscript, rawTranscript)
                    Result.success(variants)
                }

                PreferencesManager.PROVIDER_SARVAM -> {
                    val client = SarvamClient(apiKey)

                    withContext(Dispatchers.Main) { onStatusUpdate("Transcribing Indic speech...") }
                    val asrResult = client.transcribeAudio(audioFile)
                    if (asrResult.isFailure) {
                        val err = asrResult.exceptionOrNull() ?: Exception("Transcription failed")
                        return@withContext Result.failure(err)
                    }

                    val rawTranscript = asrResult.getOrNull().orEmpty()
                    if (rawTranscript.isBlank()) {
                        return@withContext Result.failure(Exception("No speech detected"))
                    }

                    withContext(Dispatchers.Main) { onStatusUpdate("Translating to English...") }
                    val polishResult = client.translateAndPolish(rawTranscript, prefs.tone)
                    val text = polishResult.getOrDefault(rawTranscript)
                    Result.success(ToneVariants(casual = text, semiFormal = text, formal = text))
                }

                else -> Result.failure(Exception("Unsupported AI provider: $provider"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Process audio failed", e)
            Result.failure(e)
        } finally {
            try {
                if (audioFile.exists()) {
                    audioFile.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete temp audio file", e)
            }
        }
    }

    suspend fun processAudio(
        audioFile: File,
        onStatusUpdate: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val result = processAudioForTones(audioFile, onStatusUpdate)
        if (result.isSuccess) {
            val variants = result.getOrNull()!!
            val tone = prefs.tone
            val text = when {
                tone.contains("Casual", ignoreCase = true) -> variants.casual
                tone.contains("Formal", ignoreCase = true) -> variants.formal
                else -> variants.semiFormal
            }
            Result.success(text)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Processing error"))
        }
    }

    companion object {
        private const val TAG = "SpeechPolisherEngine"
    }
}
