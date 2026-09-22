package com.algotix.wa_hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.abs

/**
 * Android's WebView ships no Notification API, so BootScript supplies one and
 * routes it here. Each profile gets its own notification channel and group, which
 * is what makes two accounts distinguishable in the shade — and lets the user mute
 * one account without muting the others.
 */
class HostNotifications(
    private val context: Context,
    private val onAction: (profileId: String, notificationId: String, action: String) -> Unit,
) {

    private val manager = NotificationManagerCompat.from(context)
    private val channels = HashSet<String>()
    private val posted = HashMap<String, MutableSet<String>>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val profileId = intent.getStringExtra(EXTRA_PROFILE) ?: return
            val notificationId = intent.getStringExtra(EXTRA_ID) ?: return
            val action = if (intent.action == ACTION_CLICK) CLICK else DISMISS
            if (action == CLICK) {
                context.startActivity(
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra(EXTRA_PROFILE, profileId)
                    }
                )
            }
            onAction(profileId, notificationId, action)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(ACTION_CLICK)
            addAction(ACTION_DISMISS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun dispose() {
        runCatching { context.unregisterReceiver(receiver) }
        channels.clear()
        posted.clear()
    }

    fun post(
        profileId: String,
        profileName: String,
        notificationId: String,
        title: String,
        body: String,
        silent: Boolean,
    ) {
        val channelId = ensureChannel(profileId, profileName)
        val tag = "$profileId/$notificationId"
        val numericId = abs(tag.hashCode())

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title.ifBlank { profileName })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSubText(profileName)
            .setGroup("profile-$profileId")
            .setAutoCancel(true)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(broadcast(ACTION_CLICK, profileId, notificationId, numericId))
            .setDeleteIntent(broadcast(ACTION_DISMISS, profileId, notificationId, numericId))

        runCatching { manager.notify(tag, numericId, builder.build()) }
            .onSuccess { posted.getOrPut(profileId) { HashSet() }.add(notificationId) }
    }

    /**
     * Fallback used when the count of unread chats rises but the page never
     * called the Notification API — which happens when WhatsApp routes through
     * its service worker, where a document-start script cannot reach.
     *
     * One replaceable notification per account rather than one per message, so
     * it can never pile up.
     */
    fun postUnreadSummary(profileId: String, profileName: String, unread: Int) {
        if (unread <= 0) { clearUnreadSummary(profileId); return }
        val channelId = ensureChannel(profileId, profileName)
        val text = if (unread == 1) "1 unread chat" else "$unread unread chats"
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(profileName)
            .setContentText(text)
            .setNumber(unread)
            .setGroup("profile-$profileId")
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(broadcast(ACTION_CLICK, profileId, SUMMARY, summaryId(profileId)))
            .setDeleteIntent(broadcast(ACTION_DISMISS, profileId, SUMMARY, summaryId(profileId)))
        runCatching { manager.notify(summaryTag(profileId), summaryId(profileId), builder.build()) }
    }

    fun clearUnreadSummary(profileId: String) {
        runCatching { manager.cancel(summaryTag(profileId), summaryId(profileId)) }
    }

    fun areNotificationsAllowed(): Boolean =
        runCatching { manager.areNotificationsEnabled() }.getOrDefault(false)

    private fun summaryTag(profileId: String) = "$profileId/unread"
    private fun summaryId(profileId: String) = abs(summaryTag(profileId).hashCode())

    fun cancel(profileId: String, notificationId: String) {
        val tag = "$profileId/$notificationId"
        manager.cancel(tag, abs(tag.hashCode()))
        posted[profileId]?.remove(notificationId)
    }

    fun clearProfile(profileId: String) {
        posted.remove(profileId)?.forEach { cancel(profileId, it) }
    }

    private fun ensureChannel(profileId: String, profileName: String): String {
        val id = "profile-$profileId"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !channels.add(id)) return id
        val channel = NotificationChannel(id, profileName, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Messages for $profileName"
            enableVibration(true)
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return id
    }

    private fun broadcast(action: String, profileId: String, id: String, requestCode: Int) =
        PendingIntent.getBroadcast(
            context,
            abs((action + profileId + id).hashCode()),
            Intent(action).setPackage(context.packageName)
                .putExtra(EXTRA_PROFILE, profileId)
                .putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val CLICK = "click"
        const val DISMISS = "dismiss"
        const val EXTRA_PROFILE = "profileId"
        private const val SUMMARY = "unread"
        private const val EXTRA_ID = "notificationId"
        private const val ACTION_CLICK = "com.algotix.wa_hub.NOTIFICATION_CLICK"
        private const val ACTION_DISMISS = "com.algotix.wa_hub.NOTIFICATION_DISMISS"
    }
}
