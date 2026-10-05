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
                    "Odia, Sambalpuri, Hindi, Hinglish, Tamil, Telugu, Malayalam, Kannada, code-switching conversational Indian English, Odia-English phrases (e.g. Dekha kohila, Edit kori rokhitha, Kan hauchi, Ketebele asibu)"
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

            // Filter out Whisper silence hallucinations
            val lower = transcript.lowercase()
            if (lower.contains("amara.org") ||
                lower.contains("subtitles by") ||
                lower.contains("thank you for watching") ||
                lower.contains("translated by") ||
                lower.contains("sous-titres") ||
                lower.contains("untertitel") ||
                transcript.length < 2
            ) {
                return@withContext Result.failure(
                    Exception("No clear speech heard. Please speak closer to the mic.")
                )
            }

            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "Groq ASR network error", e)
            Result.failure(e)
        }
    }

    suspend fun polishAllTones(rawTranscript: String): Result<ToneVariants> = withContext(Dispatchers.IO) {
        try {
            val systemPrompt = """
                You are an expert AI voice-to-English communication polish engine like Superflow.
                The user speaks in Odia (ଓଡ଼ିଆ), Sambalpuri, Hindi, Hinglish, Tamil, Telugu, Kannada, Malayalam, or conversational code-mixed Indian English (e.g., "Dekha kohila kan asiba", "Edit kori rokhitha", "Bhai client ko bol do kal tak report bhej denge").
                
                YOUR TASK:
                Convert and polish the user's spoken thoughts into THREE DISTINCT, FLUENT ENGLISH TONES:
                
                1. "casual":
                   - Conversational, warm, relaxed messaging style.
                   - Perfect for WhatsApp chats, close friends, or informal DMs.
                   - Use friendly phrasing or a natural emoji if suitable.
                   
                2. "semi_formal":
                   - Clear, polite, standard natural English.
                   - Perfect for colleagues, everyday work chats, and acquaintances.
                   
                3. "formal":
                   - Sophisticated, professional, executive business English.
                   - Perfect for client communications, corporate emails, Slack, and managers.
                
                CRITICAL INSTRUCTIONS:
                - Accurately translate Odia, Hindi, and regional Indian language phrases into natural English.
                - Eliminate speech disfluencies, fillers (matlab, yaani, like, you know, um, toh, mane), and awkward literal Indian English phrasing.
                - Make sure all 3 tones are NOTICEABLY DIFFERENT in vocabulary and formality.
                - Return ONLY a valid JSON object with keys: "casual", "semi_formal", "formal".
                
                Example 1:
                User: "Kal tak report bhej dunga tension mat lo"
                {
                  "casual": "Don't stress, I'll send over the report by tomorrow! 👍",
                  "semi_formal": "I will send across the report by tomorrow, no worries.",
                  "formal": "Please be assured that I will submit the report by tomorrow."
                }
                
                Example 2:
                User: "Dekha kohila kan heba"
                {
                  "casual": "Hey, tell me what happened! 🤔",
                  "semi_formal": "Please let me know how it went.",
                  "formal": "Kindly advise on the current outcome or status."
                }
            """.trimIndent()

            val messages = JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", rawTranscript))
            }

            val payload = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("messages", messages)
                put("temperature", 0.4)
                put("response_format", JSONObject().put("type", "json_object"))
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
                val content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                val parsed = extractJsonObject(content)
                val casual = parsed.optString("casual", "").ifBlank { rawTranscript }
                val semiFormal = parsed.optString("semi_formal", "").ifBlank { casual }
                val formal = parsed.optString("formal", "").ifBlank { semiFormal }

                Result.success(ToneVariants(casual = casual, semiFormal = semiFormal, formal = formal))
            } else {
                Result.failure(Exception("Empty LLM response"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq LLM network error", e)
            Result.failure(e)
        }
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
