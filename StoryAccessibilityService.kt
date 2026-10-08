package com.story.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

class StoryAccessibilityService : AccessibilityService() {
    // Android keeps this service connected, so it's also a good moment to bring STORY back if it was switched on.
    override fun onServiceConnected() { instance = this; KeepAlive.ensureRunning(this); refreshOverlay() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            // Which app is on screen (so music playing in the background can be told apart from it).
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg == packageName) return
                val cls = e.className?.toString() ?: return
                if (runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess) foregroundPkg = pkg
            }
            // A media player's notification: remember its session, to see later whether it's playing.
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val n = e.parcelableData as? Notification ?: return
                val token = if (Build.VERSION.SDK_INT >= 33) n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
                    else @Suppress("DEPRECATION") n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
                if (token != null) mediaSessions[pkg] = token
            }
        }
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { instance = null; refreshOverlay(); return super.onUnbind(intent) }

    // Station 3 moves into / out of the lock-screen-capable layer when this service turns on / off.
    private fun refreshOverlay() {
        if (OverlayService.running) runCatching { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REBUILD)) }
    }

    companion object {
        var instance: StoryAccessibilityService? = null
        @Volatile var foregroundPkg: String? = null
        private val mediaSessions = ConcurrentHashMap<String, MediaSession.Token>()

        /** Music: something playing from an app that isn't the one on screen (Spotify, YouTube Music...). */
        fun backgroundMusicPlaying(c: Context): Boolean {
            // With STORY's music access: every player on the phone, including ones started before STORY.
            val all = runCatching {
                c.getSystemService(android.media.session.MediaSessionManager::class.java)
                    .getActiveSessions(ComponentName(c, StoryMediaListener::class.java))
            }.getOrNull()
            if (all != null && all.any { it.packageName != foregroundPkg && it.playbackState?.state == PlaybackState.STATE_PLAYING }) return true
            // Without it: the players STORY has seen through their notifications.
            return mediaSessions.entries.any { (pkg, token) ->
                pkg != foregroundPkg && runCatching { MediaController(c, token).playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
            }
        }
        fun hasMusicAccess(c: Context) = runCatching {
            c.getSystemService(android.media.session.MediaSessionManager::class.java).getActiveSessions(ComponentName(c, StoryMediaListener::class.java)); true
        }.getOrDefault(false)
    }
}
