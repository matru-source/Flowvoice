package com.flowvoice.app.network

import android.util.Log
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
                .addFormDataPart(
                    "prompt",
                    "Hindi, Hinglish, Tamil, Telugu, Malayalam, Kannada, code-switching conversational Indian English"
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
            val transcript = json.optString("text", "")
            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "Groq ASR network error", e)
            Result.failure(e)
        }
    }

    suspend fun polishEnglish(rawTranscript: String, tone: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = """
                You are an expert voice-to-English communication assistant like Superflow.
                The user dictates thoughts in Hindi, Tamil, Telugu, Kannada, Malayalam, or Hinglish (code-mixed with colloquialisms and filler words).
                
                YOUR GOAL:
                1. Translate and refine the transcript into natural, fluent, grammatically polished English.
                2. Selected Tone: $tone.
                   - If 'Natural & Fluent': Smooth conversational English, retaining exact meaning.
                   - If 'Professional / Work': Crisp, executive business English (suitable for Slack, Email, LinkedIn).
                   - If 'Casual / Chat': Friendly, concise messaging style (WhatsApp, DM).
                   - If 'Concise & Direct': Short, punchy, no unnecessary fluff.
                3. Remove filler words (matlab, yaani, like, you know, um, toh).
                4. Fix grammar, tense, and awkward Indian language literal translations.
                5. Output ONLY the polished English text. Do not enclose in quotes. Do not add explanations.
            """.trimIndent()

            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", rawTranscript))
            }

            val payload = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("messages", messages)
                put("temperature", 0.3)
                put("max_tokens", 800)
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

            if (!response.isSuccessful) {
                Log.e(TAG, "Groq LLM polishing failed: HTTP ${response.code} -> $bodyString")
                return@withContext Result.failure(Exception("Polishing failed: HTTP ${response.code}"))
            }

            val json = JSONObject(bodyString)
            val choices = json.getJSONArray("choices")
            if (choices.length() > 0) {
                val polishedText = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                Result.success(polishedText)
            } else {
                Result.failure(Exception("Empty LLM response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq LLM network error", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "GroqClient"
    }
}
