package com.movtery.zalithlauncher.game.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.widget.Toast
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Foreground service (mediaProjection) yang memegang sesi rekam layar. */
class RecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val ok = data != null && ScreenRecorder.start(this, intent.getIntExtra(EXTRA_CODE, 0), data)
                if (!ok) stopSelf()
            }
            else -> {
                val file = ScreenRecorder.lastFile
                val wasRecording = ScreenRecorder.recording.value
                ScreenRecorder.stop()
                if (wasRecording) {
                    Toast.makeText(this, getString(com.movtery.zalithlauncher.R.string.maou_record_saved, file?.absolutePath ?: ""), Toast.LENGTH_LONG).show()
                }
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ScreenRecorder.stop()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rekam layar", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, RecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, applicationInfo.icon), "Berhenti rekam", stopPi
        ).build()
        val n = builder.setSmallIcon(applicationInfo.icon)
            .setContentTitle("Merekam layar...")
            .setContentText("Ketuk Berhenti rekam untuk menyelesaikan")
            .addAction(stopAction)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, n)
        }
    }

    companion object {
        const val ACTION_START = "maou.record.START"
        const val ACTION_STOP = "maou.record.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "maou_record"
        private const val ID = 7721
    }
}
