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
    companion object {
        @Volatile var instance: StoryMediaListener? = null
        /** The notifications on the phone right now (Dashboard > pull down = STORY's Notifications Panel): app, title,
         *  text, time. Ongoing ones (music, downloads...), group summaries and STORY's own are left out. */
        fun current(c: android.content.Context): String {
            val out = org.json.JSONArray()
            val list = runCatching { instance?.activeNotifications }.getOrNull() ?: return out.toString()
            val pm = c.packageManager
            for (sbn in list.sortedByDescending { it.postTime }) {
                val n = sbn.notification ?: continue
                if (sbn.packageName == c.packageName || sbn.isOngoing || (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) continue
                val ex = n.extras ?: continue
                val title = ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT))?.toString().orEmpty()
                if (title.isBlank() && text.isBlank()) continue
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
                out.put(org.json.JSONObject().put("key", sbn.key).put("pkg", sbn.packageName).put("app", app).put("title", title.take(120)).put("text", text.take(400)).put("ts", sbn.postTime))
                if (out.length() >= 100) break
            }
            return out.toString()
        }
    }
    override fun onListenerDisconnected() { if (instance === this) instance = null; super.onListenerDisconnected() }
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        // Payments already sitting in the notification shade count too (MoneyLog skips ones it already has).
        runCatching { activeNotifications?.forEach { runCatching { MoneyLog.handle(this, it) } } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) runCatching { MoneyLog.handle(this, sbn) }
        OverlayService.instance?.evalPages("window.storyNotifsChanged&&window.storyNotifsChanged();window.storyMusicChanged&&window.storyMusicChanged()")
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        OverlayService.instance?.evalPages("window.storyNotifsChanged&&window.storyNotifsChanged();window.storyMusicChanged&&window.storyMusicChanged()")
    }
}
