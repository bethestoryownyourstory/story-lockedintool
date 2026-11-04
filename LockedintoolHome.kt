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
    companion object { @Volatile var current: LockedintoolHome? = null }
    private var shot: android.widget.ImageView? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Black, never see-through (owner, 4 Nov 2026: a frozen picture of the old home screen showed behind it and nothing
        // could be swiped) - the Dashboard is drawn over it the moment it shows.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        // Behind the phone's floating windows (Samsung pop-up view...) Lockedintool stays in the back (owner, 4 Nov 2026:
        // 'Lockedintool is supposed to be in the back'): the picture of what Lockedintool showed as it stepped aside; a tap
        // on it (anywhere outside the floating window) brings the live Dashboard back.
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        shot = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.FIT_START; adjustViewBounds = true
            setOnClickListener { OverlayService.instance?.openPages("dashboard") }
        }
        root.addView(shot, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        root.setOnClickListener { OverlayService.instance?.openPages("dashboard") }
        setContentView(root)
        current = this
        showShot()
        showHome()
    }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    /** The latest picture of Lockedintool, placed exactly where the Pages were on screen. */
    fun showShot() { runOnUiThread { runCatching {
        val svc = OverlayService.instance ?: return@runCatching
        val b = svc.homeShot ?: return@runCatching
        val v = shot ?: return@runCatching
        val loc = IntArray(2); window.decorView.getLocationOnScreen(loc)
        (v.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let { it.topMargin = (svc.homeShotTop - loc[1]).coerceAtLeast(0); v.layoutParams = it }
        v.setImageBitmap(b)
    } } }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); showHome() }
    override fun onResume() { super.onResume(); showHome() }

    private fun showHome() {
        OverlayService.instance?.phoneScreenShown()   // the phone's Home came up (Only system) - it handled the swipe
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
