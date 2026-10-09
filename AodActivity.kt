package com.story.launcher

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * STORY's Always On Display: when the screen would turn off, a black screen stays on with
 * Station 3 in its exact spot, at the phone's own brightness (the overlay windows sit on top of it, unchanged). Station 3 works as
 * always -- tap it and it extends. Double-tap anywhere else for the normal lock screen. It is ALWAYS on
 * (owner): pressing power while it's up brings it straight back. While it's up, Station 3's pixels take turns (see OverlayService.setAod)
 * so nothing burns into the screen, without Station 3 moving at all.
 */
class AodActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        // Same brightness as the phone (the owner's setting, auto-brightness included) -- no override.
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        val tap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { finish(); return true }
        })
        setContentView(View(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, e -> tap.onTouchEvent(e) }
        })
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        instance = this
        OverlayService.instance?.setAod(true)
        handler.post(unlockWatch)
    }

    // Unlocked (fingerprint, face...) while this is up: Android unlocks the phone but leaves this black screen on
    // top of it. Watch for the lock going away and step aside at once - you land straight in the phone.
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var wasLocked = false
    private val unlockWatch = object : Runnable {
        override fun run() {
            val locked = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true
            if (locked) wasLocked = true
            else if (wasLocked) { finish(); overridePendingTransition(0, 0); return }
            handler.postDelayed(this, 200)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(unlockWatch)
        if (instance === this) instance = null
        OverlayService.instance?.setAod(false)
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: AodActivity? = null
        fun enabled(c: android.content.Context) = QuickAccess.aodScreen(c)  // Station 3 and/or the dial pad (Settings > QUICK ACCESS)
    }
}
