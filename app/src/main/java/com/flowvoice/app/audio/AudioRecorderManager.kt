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

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(file.absolutePath)

            try {
                prepare()
                start()
                isRecording = true
                Log.d(TAG, "Recording started -> ${file.absolutePath}")
            } catch (e: IOException) {
                Log.e(TAG, "MediaRecorder prepare failed", e)
                release()
                mediaRecorder = null
                isRecording = false
                return null
            } catch (e: IllegalStateException) {
                Log.e(TAG, "MediaRecorder start failed", e)
                release()
                mediaRecorder = null
                isRecording = false
                return null
            }
        }

        return currentOutputFile
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
