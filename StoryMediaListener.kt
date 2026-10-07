package com.story.launcher

import android.service.notification.NotificationListenerService

/**
 * Lets STORY see every media player on the phone and whether it's playing (Android only shares that
 * with apps given notification access). Used so music playing in the background is never paused
 * when the Pages open. STORY doesn't read, change or keep any notifications.
 */
class StoryMediaListener : NotificationListenerService()
