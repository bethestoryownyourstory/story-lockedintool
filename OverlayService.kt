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
 *  - Station 3: the round button at the bottom right. Opens ONLY Station 3, in a small window
 *    cut to the size of Station 3 itself, so the rest of the screen stays fully usable underneath.
 *    It stays open (over any app, through Home / Apps / app launches) until you tap its corner arrow.
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
    // Station 3's button and window live in the accessibility layer when STORY system actions is on:
    // that layer is drawn above everything, including the lock screen. Without it we fall back to
    // the normal over-other-apps layer (which Android hides on the lock screen).
    private lateinit var layerWm: WindowManager
    private var layerType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var bubbleWm: WindowManager? = null
    private val updateLoop = object : Runnable {
        // Every 20 seconds while the screen is on: new app version? new screens? Nothing while the screen is off.
        override fun run() {
            if (getSystemService(android.os.PowerManager::class.java).isInteractive) { Updater.check(this@OverlayService); checkScreens() }
            ui.postDelayed(this, 20_000L)
        }
    }
    // Every unlock is also a good moment to pick up a new STORY version.
    private val unlocked = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            AodActivity.instance?.finish()  // unlocked (e.g. fingerprint) while the Always On Display was up
            Updater.check(this@OverlayService); checkScreens()
        }
    }
    // Always On Display: when the screen goes off, STORY's dim always-on screen comes up instead
    // (if switched on). Pressing power while it's up turns the screen truly off.
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val aod = AodActivity.instance
            if (aod != null) { aod.finish(); return }
            if (!AodActivity.enabled(c)) return
            runCatching { startActivity(Intent(c, AodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
        }
    }
    // Which version of the screens (live or Test) the panes have loaded; see checkScreens().
    @Volatile private var screensSeen: String? = null
    private val dp get() = resources.displayMetrics.density

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        instance = this
        getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        pickLayer()
        addBubble()
        addBar()
        ui.post { warmUp() }  // build both panes now so Station 3 and Pages open instantly
        ui.postDelayed(updateLoop, 15_000)  // then look for a new STORY version in the background
        Updater.schedule(this)              // and keep checking every 15 minutes even if STORY is off
        val unlockFilter = android.content.IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, unlockFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(unlocked, unlockFilter)
        val offFilter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, offFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOff, offFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", false).apply(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_REBUILD) ui.post { rebuildLayer() }
        return START_STICKY
    }

    override fun onDestroy() {
        removePanelNow(); hidePicker(); destroyPanes()
        bubble?.let { runCatching { (bubbleWm ?: wm).removeView(it) } }
        instance = null
        ui.removeCallbacks(updateLoop); ui.removeCallbacks(resumeAfterInstall)
        runCatching { unregisterReceiver(unlocked) }
        runCatching { unregisterReceiver(screenOff) }
        ui.removeCallbacks(aodSwap)
        bar?.let { runCatching { wm.removeView(it) } }
        running = false
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("story", "STORY", NotificationManager.IMPORTANCE_MIN))
        // Tapping the notification opens STORY; it never switches STORY off (only "Stop STORY" does).
        val openApp = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "story").setContentTitle("STORY is on").setContentText("Stays on until you tap Stop STORY")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(openApp).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        running = true
    }

    /** Live screens for everyone; the owner (Test channel) gets the newest copy from /preview/. */
    private fun remoteBase(): String =
        REMOTE + if (Release.isOwner(this)) "preview/" else ""

    private fun pickLayer() {
        val a = StoryAccessibilityService.instance
        val awm = a?.let { runCatching { it.getSystemService(Context.WINDOW_SERVICE) as WindowManager }.getOrNull() }
        if (awm != null) { layerWm = awm; layerType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        else { layerWm = wm; layerType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
    }

    /** Asks the STORY site which screens are current; if they changed, the panes reload (when closed). */
    private fun checkScreens() {
        val url = remoteBase() + (if (Release.isOwner(this)) "source.txt" else "live-source.txt") + "?t=" + System.currentTimeMillis()
        Thread {
            val v = runCatching {
                (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
                    connectTimeout = 10000; readTimeout = 10000; useCaches = false
                    if (responseCode == 200) inputStream.bufferedReader().use { it.readText().trim() } else null
                }
            }.getOrNull() ?: return@Thread
            ui.post {
                val before = screensSeen
                screensSeen = v
                if (before != null && before != v) for (p in listOfNotNull(s3Pane, pagesPane)) { if (p.shown) p.stale = true else reload(p) }
            }
        }.start()
    }

    /** Fresh copy of the screens, skipping the website's cache. */
    private fun reload(p: Pane) {
        p.stale = false; p.loadedAt = System.currentTimeMillis()
        p.web.loadUrl(remoteBase() + "?" + (if (p.dial) "s3=1&dial=1&" else if (p.small) "s3=1&" else "") + "v=" + System.currentTimeMillis())
    }

    private fun rebuildLayer() {
        screensSeen = null
        val s3WasOpen = s3Pane?.shown == true
        removePanelNow()
        bubble?.let { runCatching { (bubbleWm ?: wm).removeView(it) } }; bubble = null
        removeBarBg()
        s3Pane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; s3Pane = null
        pagesPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; pagesPane = null
        dialPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; dialPane = null
        pickLayer(); addBubble(); warmUp()
        if (s3WasOpen) showPanel(station3Only = true)
    }

    private fun overlayParams(w: Int, h: Int, gravity: Int, x: Int, y: Int, focusable: Boolean = false): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT).apply {
            this.gravity = gravity; this.x = x; this.y = y
            pinToScreen(this)
        }
    }

    /**
     * STORY's windows stay exactly where STORY puts them. Without this, newer Android versions push
     * them up out of the gesture-bar / system-bar area -- which lifted the corner button off the corner.
     */
    private fun pinToScreen(lp: WindowManager.LayoutParams) {
        if (Build.VERSION.SDK_INT >= 30) { lp.fitInsetsTypes = 0; lp.fitInsetsSides = 0; lp.isFitInsetsIgnoringVisibility = true }
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = (40 * dp).toInt()  // same size as Station 3's own arrow
        val v = TextView(this).apply {
            text = "↖"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                // A corner piece: rounded only on the top-left, square into the screen corner.
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 0f, 0f, 0f, 0f, 0f, 0f)
                // Solid black, no outline (owner's call): nothing behind it, like Station 3's white bar, shows through.
                setColor(Color.parseColor("#FF111111"))
            }
            // The ONE Station 3 button: it stays on screen, exactly the same, open or closed -- tap to open, tap to close.
            // It reacts the instant your finger touches it (not when you lift it), so there's no wait.
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    if (s3Pane?.shown == true) closeStation3Now() else showPanel(station3Only = true)
                }
                true
            }
        }
        bubble = v
        bubbleWm = layerWm
        bubbleLp = overlayParams(size, size, Gravity.BOTTOM or Gravity.END, 0, 0).also { it.type = layerType }
        layerWm.addView(v, bubbleLp)  // flush in the bottom-right corner
    }
    private var bubbleLp: WindowManager.LayoutParams? = null

    /** Keeps the Station 3 button above Station 3's own windows (it sits on top of the bar's right end). */
    private fun raiseBubble() {
        val v = bubble ?: return; val lp = bubbleLp ?: return
        runCatching { (bubbleWm ?: wm).removeView(v); (bubbleWm ?: wm).addView(v, lp) }
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
    private class Pane(val frame: FrameLayout, val web: WebView, val lp: WindowManager.LayoutParams, val small: Boolean, val wm: WindowManager, val dial: Boolean = false) {
        var shown = false; var loadedAt = System.currentTimeMillis(); var stale = false
        // Station 3 only: true while its page (re)loads -- it stays invisible until it's drawn, never a white box.
        var loading = true
        // Station 3 only: the window size while open, cut down to what Station 3 really covers.
        var openW = lp.width; var openH = lp.height
    }
    private var s3Pane: Pane? = null
    private var pagesPane: Pane? = null
    // The dial pad's own window: sits still in the middle above Station 3, shown / hidden, never resized.
    private var dialPane: Pane? = null

    private fun flagsFor(small: Boolean, shown: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return if (!shown) base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // No "watch outside touch": touching the app underneath must never close Station 3.
        else if (small) base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildPane(small: Boolean, dial: Boolean = false): Pane {
        val themed = ContextThemeWrapper(this, R.style.Theme_Story)
        val frame = FrameLayout(themed)
        val wv = WebView(themed).apply {
            setBackgroundColor(if (small) Color.TRANSPARENT else Color.BLACK)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            addJavascriptInterface(StoryBridge(this@OverlayService, this@OverlayService, station3 = small, dial = dial), "StoryNative")
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val query = if (dial) "?s3=1&dial=1" else if (small) "?s3=1" else ""
        var paneRef: Pane? = null
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (small) paneRef?.let { it.loading = true; it.frame.alpha = 0f; syncBarBg() }
            }
            // Android sometimes shuts the page down to free memory, which leaves a blank white window:
            // build STORY's windows again instead (and don't let it take the app down).
            override fun onRenderProcessGone(v: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                ui.post { rebuildLayer() }
                return true
            }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? = loader.shouldInterceptRequest(r.url)
            // STORY pages only: never navigate this window to any other site.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString(); return !(u.startsWith(REMOTE) || u.startsWith(LOCAL))
            }
            // No signal / site unreachable: fall back to the copy built into the app.
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) v.loadUrl(LOCAL + query)
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) v.loadUrl(LOCAL + query)
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (dial) {
                    v.evaluateJavascript("window.storyDialOnly&&window.storyDialOnly()", null); v.evaluateJavascript(DIAL_JS, null)
                    ui.postDelayed({ paneRef?.let { it.loading = false; if (it.shown) it.frame.alpha = 1f }; syncBarBg() }, 200)
                } else if (small) {
                    v.evaluateJavascript(exactGeometryJs(), null)
                    v.evaluateJavascript("window.storyStation3Only&&window.storyStation3Only()", null); v.evaluateJavascript(S3_JS, null)
                    // Show it again once Station 3 has actually drawn (a moment after the page is ready).
                    ui.postDelayed({ paneRef?.let { it.loading = false; if (it.shown) it.frame.alpha = 1f }; syncBarBg() }, 200)
                }
            }
        }
        // Live: the screens come from the STORY site, so every upload reaches every phone with no reinstall.
        wv.loadUrl(remoteBase() + query + (if (query.isEmpty()) "?" else "&") + "v=" + System.currentTimeMillis())  // skip the website cache
        // Station 3's page spans the whole screen width (plus the 10dp it reaches past the right edge), so the
        // dial pad can open in the middle of the screen. The window itself is cut down to what's showing.
        val s3W = resources.displayMetrics.widthPixels + (10 * dp).toInt()
        val s3H = minOf((640 * dp).toInt(), realHeightPx())
        // Station 3's page always lays out at full size, pinned to the corner; its window may be
        // smaller (see setStation3Size) and simply cuts off the empty part, so nothing reflows.
        // The dial pad page also lays out at a fixed size (pinned bottom-left); its window just cuts it to the dial pad.
        val dialW = ((280 + 70) * dp).toInt(); val dialH = minOf((560 * dp).toInt(), realHeightPx())
        frame.addView(wv, when {
            dial -> FrameLayout.LayoutParams(dialW, dialH, Gravity.BOTTOM or Gravity.START)
            small -> FrameLayout.LayoutParams(s3W, s3H, Gravity.BOTTOM or Gravity.END)
            else -> FrameLayout.LayoutParams(-1, -1)
        })
        frame.alpha = 0f
        val lp = if (dial)
            WindowManager.LayoutParams(dialW, dialH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(true, false), PixelFormat.TRANSLUCENT).apply {
                // Middle of the screen, just above Station 3's 40dp bar (which sits on the bottom edge).
                gravity = Gravity.BOTTOM or Gravity.START
                x = (resources.displayMetrics.widthPixels - (280 * dp).toInt()) / 2; y = (52 * dp).toInt()
            }
        else if (small)
            WindowManager.LayoutParams(s3W, s3H, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(true, false), PixelFormat.TRANSLUCENT).apply {
                // Station 3's arrow sits 10dp inside this window, so nudge the window 10dp off-screen
                // and the arrow lands exactly on the same corner as the button.
                gravity = Gravity.BOTTOM or Gravity.END; x = -(10 * dp).toInt(); y = -(10 * dp).toInt()
            }
        else
            WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(false, false), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        if (!small) { frame.setBackgroundColor(Color.BLACK); frame.setPadding(0, statusBarHeightPx(), 0, 0) }  // black continues behind the status bar; pages start below it
        // Hidden from the start, and fully see-through to Android too: on Android 12+ an untouchable
        // window that isn't see-through still swallows touches meant for the apps underneath
        // ("isn't optimised for the latest version of Android. Screen touches may be delayed...").
        lp.alpha = 0f
        pinToScreen(lp)  // Station 3's window sits exactly on the corner, like the button
        val paneWm = if (small) layerWm else wm
        if (small) lp.type = layerType
        val p = Pane(frame, wv, lp, small, paneWm, dial)
        paneRef = p
        paneWm.addView(frame, lp)
        return p
    }

    /**
     * The exact pixel gap (the 10dp Station 3's window reaches past the screen edges) and button size
     * (40dp) the app uses, in the page's units -- so the page's white bar lines up with the app's
     * Station 3 button to the pixel, with no sliver of white above or below it.
     */
    private fun exactGeometryJs(): String {
        val gap = (10 * dp).toInt() / dp; val btn = (40 * dp).toInt() / dp
        return "window.__s3Gap=$gap;window.__s3Btn=$btn;document.documentElement.classList.add('s3-native-bar');"
    }

    private fun warmUp() {
        val fresh = s3Pane == null || dialPane == null
        if (s3Pane == null) { addBarBg(); s3Pane = buildPane(true) }  // the white goes in first, so it sits under Station 3's page
        if (pagesPane == null) pagesPane = buildPane(false)
        if (dialPane == null) dialPane = buildPane(true, dial = true)
        if (fresh) raiseBubble()  // done while Station 3 is still hidden, so nothing visibly changes later
        addBar()
    }

    private fun setShown(p: Pane, shown: Boolean) {
        p.shown = shown
        p.lp.flags = flagsFor(p.small, shown)
        // Android 12+ throws away touches that pass through another app's fully opaque window,
        // even an untouchable one. A hidden pane is fully see-through, so apps underneath get every touch.
        p.lp.alpha = if (shown) 1f else 0f
        if (p.small && shown) { p.lp.width = p.openW; p.lp.height = p.openH }
        p.frame.alpha = if (shown && !(p.small && p.loading)) 1f else 0f
        runCatching { p.wm.updateViewLayout(p.frame, p.lp) }
        if (p === s3Pane) syncBarBg()
        if (shown && !p.small) p.web.requestFocus()
    }

    private fun showPanel(station3Only: Boolean) {
        // An update that has waited 3 days is required: STORY opens the update window instead.
        if (Release.isRequired(this)) {
            runCatching { startActivity(Intent(this, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        warmUp()
        if (station3Only) {
            pagesPane?.takeIf { it.shown }?.let { hideOne(it) }
            // Instant: Station 3's page is already drawn and sized (done once, when it loaded), so opening
            // only shows its window -- no redraw, no re-measuring, no other windows touched.
            val p = s3Pane!!
            if (!p.shown) setShown(p, true)
            updateBubble()
            return
        } else {
            // Station 3 stays open; Pages opens underneath it.
            val p = pagesPane!!
            p.web.evaluateJavascript("window.storyShow&&window.storyShow(null,false)", null)
            if (!p.shown) setShown(p, true)
            s3Pane?.takeIf { it.shown && it.wm === p.wm }?.let { s3 -> runCatching { s3.wm.removeView(s3.frame); s3.wm.addView(s3.frame, s3.lp) } }
        }
        updateBubble()
        addBar() // re-add last so the STORY bar stays on top of everything, including the pages
    }

    /** The Station 3 button is always there (open or closed); only the full-screen Pages, which have their own, hide it. */
    private fun updateBubble() {
        bubble?.visibility = if (pagesPane?.shown == true) View.GONE else View.VISIBLE
    }

    private fun hideOne(p: Pane) {
        // Station 3 and the dial pad keep their page exactly as it is while hidden, so showing them
        // again changes nothing on screen (no re-layout, no resize, no jump).
        if (!p.small) p.web.evaluateJavascript("window.storyReset&&window.storyReset()", null)
        setShown(p, false)
        // New screens were published: pick them up in the background. Station 3 / the dial pad only reload
        // then (never on a timer), so tapping them open is always instant.
        if (p.stale || (!p.small && System.currentTimeMillis() - p.loadedAt > 5 * 60_000)) reload(p)
    }

    private fun removePanelNow() {
        dialPane?.takeIf { it.shown }?.let { hideOne(it) }
        s3Pane?.takeIf { it.shown }?.let { hideOne(it) }
        pagesPane?.takeIf { it.shown }?.let { hideOne(it) }
        updateBubble()
    }

    // ---- Station 3's white bar, drawn by the app with exactly the same pixel maths as the Station 3 button,
    //      so its top and bottom match the button perfectly. The page draws only the icons on it. ----
    private var barBg: View? = null
    private var barBgLp: WindowManager.LayoutParams? = null
    private var barBgWm: WindowManager? = null

    private fun addBarBg() {
        removeBarBg()
        val v = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 0f, 0f, 0f, 0f, 0f, 0f)  // like the button: rounded top-left
                setColor(Color.WHITE)
            }
        }
        val h = (40 * dp).toInt()  // the button's own height, same rounding
        val lp = overlayParams(getSharedPreferences("story", MODE_PRIVATE).getInt("bar_w", (220 * dp).toInt()), h, Gravity.BOTTOM or Gravity.END, 0, 0).also {
            it.type = layerType
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            it.alpha = 0f
        }
        v.visibility = View.INVISIBLE
        barBg = v; barBgLp = lp; barBgWm = layerWm
        runCatching { layerWm.addView(v, lp) }
    }

    private fun removeBarBg() {
        barBg?.let { runCatching { (barBgWm ?: wm).removeView(it) } }
        barBg = null; barBgLp = null
    }

    /** The white shows exactly while Station 3's page shows. */
    private fun syncBarBg() {
        val v = barBg ?: return; val lp = barBgLp ?: return
        val on = s3Pane?.let { it.shown && !it.loading } == true
        v.visibility = if (on) View.VISIBLE else View.INVISIBLE
        val a = if (on) 1f else 0f
        if (lp.alpha != a) { lp.alpha = a; runCatching { (barBgWm ?: wm).updateViewLayout(v, lp) } }
    }

    override fun setBarWidth(w: Double) {
        ui.post {
            val v = barBg ?: return@post; val lp = barBgLp ?: return@post
            val px = Math.round(w * dp).toInt()
            if (px <= 0 || lp.width == px) return@post
            lp.width = px
            getSharedPreferences("story", MODE_PRIVATE).edit().putInt("bar_w", px).apply()
            runCatching { (barBgWm ?: wm).updateViewLayout(v, lp) }
        }
    }

    // ---- Always On Display burn-in protection ----
    // While the always-on screen is up, a fine black checkerboard (every other pixel) lies over Station 3's
    // windows, and every minute it swaps to the other half of the pixels. So each pixel is lit only half
    // the time and nothing stays on one spot -- while Station 3 itself never moves a pixel.
    private var aodOn = false
    private var aodPhase = false
    private val aodSwap = object : Runnable { override fun run() { aodPhase = !aodPhase; applyAodMask(); ui.postDelayed(this, 60_000) } }

    fun setAod(on: Boolean) {
        ui.post {
            aodOn = on
            ui.removeCallbacks(aodSwap)
            if (on) ui.postDelayed(aodSwap, 60_000)
            applyAodMask()
        }
    }

    private fun checker(phase: Boolean): android.graphics.drawable.Drawable {
        val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val on = Color.BLACK; val off = Color.TRANSPARENT
        bmp.setPixel(0, 0, if (phase) on else off); bmp.setPixel(1, 1, if (phase) on else off)
        bmp.setPixel(1, 0, if (phase) off else on); bmp.setPixel(0, 1, if (phase) off else on)
        bmp.density = resources.displayMetrics.densityDpi  // one checker square = one screen pixel
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
            paint.isFilterBitmap = false; paint.isAntiAlias = false
        }
    }

    private fun applyAodMask() {
        val views = listOfNotNull(bubble, barBg, s3Pane?.frame, dialPane?.frame)
        for (v in views) v.foreground = if (aodOn) checker(aodPhase) else null
    }

    private fun destroyPanes() {
        removeBarBg()
        for (p in listOfNotNull(s3Pane, pagesPane, dialPane)) { runCatching { p.wm.removeView(p.frame) }; p.web.destroy() }
        s3Pane = null; pagesPane = null; dialPane = null
    }

    /** Gets the Pages out of the way (Home, Apps, opening an app). Station 3 is left exactly as it is. */
    /**
     * Android won't let you press "Update" while anything is drawn over the screen, so STORY
     * steps aside while the install prompt is up. The update restarts STORY by itself; if you
     * cancel instead, STORY comes back after 90 seconds.
     */
    fun pauseForInstall() {
        ui.post {
            ui.removeCallbacks(resumeAfterInstall)
            removePanelNow(); hidePicker(); destroyPanes()
            bubble?.let { runCatching { (bubbleWm ?: wm).removeView(it) } }; bubble = null
            bar?.let { runCatching { wm.removeView(it) } }; bar = null
            ui.postDelayed(resumeAfterInstall, 90_000)
        }
    }
    private val resumeAfterInstall = Runnable { if (bubble == null) { pickLayer(); addBubble(); warmUp() } }

    /** The install prompt is gone (installed, cancelled or failed): bring STORY straight back. */
    fun resumeNow() { ui.post { ui.removeCallbacks(resumeAfterInstall); resumeAfterInstall.run() } }

    override fun hidePanel() { ui.post { pagesPane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble() } }

    /** Station 3's corner arrow was tapped: the one way Station 3 closes. */
    override fun closeStation3() { ui.post { closeStation3Now() } }

    /** Closes Station 3 (and its dial pad) right now, on the spot. */
    private fun closeStation3Now() { dialPane?.takeIf { it.shown }?.let { hideOne(it) }; s3Pane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble() }

    override fun toggleDialpad() { ui.post {
        val d = dialPane ?: return@post
        if (d.shown) hideOne(d) else if (s3Pane?.shown == true) setShown(d, true)
    } }

    override fun closeDialpad() { ui.post { dialPane?.takeIf { it.shown }?.let { hideOne(it) } } }

    /** Fits the dial pad's window to it (dial pad + the X beside it), centred on the screen. Done while hidden. */
    override fun setDialSize(w: Int, h: Int) {
        ui.post {
            val p = dialPane ?: return@post
            val full = p.web.layoutParams
            p.openW = ((w + 58) * dp).toInt().coerceAtMost(full.width)
            p.openH = ((h + 2) * dp).toInt().coerceAtMost(full.height)
            val x = (resources.displayMetrics.widthPixels - (w * dp).toInt()) / 2
            if (p.lp.width != p.openW || p.lp.height != p.openH || p.lp.x != x) {
                p.lp.width = p.openW; p.lp.height = p.openH; p.lp.x = x
                runCatching { p.wm.updateViewLayout(p.frame, p.lp) }
            }
        }
    }

    override fun setStation3Size(w: Int, h: Int) {
        ui.post {
            // Sized once, while still hidden (the page is always kept open), so it never changes on screen.
            val p = s3Pane ?: return@post
            val full = p.web.layoutParams
            val pad = 16  // room for Station 3's shadow
            p.openW = ((w + pad) * dp).toInt().coerceIn((40 * dp).toInt(), full.width)
            p.openH = ((h + pad) * dp).toInt().coerceIn((40 * dp).toInt(), full.height)
            if (p.lp.width != p.openW || p.lp.height != p.openH) {
                p.lp.width = p.openW; p.lp.height = p.openH
                runCatching { p.wm.updateViewLayout(p.frame, p.lp) }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.story.launcher.STOP"
        const val REMOTE = "https://bethestoryownyourstory.github.io/story-lockedintool/"
        const val LOCAL = "https://appassets.androidplatform.net/assets/index.html"
        const val ACTION_REBUILD = "com.story.launcher.REBUILD"
        @Volatile var instance: OverlayService? = null
        @Volatile var running = false

        /**
         * Added to Station 3's page by the app itself, so it works with every version of the
         * screens (live or Test): taps on empty space do nothing (Station 3 stays open), and
         * Station 3 reports the corner area it covers so its window can shrink to just that.
         */
        /** Added to the dial pad's page: reports its size so its window fits it, and ignores taps on empty space. */
        private const val DIAL_JS = """(function(){
  if(window.__storyDialNative) return; window.__storyDialNative = true;
  window.addEventListener('click', function(e){
    var t = e.target;
    if(t && t.closest && t.closest('.station3-dialpad-card')) return;
    e.stopPropagation(); e.preventDefault();
  }, true);
  var last = '';
  function report(){
    var pad = document.querySelector('.station3-dialpad-float');
    if(!pad || !window.StoryNative || !window.StoryNative.setDialSize) return;
    var b = pad.getBoundingClientRect();
    if(!(b.width > 0 && b.height > 0)) return;
    var k = Math.ceil(b.width) + 'x' + Math.ceil(b.height);
    if(k === last) return; last = k;
    window.StoryNative.setDialSize(Math.ceil(b.width), Math.ceil(b.height));
  }
  var queued = false;
  function soon(){ if(queued) return; queued = true; requestAnimationFrame(function(){ queued = false; report(); }); }
  new MutationObserver(soon).observe(document.documentElement, { subtree:true, childList:true, attributes:true, attributeFilter:['class','style'] });
  window.addEventListener('resize', soon);
  report();
})();"""

        private const val S3_JS = """(function(){
  if(window.__storyS3Native) return; window.__storyS3Native = true;
  var PIECES = '.station3-corner-arrow,.station3-bar-card,.station3-dialpad-card,.station3-quick-row';
  window.addEventListener('click', function(e){
    if(!document.body || !document.body.classList.contains('s3-only')) return;
    var t = e.target;
    if(t && t.closest && t.closest(PIECES)) return;
    e.stopPropagation(); e.preventDefault();
  }, true);
  var last = '';
  function report(force){
    if(force) last = '';
    if(!document.body || !document.body.classList.contains('s3-only') || !window.StoryNative || !window.StoryNative.setStation3Size) return;
    var vw = window.innerWidth, vh = window.innerHeight, minL = vw, minT = vh;
    document.querySelectorAll('.station3-region ' + PIECES.split(',').join(',.station3-region ')).forEach(function(el){
      var b = el.getBoundingClientRect();
      if(b.width > 0 && b.height > 0){ minL = Math.min(minL, b.left); minT = Math.min(minT, b.top); }
    });
    if(minL >= vw || minT >= vh) return;
    var k = Math.ceil(vw - minL) + 'x' + Math.ceil(vh - minT);
    if(k === last) return; last = k;
    window.StoryNative.setStation3Size(Math.ceil(vw - minL), Math.ceil(vh - minT));
    var bc = document.querySelector('.station3-region .station3-bar-card');
    if(bc && window.StoryNative.setBarWidth) window.StoryNative.setBarWidth(bc.getBoundingClientRect().width);
  }
  var queued = false;
  function soon(){ if(queued) return; queued = true; requestAnimationFrame(function(){ queued = false; report(); }); }
  new MutationObserver(soon).observe(document.documentElement, { subtree:true, childList:true, attributes:true, attributeFilter:['class','style'] });
  document.addEventListener('transitionend', soon, true);
  window.addEventListener('resize', soon);
  window.__storyS3Report = report;
  report();
})();"""
    }
}
