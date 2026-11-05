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
    companion object {
        @Volatile var instance: LockDashActivity? = null
        /** The lock screen's look - your Home Screen a little blurry and dimmed, the glass tape across the screen and
         *  DEVICE LOCKED. The same on the lock screen and on the Always On Display (owner: 'like how your Lockedintool
         *  lock screen is ... don't dim it'). */
        fun lockLook(act: Activity): FrameLayout {
            val root = FrameLayout(act).apply { setBackgroundColor(Color.BLACK) }
            val shot = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }
            root.addView(shot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            root.addView(android.view.View(act).apply { setBackgroundColor(Color.argb(95, 0, 0, 0)) },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            root.addView(LockArt(act).apply { tag = "lockart" }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Your Dashboard exactly as it last showed (the same picture Lockedintool's Home uses).
        val svc = OverlayService.instance
        val prefs = act.getSharedPreferences("story", android.content.Context.MODE_PRIVATE)
        val exactOk = Build.VERSION.SDK_INT < 34 || prefs.getBoolean("home_shot_exact", false)
        val b = svc?.homeShot?.takeIf { !it.isRecycled }
            ?: (if (exactOk) runCatching { BitmapFactory.decodeFile(java.io.File(act.filesDir, "home_shot.jpg").path) }.getOrNull() else null)
        val top = if (svc?.homeShot != null) svc.homeShotTop else prefs.getInt("home_shot_top", 0)
        if (b == null) ProblemLog.record(act, "lockscreen", "no Home Screen picture: mem=${svc?.homeShot != null} file=${java.io.File(act.filesDir, "home_shot.jpg").exists()} exact=$exactOk last=${svc?.homeShotWhy}", "lock screen")
        if (b != null) {
            (shot.layoutParams as FrameLayout.LayoutParams).topMargin = top.coerceAtLeast(0)
            val r = 9f * act.resources.displayMetrics.widthPixels / 1080f
            if (Build.VERSION.SDK_INT >= 31) { shot.setImageBitmap(b); shot.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(r, r, android.graphics.Shader.TileMode.CLAMP)) }
            else runCatching {   // older phones: blurred by shrinking and growing the picture again
                val small = android.graphics.Bitmap.createScaledBitmap(b, (b.width / 10).coerceAtLeast(1), (b.height / 10).coerceAtLeast(1), true)
                shot.setImageBitmap(android.graphics.Bitmap.createScaledBitmap(small, b.width, b.height, true))
            }.onFailure { shot.setImageBitmap(b) }
        }
            return root
        }
    }
    private var asking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        val root = lockLook(this)
        // The lock screen's look (owner, 5 Nov 2026, approved from previews): your screen behind it a little blurry and
        // dimmed ('see how the background is a lil blurry - that's perfect'), two long, thin strips of see-through glass
        // tape across the whole screen ('the tape not that thick, just long ... transparent a lil more, look beautiful')
        // and DEVICE LOCKED in the logo's glitch style ('only the tape and the words device locked ... no colour'). No
        // padlock ('remove the lock and that's it'), no other words. (Was: a 'Locked / Tap to unlock' card, then the logo.)
        // Unlock options (owner, 5 Nov 2026, with a picture: 'Can Lockedintool have unlock options? Because my phone does
        // this thing that just unlocks my phone - this is annoying'; 'So you only get unlock options when you tap the
        // screen when it's locked'; 'don't make the unlock options background transparent, make it the same as that
        // picture'): nothing asks by itself any more. A tap on the locked screen shows Fingerprint / Face Unlock / Enter
        // PIN - solid dark rounded rows, icon | name | arrow - and each opens the phone's OWN unlock (Android lets no app
        // choose the method: the phone's unlock screen takes whichever you use).
        val opts = unlockOptions()
        opts.visibility = android.view.View.GONE
        optsView = opts
        // Watch for the phone unlocking by itself (fingerprint / face straight on the lock screen).
        if (Build.VERSION.SDK_INT >= 33) runCatching {
            val km = getSystemService(KeyguardManager::class.java)
            // Only a real unlock counts: locked first, then unlocked (it's put up while the screen goes off, before the
            // phone has locked - that first 'not locked' must not close it).
            var wasLocked = km.isKeyguardLocked
            val l = KeyguardManager.KeyguardLockedStateListener { locked -> if (locked) wasLocked = true else if (wasLocked && !isFinishing) unlockedNow() }
            km.addKeyguardLockedStateListener(mainExecutor, l); lockListener = l
        }
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { showOptions() }
        val dp = resources.displayMetrics.density
        root.addView(opts, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            val side = (resources.displayMetrics.widthPixels * 0.065f).toInt(); leftMargin = side; rightMargin = side; bottomMargin = (64 * dp).toInt() })
        hintView = android.widget.TextView(this).apply { setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setShadowLayer(6 * dp, 0f, 0f, Color.BLACK); visibility = android.view.View.GONE }
        root.addView(hintView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = (64 * dp + 3 * 68 * dp + 2 * 12 * dp + 18 * dp).toInt() })
        root.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) showOptions(); true }
        setContentView(root)
        instance = this
    }
    private fun unlockOptions(): android.view.View {
        val dp = resources.displayMetrics.density
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        for ((i, name) in listOf("Fingerprint", "Face Unlock", "Enter PIN").withIndex()) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(Color.rgb(22, 22, 22), Color.rgb(10, 10, 10))).apply { cornerRadius = 24 * dp; setStroke((1 * dp).toInt(), Color.rgb(48, 48, 48)) }
                isClickable = true; contentDescription = name
                // Each button does what it says (owner, 5 Nov 2026: 'I wanted it to work as how the buttons are saying ...
                // It should only pop up when I press Enter PIN'): Fingerprint and Face Unlock never open the phone's PIN
                // pad - the phone's own fingerprint sensor / face unlock work straight on this screen (unlockedNow closes
                // it); only Enter PIN opens the phone's unlock screen.
                setOnClickListener { when (i) {
                    0 -> hint("Touch the fingerprint sensor")
                    1 -> hint("Look at your phone")
                    else -> { hint(null); ask() } } }
            }
            row.addView(UnlockIcon(this, i), android.widget.LinearLayout.LayoutParams((76 * dp).toInt(), (68 * dp).toInt()))
            row.addView(android.view.View(this).apply { setBackgroundColor(Color.rgb(44, 44, 44)) },
                android.widget.LinearLayout.LayoutParams((1 * dp).toInt(), (40 * dp).toInt()))
            row.addView(android.widget.TextView(this).apply { text = name; setTextColor(Color.WHITE); textSize = 19f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL); setPadding((20 * dp).toInt(), 0, 0, 0) },
                android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(UnlockIcon(this, 3), android.widget.LinearLayout.LayoutParams((52 * dp).toInt(), (68 * dp).toInt()))
            col.addView(row, android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (68 * dp).toInt()).apply {
                if (i > 0) topMargin = (12 * dp).toInt() })
        }
        return col
    }
    private fun lockSign(): android.view.View {
        val dp = resources.displayMetrics.density
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val w = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        box.addView(ImageView(this).apply { setImageResource(R.drawable.device_locked); scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true; contentDescription = "Device locked" },
            android.widget.LinearLayout.LayoutParams(w, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(android.widget.TextView(this).apply { text = "Tap to unlock"; setTextColor(Color.WHITE); textSize = 14f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setShadowLayer(6 * dp, 0f, 0f, Color.BLACK); setPadding(0, (10 * dp).toInt(), 0, 0) },
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        return box
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
        // The words decide the card's width (owner's screenshot, 5 Nov 2026: 'Loc' / 'Tap to' - cut to the icon's width).
        val wrap = { android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT) }
        box.addView(android.widget.TextView(this).apply { text = "Locked"; setTextColor(Color.WHITE); textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(0, (8 * dp).toInt(), 0, 0); maxLines = 1 }, wrap())
        box.addView(android.widget.TextView(this).apply { text = "Tap to unlock"; setTextColor(Color.argb(191, 255, 255, 255)); textSize = 13f
            gravity = Gravity.CENTER; setPadding(0, (4 * dp).toInt(), 0, 0); maxLines = 1 }, wrap())
        return box
    }
    override fun onResume() { super.onResume(); overridePendingTransition(0, 0); window.decorView.post { stillLocked() } }
    override fun onPause() { super.onPause(); overridePendingTransition(0, 0) }
    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) (lockListener as? KeyguardManager.KeyguardLockedStateListener)?.let { l ->
            runCatching { getSystemService(KeyguardManager::class.java)?.removeKeyguardLockedStateListener(l) } }
        if (instance === this) instance = null; super.onDestroy() }
    private var lockListener: Any? = null
    /** The phone was unlocked some other way - its fingerprint sensor straight on the lock screen, face unlock (owner's
     *  screenshot, 5 Nov 2026: 'Phone unlocked with fingerprint' with DEVICE LOCKED still showing): this goes at once
     *  and you land on your Home Screen, the same as unlocking through the options. */
    fun unlockedNow() {
        if (isFinishing) return
        asking = false
        OverlayService.instance?.lockDashUnlocked()
        OverlayService.instance?.lockDashOpenHome(this)
        finish(); overridePendingTransition(0, 0)
    }
    // Back / a swipe never drops you on the phone's own lock screen (owner, 5 Nov 2026: 'when I swipe to the right or
    // anywhere my phone's lockscreen appears - that's wrong'): it shows the unlock options instead.
    private var optsView: android.view.View? = null
    // Untouched it looks exactly like the Always On Display (the full-screen tape + DEVICE LOCKED - owner, 5 Nov 2026: 'you
    // see always on display how it was beautiful ... the unlock options ... should only show if the screen is tapped. If
    // the screen is not tapped, then my always on display shows as it is. After it's tapped, then it can'); a tap moves
    // the sign into the top area and shows the options.
    private fun showOptions() {
        val art = window.decorView.findViewWithTag<android.view.View>("lockart") as? LockArt
        // The sign shrinks from the full screen into the top area (the owner's animation), then the options show.
        if (art == null) optsView?.visibility = android.view.View.VISIBLE
        else art.shrink { optsView?.visibility = android.view.View.VISIBLE }
    }
    private var hintView: android.widget.TextView? = null
    private fun hint(t: String?) { hintView?.let { it.text = t ?: ""; it.visibility = if (t == null) android.view.View.GONE else android.view.View.VISIBLE } }
    /** The screen went off: back to the untouched look - the full-screen sign, no options (they show only after a tap). */
    fun resetLook() {
        if (isFinishing) return
        optsView?.visibility = android.view.View.GONE; hint(null)
        (window.decorView.findViewWithTag<android.view.View>("lockart") as? LockArt)?.reset()
    }
    @Deprecated("Back shows the unlock options")
    override fun onBackPressed() { showOptions() }
    override fun onStop() {
        super.onStop()
        // Something took the screen while the phone is still locked (the Home swipe -> the phone's own lock screen):
        // Lockedintool's lock screen comes straight back. Not while the phone's unlock is up, and never over the camera
        // or a call (lockDashAgain checks).
        if (!isFinishing && !asking) OverlayService.instance?.lockDashAgain()
    }

    /** The screen came on: ask for the unlock now (the screen was off when this came up). */
    fun screenOnNow() { if (!isFinishing) window.decorView.post { stillLocked() } }
    /** The screen is on: if the phone didn't actually lock (it was off only briefly) this closes and you stay where you
     *  were. Locked: it just shows - nothing asks until you choose an unlock option. */
    private fun stillLocked() {
        if (isFinishing) return
        val awake = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        if (!awake || AodActivity.instance != null) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) { finish(); overridePendingTransition(0, 0) }
    }
    /** The phone's own unlock prompt, over the Dashboard. */
    private fun ask() {
        if (asking || isFinishing) return
        val km = getSystemService(KeyguardManager::class.java)
        val awake = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        if (!awake) return   // put up while the screen went off: it asks once you turn the screen on
        if (AodActivity.instance != null) return   // the Always On Display is over it: it asks once that ends (onResume)
        if (km == null || !km.isKeyguardLocked) { finish(); overridePendingTransition(0, 0); return }   // the phone didn't lock (it was off only briefly): you stay where you were
        if (Build.VERSION.SDK_INT < 26) { finish(); return }
        asking = true
        runCatching {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                // Unlocked: you land where the lock screen showed - your Lockedintool Home Screen - not the app you were in
                // (owner, 5 Nov 2026: 'whatever is showing at the background of the unlock ... that's where I will be ...
                // for me to go to an app I was using I need to swipe up'). Your apps stay in the phone's Recents.
                override fun onDismissSucceeded() { asking = false
                    OverlayService.instance?.lockDashUnlocked()   // your phone's unlock counts for Lockedintool too - no second prompt
                    OverlayService.instance?.lockDashOpenHome(this@LockDashActivity)
                    finish(); overridePendingTransition(0, 0) }
                override fun onDismissCancelled() { asking = false }   // stays; a tap asks again
                override fun onDismissError() { asking = false; OverlayService.instance?.lockDashLeft(); finish() }   // never stuck: the phone's own lock screen
            })
        }.onFailure { asking = false; OverlayService.instance?.lockDashLeft(); finish() }
    }
}

