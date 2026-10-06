package com.story.launcher

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewAssetLoader

/**
 * STORY as a layer over the phone. Your home screen, lock screen and apps stay exactly as they are.
 *  - Station 3: the round button at the bottom right. Opens ONLY Station 3, in a small window,
 *    so the rest of the screen stays fully usable underneath.
 *  - The STORY bar: sits on the phone's own gesture line (or draws a line on phones that don't
 *    have one). It is the ONE bottom line, everywhere, including while STORY pages are open.
 *      tap = Apps | Pages, swipe up = real home screen, hold then swipe up = real recents.
 *  - The STORY panel: full-screen Money / Wallet / Focus pages, shown only when you pick Pages.
 */
class OverlayService : Service(), StoryBridge.Host {
    private lateinit var wm: WindowManager
    private val ui = Handler(Looper.getMainLooper())
    private var bubble: View? = null
    private var bar: View? = null
    private var picker: View? = null
    private val dp get() = resources.displayMetrics.density

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addBubble()
        addBar()
        ui.post { warmUp() }  // build both panes now so Station 3 and Pages open instantly
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        removePanelNow(); hidePicker(); destroyPanes()
        bubble?.let { runCatching { wm.removeView(it) } }
        bar?.let { runCatching { wm.removeView(it) } }
        running = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("story", "STORY", NotificationManager.IMPORTANCE_MIN))
        val stop = android.app.PendingIntent.getService(this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "story").setContentTitle("STORY is on").setContentText("Tap to turn off")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(stop).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        running = true
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int, x: Int, y: Int, focusable: Boolean = false, watchOutside: Boolean = false): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (watchOutside) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        return WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity; this.x = x; this.y = y
        }
    }

    /** True when the phone uses swipe gestures (so it already has a gesture line). 3-button phones don't. */
    private fun phoneHasGestureLine(): Boolean {
        val mode = runCatching { Settings.Secure.getInt(contentResolver, "navigation_mode") }.getOrNull()
        if (mode != null) return mode == 2
        val samsung = runCatching { Settings.Global.getInt(contentResolver, "navigation_bar_gesture_while_hidden") }.getOrNull()
        return samsung == 1
    }

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * dp).toInt()
    }

    private fun realHeightPx(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.height() else resources.displayMetrics.heightPixels

    private fun navBarHeightPx(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (48 * dp).toInt()
    }

    // ---- Station 3 button (bottom right) ----
    private fun addBubble() {
        val size = (40 * dp).toInt()  // same size as Station 3's own arrow
        val v = TextView(this).apply {
            text = "↖"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                // A corner piece: rounded only on the top-left, square into the screen corner.
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 0f, 0f, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#F2111111")); setStroke((1 * dp).toInt(), Color.parseColor("#55FFFFFF"))
            }
            setOnClickListener { showPanel(station3Only = true) }  // Station 3 ONLY, never the full STORY pages
        }
        bubble = v
        wm.addView(v, overlayParams(size, size, Gravity.BOTTOM or Gravity.END, 0, 0))  // flush in the bottom-right corner
    }

    // ---- The STORY bar: the one bottom line ----
    @SuppressLint("ClickableViewAccessibility")
    private fun addBar() {
        bar?.let { runCatching { wm.removeView(it) } }
        val hasLine = phoneHasGestureLine()
        val w = (150 * dp).toInt(); val h = (26 * dp).toInt()
        val touch = ViewConfiguration.get(this).scaledTouchSlop
        val v: View = if (hasLine) View(this) else FrameLayout(this).apply {
            // Phones with 3-button navigation have no gesture line, so draw one.
            addView(View(context).apply {
                background = GradientDrawable().apply { cornerRadius = 3 * dp; setColor(Color.parseColor("#B3FFFFFF")) }
            }, FrameLayout.LayoutParams((110 * dp).toInt(), (5 * dp).toInt(), Gravity.CENTER))
        }
        var downT = 0L; var downY = 0f; var downX = 0f; var moved = false; var held = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downT = System.currentTimeMillis(); downX = e.rawX; downY = e.rawY; moved = false; held = false; hidePicker() }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > touch) {
                        moved = true; held = System.currentTimeMillis() - downT >= 350
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dy = downY - e.rawY
                    if (!moved) showPicker()                                     // tap
                    else if (dy > 60 * dp) {                                      // swipe up
                        hidePanel()
                        val a = if (held) AccessibilityService.GLOBAL_ACTION_RECENTS else AccessibilityService.GLOBAL_ACTION_HOME
                        StoryAccessibilityService.instance?.performGlobalAction(a)
                    }
                }
            }
            true
        }
        bar = v
        val bottom = if (hasLine) 0 else navBarHeightPx() + (6 * dp).toInt()
        wm.addView(v, overlayParams(w, h, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, bottom))
    }

    // ---- Apps | Pages switch ----
    private fun showPicker() {
        hidePicker()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding((22 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * dp; setColor(Color.parseColor("#F2111111")); setStroke((1 * dp).toInt(), Color.parseColor("#44FFFFFF")) }
        }
        fun word(t: String, onTap: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(Color.WHITE); textSize = 18f; setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0); setOnClickListener { hidePicker(); onTap() }
        }
        // Apps = the phone's own apps / home screen. There is no second "STORY apps" screen.
        row.addView(word("Apps") { hidePanel(); StoryAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) })
        row.addView(TextView(this).apply { text = "|"; setTextColor(Color.parseColor("#66FFFFFF")); textSize = 18f })
        row.addView(word("Pages") { showPanel(station3Only = false) })  // the only way into STORY itself
        picker = row
        wm.addView(row, overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (64 * dp).toInt() + (if (phoneHasGestureLine()) 0 else navBarHeightPx())))
        ui.postDelayed({ hidePicker() }, 4000)
    }

    private fun hidePicker() { picker?.let { runCatching { wm.removeView(it) } }; picker = null }

    // ---- STORY panes (built once, kept warm, so they open instantly) ----
    private class Pane(val frame: FrameLayout, val web: WebView, val lp: WindowManager.LayoutParams, val small: Boolean) { var shown = false }
    private var s3Pane: Pane? = null
    private var pagesPane: Pane? = null

    private fun flagsFor(small: Boolean, shown: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (!shown) base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else if (small) base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        else base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildPane(small: Boolean): Pane {
        val themed = ContextThemeWrapper(this, R.style.Theme_Story)
        val frame = FrameLayout(themed)
        val wv = WebView(themed).apply {
            setBackgroundColor(if (small) Color.TRANSPARENT else Color.BLACK)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            addJavascriptInterface(StoryBridge(this@OverlayService, this@OverlayService), "StoryNative")
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
            override fun onPageFinished(v: WebView, url: String) {
                if (small) v.evaluateJavascript("window.storyStation3Only&&window.storyStation3Only()", null)
            }
        }
        wv.loadUrl("https://appassets.androidplatform.net/assets/index.html" + if (small) "?s3=1" else "")
        frame.addView(wv, FrameLayout.LayoutParams(-1, -1))
        frame.alpha = 0f
        val lp = if (small)
            WindowManager.LayoutParams((340 * dp).toInt(), (520 * dp).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(true, false), PixelFormat.TRANSLUCENT).apply {
                // Station 3's arrow sits 10dp inside this window, so nudge the window 10dp off-screen
                // and the arrow lands exactly on the same corner as the button.
                gravity = Gravity.BOTTOM or Gravity.END; x = -(10 * dp).toInt(); y = -(10 * dp).toInt()
            }
        else
            WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(false, false), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        if (!small) { frame.setBackgroundColor(Color.BLACK); frame.setPadding(0, statusBarHeightPx(), 0, 0) }  // black continues behind the status bar; pages start below it
        val p = Pane(frame, wv, lp, small)
        if (small) frame.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) hidePanel(); false }
        wm.addView(frame, lp)
        return p
    }

    private fun warmUp() {
        if (s3Pane == null) s3Pane = buildPane(true)
        if (pagesPane == null) pagesPane = buildPane(false)
        addBar()
    }

    private fun setShown(p: Pane, shown: Boolean) {
        p.shown = shown
        p.lp.flags = flagsFor(p.small, shown)
        p.frame.alpha = if (shown) 1f else 0f
        runCatching { wm.updateViewLayout(p.frame, p.lp) }
        if (shown && !p.small) p.web.requestFocus()
    }

    private fun showPanel(station3Only: Boolean) {
        warmUp()
        if (station3Only) {
            pagesPane?.takeIf { it.shown }?.let { hideOne(it) }
            val p = s3Pane!!
            if (!p.shown) { p.web.evaluateJavascript("window.storyStation3Only&&window.storyStation3Only()", null); setShown(p, true) }
        } else {
            s3Pane?.takeIf { it.shown }?.let { hideOne(it) }
            val p = pagesPane!!
            p.web.evaluateJavascript("window.storyShow&&window.storyShow(null,false)", null)
            if (!p.shown) setShown(p, true)
        }
        bubble?.visibility = View.GONE
        addBar() // re-add last so the STORY bar stays on top of everything, including the pages
    }

    private fun hideOne(p: Pane) {
        p.web.evaluateJavascript("window.storyReset&&window.storyReset()", null)
        setShown(p, false)
    }

    private fun removePanelNow() {
        s3Pane?.takeIf { it.shown }?.let { hideOne(it) }
        pagesPane?.takeIf { it.shown }?.let { hideOne(it) }
        bubble?.visibility = View.VISIBLE
    }

    private fun destroyPanes() {
        for (p in listOfNotNull(s3Pane, pagesPane)) { runCatching { wm.removeView(p.frame) }; p.web.destroy() }
        s3Pane = null; pagesPane = null
    }

    override fun hidePanel() { ui.post { removePanelNow() } }

    companion object {
        const val ACTION_STOP = "com.story.launcher.STOP"
        @Volatile var running = false
    }
}
