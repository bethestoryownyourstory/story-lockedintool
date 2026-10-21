package com.story.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.app.Notification
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Dashboard's music card (owner): ANY music player on the phone, not just Spotify. Layouts > Dashboard > Music player
 * picks which one (pref 'story' music_player; empty = whatever is playing). Shows what's playing (title, artist, cover,
 * playing / paused) from the phone's media sessions (needs Lockedintool's notification access) and controls it
 * (play / pause, previous, next). Holding the card opens the player app.
 */
object MusicPlayer {
    private fun prefs(c: Context) = c.getSharedPreferences("story", Context.MODE_PRIVATE)
    fun chosen(c: Context): String = prefs(c).getString("music_player", "") ?: ""
    fun choose(c: Context, pkg: String) { prefs(c).edit().putString("music_player", pkg).apply() }

    /** Every app on the phone that can play music: music apps + apps with a media browser service (Spotify, YouTube Music...). */
    fun players(c: Context): String {
        val pm = c.packageManager
        val pkgs = LinkedHashSet<String>()
        runCatching { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0).forEach { pkgs.add(it.activityInfo.packageName) } }
        runCatching { pm.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0).forEach { pkgs.add(it.serviceInfo.packageName) } }
        runCatching { sessions(c).forEach { pkgs.add(it.packageName) } }
        val out = JSONArray()
        pkgs.filter { it != c.packageName && pm.getLaunchIntentForPackage(it) != null }
            .map { it to runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrDefault(it) }
            .sortedBy { it.second.lowercase() }
            .forEach { (p, n) -> out.put(JSONObject().put("pkg", p).put("name", n)) }
        return out.toString()
    }

    private fun sessions(c: Context): List<MediaController> = runCatching {
        c.getSystemService(android.media.session.MediaSessionManager::class.java).getActiveSessions(ComponentName(c, StoryMediaListener::class.java))
    }.getOrDefault(emptyList())

    /** Playing (or about to: buffering, skipping...). */
    private fun on(s: PlaybackState?) = when (s?.state) {
        PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING, PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING, PlaybackState.STATE_SKIPPING_TO_NEXT, PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> true
        else -> false
    }
    /** The music app that played last: with nothing playing the card (and its buttons) stay on it - never jump to another app. */
    private fun remember(c: Context, pkg: String) { if (pkg.isNotEmpty() && pkg != c.packageName && prefs(c).getString("music_last", "") != pkg) prefs(c).edit().putString("music_last", pkg).apply() }
    private fun last(c: Context) = prefs(c).getString("music_last", "") ?: ""
    // A player's session from its media notification: still reachable when Android no longer lists it (paused a long time).
    private val tokens = java.util.concurrent.ConcurrentHashMap<String, MediaSession.Token>()
    fun noteNotification(c: Context, sbn: StatusBarNotification) {
        val ex = sbn.notification?.extras ?: return
        val t = if (Build.VERSION.SDK_INT >= 33) ex.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            else @Suppress("DEPRECATION") ex.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (t != null) { tokens[sbn.packageName] = t; if (on(runCatching { MediaController(c, t).playbackState }.getOrNull())) remember(c, sbn.packageName) }
    }
    private fun controllerFor(c: Context, pkg: String): MediaController? {
        if (pkg.isEmpty()) return null
        val l = sessions(c).filter { it.packageName == pkg }
        (l.firstOrNull { on(it.playbackState) } ?: l.firstOrNull { it.metadata != null } ?: l.firstOrNull())?.let { return it }
        return tokens[pkg]?.let { runCatching { MediaController(c, it) }.getOrNull() }?.takeIf { it.playbackState != null || it.metadata != null }
    }

    /** The session the card follows: the chosen player's; with none chosen, the one playing, else the last music app that played. */
    private fun session(c: Context): MediaController? {
        val want = chosen(c)
        if (want.isNotEmpty()) return controllerFor(c, want)
        val all = sessions(c)
        all.firstOrNull { on(it.playbackState) }?.let { remember(c, it.packageName); return it }
        return controllerFor(c, last(c)) ?: all.firstOrNull()
    }

    private fun label(c: Context, pkg: String) = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    fun state(c: Context): String {
        val o = JSONObject().put("access", StoryAccessibilityService.hasMusicAccess(c)).put("chosen", chosen(c))
        val want = chosen(c)
        if (want.isNotEmpty()) o.put("chosenName", label(c, want))
        val s = session(c) ?: return o.toString()
        val md = s.metadata
        o.put("pkg", s.packageName)
            .put("app", label(c, s.packageName))
            .put("playing", on(s.playbackState))
            .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "")
            .put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "")
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (art != null) runCatching {
            val b = Bitmap.createScaledBitmap(art, 96, 96 * art.height / maxOf(1, art.width), true)
            val bos = java.io.ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.JPEG, 80, bos)
            o.put("art", "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP))
        }
        return o.toString()
    }

    /** Old screens: play / pause / toggle / next / prev, no answer. */
    fun command(c: Context, cmd: String) = commandFor(c, cmd, "") { }

    // ---- The card's buttons (owner: a paused song wouldn't play again). The command goes to the card's own player and is
    //      CHECKED: if the player didn't react, the next way is tried (its controls, then a media button like headphones,
    //      then play/pause). Runs once per tap on its own thread, waits on the player's own callbacks (no loop), at most ~3s;
    //      a newer tap cancels the rest of an older one. Lockedintool lets go of the sound it quietened under the Pages. ----
    private val worker by lazy { Handler(HandlerThread("story-music").apply { start() }.looper) }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var gen = 0
    fun commandFor(c: Context, cmd0: String, cardPkg: String, report: (String) -> Unit) {
        val my = ++gen
        worker.post {
            val r = runCatching { runCmd(c, cmd0, cardPkg) { my != gen } }.getOrElse { JSONObject().put("cmd", cmd0).put("ok", false).put("why", "error") }
            if (my == gen) report(r.toString())
        }
    }
    private fun key(code: Int, send: (KeyEvent) -> Unit) {
        val t = SystemClock.uptimeMillis()
        send(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0)); send(KeyEvent(t, t + 10, KeyEvent.ACTION_UP, code, 0))
    }
    /** Waits (up to ms) for the player to report what we asked for. */
    private fun await(ctl: MediaController, ms: Long, ok: () -> Boolean): Boolean {
        if (ok()) return true
        val latch = CountDownLatch(1)
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) { if (ok()) latch.countDown() }
            override fun onMetadataChanged(metadata: MediaMetadata?) { if (ok()) latch.countDown() }
            override fun onSessionDestroyed() { latch.countDown() }
        }
        runCatching { ctl.registerCallback(cb, main) }
        try { latch.await(ms, TimeUnit.MILLISECONDS) } finally { runCatching { ctl.unregisterCallback(cb) } }
        return ok()
    }
    private fun runCmd(c: Context, cmd0: String, cardPkg: String, stale: () -> Boolean): JSONObject {
        val am = c.getSystemService(AudioManager::class.java)
        val pkg = chosen(c).ifEmpty { cardPkg }.ifEmpty { last(c) }
        val ctl = controllerFor(c, pkg) ?: (if (chosen(c).isEmpty()) session(c) else null)
        val target = ctl?.packageName ?: pkg
        val cmd = if (cmd0 == "toggle") (if (on(ctl?.playbackState)) "pause" else "play") else cmd0
        val out = JSONObject().put("cmd", cmd).put("pkg", target).put("app", if (target.isEmpty()) "" else label(c, target))
        if (cmd == "play") OverlayService.instance?.musicWanted()
        val want: Boolean? = when (cmd) { "play" -> true; "pause" -> false; else -> null }
        val code = when (cmd) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS }
        if (ctl != null) {
            val t0 = ctl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val ok = { if (want != null) on(ctl.playbackState) == want else ctl.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != t0 }
            // play / pause: its controls, then a media button, then play/pause (each only if the player still isn't there - a slow
            // player is never toggled back). next / previous: its controls, and a media button only if nothing changed at all.
            val steps = if (want != null) listOf<Pair<String, () -> Unit>>(
                "controls" to { val t = ctl.transportControls; if (want) t.play() else t.pause() },
                "button" to { key(code) { ctl.dispatchMediaButtonEvent(it) } },
                "playpause" to { if (on(ctl.playbackState) != want) key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { ctl.dispatchMediaButtonEvent(it) } })
            else listOf<Pair<String, () -> Unit>>(
                "controls" to { val t = ctl.transportControls; if (cmd == "next") t.skipToNext() else t.skipToPrevious() },
                "button" to { key(code) { ctl.dispatchMediaButtonEvent(it) } })
            for ((how, act) in steps) {
                if (stale()) return out.put("ok", false).put("why", "replaced")
                runCatching { act() }
                if (await(ctl, if (how == "playpause") 1100L else if (want == null) 1200L else 800L, ok)) {
                    if (cmd == "play") remember(c, target)
                    return out.put("ok", true).put("how", how)
                }
            }
            return out.put("ok", false).put("why", "refused")
        }
        // No session left at all (the player was closed): the phone's own media key, exactly like a headphone button - Android
        // gives it to the player that played last and lets it start again.
        val before = am.isMusicActive
        runCatching { key(code) { am.dispatchMediaKeyEvent(it) } }
        if (want == true) { val t = SystemClock.uptimeMillis(); while (!am.isMusicActive && SystemClock.uptimeMillis() - t < 1500) SystemClock.sleep(150) }
        val now = session(c)
        now?.packageName?.let { out.put("pkg", it).put("app", label(c, it)) }
        val worked = if (want == true) am.isMusicActive else if (want == false) !am.isMusicActive || before != am.isMusicActive else true
        return out.put("ok", worked).put("how", "mediakey").put("why", if (worked) "" else if (now == null && target.isEmpty()) "no-player" else "refused")
    }

    /** The app the card belongs to: the chosen player, else the one playing, else the last music app. */
    fun appToOpen(c: Context): String = chosen(c).ifEmpty { session(c)?.packageName ?: last(c) }
}
