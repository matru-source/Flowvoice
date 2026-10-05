package com.flowvoice.app.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    var isRecording: Boolean = false
        private set

    fun startRecording(): File? {
        if (isRecording) {
            stopRecording()
        }

        val audioDir = File(context.cacheDir, "audio_recordings")
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }

        val file = File(audioDir, "dictation_${System.currentTimeMillis()}.m4a")
        currentOutputFile = file

        try {
            // First attempt: VOICE_RECOGNITION source (hardware noise suppression + AGC)
            mediaRecorder = try {
                createRecorder(file, MediaRecorder.AudioSource.VOICE_RECOGNITION)
            } catch (e: Exception) {
                Log.w(TAG, "VOICE_RECOGNITION source failed to prepare, falling back to MIC", e)
                createRecorder(file, MediaRecorder.AudioSource.MIC)
            }

            mediaRecorder?.start()
            isRecording = true
            Log.d(TAG, "Recording started -> ${file.absolutePath}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission not granted", e)
            releaseRecorder()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder start failed", e)
            releaseRecorder()
            return null
        }

        return currentOutputFile
    }

    private fun createRecorder(file: File, audioSource: Int): MediaRecorder {
        return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(audioSource)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(file.absolutePath)
            prepare()
        }
    }

    private fun releaseRecorder() {
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            // Ignore
        } finally {
            mediaRecorder = null
            isRecording = false
        }
    }

    fun stopRecording(): File? {
        if (!isRecording) return currentOutputFile

        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Stop failed or audio file too short", e)
        } finally {
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
            isRecording = false
        }

        return currentOutputFile
    }

    fun cancelRecording() {
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Cancel stop failed", e)
            } finally {
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                isRecording = false
            }
        }

        currentOutputFile?.let {
            if (it.exists()) {
                it.delete()
            }
        }
        currentOutputFile = null
    }

    fun getMaxAmplitude(): Int {
        return if (isRecording) {
            try {
                mediaRecorder?.maxAmplitude ?: 0
            } catch (e: Exception) {
                0
            }
        } else {
            0
        }
    }

    companion object {
        private const val TAG = "AudioRecorderManager"
    }
}
