package com.slm.wnet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class WnetService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): WnetService = this@WnetService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
    }

    private fun startForegroundService() {
        val channelId = "WnetConnectionChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Wnet Service", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Wnet متصل")
            .setContentText("الشبكة نشطة في الخلفية")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .build()

        startForeground(1, notification)
    }
    
    // هنا سيتم نقل منطق الاتصال لاحقاً لضمان بقائه حياً
    // لتبسيط الأمر حالياً، مجرد وجود الـ Service سيمنع النظام من قتل التطبيق بسرعة
}
