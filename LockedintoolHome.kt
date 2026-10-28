package com.story.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Lockedintool as your phone's HOME app - part of ONLY SYSTEM (owner, 28 Oct 2026: 'lockedintool becomes your home
 * screen thats what i meant ... apps should still work ... I dont exit lockedintool'; chose 'Make it my real Home app'
 * after the accessibility trick broke the phone's own nav). When Lockedintool is the phone's Home app, the phone's OWN
 * navigation works 100% normally - swipe up / home button -> the Dashboard (this activity), recents and app-switching
 * exactly like the phone - because the SYSTEM handles them, not Lockedintool fighting them with an overlay redirect.
 *
 * This window stays TRANSPARENT; the visible 'home' is Lockedintool's own Dashboard, drawn as an overlay by
 * OverlayService, so the phone's gesture nav keeps working over it. The owner reaches apps through the Dashboard's APPS
 * button and the phone's Recents. Turning Only system on asks the owner to pick Lockedintool as the Home app (Android's
 * own prompt); they switch their normal launcher back there any time, so it is never a trap.
 */
class LockedintoolHome : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showHome() }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); showHome() }
    override fun onResume() { super.onResume(); showHome() }

    private fun showHome() {
        runCatching {
            // Not set up / started yet -> the setup screen, never a blank home.
            if (!getSharedPreferences("story", MODE_PRIVATE).getBoolean("enabled", false)) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runCatching
            }
            KeepAlive.ensureRunning(this)
            val svc = OverlayService.instance
            if (svc != null) svc.openPages("dashboard")                              // show the Dashboard overlay (the real 'home')
            else startForegroundService(Intent(this, OverlayService::class.java))     // bring it back, it opens on the Dashboard
        }
    }
}
