package dev.beka.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.beka.BekaApp

/**
 * Notification access is what Android requires to list other apps' media sessions. Contents are
 * only read for apps the user made a notification rule for (see [dev.beka.rules.Proactive]).
 */
class BekaNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        (application as BekaApp).proactive.onNotification(sbn)
    }
}
