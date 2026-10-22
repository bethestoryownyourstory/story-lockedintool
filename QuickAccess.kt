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
 *  dialAod   = the dial pad on the Always On Display too
 *  dash      = Station 3 on the Dashboard: always open at the bottom of the Dashboard, no button there (owner)
 *  wallet    = the Wallet page button on the lock screen (owner: 'put the wallet button for lock screen under quick access')
 *  walletAod = the Wallet page button on the Always On Display too (owner: 'and also add Always On Display for it') */
object QuickAccess {
    private fun p(c: Context) = c.getSharedPreferences("story", Context.MODE_PRIVATE)
    fun mode(c: Context) = p(c).getString("qa_mode", "s3dial") ?: "s3dial"
    fun where(c: Context) = p(c).getString("qa_where", "both") ?: "both"
    fun s3Aod(c: Context) = p(c).getBoolean("aod", false)
    fun lockDial(c: Context) = mode(c) != "s3dial" && p(c).getBoolean("qa_lockdial", false)
    fun dialAod(c: Context) = lockDial(c) && p(c).getBoolean("aod_dial", false)
    fun station3On(c: Context) = mode(c) != "dialonly"
    fun s3Dash(c: Context) = station3On(c) && p(c).getBoolean("qa_dash", true)
    fun walletLock(c: Context) = p(c).getBoolean("qa_wallet", true)
    fun walletAod(c: Context) = walletLock(c) && p(c).getBoolean("aod_wallet", false)
    /** Station 3's dial pad button only in "Station 3 with dial pad". */
    fun dialInStation3(c: Context) = mode(c) == "s3dial"
    /** STORY's Always On Display screen runs when anything is meant to show on it. */
    fun aodScreen(c: Context) = (station3On(c) && s3Aod(c)) || dialAod(c) || walletAod(c)

    fun json(c: Context): String = JSONObject()
        .put("mode", mode(c)).put("where", where(c)).put("s3Aod", s3Aod(c))
        .put("lockDial", p(c).getBoolean("qa_lockdial", false)).put("dialAod", p(c).getBoolean("aod_dial", false))
        .put("dash", p(c).getBoolean("qa_dash", true))
        .put("wallet", walletLock(c)).put("walletAod", p(c).getBoolean("aod_wallet", false))
        .toString()

    fun save(c: Context, s: String) {
        val j = runCatching { JSONObject(s) }.getOrNull() ?: return
        val e = p(c).edit()
        j.optString("mode").takeIf { it in listOf("s3dial", "separate", "dialonly") }?.let { e.putString("qa_mode", it) }
        j.optString("where").takeIf { it in listOf("pages", "apps", "both") }?.let { e.putString("qa_where", it) }
        if (j.has("s3Aod")) e.putBoolean("aod", j.optBoolean("s3Aod"))
        if (j.has("lockDial")) e.putBoolean("qa_lockdial", j.optBoolean("lockDial"))
        if (j.has("dialAod")) e.putBoolean("aod_dial", j.optBoolean("dialAod"))
        if (j.has("dash")) e.putBoolean("qa_dash", j.optBoolean("dash"))
        if (j.has("wallet")) e.putBoolean("qa_wallet", j.optBoolean("wallet"))
        if (j.has("walletAod")) e.putBoolean("aod_wallet", j.optBoolean("walletAod"))
        e.apply()
    }
}