/** Two long, thin strips of see-through glass tape crossing the whole screen, and DEVICE LOCKED (device_locked.png - the
 *  logo's glitch words, white) where they cross. Drawn to fit any screen; nothing moves (no animation anywhere). */
class LockArt(ctx: android.content.Context) : android.view.View(ctx) {
    /** false = the full-screen sign (the Always On Display, the lock screen untouched); true = in the top area, with the unlock options under it. */
    /** 0 = the full-screen sign (the Always On Display, the lock screen untouched), 1 = in the top area with the unlock
     *  options under it. In between only while shrink() moves it - the owner's own animation (owner, 5 Nov 2026: 'the
     *  logo should get an animation that goes from the big full screen to that size of how it will be when the unlock
     *  options show'), the one exception to 'no animations anywhere'. */
    var progress = 0f
    val compact get() = progress >= 1f
    private var anim: android.animation.ValueAnimator? = null
    fun reset() { anim?.removeAllListeners(); anim?.removeAllUpdateListeners(); anim?.cancel(); anim = null; progress = 0f; invalidate() }
    fun shrink(onEnd: () -> Unit) {
        if (progress >= 1f || anim != null) { if (progress >= 1f) onEnd(); return }
        anim = android.animation.ValueAnimator.ofFloat(progress, 1f).apply {
            duration = 320; interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { anim = null; progress = 1f; invalidate(); onEnd() } })
            start()
        }
    }
    private val words = runCatching { BitmapFactory.decodeResource(ctx.resources, R.drawable.device_locked) }.getOrNull()
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL; color = Color.WHITE }
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; color = Color.WHITE }
    private val bmp = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val clear = android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    override fun onDraw(c: android.graphics.Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); if (w <= 0f || h <= 0f) return
        val t = progress.coerceIn(0f, 1f)
        // The sign's area: the whole screen -> the top area (owner, 5 Nov 2026, circling the top of a picture: 'our screen
        // is locked indicator - not as the full background but where I drew'), its edges fading out there.
        val small = android.graphics.RectF(w * 0.065f, h * 0.107f, w * 0.935f, h * 0.538f)
        val box = android.graphics.RectF(lerp(0f, small.left, t), lerp(0f, small.top, t), lerp(w, small.right, t), lerp(h, small.bottom, t))
        val cx = lerp(w / 2f, small.centerX(), t); val cy = lerp(h * 0.47f, small.centerY(), t)
        val layer = c.saveLayer(box, null)
        tape(c, cx, cy, 120.5f, w * 0.13f, w * 0.20f, box.width(), box.height())   // the strip underneath
        tape(c, cx, cy, 31.9f, w * 0.15f, w * 0.22f, box.width(), box.height())    // the strip on top
        val f = 60f * w / 1080f * t
        if (f > 0.5f) {
            fun fade(x0: Float, y0: Float, x1: Float, y1: Float, r: android.graphics.RectF) {
                clear.shader = android.graphics.LinearGradient(x0, y0, x1, y1, Color.BLACK, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP); c.drawRect(r, clear) }
            fade(box.left, 0f, box.left + f, 0f, android.graphics.RectF(box.left, box.top, box.left + f, box.bottom))
            fade(box.right, 0f, box.right - f, 0f, android.graphics.RectF(box.right - f, box.top, box.right, box.bottom))
            fade(0f, box.top, 0f, box.top + f, android.graphics.RectF(box.left, box.top, box.right, box.top + f))
            fade(0f, box.bottom, 0f, box.bottom - f, android.graphics.RectF(box.left, box.bottom - f, box.right, box.bottom))
        }
        c.restoreToCount(layer)
        words?.let { b ->
            val ww = lerp(w * 0.9185f, small.width() * 0.86f, t); val wh = ww * b.height / b.width
            val mid = cy - lerp(w * 0.0179f, 0f, t)
            c.drawBitmap(b, null, android.graphics.RectF(cx - ww / 2f, mid - wh / 2f, cx + ww / 2f, mid + wh / 2f), bmp)
        }
    }
    private fun tape(c: android.graphics.Canvas, cx: Float, cy: Float, angle: Float, tw: Float, period: Float, w: Float, h: Float) {
        val len = Math.hypot(w.toDouble(), h.toDouble()).toFloat() + period * 2
        val half = tw / 2f; val skew = 0.55f; val step = period / 2f
        c.save(); c.translate(cx, cy); c.rotate(angle)
        var k = Math.floor(((-len - half * skew) / step).toDouble()).toInt()
        val last = Math.ceil(((len + half * skew) / step).toDouble()).toInt()
        val path = android.graphics.Path()
        while (k <= last) {
            val a0 = k * step; val a1 = (k + 1) * step
            path.reset()
            path.moveTo(a0 + half * skew, -half); path.lineTo(a1 + half * skew, -half)
            path.lineTo(a1 - half * skew, half); path.lineTo(a0 - half * skew, half); path.close()
            fill.alpha = if (Math.floorMod(k, 2) == 0) 41 else 13   // the light and the dark stripes, both see-through
            c.drawPath(path, fill)
            k++
        }
        // soft bright edges along both sides
        edge.strokeWidth = 2.2f * w / 1080f; edge.alpha = 140
        c.drawLine(-len, -half, len, -half, edge); c.drawLine(-len, half, len, half, edge)
        c.restore()
    }
}

