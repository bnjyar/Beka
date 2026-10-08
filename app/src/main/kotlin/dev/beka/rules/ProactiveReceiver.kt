package dev.beka.rules

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.beka.BekaApp

/** Beka's own broadcasts: the schedule alarm and the "Salah trigger" button. Not exported. */
class ProactiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BekaApp
        when (intent.action) {
            ACTION_ALARM -> app.proactive.onAlarm()
            ACTION_WRONG -> {
                context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
                app.proactive.markWrong(intent.getLongExtra(EXTRA_RUN, -1))
            }
        }
    }

    companion object {
        private const val ACTION_ALARM = "dev.beka.RULE_ALARM"
        private const val ACTION_WRONG = "dev.beka.RULE_WRONG"
        private const val EXTRA_RUN = "run"
        private const val EXTRA_NOTIFICATION = "notification"

        fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ProactiveReceiver::class.java).setAction(ACTION_ALARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun wrongIntent(context: Context, runId: Long, notificationId: Int): PendingIntent = PendingIntent.getBroadcast(
            context, notificationId,
            Intent(context, ProactiveReceiver::class.java).setAction(ACTION_WRONG)
                .putExtra(EXTRA_RUN, runId)
                .putExtra(EXTRA_NOTIFICATION, notificationId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** Alarms do not survive a reboot or an update; set the next one again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        (context.applicationContext as BekaApp).proactive.reschedule()
    }
}
