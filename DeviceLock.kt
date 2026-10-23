package com.story.launcher

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** "Make Lockedintool permanent" (owner, Oct 2026: 'make Lockedintool impossible to delete ... it can not be removed';
 *  an opt-in choice under Plans - the user agrees by scrolling through the whole message first; on the owner's own
 *  phone it's on). When on, Lockedintool is a device administrator, so Android won't let it be uninstalled or the
 *  admin switched off the normal way.
 *
 *  HONEST LIMIT (never pretend otherwise): this is the strongest a normal app can do. Someone with the phone in their
 *  hands and the right tools can still wipe it - a factory reset (erases the whole phone) or re-flashing removes ANY
 *  app. Only Device Owner (set during factory-reset provisioning) or software the phone's maker bakes into the
 *  firmware is stronger. Pref 'story' permanent = the user's choice (the real lock is `active`). */
class LitDeviceAdmin : DeviceAdminReceiver() {
    // Shown by Android if someone tries to switch the admin off (the step before an uninstall).
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Lockedintool is set to stay on this phone. Turning this off lets Lockedintool be removed."
}

object DeviceLock {
    private const val PREF = "permanent"
    fun component(c: Context) = ComponentName(c, LitDeviceAdmin::class.java)
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    /** The real lock: Lockedintool is an active device admin (can't be uninstalled the normal way). */
    fun active(c: Context): Boolean = runCatching { dpm(c).isAdminActive(component(c)) }.getOrDefault(false)
    /** The user's choice, kept across updates. */
    fun wanted(c: Context): Boolean = c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean(PREF, false)
    fun setWanted(c: Context, on: Boolean) = c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()

    /** The Android screen that asks the user to make Lockedintool a device administrator. */
    fun requestIntent(c: Context): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(c))
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Lockedintool stays on this phone and can't be uninstalled or switched off the normal way. You agreed to this in Lockedintool.")
}
