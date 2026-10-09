package com.story.launcher

import android.content.Context
import org.json.JSONObject

/** Settings > QUICK ACCESS (owner's design). One of three:
 *  - "s3dial"   Station 3 with dial pad (Station 3 as it always was)
 *  - "separate" Station 3 dial pad separate: Station 3 without its dial pad button; the dial pad lives on the lock screen
 *  - "dialonly" Dial pad only: no Station 3; the dial pad on the lock screen
 *  where     = Station 3 shows on "pages", "apps" or "both"
 *  aod       = Station 3 on STORY's Always On Display (the same switch the setup screen always had)
 *  lockDial  = the dial pad on the lock screen (always open, flush to the bottom) - separate / dial pad only
 *  dialAod   = the dial pad on the Always On Display too */
object QuickAccess {
    private fun p(c: Context) = c.getSharedPreferences("story", Context.MODE_PRIVATE)
    fun mode(c: Context) = p(c).getString("qa_mode", "s3dial") ?: "s3dial"
    fun where(c: Context) = p(c).getString("qa_where", "both") ?: "both"
    fun s3Aod(c: Context) = p(c).getBoolean("aod", false)
    fun lockDial(c: Context) = mode(c) != "s3dial" && p(c).getBoolean("qa_lockdial", false)
    fun dialAod(c: Context) = lockDial(c) && p(c).getBoolean("aod_dial", false)
    fun station3On(c: Context) = mode(c) != "dialonly"
    /** Station 3's dial pad button only in "Station 3 with dial pad". */
    fun dialInStation3(c: Context) = mode(c) == "s3dial"
    /** STORY's Always On Display screen runs when anything is meant to show on it. */
    fun aodScreen(c: Context) = (station3On(c) && s3Aod(c)) || dialAod(c)

    fun json(c: Context): String = JSONObject()
        .put("mode", mode(c)).put("where", where(c)).put("s3Aod", s3Aod(c))
        .put("lockDial", p(c).getBoolean("qa_lockdial", false)).put("dialAod", p(c).getBoolean("aod_dial", false))
        .toString()

    fun save(c: Context, s: String) {
        val j = runCatching { JSONObject(s) }.getOrNull() ?: return
        val e = p(c).edit()
        j.optString("mode").takeIf { it in listOf("s3dial", "separate", "dialonly") }?.let { e.putString("qa_mode", it) }
        j.optString("where").takeIf { it in listOf("pages", "apps", "both") }?.let { e.putString("qa_where", it) }
        if (j.has("s3Aod")) e.putBoolean("aod", j.optBoolean("s3Aod"))
        if (j.has("lockDial")) e.putBoolean("qa_lockdial", j.optBoolean("lockDial"))
        if (j.has("dialAod")) e.putBoolean("aod_dial", j.optBoolean("dialAod"))
        e.apply()
    }
}
