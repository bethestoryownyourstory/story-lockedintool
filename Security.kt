package com.story.launcher

import android.app.KeyguardManager
import android.content.Context

/** Settings > SECURITY (owner): "Phone's security" (the default - the owner's words: "I can access Lockedintool anywhere,
 *  it just uses my phone's security like when I unlock my phone") or "Lockedintool security" ("every time I try using
 *  anything from Lockedintool I will have to unlock it" - the phone's own fingerprint / face / PIN, asked by Lockedintool
 *  each time you come into it). Pref 'story' sec_mode = phone | lit. */
object Security {
    private const val PREF = "sec_mode"
    fun lit(c: Context): Boolean = c.getSharedPreferences("story", Context.MODE_PRIVATE).getString(PREF, "phone") == "lit"
    /** Lockedintool security unlocks with the phone's own lock - a phone with no screen lock has nothing to ask for. */
    fun phoneSecure(c: Context): Boolean = c.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    fun set(c: Context, lit: Boolean): Boolean {
        if (lit && !phoneSecure(c)) return false
        c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putString(PREF, if (lit) "lit" else "phone").apply()
        OverlayService.instance?.securityChanged()
        return true
    }
}
