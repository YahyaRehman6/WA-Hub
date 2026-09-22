package com.algotix.wa_hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process alive while sessions are open.
 *
 * Linking a WhatsApp account by code means leaving this app to type the code
 * into WhatsApp. While the user is there, Android backgrounds this process and,
 * on Android 12+, *freezes* it. WhatsApp's phone side then waits for the web
 * client to answer the pairing handshake, gets nothing from a frozen process,
 * and reports the link as failed. The same freeze is why a backgrounded profile
 * stops receiving.
 *
 * A foreground service is the sanctioned way to say "this process is doing
 * user-visible work"; the cost is a persistent, silent notification.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            build(count),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        return START_STICKY
    }

    private fun build(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android insists a foreground service shows a notification; MIN
            // importance keeps it out of the status bar and folded away at the
            // bottom of the shade, and the user may switch the channel off
            // entirely — the service keeps running either way.
            manager.deleteNotificationChannel("sessions")
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Background", NotificationManager.IMPORTANCE_MIN).apply {
                        description = "Keeps your sessions connected in the background"
                        setShowBadge(false)
                    },
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Your sessions stay connected"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("WA Hub")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val NOTIFICATION_ID = 7001
        private const val EXTRA_COUNT = "count"

        /**
         * Measured with the service off: Android put the app straight into
         * cached=true empty=true — frozen — and no message reached the phone
         * until it was reopened. Android shows a notification for every
         * foreground service and gives no way to run one without it, so the
         * service stays, with the quietest notification the platform allows:
         * MIN importance, silent, no status-bar icon, hidden on the lock
         * screen. The user can switch its channel off in one tap.
         */
        private const val ENABLED = true

        fun update(context: Context, liveCount: Int) {
            if (!ENABLED) { stop(context); return }
            if (liveCount <= 0) {
                stop(context)
                return
            }
            val intent = Intent(context, KeepAliveService::class.java).putExtra(EXTRA_COUNT, liveCount)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
