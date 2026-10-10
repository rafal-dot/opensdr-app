package org.opensdr.amhost.sdr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.opensdr.amhost.AmHostService
import org.opensdr.amhost.MainActivity

/** Notification and wake lock owned by the service that owns the receiver. */
internal class ReceiverForegroundNotification(private val service: Service) {
    private var wakeLock: PowerManager.WakeLock? = null

    fun show(frequencyText: String?) {
        createNotificationChannel()
        service.startForeground(NOTIFICATION_ID, buildNotification(frequencyText))
        acquireWakeLock()
    }

    fun release() {
        try {
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } finally {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = service.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun buildNotification(frequencyText: String?): Notification {
        val launchIntent = Intent(service, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(service, 0, launchIntent, flags)
        val stopAudioIntent = Intent(service, AmHostService::class.java).apply {
            action = AmHostService.ACTION_STOP_AUDIO_FROM_NOTIFICATION
        }
        val stopAudioPendingIntent = PendingIntent.getService(
            service,
            STOP_AUDIO_REQUEST_CODE,
            stopAudioIntent,
            flags,
        )
        val title = if (frequencyText.isNullOrBlank()) {
            "OpenSDR Radio Host active"
        } else {
            "OpenSDR Radio ${frequencyText} MHz"
        }
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentTitle(title)
            .setContentText("Background receiver audio is running")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop audio", stopAudioPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = service.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Receiver Audio",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps SDR receiver audio active while the screen is locked"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "opensdr_am_audio"
        private const val NOTIFICATION_ID = 4201
        private const val STOP_AUDIO_REQUEST_CODE = 4202
        private const val WAKE_LOCK_TAG = "opensdr:am_audio"
    }
}