/** The unlock options' icons, drawn (no emoji): 0 fingerprint, 1 face, 2 PIN keypad, 3 the arrow. */
class UnlockIcon(ctx: android.content.Context, private val kind: Int) : android.view.View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val pen = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.2f * dp; color = Color.WHITE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
    private val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL; color = Color.WHITE }
    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f; val u = 1.2f * dp
        when (kind) {
            0 -> { // fingerprint: open arcs around the centre
                for ((k, r) in listOf(4f, 8f, 12f, 16f).withIndex()) {
                    val rr = r * u; val start = if (k % 2 == 0) 200f else 215f
                    c.drawArc(cx - rr, cy - rr, cx + rr, cy + rr, start, 250f - k * 12f, false, pen)
                }
            }
            1 -> { // face: corner brackets, two eyes, a smile
                val h = 15f * u; val l = 6f * u
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    c.drawLine(cx + sx * h, cy + sy * h, cx + sx * (h - l), cy + sy * h, pen)
                    c.drawLine(cx + sx * h, cy + sy * h, cx + sx * h, cy + sy * (h - l), pen)
                }
                c.drawCircle(cx - 5f * u, cy - 3f * u, 1.6f * u, dot); c.drawCircle(cx + 5f * u, cy - 3f * u, 1.6f * u, dot)
                c.drawArc(cx - 6f * u, cy - 1f * u, cx + 6f * u, cy + 8f * u, 20f, 140f, false, pen)
            }
            2 -> { // PIN keypad: 3 x 3 dots
                for (r in -1..1) for (k in -1..1) c.drawCircle(cx + k * 9f * u, cy + r * 9f * u, 2.6f * u, dot)
            }
            else -> { // the arrow
                c.drawLine(cx - 3f * u, cy - 7f * u, cx + 4f * u, cy, pen); c.drawLine(cx + 4f * u, cy, cx - 3f * u, cy + 7f * u, pen)
            }
        }
    }
}
