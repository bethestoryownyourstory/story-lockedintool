package com.story.launcher

import android.content.Context

/** Lockedintool is an APP first; being a SYSTEM is a feature you switch on (owner, 5 Nov 2026: 'Let's make lockedintool
 *  an app also. The systems will be a feature that someone will choose for themselves. If turned on lockedintool will
 *  work as a system'; Settings > System switch, turned on / off only after the phone's own fingerprint / face / PIN).
 *  OFF = a pure app (owner chose 'Nothing - pure app'): you open it from its icon and use the Dashboard and Pages inside
 *  its own window; nothing runs outside it - no Station 3 over apps, no Apps | Lockedintool switch, no Lockedintool lock
 *  screen, no Always On Display, no lock-screen dial pad / Wallet button, no live widgets. Messages and calls still come
 *  in as notifications. ON = Second system or Only system. Phones that already ran Lockedintool before this keep
 *  working as a system (pref 'enabled' was set); new installs start as the app. */
object AppMode {
    private const val PREF = "system_on"
    fun systemOn(c: Context): Boolean {
        val p = c.getSharedPreferences("story", Context.MODE_PRIVATE)
        if (!p.contains(PREF)) { val v = p.getBoolean("enabled", false); p.edit().putBoolean(PREF, v).apply(); return v }
        return p.getBoolean(PREF, true)
    }
    fun appOnly(c: Context) = !systemOn(c)
    fun set(c: Context, on: Boolean) { c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply() }
}
