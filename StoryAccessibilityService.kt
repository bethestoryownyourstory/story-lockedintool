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
        // Windows moved / focus changed (the phone's panel pulled down or closed...): Station 3 checks whether the
        // phone's own panel is in front (OverlayService.windowsMoved - one short look, after things settle).
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) OverlayService.instance?.windowsMoved()
        val pkg = e.packageName?.toString() ?: return
        // (The phone-wide adult-content filter that read other apps' browser address bars was REMOVED here - owner,
        //  27 Oct 2026 - because reading other apps' screen content is a stalkerware signal that kept Google Play
        //  Protect flagging the app as "Harmful". Adult content is still blocked inside Lockedintool's own search.)
        // The lock screen asks for your PIN / password / pattern: STORY's lock screen dial pad gets out of the way
        // at once, so it can never cover it (owner: never get locked out of your own phone).
        if (pkg == "com.android.systemui") {
            if (e.isPassword || ENTRY_TREE_CLASS.containsMatchIn(e.className?.toString() ?: "")) OverlayService.instance?.lockBouncerShown(sure = true)
            else if (looksLikeUnlockEntry(e)) OverlayService.instance?.lockBouncerShown(sure = false)
        }
        when (e.eventType) {
            // Which app is on screen (so music playing in the background can be told apart from it).
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = e.className?.toString() ?: return
                val activity = runCatching { packageManager.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
                if (activity) frontPkg = pkg   // Lockedintool's own screens too (Lockedintool as your system)
                if (pkg == packageName) return
                if (activity) { foregroundPkg = pkg; OverlayService.instance?.foregroundChanged(pkg) }
            }
            // The home screen moved (swiping between screens, the app drawer...) or windows changed: STORY's live
            // widgets step aside and come back once it's still.
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> if (pkg == foregroundPkg) OverlayService.instance?.widgetLiveEvent(true)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> OverlayService.instance?.widgetLiveEvent(false)
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

    // (The phone-wide adult-content filter that lived here - reading other apps' browser address bars and blocking
    //  adult apps by package - was REMOVED on 27 Oct 2026 to clear Google Play Protect's "Harmful app" flag, since an
    //  unremovable app that reads what you do in other apps is the stalkerware fingerprint. Adult content is still
    //  blocked inside Lockedintool's own search bars - that never leaves the app, so it's safe. ContentGuard.kt and
    //  BlockActivity.kt are no longer used by this service.)

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
        /** Unlocked: is the phone's own panel (notifications / quick settings - or another SystemUI screen such as the
         *  power menu) in front right now? It takes the screen's focus while it's pulled down and gives it back when
         *  it closes (a notification popping up at the top doesn't). null = can't tell. */
        fun panelInFront(): Boolean? {
            val s = instance ?: return null
            val ws = runCatching { s.windows }.getOrNull() ?: return null
            return ws.any { w -> w.isFocused && w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                runCatching { w.root?.packageName?.toString() }.getOrNull() == "com.android.systemui" }
        }
        // The phone's own output switcher / Media output panel (SystemUI, or Samsung's media panel) - never Settings.
        private val OUT_PKGS = Regex("^com\\.android\\.systemui$|^com\\.samsung\\.android\\..*(mdx|quickboard|media)")
        /** Media output (OutputSwitchActivity): the phone's own list is up - tap the output picked in Lockedintool (an
         *  exact name only; from the quick panel its Media output first), then close it. Not found in ~3 s: the list
         *  stays up for you to pick. A few short looks only while it's up - nothing runs otherwise. */
        fun pickOutput(names: List<String>, viaPanel: Boolean) {
            val want = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            if (want.isEmpty() || instance == null) return
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            var panelOpen = !viaPanel
            var tries = 0
            val step = object : Runnable {
                override fun run() {
                    val s = instance ?: return
                    if (++tries > 20) return
                    if (!panelOpen) {
                        if (clickIn { said(it) == "media output" }) { panelOpen = true; tries = 0 }
                        h.postDelayed(this, 150); return
                    }
                    if (clickIn { said(it) in want }) {
                        h.postDelayed({
                            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                            if (viaPanel && Build.VERSION.SDK_INT >= 31) h.postDelayed({ instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }, 350)
                        }, 700)
                        return
                    }
                    h.postDelayed(this, 150)
                }
            }
            h.postDelayed(step, 250)
        }
        private fun said(n: android.view.accessibility.AccessibilityNodeInfo) = (n.text ?: n.contentDescription ?: "").toString().trim().lowercase()
        /** Taps the first visible node in the phone's output switcher / panel that matches (or the row holding it). */
        private fun clickIn(match: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean): Boolean {
            val s = instance ?: return false
            val ws = runCatching { s.windows }.getOrNull() ?: return false
            for (w in ws) {
                val r = runCatching { w.root }.getOrNull() ?: continue
                if (!OUT_PKGS.containsMatchIn(r.packageName?.toString() ?: "")) continue
                var hit: android.view.accessibility.AccessibilityNodeInfo? = null
                find(r, 0, intArrayOf(0)) { n -> if (match(n)) { hit = n; true } else false }
                var c = hit ?: continue
                var up = 0
                while (!c.isClickable && up < 6) { c = c.parent ?: break; up++ }
                if (c.isClickable && c.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            return false
        }
        private val STORY_WIDGET = Regex("^(?:Lockedintool|STORY) .+ widget #(\\d+)$")
        /** Where each STORY widget is on the home screen right now (widget id -> its place on the screen). */
        fun storyWidgets(home: String): Map<Int, android.graphics.Rect> {
            val s = instance ?: return emptyMap()
            val out = HashMap<Int, android.graphics.Rect>()
            val ws = runCatching { s.windows }.getOrNull() ?: return out
            for (w in ws) {
                val r = runCatching { w.root }.getOrNull() ?: continue
                if (r.packageName?.toString() != home) continue
                find(r, 0, intArrayOf(0)) { n ->
                    STORY_WIDGET.find(n.contentDescription?.toString() ?: "")?.let { m ->
                        val b = android.graphics.Rect(); n.getBoundsInScreen(b)
                        if (b.width() > 0 && b.height() > 0) out[m.groupValues[1].toInt()] = b
                    }
                    false
                }
            }
            return out
        }
        @Volatile var foregroundPkg: String? = null
        @Volatile var frontPkg: String? = null
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
