package com.flowvoice.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class FlowApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID_VOICE_SERVICE,
                "FlowVoice Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps voice dictation running smoothly across apps"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        lateinit var instance: FlowApplication
            private set
        const val CHANNEL_ID_VOICE_SERVICE = "flow_voice_channel"
    }
}
