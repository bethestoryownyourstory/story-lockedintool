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
        // The lock screen asks for your PIN / password / pattern: STORY's lock screen dial pad gets out of the way
        // at once, so it can never cover it (owner: never get locked out of your own phone).
        if (pkg == "com.android.systemui") {
            if (e.isPassword || ENTRY_TREE_CLASS.containsMatchIn(e.className?.toString() ?: "")) OverlayService.instance?.lockBouncerShown(sure = true)
            else if (looksLikeUnlockEntry(e)) OverlayService.instance?.lockBouncerShown(sure = false)
        }
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
    /** Weaker signs (a bouncer container, the lock screen saying "PIN"...): only trusted when STORY can't see
     *  the screen itself - a failed fingerprint's "use PIN" hint must not send the dial pad away. */
    private fun looksLikeUnlockEntry(e: AccessibilityEvent): Boolean {
        val cls = e.className?.toString() ?: ""
        if (ENTRY_CLASS.containsMatchIn(cls)) return true
        // What the lock screen announces when it asks (e.g. "PIN area", "Enter PIN", "Draw your pattern").
        val said = (e.text.joinToString(" ") + " " + (e.contentDescription ?: "")).lowercase()
        return e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            Regex("\\bpin\\b|password|pattern|enter.*code").containsMatchIn(said)
    }
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean { instance = null; refreshOverlay(); return super.onUnbind(intent) }

    // Station 3 moves into / out of the lock-screen-capable layer when this service turns on / off.
    private fun refreshOverlay() {
        if (OverlayService.running) runCatching { startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REBUILD)) }
    }

    companion object {
        var instance: StoryAccessibilityService? = null
        private val ENTRY_CLASS = Regex("Keyguard.*(PIN|Pin|Password|Pattern|Sim|Puk)|PasswordTextView|PinView|PatternView|NumPadKey|Bouncer")
        private val ENTRY_ID = Regex("/(pinEntry|passwordEntry|lockPatternView|keyguard_(pin|password|pattern|sim_pin|sim_puk)_view|key_enter|key[0-9])$")
        // On screen (not just in the event stream): only the entry itself counts - never a bouncer container,
        // which some phones keep "visible" even when it isn't showing.
        private val ENTRY_TREE_CLASS = Regex("PasswordTextView|NumPadKey|LockPatternView|Keyguard(PIN|Pin|Password|Pattern|SimPin|SimPuk)View")

        /** Is the phone's own PIN / password / pattern page on screen RIGHT NOW? Looks at what SystemUI is
         *  actually showing. true / false, or null when it can't tell (no window access) - then the old
         *  event-based guard is used instead. */
        fun unlockEntryShowing(): Boolean? = systemUiShows { n ->
            n.isPassword || (n.viewIdResourceName ?: "").let { it.isNotEmpty() && ENTRY_ID.containsMatchIn(it) } ||
                ENTRY_TREE_CLASS.containsMatchIn(n.className?.toString() ?: "")
        }

        // The phone's own quick settings / notification panel pulled down (its tiles, brightness slider...).
        private val SHADE_ID = Regex("/(quick_settings_panel|quick_qs_panel|qs_panel|qs_tile.*|tile_label|brightness.*|qs_brightness.*)$")
        private val SHADE_CLASS = Regex("QSTile|QSPanel|BrightnessSlider|ToggleSlider")
        // Quick settings tiles you can tap (Wi-Fi, Bluetooth...) - never on a lock screen at rest (its status icons
        // say "Bluetooth on" too, but can't be tapped).
        private val SHADE_WORDS = Regex("^(wi-?fi|bluetooth|internet|mobile data|airplane|aeroplane|brightness|open settings|settings)\\b")
        /** Is the phone's quick settings panel pulled down right now? (null = can't tell) */
        fun shadeShowing(): Boolean? = systemUiShows { n ->
            if ((n.viewIdResourceName ?: "").let { it.isNotEmpty() && SHADE_ID.containsMatchIn(it) }) return@systemUiShows true
            if (SHADE_CLASS.containsMatchIn(n.className?.toString() ?: "")) return@systemUiShows true
            val said = ((n.contentDescription ?: n.text ?: "").toString()).trim().lowercase()
            said.isNotEmpty() && SHADE_WORDS.containsMatchIn(said) && (n.isClickable || (n.className?.toString() ?: "").endsWith("SeekBar"))
        }

        /** Does any visible part of SystemUI's windows match? true / false, or null when STORY can't see them. */
        private fun systemUiShows(match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): Boolean? {
            val s = instance ?: return null
            val ws = runCatching { s.windows }.getOrNull() ?: return null
            var looked = false
            for (w in ws) {
                val r = runCatching { w.root }.getOrNull() ?: continue
                if (r.packageName?.toString() != "com.android.systemui") continue
                looked = true
                if (find(r, 0, intArrayOf(0), match)) return true
            }
            return if (looked) false else null
        }
        private fun find(n: android.view.accessibility.AccessibilityNodeInfo, depth: Int, count: IntArray,
                         match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): Boolean {
            if (depth > 40 || ++count[0] > 1500) return false
            if (!n.isVisibleToUser) return false
            if (match(n)) return true
            for (i in 0 until n.childCount) {
                val c = runCatching { n.getChild(i) }.getOrNull() ?: continue
                if (find(c, depth + 1, count, match)) return true
            }
            return false
        }
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
