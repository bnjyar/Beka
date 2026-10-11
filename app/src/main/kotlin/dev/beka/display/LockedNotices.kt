package dev.beka.display

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import dev.beka.BekaApp
import dev.beka.R

/**
 * While the phone is locked or dark the overlay is out of sight, so a background run asks for
 * confirmation and reports its result through notifications instead (fase 9).
 */
class LockedNotices(private val context: Context) {

    /** Screen on and unlocked: the overlay's cards can be seen. */
    fun phoneInUse(): Boolean =
        context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Posts Allow / Deny; the answer reaches [dev.beka.chat.ChatSession.answerConfirm]. */
    fun postConfirm(title: String, text: String) {
        val allow = Notification.Action.Builder(null, context.getString(R.string.notif_allow), answer(true))
        // Approving a send from the lock screen must need the screen lock (pattern/PIN); refusing does not.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) allow.setAuthenticationRequired(true)
        val allowAction = allow.build()
        val deny = Notification.Action.Builder(null, context.getString(R.string.notif_deny), answer(false)).build()
        notify(
            CONFIRM_ID,
            builder(CONFIRM_CHANNEL, context.getString(R.string.notif_channel_confirm), NotificationManager.IMPORTANCE_HIGH, title, text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setOngoing(true)
                .addAction(allowAction)
                .addAction(deny)
                // Shown on a lock screen that hides sensitive content: the buttons stay, the details do not.
                .setPublicVersion(
                    builder(CONFIRM_CHANNEL, context.getString(R.string.notif_channel_confirm), NotificationManager.IMPORTANCE_HIGH, context.getString(R.string.notif_confirm_public_title), context.getString(R.string.notif_confirm_public_text))
                        .addAction(allowAction)
                        .addAction(deny)
                        .build()
                )
                .build(),
        )
    }

    fun cancelConfirm() = context.getSystemService(NotificationManager::class.java).cancel(CONFIRM_ID)

    /** Quiet: waits in the shade without lighting up a locked screen (a high-importance one did). */
    fun postResult(title: String, text: String) {
        val public = builder(RESULT_CHANNEL, context.getString(R.string.notif_channel_result), NotificationManager.IMPORTANCE_LOW, context.getString(R.string.notif_result_public_title), context.getString(R.string.notif_result_public_text)).build()
        notify(RESULT_ID, builder(RESULT_CHANNEL, context.getString(R.string.notif_channel_result), NotificationManager.IMPORTANCE_LOW, title, text).setAutoCancel(true).setPublicVersion(public).build())
    }

    private fun builder(channel: String, name: String, importance: Int, title: String, text: String): Notification.Builder {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(OLD_CHANNEL)
        manager.createNotificationChannel(NotificationChannel(channel, name, importance))
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
    }

    private fun notify(id: Int, notification: Notification) =
        context.getSystemService(NotificationManager::class.java).notify(id, notification)

    private fun answer(allow: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context, if (allow) 1 else 0,
        Intent(context, Receiver::class.java).setAction(ACTION_ANSWER).putExtra(EXTRA_ALLOW, allow),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Not exported: only the notification's own buttons reach it. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_ANSWER) return
            val app = context.applicationContext as BekaApp
            val allow = intent.getBooleanExtra(EXTRA_ALLOW, false)
            // Android 11 cannot make the button ask for the screen lock: there, yes only counts unlocked.
            if (allow && Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            ) return
            app.lockedNotices.cancelConfirm()
            app.chat.answerConfirm(allow)
        }
    }

    private companion object {
        /** v0.1.84–93 used one high-importance channel for both; importance cannot change once created. */
        const val OLD_CHANNEL = "background"
        const val CONFIRM_CHANNEL = "background_confirm"
        const val RESULT_CHANNEL = "background_result"
        const val CONFIRM_ID = 7101
        const val RESULT_ID = 7102
        const val ACTION_ANSWER = "dev.beka.BACKGROUND_ANSWER"
        const val EXTRA_ALLOW = "allow"
    }
}
