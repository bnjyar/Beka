package dev.beka.media

import android.service.notification.NotificationListenerService

/**
 * Exists so the user can grant notification access, which is what Android requires to list other
 * apps' media sessions. Notification contents are not read here (that comes with proactive rules).
 */
class BekaNotificationListener : NotificationListenerService()
