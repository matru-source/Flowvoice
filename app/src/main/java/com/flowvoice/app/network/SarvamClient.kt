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
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SarvamClient(private val apiKey: String) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeAudio(audioFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val audioMediaType = "audio/m4a".toMediaTypeOrNull()
            val fileBody = audioFile.asRequestBody(audioMediaType)

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "saarika:v2")
                .addFormDataPart("file", audioFile.name, fileBody)
                .build()

            val request = Request.Builder()
                .url("https://api.sarvam.ai/speech-to-text")
                .header("api-subscription-key", apiKey)
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Sarvam ASR failed: HTTP ${response.code} -> $bodyString")
                return@withContext Result.failure(Exception("Sarvam ASR failed: HTTP ${response.code}"))
            }

            val json = JSONObject(bodyString)
            val transcript = json.optString("transcript", "")
            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "Sarvam ASR network error", e)
            Result.failure(e)
        }
    }

    suspend fun translateAndPolish(rawTranscript: String, tone: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Sarvam Translation API or fallback translation
            val payload = JSONObject().apply {
                put("input", rawTranscript)
                put("source_language_code", "hi-IN")
                put("target_language_code", "en-IN")
                put("mode", "formal")
            }

            val requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("https://api.sarvam.ai/translate")
                .header("api-subscription-key", apiKey)
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                Log.e(TAG, "Sarvam Translate failed: HTTP ${response.code} -> $bodyString")
                return@withContext Result.failure(Exception("Sarvam translation failed: HTTP ${response.code}"))
            }

            val json = JSONObject(bodyString)
            val translatedText = json.optString("translated_text", rawTranscript)
            Result.success(translatedText)
        } catch (e: Exception) {
            Log.e(TAG, "Sarvam translate network error", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "SarvamClient"
    }
}
