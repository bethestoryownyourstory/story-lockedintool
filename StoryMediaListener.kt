package com.story.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets STORY see every media player on the phone and whether it's playing (Android only shares that
 * with apps given notification access). Used so music playing in the background is never paused
 * when the Pages open. It also reads the payment notifications from your bank / payment apps for the
 * Money and Wallet pages (MoneyLog - kept only on this phone); STORY never changes or removes a notification.
 */
class StoryMediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        // Payments already sitting in the notification shade count too (MoneyLog skips ones it already has).
        runCatching { activeNotifications?.forEach { runCatching { MoneyLog.handle(this, it) } } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) runCatching { MoneyLog.handle(this, sbn) }
    }
}
