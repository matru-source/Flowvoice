package com.flowvoice.app.network

import android.util.Log
import com.flowvoice.app.data.ToneVariants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GroqClient(private val apiKey: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioMediaType = "audio/m4a".toMediaTypeOrNull()
            val fileBody = audioFile.asRequestBody(audioMediaType)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("file", audioFile.name, fileBody)
                .addFormDataPart("temperature", "0.0")
                .addFormDataPart(
                    "prompt",
                    "Odia, Sambalpuri, Hindi, Hinglish, Tamil, Telugu, Malayalam, Kannada, code-switching conversational Indian English."
                )
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq ASR failed: HTTP ${response.code} -> $bodyString")
                return@withContext Result.failure(Exception("Transcription failed: HTTP ${response.code}"))
            }

            val json = JSONObject(bodyString)
            val transcript = json.optString("text", "").trim()
            if (isHallucinationOrEmpty(transcript)) {
                return@withContext Result.failure(Exception("No clear speech detected. Please speak closer to the mic."))
            }

            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "Groq ASR network error", e)
            Result.failure(e)
        }
    }

    /**
     * Translates any spoken Indian language (Hindi, Odia, Sambalpuri, Tamil, Telugu, etc.)
     * directly into fluent English using Whisper Large v3.
     */
    suspend fun translateAudioToEnglish(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioMediaType = "audio/m4a".toMediaTypeOrNull()
            val fileBody = audioFile.asRequestBody(audioMediaType)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3")
                .addFormDataPart("file", audioFile.name, fileBody)
                .addFormDataPart("temperature", "0.0")
                .addFormDataPart(
                    "prompt",
                    "Translate faithfully into clear, natural English from Odia, Sambalpuri, Hindi, Hinglish, Tamil, Telugu, Kannada, or Malayalam."
                )
                .build()

            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/translations")
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq audio translation failed: HTTP ${response.code} -> $bodyString")
                return@withContext Result.failure(Exception("Translation failed: HTTP ${response.code}"))
            }

            val json = JSONObject(bodyString)
            val translatedText = json.optString("text", "").trim()

            if (isHallucinationOrEmpty(translatedText)) {
                return@withContext Result.failure(Exception("No clear speech detected. Please speak closer to the mic."))
            }

            Result.success(translatedText)
        } catch (e: Exception) {
            Log.e(TAG, "Groq audio translation error", e)
            Result.failure(e)
        }
    }

    private fun isHallucinationOrEmpty(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return true
        val lower = trimmed.lowercase()

        val phrases = listOf(
            "amara.org",
            "subtitles by",
            "subtitles",
            "thank you for watching",
            "thanks for watching",
            "subscribe to",
            "please subscribe",
            "like and subscribe",
            "translated by",
            "sous-titres",
            "untertitel",
            "captioning",
            "closed captions",
            "transcription by",
            "watching!",
            "bye.",
            "[music]",
            "(music)",
            "[applause]",
            "(applause)",
            "[silence]",
            "(silence)"
        )
        if (phrases.any { lower.contains(it) }) return true

        // Filter out single punctuation or single filler word hallucinations from silence
        val clean = lower.replace(Regex("[^a-zA-Z0-9\\u0900-\\u097F\\u0B00-\\u0B7F]"), " ").trim()
        val words = clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        if (words.size == 1 && words[0] in listOf("you", "the", "a", "an", "so", "oh", "um", "uh", "thank", "thanks", "bye")) {
            return true
        }

        return false
    }

    /**
     * Translates raw Indic text (Hindi, Odia, etc.) into clear English using Groq LLM.
     */
    suspend fun translateTextToEnglish(text: String): Result<String> = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isBlank()) return@withContext Result.failure(Exception("No text to translate"))

        val systemPrompt = "You are a professional translator. Translate the following text faithfully into clear, natural, modern English. Output ONLY the translated English text, without markdown, quotes, explanations, or notes."
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", clean))
        }

        val candidateModels = listOf("llama-3.1-8b-instant", "llama-3.3-70b-versatile")
        for (modelName in candidateModels) {
            try {
                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messages)
                    put("temperature", 0.2)
                    put("max_tokens", 350)
                }

                val requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val bodyString = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val json = JSONObject(bodyString)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val translated = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                            .removeSurrounding("\"")
                        if (translated.isNotBlank()) {
                            return@withContext Result.success(translated)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "translateTextToEnglish error with model $modelName", e)
            }
        }
        Result.failure(Exception("Failed to translate text to English"))
    }

    /**
     * Refines the English text into three distinctly different communication styles:
     * Casual, Semi-formal, and Formal.
     */
    suspend fun polishAllTones(englishBaseText: String): Result<ToneVariants> = withContext(Dispatchers.IO) {
        val candidateModels = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")

        val systemPrompt = """
            You are Superflow AI, an elite voice communication assistant.
            You are given a message spoken by the user (which is already in English):
            
            YOUR TASK:
            Generate THREE DISTINCTLY DIFFERENT, highly polished English versions of this message:
            
            1. "casual": Warm, informal, relaxed messaging style (for WhatsApp chats, friends, casual DMs, with a natural emoji).
            2. "semi_formal": Clear, polite, standard everyday professional English (for colleagues, team chats, acquaintances).
            3. "formal": Executive, professional, sophisticated business English (for client emails, corporate reports, managers).
            
            CRITICAL RULES:
            - The three tones MUST be noticeably different in vocabulary, phrasing, and structure.
            - Never return identical sentences for the tones.
            - Output ONLY a valid JSON object with keys: "casual", "semi_formal", "formal".
            
            Example:
            Input: "I will not be going these days, so please focus on your work. Thank you."
            {
              "casual": "Hey, I won't be around for a few days, so please focus on your work! Thanks 👍",
              "semi_formal": "I will not be attending these days, so please concentrate on your work. Thank you.",
              "formal": "Please be advised that I will be unavailable during this period. Kindly prioritize your respective duties. Thank you."
            }
        """.trimIndent()

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", englishBaseText))
        }

        for (modelName in candidateModels) {
            try {
                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("messages", messages)
                    put("temperature", 0.5)
                    put("max_tokens", 600)
                }

                val requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())

                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val bodyString = response.body?.string().orEmpty()

                if (response.isSuccessful) {
                    val json = JSONObject(bodyString)
                    val choices = json.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val content = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()

                        val parsed = extractJsonObject(content)
                        val casual = parsed.optString("casual", "").trim()
                        val semiFormal = parsed.optString("semi_formal", "").trim()
                        val formal = parsed.optString("formal", "").trim()

                        if (casual.isNotBlank() && semiFormal.isNotBlank() && formal.isNotBlank()) {
                            return@withContext Result.success(
                                ToneVariants(
                                    casual = casual,
                                    semiFormal = semiFormal,
                                    formal = formal,
                                    original = englishBaseText
                                )
                            )
                        }
                    }
                } else {
                    Log.w(TAG, "Model $modelName returned HTTP ${response.code}, trying fallback model...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error with model $modelName, trying fallback...", e)
            }
        }

        // Guaranteed fallback if all LLM models encounter issues
        Result.success(generateToneFallbacks(englishBaseText))
    }

    fun generateToneFallbacks(text: String): ToneVariants {
        val clean = text.trim().removeSuffix(".")
        val casual = "Hey, $clean! 👍"
        val semiFormal = "$clean."
        val formal = "Please be advised that $clean."
        return ToneVariants(casual = casual, semiFormal = semiFormal, formal = formal, original = text)
    }

    private fun extractJsonObject(text: String): JSONObject {
        val clean = text.trim()
        val firstBrace = clean.indexOf('{')
        val lastBrace = clean.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace != -1 && lastBrace > firstBrace) {
            val jsonSubstring = clean.substring(firstBrace, lastBrace + 1)
            return JSONObject(jsonSubstring)
        }
        return JSONObject(clean)
    }

    suspend fun polishEnglish(rawTranscript: String, tone: String): Result<String> = withContext(Dispatchers.IO) {
        val result = polishAllTones(rawTranscript)
        if (result.isSuccess) {
            val variants = result.getOrNull()!!
            val text = when {
                tone.contains("Casual", ignoreCase = true) -> variants.casual
                tone.contains("Formal", ignoreCase = true) -> variants.formal
                else -> variants.semiFormal
            }
            Result.success(text)
        } else {
            Result.failure(result.exceptionOrNull()!!)
        }
    }

    companion object {
        private const val TAG = "GroqClient"
    }
}
