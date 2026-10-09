package com.story.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/** Station 3 > Call history (owner): every call made from STORY (the dial pad - Station 3's and the lock screen's -
 *  and STORY-to-STORY calls / video calls once they're built). Each call: who, voice / video, made / got / missed,
 *  when it started, when it ended and how long it lasted. Kept on the phone (prefs "calls", newest first, max 500).
 *  A call is saved the moment it's placed; when it ends, the phone's call log fills in the real end and talk time. */
object CallHistory {
    private fun p(c: Context) = c.getSharedPreferences("story", Context.MODE_PRIVATE)
    private const val MAX = 500

    fun json(c: Context): String = p(c).getString("calls", "[]") ?: "[]"
    private fun list(c: Context) = runCatching { JSONArray(json(c)) }.getOrDefault(JSONArray())
    private fun save(c: Context, a: JSONArray) {
        val out = JSONArray(); for (i in 0 until minOf(a.length(), MAX)) out.put(a.get(i))
        p(c).edit().putString("calls", out.toString()).apply()
    }

    /** A call placed from STORY's dial pad: saved now (start = now), finished later by [finishFromLog]. */
    fun placed(c: Context, number: String): String {
        val id = "c" + System.currentTimeMillis()
        val e = JSONObject().put("id", id).put("number", number).put("kind", "voice").put("dir", "out")
            .put("start", System.currentTimeMillis()).put("pending", true)
        contactName(c, number)?.let { e.put("name", it) }
        val a = list(c); val out = JSONArray().put(e); for (i in 0 until a.length()) out.put(a.get(i))
        save(c, out)
        return id
    }

    /** The phone's call log changed (a call ended): fill in the pending STORY calls from it. Returns true if any changed. */
    fun finishFromLog(c: Context): Boolean {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return false
        val a = list(c); var changed = false
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            if (!e.optBoolean("pending")) continue
            val start = e.optLong("start")
            // Too old to still be waiting for: leave it with only its start.
            if (System.currentTimeMillis() - start > 6 * 3600_000L) { e.remove("pending"); changed = true; continue }
            val digits = e.optString("number").filter { it.isDigit() }.takeLast(9)
            runCatching {
                c.contentResolver.query(CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE),
                    "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?",
                    arrayOf((start - 15_000).toString(), CallLog.Calls.OUTGOING_TYPE.toString()),
                    "${CallLog.Calls.DATE} ASC")?.use { cur ->
                    while (cur.moveToNext()) {
                        val num = (cur.getString(0) ?: "").filter { it.isDigit() }.takeLast(9)
                        if (digits.isNotEmpty() && num != digits) continue
                        val date = cur.getLong(1); val dur = cur.getLong(2)
                        e.put("start", date).put("duration", dur)
                        // Ended = now (the log is written the moment the call ends), never before start + talk time.
                        e.put("end", maxOf(System.currentTimeMillis(), date + dur * 1000))
                        e.remove("pending"); changed = true
                        break
                    }
                }
            }
        }
        if (changed) save(c, a)
        return changed
    }

    /** The contact's name for a number, if STORY may read contacts. */
    private fun contactName(c: Context, number: String): String? {
        if (c.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }
}
