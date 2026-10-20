package com.story.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.KeyEvent
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

    /** The session the card follows: the chosen player's; with none chosen, the one playing (else the newest). */
    private fun session(c: Context): MediaController? {
        val all = sessions(c); val want = chosen(c)
        if (want.isNotEmpty()) return all.firstOrNull { it.packageName == want }
        return all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }

    fun state(c: Context): String {
        val o = JSONObject().put("access", StoryAccessibilityService.hasMusicAccess(c)).put("chosen", chosen(c))
        val want = chosen(c)
        if (want.isNotEmpty()) o.put("chosenName", runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(want, 0)).toString() }.getOrDefault(want))
        val s = session(c) ?: return o.toString()
        val md = s.metadata
        o.put("pkg", s.packageName)
            .put("app", runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(s.packageName, 0)).toString() }.getOrDefault(s.packageName))
            .put("playing", s.playbackState?.state == PlaybackState.STATE_PLAYING)
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

    /** play / pause / toggle / next / prev on the player. Nothing playing yet: the chosen player is told to play. */
    fun command(c: Context, cmd: String) {
        val s = session(c)
        // 'Whatever is playing' (no player chosen): the phone's own media keys, exactly like headphone buttons - Android
        // sends them to the player in use and lets it start playing again (a paused song wouldn't restart otherwise).
        if (chosen(c).isEmpty()) {
            val playing = s?.playbackState?.state == PlaybackState.STATE_PLAYING
            val key = when (cmd) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; else -> if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY }
            if (runCatching { val am = c.getSystemService(android.media.AudioManager::class.java)
                    for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) am.dispatchMediaKeyEvent(KeyEvent(a, key)) }.isSuccess) return
        }
        if (s != null) {
            // Sent like a headphone / media button first (owner: tapping play on a paused song did nothing): Android lets
            // the player start playing again from a media button, while a plain play() from another app is often refused.
            val playing = s.playbackState?.state == PlaybackState.STATE_PLAYING
            val key = when (cmd) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; else -> if (playing) KeyEvent.KEYCODE_MEDIA_PAUSE else KeyEvent.KEYCODE_MEDIA_PLAY }
            val sent = runCatching { s.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, key)) && s.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, key)) }.getOrDefault(false)
            if (sent) return
            val t = s.transportControls
            when (cmd) {
                "next" -> t.skipToNext()
                "prev" -> t.skipToPrevious()
                "play" -> t.play()
                "pause" -> t.pause()
                else -> if (s.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            }
            return
        }
        val pkg = chosen(c)
        val code = when (cmd) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; else -> KeyEvent.KEYCODE_MEDIA_PLAY }
        // No live session (a player paused for a while drops it): the phone's own media key goes to the player that
        // played last - exactly like pressing play on headphones.
        if (pkg.isEmpty()) {
            runCatching { val am = c.getSystemService(android.media.AudioManager::class.java)
                for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) am.dispatchMediaKeyEvent(KeyEvent(a, code)) }
            return
        }
        for (a in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) runCatching {
            c.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(a, code)))
        }
    }

    /** The app the card belongs to: the chosen player, else the one playing. */
    fun appToOpen(c: Context): String = chosen(c).ifEmpty { session(c)?.packageName ?: "" }
}
