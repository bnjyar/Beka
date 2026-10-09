package dev.beka.media

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.beka.BekaApp

/**
 * Notification access is what Android requires to list other apps' media sessions. Contents are
 * only read for apps the user made a notification rule for (see [dev.beka.rules.Proactive]), and
 * when the user asks the assistant to read their notifications (see [dev.beka.phone.AndroidPhone]).
 */
class BekaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        (application as BekaApp).proactive.onNotification(sbn)
    }

    companion object {
        /** The connected listener, or null while notification access is off. */
        @Volatile
        var instance: BekaNotificationListener? = null
            private set
    }
}
