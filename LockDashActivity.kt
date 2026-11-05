package com.story.launcher

import android.app.Activity
import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Lockedintool's lock screen (owner, 5 Nov 2026: 'I want the same pop-up to be my lock screen, so when Lockedintool is
 * on Only system, that means when you turn on your phone, you will see your dashboard or whatever you have set, and then
 * you would see the unlock phone pop up, and then you have to unlock it, and then you can start using your phone. It just
 * shows Lockedintool, no time, no nothing - your phone always shows the time at the top').
 *
 * Only system: when the screen comes on while the phone is locked, this shows your Dashboard (the Home's exact copy of
 * it) over the phone's lock screen and straight away asks the PHONE for its own unlock (requestDismissKeyguard - its
 * fingerprint / face / PIN prompt; Lockedintool never checks or replaces the phone's lock). Unlocked -> it closes and you
 * are where you were (your app, or the Dashboard). Nothing on it can be used before the unlock: a tap asks again. Back
 * (or anything going wrong) closes it and leaves the phone's own lock screen - you can never be locked out.
 */
class LockDashActivity : Activity() {
    companion object { @Volatile var instance: LockDashActivity? = null }
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val shot = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }
        root.addView(shot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // It must SAY it's locked (owner, 5 Nov 2026: 'A locked screen should say that it is locked because I'm confused, I
        // think it's unlocked'): a card in the middle - the lock, 'Locked', 'Tap to unlock' - like Lockedintool security's lock.
        root.addView(lockCard(), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) ask(); true }
        setContentView(root)
        instance = this
        // Your Dashboard exactly as it last showed (the same picture Lockedintool's Home uses).
        val svc = OverlayService.instance
        val prefs = getSharedPreferences("story", MODE_PRIVATE)
        val exactOk = Build.VERSION.SDK_INT < 34 || prefs.getBoolean("home_shot_exact", false)
        val b = svc?.homeShot?.takeIf { !it.isRecycled }
            ?: (if (exactOk) runCatching { BitmapFactory.decodeFile(java.io.File(filesDir, "home_shot.jpg").path) }.getOrNull() else null)
        val top = if (svc?.homeShot != null) svc.homeShotTop else prefs.getInt("home_shot_top", 0)
        if (b != null) { shot.setImageBitmap(b); (shot.layoutParams as FrameLayout.LayoutParams).topMargin = top.coerceAtLeast(0) }
    }
    private fun lockCard(): android.view.View {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * dp).toInt(), (22 * dp).toInt(), (28 * dp).toInt(), (22 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 22 * dp; setColor(Color.argb(209, 0, 0, 0)); setStroke((1 * dp).toInt(), Color.argb(36, 255, 255, 255)) }
        }
        // The lock icon (drawn - no emoji): a body and a shackle, the same as Lockedintool security's lock.
        val icon = object : android.view.View(this) {
            val pnt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.8f * dp; color = Color.WHITE; strokeCap = android.graphics.Paint.Cap.ROUND }
            override fun onDraw(c: android.graphics.Canvas) {
                val u = width / 24f
                c.drawRoundRect(4.5f * u, 10.5f * u, 19.5f * u, 20.5f * u, 2.5f * u, 2.5f * u, pnt)
                c.drawArc(8f * u, 3.5f * u, 16f * u, 11.5f * u, 180f, 180f, false, pnt)
                c.drawLine(8f * u, 7.5f * u, 8f * u, 10.5f * u, pnt); c.drawLine(16f * u, 7.5f * u, 16f * u, 10.5f * u, pnt)
            }
        }
        box.addView(icon, android.widget.LinearLayout.LayoutParams((38 * dp).toInt(), (38 * dp).toInt()))
        box.addView(android.widget.TextView(this).apply { text = "Locked"; setTextColor(Color.WHITE); textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0) })
        box.addView(android.widget.TextView(this).apply { text = "Tap to unlock"; setTextColor(Color.argb(191, 255, 255, 255)); textSize = 13f
            gravity = Gravity.CENTER; setPadding(0, (4 * dp).toInt(), 0, 0) })
        return box
    }
    override fun onResume() { super.onResume(); overridePendingTransition(0, 0); window.decorView.post { ask() } }
    override fun onPause() { super.onPause(); overridePendingTransition(0, 0) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    @Deprecated("Back closes it: the phone's own lock screen is underneath")
    override fun onBackPressed() { OverlayService.instance?.lockDashLeft(); finish() }

    /** The phone's own unlock prompt, over the Dashboard. */
    private fun ask() {
        if (asking || isFinishing) return
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { finish(); return }   // already unlocked
        if (Build.VERSION.SDK_INT < 26) { finish(); return }
        asking = true
        runCatching {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { asking = false; finish(); overridePendingTransition(0, 0) }
                override fun onDismissCancelled() { asking = false }   // stays; a tap asks again
                override fun onDismissError() { asking = false; OverlayService.instance?.lockDashLeft(); finish() }   // never stuck: the phone's own lock screen
            })
        }.onFailure { asking = false; OverlayService.instance?.lockDashLeft(); finish() }
    }
}
