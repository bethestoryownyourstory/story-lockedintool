package com.story.launcher

import android.content.Context
import android.telecom.TelecomManager

/** Lockedintool as your system (owner, a section on the Subscription page): the DASHBOARD is your home screen, so the
 *  phone's own home screen is sent to the Dashboard and you always land back on Lockedintool. Your apps all still work
 *  (owner, 28 Oct 2026) - open them from the Apps button, app pages or recent apps; only the bare home screen redirects.
 *  It only goes off by logging out or deleting your account (owner).
 *  Always still works: phone calls (the phone's dialer / call screen) and emergency, the lock screen and the phone's
 *  own panel, Android's installer (Lockedintool's updates) and permission questions, and whatever Lockedintool itself
 *  opens for one of its own features (camera, gallery, your bank's approval page, a document, a permission switch...):
 *  every launch Lockedintool makes lets the app it opens through (grant), while it stays in front. */
object SystemMode {
    private const val PREF = "system_mode"
    fun on(c: Context): Boolean = c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean(PREF, false)
    fun set(c: Context, v: Boolean) { c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putBoolean(PREF, v).apply() }

    private val ALWAYS = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.server.telecom", "com.android.incallui",
        "com.android.emergency", "com.google.android.apps.safetyhub", "com.samsung.android.emergency",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.documentsui", "com.google.android.documentsui",
        "com.android.providers.media.module", "com.google.android.providers.media.module", "com.android.printspooler",
        "com.google.android.gms")

    @Volatile private var graceUntil = 0L
    @Volatile private var grantedPkg: String? = null
    /** Lockedintool is opening something itself: the app that comes up next is let through. */
    fun grant() { graceUntil = System.currentTimeMillis() + 20_000 }
    /** The Pages are back: what Lockedintool opened before doesn't count any more. */
    fun reset() { grantedPkg = null; graceUntil = 0L }

    fun allowed(c: Context, pkg: String, home: String?): Boolean {
        if (pkg == c.packageName || pkg in ALWAYS || pkg == grantedPkg) return true
        val dialer = runCatching { (c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage }.getOrNull()
        if (pkg == dialer) return true
        if (pkg != home && System.currentTimeMillis() < graceUntil) { grantedPkg = pkg; graceUntil = 0L; return true }
        return false
    }

    /** Apps work in Only system now (owner, 28 Oct 2026: 'apps on dashboard for only system are not available please
     *  fix that'): opening an app / app page / trading platform / sharing to an app is allowed - the Dashboard stays
     *  your home, nothing else is blocked. Kept for its callers; it never refuses. */
    fun refuses(c: Context): Boolean = false

}
