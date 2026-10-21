package com.story.launcher

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Problem reports (owner: "something always running that keeps Lockedintool fast and working, and keeps fixing it").
 * Every phone tells Lockedintool's server what went wrong - errors in any window, crashes, a window that broke, an app
 * that was slow to open, music / camera / updates failing - so they get fixed for EVERY phone. Never anything personal:
 * no account, names, messages, money, numbers or links (scrubbed here before anything is kept); the phone is counted by
 * a random install id only. Kept in prefs "problems" (at most 40, each problem once an hour) and sent when there is a
 * connection - nothing runs while there's nothing to send.
 */
object ProblemLog {
    private const val URL = "https://story-msg.percyjuniormaseko0.workers.dev/v1/problems"
    private fun p(c: Context) = c.getSharedPreferences("problems", Context.MODE_PRIVATE)
    @Volatile private var sending = false

    private fun pid(c: Context): String = p(c).getString("pid", null) ?: java.util.UUID.randomUUID().toString().also { p(c).edit().putString("pid", it).apply() }

    /** Takes out anything that could be personal: emails, links (only the site stays), quoted text, long numbers. */
    fun scrub(s: String?): String = (s ?: "").take(600)
        .replace(Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+"), "<email>")
        .replace(Regex("(https?|content|file)://([^/\\s'\"]*)[^\\s'\"]*")) { m -> m.groupValues[1] + "://" + m.groupValues[2] + "/…" }
        .replace(Regex("\"[^\"]{0,200}\"|'[^']{0,200}'|“[^”]{0,200}”"), "\"…\"")
        .replace(Regex("\\d{4,}"), "#")
        .replace(Regex("\\s+"), " ").trim().take(300)

    /** Something went wrong. kind: js / crash / window / slow / music / camera / update ...; place: where (no personal data). */
    fun record(c: Context, kind: String, msg: String?, place: String = "", sync: Boolean = false) {
        runCatching {
            val m = scrub(msg); if (m.isEmpty()) return
            val k = kind.take(24); val pl = scrub(place).take(120)
            val key = "$k|$pl|$m"
            val prefs = p(c); val now = System.currentTimeMillis()
            val seen = JSONObject(prefs.getString("seen", "{}") ?: "{}")
            // The same problem goes once an hour (how often it happened is counted).
            val q = JSONArray(prefs.getString("queue", "[]") ?: "[]")
            for (i in 0 until q.length()) { val it = q.getJSONObject(i); if (it.optString("key") == key) { it.put("n", it.optInt("n", 1) + 1); save(c, q, seen, sync); return } }
            if (now - seen.optLong(key, 0) < 3_600_000) return
            seen.put(key, now)
            if (q.length() >= 40) q.remove(0)
            q.put(JSONObject().put("key", key).put("kind", k).put("msg", m).put("place", pl).put("n", 1))
            save(c, q, seen, sync)
            if (!sync) flushSoon(c)
        }
    }
    private fun save(c: Context, q: JSONArray, seen: JSONObject, sync: Boolean) {
        // Forget old "already sent this hour" marks so the list stays small.
        val now = System.currentTimeMillis(); val keep = JSONObject()
        seen.keys().forEach { k -> if (now - seen.optLong(k, 0) < 3_600_000) keep.put(k, seen.optLong(k)) }
        val e = p(c).edit().putString("queue", q.toString()).putString("seen", keep.toString())
        if (sync) e.commit() else e.apply()
    }

    private val ui by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var flushQueued = false
    fun flushSoon(c: Context) { if (flushQueued) return; flushQueued = true; ui.postDelayed({ flushQueued = false; flush(c) }, 5000) }

    /** Sends what's waiting (in the background). Called when something is recorded and whenever the app checks for updates. */
    fun flush(c: Context) {
        if (sending) return
        val prefs = p(c); val q = JSONArray(prefs.getString("queue", "[]") ?: "[]")
        if (q.length() == 0) return
        sending = true
        Thread {
            try {
                val items = JSONArray(); for (i in 0 until q.length()) { val it = q.getJSONObject(i); items.put(JSONObject().put("kind", it.optString("kind")).put("msg", it.optString("msg")).put("place", it.optString("place")).put("n", it.optInt("n", 1))) }
                val app = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: ""
                val scr = c.getSharedPreferences("story", Context.MODE_PRIVATE).getString("screens_seen", "") ?: ""
                val body = JSONObject().put("pid", pid(c)).put("app", app).put("scr", scr)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})").put("items", items).toString()
                val conn = (java.net.URL(URL).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 10000; readTimeout = 10000
                    setRequestProperty("content-type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode == 200) {
                    // Only what was sent is taken off (new ones may have come in meanwhile).
                    val now = JSONArray(prefs.getString("queue", "[]") ?: "[]"); val left = JSONArray()
                    val sentKeys = (0 until q.length()).map { q.getJSONObject(it).optString("key") }.toSet()
                    for (i in 0 until now.length()) { val it = now.getJSONObject(i); if (it.optString("key") !in sentKeys) left.put(it) }
                    prefs.edit().putString("queue", left.toString()).apply()
                }
                conn.disconnect()
            } catch (_: Throwable) { } finally { sending = false }
        }.start()
    }

    /** A crash anywhere in Lockedintool is written down first (it's sent the next time the app runs), then Android carries on as usual. */
    fun catchCrashes(c: Context) {
        val app = c.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashCatcher) return
        Thread.setDefaultUncaughtExceptionHandler(CrashCatcher(app, prev))
    }
    private class CrashCatcher(val c: Context, val prev: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching {
                val root = generateSequence(e) { it.cause }.take(8).last()
                val at = (e.stackTrace.firstOrNull { it.className.startsWith("com.story") } ?: e.stackTrace.firstOrNull())?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: ""
                record(c, "crash", "${e.javaClass.simpleName}: ${e.message}" + (if (root !== e) " / cause ${root.javaClass.simpleName}: ${root.message}" else ""), "thread ${t.name} at $at", sync = true)
            }
            prev?.uncaughtException(t, e)
        }
    }
}
