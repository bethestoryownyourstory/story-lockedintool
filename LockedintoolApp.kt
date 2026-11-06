package com.story.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Lockedintool as an APP (AppMode off - owner, 5 Nov 2026: 'Let's make lockedintool an app also'). A normal app
 *  window: while it is on screen the Pages live inside it (the same way Only system's Home holds them -
 *  OverlayService.appShow), and when you leave it they go away with it - nothing of Lockedintool stays over other apps.
 *  Opened from the app icon (MainActivity forwards here once Lockedintool is set up), a widget or a notification. */
class LockedintoolApp : Activity() {
    companion object {
        @Volatile var current: LockedintoolApp? = null
        const val EXTRA_PAGE = "page"
        fun open(c: android.content.Context, page: String?) {
            runCatching { c.startActivity(Intent(c, LockedintoolApp::class.java).apply { if (page != null) putExtra(EXTRA_PAGE, page) }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
        }
    }
    private var page: String? = null
    private var tries = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        setContentView(android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) })
        current = this
        page = intent.getStringExtra(EXTRA_PAGE)
        KeepAlive.ensureRunning(this)
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); intent?.getStringExtra(EXTRA_PAGE)?.let { page = it } }
    override fun onResume() { super.onResume(); tries = 0; attach() }
    private fun attach() {
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            val svc = OverlayService.instance
            if (svc == null) {   // Lockedintool is still starting: look again in a moment (a few times at most)
                if (tries++ < 10) window.decorView.postDelayed({ attach() }, 300)
                return@post
            }
            svc.appShow(this, page); page = null
        }
    }
    override fun onStop() { OverlayService.instance?.appStopped(this); super.onStop() }
    override fun onDestroy() { OverlayService.instance?.homeGone(this); if (current === this) current = null; super.onDestroy() }
}
