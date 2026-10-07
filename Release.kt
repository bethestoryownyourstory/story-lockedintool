package com.story.launcher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * STORY's two release channels.
 *  - Test: the owner's phone. Gets every new build and the newest screens straight away, silently.
 *  - Live: everyone else. Gets a build only after the owner publishes it, and is asked first
 *    ("Update available": Update now / Later); after 3 days of Later the update is required.
 *
 * Owner mode is unlocked on the setup screen (tap the build line 7 times) with a PIN that is
 * checked by the STORY release server, never inside the app. The server's address is read from
 * release.json in the repo, so it can be set or changed without a new build.
 */
object Release {
    private const val GH = "https://github.com/bethestoryownyourstory/story-lockedintool/releases/download/"
    const val TEST_BASE = GH + "latest/"
    const val LIVE_BASE = GH + "live/"
    private const val CONFIG = "https://raw.githubusercontent.com/bethestoryownyourstory/story-lockedintool/main/release.json"
    const val REQUIRED_AFTER_MS = 3 * 24 * 60 * 60_000L
    const val LATER_MS = 24 * 60 * 60_000L

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("story", Context.MODE_PRIVATE)

    fun isOwner(ctx: Context) = prefs(ctx).getBoolean("owner", false)
    fun setOwner(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("owner", on).apply() }
    fun base(ctx: Context) = if (isOwner(ctx)) TEST_BASE else LIVE_BASE

    /** The user signed "Automatic updates - I agree" once: updates install by themselves, never asked again. */
    fun agreedAuto(ctx: Context) = prefs(ctx).getBoolean("auto_agreed", false)
    fun setAgreedAuto(ctx: Context) { prefs(ctx).edit().putBoolean("auto_agreed", true).apply() }

    // ---- Live users: the pending update and its 3-day clock ----
    /** A newer Live version was found: start (or keep) the 3-day clock. */
    fun notePending(ctx: Context, version: Long) {
        val p = prefs(ctx)
        if (p.getLong("upd_version", 0) == 0L) p.edit().putLong("upd_first_seen", System.currentTimeMillis()).apply()
        p.edit().putLong("upd_version", version).apply()
    }
    fun clearPending(ctx: Context) { prefs(ctx).edit().remove("upd_version").remove("upd_first_seen").remove("upd_later_until").apply() }
    fun hasPending(ctx: Context) = prefs(ctx).getLong("upd_version", 0) > 0
    fun isRequired(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getLong("upd_version", 0) > 0 && System.currentTimeMillis() - p.getLong("upd_first_seen", 0) >= REQUIRED_AFTER_MS
    }
    fun later(ctx: Context) { prefs(ctx).edit().putLong("upd_later_until", System.currentTimeMillis() + LATER_MS).apply() }
    fun laterUntil(ctx: Context) = prefs(ctx).getLong("upd_later_until", 0)

    // ---- The release server (PIN check + publishing) ----
    class Answer(val ok: Boolean, val message: String)

    /** Run off the main thread. path = "verify" or "publish". */
    fun askServer(path: String, pin: String): Answer = try {
        val server = JSONObject(get("$CONFIG?t=${System.currentTimeMillis()}")).optString("server").trimEnd('/')
        if (server.isEmpty()) Answer(false, "The release server isn't set up yet.")
        else {
            val c = (URL("$server/$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 30000
                setRequestProperty("Content-Type", "application/json")
            }
            c.outputStream.use { it.write(JSONObject().put("pin", pin).toString().toByteArray()) }
            val body = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            val j = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            Answer(j.optBoolean("ok"), j.optString("error", "Server answered ${c.responseCode}"))
        }
    } catch (e: Throwable) { Answer(false, "Couldn't reach the release server: ${e.message}") }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 15000; readTimeout = 15000; useCaches = false
        inputStream.bufferedReader().use { it.readText() }
    }
}
