package com.story.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Optional Home app (owner, 28 Oct 2026: chose 'Lockedintool as your Home app' - so the phone's OWN gestures work
 * inside Lockedintool: swipe up = home -> the Dashboard, swipe up + hold = Recents, exactly like the phone - because
 * the SYSTEM handles them, not Lockedintool re-creating a bar).
 *
 * OPT-IN and reversible: Lockedintool only becomes the home when the owner picks it in Settings > (Default) apps >
 * Home app (the setup screen has a button that opens that picker), and they switch their normal launcher back there
 * any time - so it is never a trap. This window stays TRANSPARENT; the visible "home" is Lockedintool's own Dashboard,
 * drawn as an overlay by OverlayService, so the phone's gesture nav keeps working as normal over it. The owner reaches
 * their apps through Lockedintool's own Apps screen and the phone's Recents.
 */
class LockedintoolHome : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHome()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        showHome()
    }

    override fun onResume() {
        super.onResume()
        showHome()
    }

    private fun showHome() {
        runCatching {
            // Not set up / started yet -> the setup screen, never a blank home.
            if (!getSharedPreferences("story", MODE_PRIVATE).getBoolean("enabled", false)) {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runCatching
            }
            KeepAlive.ensureRunning(this)
            val svc = OverlayService.instance
            if (svc != null) svc.openPages("dashboard")               // show the Dashboard overlay (the real "home")
            else startForegroundService(Intent(this, OverlayService::class.java))   // bring it back, it opens on the Dashboard
        }
    }
}
