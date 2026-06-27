package com.family.bankapp.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.family.bankapp.MainActivity
import com.family.bankapp.R

class AppUpdateDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val progress = intent?.getIntExtra(EXTRA_PERCENT, -1)?.takeIf { it >= 0 }
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(this, progressText, progress)
                )
                return START_STICKY
            }
        }
    }

    companion object {
        private const val ACTION_STOP = "com.family.bankapp.update.STOP"
        private const val EXTRA_PERCENT = "percent"
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "app_updates"
        const val CHANNEL_NAME = "App updates"

        private var progressText = "Downloading Family Bank update…"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "In-app update downloads"
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }
        }

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, AppUpdateDownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateProgress(context: Context, progress: AppDownloadProgress) {
            progressText = progress.label.let { label ->
                progress.percent?.let { "$label ($it%)" } ?: label
            }
            val intent = Intent(context, AppUpdateDownloadService::class.java).apply {
                putExtra(EXTRA_PERCENT, progress.percent ?: -1)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, AppUpdateDownloadService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        private fun buildNotification(
            context: Context,
            text: String,
            percent: Int?
        ): Notification {
            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Family Bank update")
                .setContentText(text)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
            if (percent != null) {
                builder.setProgress(100, percent.coerceIn(0, 100), false)
            } else {
                builder.setProgress(0, 0, true)
            }
            return builder.build()
        }
    }
}
