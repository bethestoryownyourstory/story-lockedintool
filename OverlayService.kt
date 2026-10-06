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
    private var panel: FrameLayout? = null
    private var web: WebView? = null
    private var panelIsFull = false
    private val dp get() = resources.displayMetrics.density

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        addBubble()
        addBar()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        hidePanel(); hidePicker()
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

    private fun navBarHeightPx(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (48 * dp).toInt()
    }

    // ---- Station 3 button (bottom right) ----
    private fun addBubble() {
        val size = (46 * dp).toInt()
        val v = TextView(this).apply {
            text = "↖"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E6111111")); setStroke((1 * dp).toInt(), Color.parseColor("#55FFFFFF")) }
            setOnClickListener { showPanel(station3Only = true) }  // Station 3 ONLY, never the full STORY pages
        }
        bubble = v
        wm.addView(v, overlayParams(size, size, Gravity.BOTTOM or Gravity.END, (10 * dp).toInt(), (30 * dp).toInt()))
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

    // ---- STORY panel ----
    @SuppressLint("SetJavaScriptEnabled")
    private fun showPanel(station3Only: Boolean) {
        if (panel != null) {
            if (!station3Only && !panelIsFull) { removePanelNow() } else {
                if (!station3Only) web?.evaluateJavascript("window.storyShow&&window.storyShow(null,false)", null)
                return
            }
        }
        val themed = ContextThemeWrapper(this, R.style.Theme_Story)
        val frame = FrameLayout(themed)
        val wv = WebView(themed).apply {
            setBackgroundColor(if (station3Only) Color.TRANSPARENT else Color.BLACK)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            addJavascriptInterface(StoryBridge(this@OverlayService, this@OverlayService), "StoryNative")
        }
        val js = if (station3Only) "window.storyStation3Only&&window.storyStation3Only()" else "window.storyShow&&window.storyShow(null,false)"
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
            override fun onPageFinished(v: WebView, url: String) { v.evaluateJavascript(js, null) }
        }
        wv.loadUrl("https://appassets.androidplatform.net/assets/index.html" + if (station3Only) "?s3=1" else "")
        frame.addView(wv, FrameLayout.LayoutParams(-1, -1))
        web = wv; panel = frame; panelIsFull = !station3Only

        if (station3Only) {
            // A small window in the bottom-right corner ONLY. Everything outside it keeps working
            // normally underneath; touching outside it closes Station 3.
            frame.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) hidePanel(); false }
            wm.addView(frame, overlayParams((340 * dp).toInt(), (520 * dp).toInt(), Gravity.BOTTOM or Gravity.END, 0, 0, focusable = false, watchOutside = true))
            bubble?.visibility = View.GONE
        } else {
            wm.addView(frame, overlayParams(-1, -1, Gravity.TOP or Gravity.START, 0, 0, focusable = true))
            bubble?.visibility = View.GONE
        }
        addBar() // re-add last so the STORY bar stays on top of everything, including the pages
    }

    private fun removePanelNow() {
        panel?.let { runCatching { wm.removeView(it) } }
        web?.destroy(); web = null; panel = null; panelIsFull = false
        bubble?.visibility = View.VISIBLE
    }

    override fun hidePanel() { ui.post { removePanelNow() } }

    companion object {
        const val ACTION_STOP = "com.story.launcher.STOP"
        @Volatile var running = false
    }
}
