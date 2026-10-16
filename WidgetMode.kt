package com.story.launcher

import android.content.Context

/** Only use widgets (owner, Settings): Lockedintool is only its home screen widgets - the Pages, Station 3, the dial
 *  pads, the bottom line, the Always On Display and everything else are gone. Turned off (or on) only by opening the
 *  Lockedintool app, which then shows just this one switch. Not together with Only system. */
object WidgetMode {
    private const val PREF = "widgets_only"
    fun on(c: Context): Boolean = c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean(PREF, false)
    fun set(c: Context, v: Boolean): Boolean {
        if (v && SystemMode.on(c)) return false
        c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putBoolean(PREF, v).apply()
        OverlayService.instance?.widgetModeChanged()
        return true
    }
}
