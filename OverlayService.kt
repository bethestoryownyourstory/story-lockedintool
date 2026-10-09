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
    private var gated = false
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
            userPresentAt = System.currentTimeMillis()
            AodActivity.instance?.finish()  // unlocked (e.g. fingerprint) while the Always On Display was up
            ui.post { syncLockDial() }
            for (d in longArrayOf(300, 1000, 2500)) ui.postDelayed({ syncLockDial(); updateBubble() }, d)
            Updater.check(this@OverlayService); checkScreens()
        }
    }
    // Always On Display: when the screen goes off, STORY's dim always-on screen comes up instead
    // (if switched on). Pressing power while it's up turns the screen truly off.
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOffAt = System.currentTimeMillis(); lockDialAway = false; lockDialPeek = false; lockDialBackAt = 0L; onScreenOff(); ui.post { syncLockDial() } }
    }
    // Screen on: the lock screen may be showing -> the lock screen dial pad (Settings > QUICK ACCESS).
    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { ui.post { syncLockDial() }; ui.postDelayed({ syncLockDial() }, 400) }
    }
    // Always On Display: the moment the screen goes off, switch it straight back on with Station 3
    // showing. Android's display watcher usually reports "off" before the screen-off broadcast does,
    // so whichever comes first wins; the other one is ignored.
    @Volatile private var aodStartedAt = 0L
    private fun onScreenOff() {
        val now = System.currentTimeMillis()
        if (now - aodStartedAt < 800) return               // the same screen-off, reported twice
        aodStartedAt = now
        val aod = AodActivity.instance
        if (aod != null && !aod.isFinishing) {
            // Power pressed on the Always On Display (owner): the screen comes straight back on the lock screen,
            // with Station 3 and the lock screen dial pad. Press power there and the Always On Display returns.
            aod.finish(); aod.overridePendingTransition(0, 0)
            if (AodActivity.enabled(this)) wakeForAod()
            return
        }
        if (!AodActivity.enabled(this)) return
        // Wake the screen right now, in parallel with the black screen starting (not after it).
        wakeForAod()
        val i = Intent(this, AodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        runCatching { startActivity(i) }
        // Some phones quietly block opening it from the background: ask again the way Android allows explicitly.
        val startedAt = aodStartedAt
        ui.postDelayed({
            if (AodActivity.instance == null && AodActivity.enabled(this) && aodStartedAt == startedAt && userPresentAt < startedAt) runCatching {
                wakeForAod()
                val pi = android.app.PendingIntent.getActivity(this, 8, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                if (Build.VERSION.SDK_INT >= 34) pi.send(android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle())
                else pi.send()
            }
        }, 1000)
    }
    private fun wakeForAod() {
        runCatching {
            @Suppress("DEPRECATION")
            getSystemService(android.os.PowerManager::class.java).newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
                "story:aod").acquire(1_500)
        }
    }
    private val displayWatch = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) {
            if (id != android.view.Display.DEFAULT_DISPLAY) return
            val d = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(id) ?: return
            if (d.state == android.view.Display.STATE_OFF && !getSystemService(android.os.PowerManager::class.java).isInteractive) onScreenOff()
        }
    }
    // An app was installed or removed: rebuild the Apps list and tell the Pages, so it's right next time.
    private val appsChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            StoryBridge.refreshApps(c) { ui.post { pagesPane?.web?.evaluateJavascript("window.storyAppsChanged&&window.storyAppsChanged()", null) } }
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
        // Using STORY means agreeing to automatic updates (everyone, owner included). Not agreed yet:
        // show the agreement and stay off; agreeing starts STORY.
        if (!Release.agreedAuto(this)) { gated = true; running = false; UpdateActivity.ask(this, force = true); stopSelf(); return }
        instance = this
        getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
        quietApps(false)  // never leave the phone muted (e.g. STORY was restarted while the Pages were open)
        pickLayer()
        addBubble()
        addBar()
        ui.post { warmUp() }  // build both panes now so Station 3 and Pages open instantly
        ui.postDelayed(updateLoop, 15_000)  // then look for a new STORY version in the background
        Updater.schedule(this)              // and keep checking every 15 minutes even if STORY is off
        StoryBridge.refreshApps(this)  // the Apps area's list is ready before it's ever opened
        val pkgFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(appsChanged, pkgFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(appsChanged, pkgFilter)
        val unlockFilter = android.content.IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, unlockFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(unlocked, unlockFilter)
        val offFilter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, offFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOff, offFilter)
        runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayWatch, ui) }
        val onFilter = android.content.IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, onFilter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOn, onFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (gated) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) { getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", false).apply(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_REBUILD) ui.post { rebuildLayer() }
        return START_STICKY
    }

    override fun onDestroy() {
        if (gated) { super.onDestroy(); return }
        quietApps(false)
        removePanelNow(); hidePicker(); destroyPanes()
        bubble?.let { runCatching { (bubbleWm ?: wm).removeView(it) } }
        instance = null
        ui.removeCallbacks(updateLoop); ui.removeCallbacks(resumeAfterInstall)
        runCatching { unregisterReceiver(unlocked) }
        runCatching { unregisterReceiver(screenOn) }
        runCatching { unregisterReceiver(screenOff) }
        runCatching { unregisterReceiver(appsChanged) }
        ui.removeCallbacks(hideToast); hideToast.run()
        runCatching { getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayWatch) }
        ui.removeCallbacks(aodSwap)
        bar?.let { runCatching { (barWm ?: wm).removeView(it) } }
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
                if (before != null && before != v) for (p in listOfNotNull(s3Pane, pagesPane, dialPane, profilePane, profileGapPane, lockDialPane)) { if (p.shown) p.stale = true else reload(p) }  // every STORY window, the dial pad included
            }
        }.start()
    }

    /** Fresh copy of the screens, skipping the website's cache. */
    private fun reload(p: Pane) {
        p.stale = false; p.loadedAt = System.currentTimeMillis()
        p.web.loadUrl(remoteBase() + "?" + (if (p.lock) "s3=1&dial=1&lock=1&" else if (p.gap) "s3=1&profile=1&gap=1&" else if (p.profile) "s3=1&profile=1&" else if (p.dial) "s3=1&dial=1&" else if (p.small) "s3=1&" else "") + "v=" + System.currentTimeMillis())
    }

    private fun rebuildLayer() {
        screensSeen = null
        val s3WasOpen = s3Pane?.shown == true
        removePanelNow()
        bubble?.let { runCatching { (bubbleWm ?: wm).removeView(it) } }; bubble = null
        removeBarBg()
        s3Pane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; s3Pane = null
        quietApps(false)
        pagesPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; pagesPane = null
        dialPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; dialPane = null
        profilePane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; profilePane = null
        profileGapPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; profileGapPane = null
        lockDialPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; lockDialPane = null
        removeProfileStrip()
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
            text = "↖"; setTextColor(th(Color.WHITE)); textSize = 18f; gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                // A corner piece: rounded only on the top-left, square into the screen corner.
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 0f, 0f, 0f, 0f, 0f, 0f)
                // Solid black, no outline (owner's call): nothing behind it, like Station 3's white bar, shows through.
                setColor(th(Color.parseColor("#FF000000")))
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
        bar?.let { runCatching { (barWm ?: wm).removeView(it) } }
        val hasLine = phoneHasGestureLine()
        val w = (150 * dp).toInt(); val h = (26 * dp).toInt()
        val touch = ViewConfiguration.get(this).scaledTouchSlop
        // STORY's own line, only on phones that don't have one (3-button navigation). It's the exact size
        // and shape of Android's own gesture line (read from the phone's System UI), so it's the same line.
        val (lineW, lineH, lineR) = phoneLineSize()
        val line = View(this).apply {
            background = GradientDrawable().apply { cornerRadius = lineR; setColor(th(Color.parseColor("#B3FFFFFF"))) }
        }
        val v: View = FrameLayout(this).apply { addView(line, FrameLayout.LayoutParams(lineW, lineH, Gravity.CENTER)) }
        barLine = line; barHasLine = hasLine
        updateBarLine()
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
        barWm = layerWm
        barLp = overlayParams(w, h, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, bottom).also { it.type = layerType }
        barLp!!.flags = barFlags(barLp!!.flags)
        layerWm.addView(v, barLp)
    }
    private var barLp: WindowManager.LayoutParams? = null
    // While Station 3 is open its own buttons come first: the bottom line's tap area (which sits under
    // Station 3's bar) stops catching taps, so a tap on Station 3 is only ever Station 3's.
    private fun barFlags(f: Int): Int {
        val s3Open = s3Pane?.shown == true
        return if (s3Open) f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else f and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
    }
    private fun syncBarTouch() {
        val v = bar ?: return; val lp = barLp ?: return
        val f = barFlags(lp.flags)
        if (f != lp.flags) { lp.flags = f; runCatching { (barWm ?: wm).updateViewLayout(v, lp) } }
    }
    private var barWm: WindowManager? = null
    private var barLine: View? = null
    private var barHasLine = false
    // The phone's own line shows everywhere (the Pages sit under it), so STORY only draws one when the phone has none.
    private fun updateBarLine() { barLine?.visibility = if (!barHasLine) View.VISIBLE else View.INVISIBLE }

    /** Android's gesture line size (width, height, corner radius in px), from the phone's own System UI. */
    private fun phoneLineSize(): Triple<Int, Int, Float> {
        val r = runCatching { packageManager.getResourcesForApplication("com.android.systemui") }.getOrNull()
        fun dim(name: String, fallbackDp: Float): Float {
            val id = r?.getIdentifier(name, "dimen", "com.android.systemui") ?: 0
            return if (id != 0) runCatching { r!!.getDimension(id) }.getOrDefault(fallbackDp * dp) else fallbackDp * dp
        }
        val w = dim("navigation_home_handle_width", 108f)
        val radius = dim("navigation_handle_radius", 2f)
        val h = maxOf(dim("navigation_handle_height", 0f), radius * 2)
        return Triple(Math.round(w), maxOf(1, Math.round(h)), radius)
    }

    // ---- Apps | Pages switch ----
    private fun showPicker() {
        hidePicker()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding((22 * dp).toInt(), (12 * dp).toInt(), (22 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * dp; setColor(th(Color.parseColor("#F2111111"))); setStroke((1 * dp).toInt(), th(Color.parseColor("#44FFFFFF"))) }
        }
        fun word(t: String, onTap: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(th(Color.WHITE)); textSize = 18f; setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0); setOnClickListener { hidePicker(); onTap() }
        }
        // Apps = straight back to whatever was underneath (the app you were on, untouched, or the home screen).
        row.addView(word("Apps") { hidePanel() })
        row.addView(TextView(this).apply { text = "|"; setTextColor(th(Color.parseColor("#66FFFFFF"))); textSize = 18f })
        row.addView(word("Pages") { showPanel(station3Only = false) })  // the only way into STORY itself
        picker = row; pickerWm = layerWm
        layerWm.addView(row, overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (64 * dp).toInt() + (if (phoneHasGestureLine()) 0 else navBarHeightPx())).also { it.type = layerType })
        ui.postDelayed({ hidePicker() }, 4000)
    }

    private var pickerWm: WindowManager? = null
    private fun hidePicker() { picker?.let { runCatching { (pickerWm ?: wm).removeView(it) } }; picker = null }

    // ---- STORY panes (built once, kept warm, so they open instantly) ----
    private class Pane(val frame: FrameLayout, val web: WebView, val lp: WindowManager.LayoutParams, val small: Boolean, var wm: WindowManager, val dial: Boolean = false, val profile: Boolean = false, val gap: Boolean = false, val lock: Boolean = false) {
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
    private var profilePane: Pane? = null
    // Profile is three windows so Station 3's gap is a REAL gap (touches go straight through it to whatever is
    // behind): Profile itself (stops just above the gap's line), the gap's own untouchable window (the line and
    // the little men, see-through), and a plain strip in Profile's colour to the gap's left (takes touches).
    private var profileGapPane: Pane? = null
    private var profileStrip: View? = null
    private var profileStripLp: WindowManager.LayoutParams? = null
    private var profileStripWm: WindowManager? = null
    /** The bottom band Profile leaves to the gap: Station 3's 40dp bar plus the 2dp line along its top. */
    private fun profileBandPx() = (40 * dp).toInt() + Math.round(2 * dp)
    private fun profileGapWPx() = (barBgLp?.width ?: getSharedPreferences("story", MODE_PRIVATE).getInt("bar_w", (220 * dp).toInt())) + Math.round(2 * dp)

    private fun flagsFor(small: Boolean, shown: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return if (!shown) base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // No "watch outside touch": touching the app underneath must never close Station 3.
        else if (small) base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        else base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildPane(small: Boolean, dial: Boolean = false, profile: Boolean = false, gap: Boolean = false, lock: Boolean = false): Pane {
        val themed = ContextThemeWrapper(this, R.style.Theme_Story)
        val frame = FrameLayout(themed)
        val wv = WebView(themed).apply {
            setBackgroundColor(if (small) Color.TRANSPARENT else th(Color.BLACK))
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.mediaPlaybackRequiresUserGesture = false  // Create plays back the video you just made
            addJavascriptInterface(StoryBridge(this@OverlayService, this@OverlayService, station3 = small, dial = dial, profile = profile || gap, lock = lock), "StoryNative")
        }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val query = if (lock) "?s3=1&dial=1&lock=1" else if (gap) "?s3=1&profile=1&gap=1" else if (profile) "?s3=1&profile=1" else if (dial) "?s3=1&dial=1" else if (small) "?s3=1" else ""
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
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (u.startsWith(ProfilePhotoActivity.PHOTO_BASE)) return photoResponse(r)
                return loader.shouldInterceptRequest(r.url)
            }
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
                if (profile) {
                    v.evaluateJavascript(profileGeometryJs(), null)
                    v.evaluateJavascript("window.storyProfileOnly&&window.storyProfileOnly()", null)
                    ui.postDelayed({ paneRef?.let { it.loading = false; if (it.shown) it.frame.alpha = 1f } }, 200)
                } else if (dial) {
                    v.evaluateJavascript("window.storyDialOnly&&window.storyDialOnly()", null); v.evaluateJavascript(DIAL_JS, null)
                    ui.postDelayed({ paneRef?.let { it.loading = false; if (it.shown) it.frame.alpha = 1f }; syncBarBg() }, 200)
                } else if (small) {
                    v.evaluateJavascript(exactGeometryJs(), null)
                    v.evaluateJavascript("window.__storyLocked=${keyguardLocked()};window.storyStation3Only&&window.storyStation3Only()", null); v.evaluateJavascript(S3_JS, null)
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
            profile -> FrameLayout.LayoutParams(-1, -1)
            dial -> FrameLayout.LayoutParams(dialW, dialH, Gravity.BOTTOM or Gravity.START)
            small -> FrameLayout.LayoutParams(s3W, s3H, Gravity.BOTTOM or Gravity.END)
            else -> FrameLayout.LayoutParams(-1, -1)
        })
        frame.alpha = 0f
        val lp = if (profile)
            if (gap)
                // Station 3's gap: bottom-right, exactly the bar plus its line; never takes a touch.
                WindowManager.LayoutParams(profileGapWPx(), profileBandPx(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(true, false), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.END }
            else
                // Profile: the whole screen down to just above the gap's line.
                WindowManager.LayoutParams(-1, realHeightPx() - profileBandPx(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(true, false), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        else if (dial && lock)
            // Lock screen dial pad: always open, flush to the very bottom, centred (the lock screen has no bottom bar).
            WindowManager.LayoutParams(dialW, dialH, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(true, false), PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                x = (resources.displayMetrics.widthPixels - (280 * dp).toInt()) / 2; y = 0
            }
        else if (dial)
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
            // The whole screen, under the phone's own status bar (so its notification panel works exactly as
            // everywhere else). On 3-button phones it stops above the buttons so they keep working.
            WindowManager.LayoutParams(-1, if (phoneHasGestureLine()) -1 else realHeightPx() - navBarHeightPx(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flagsFor(false, false), PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        // Black behind the status bar; the page itself starts right below it, flush (no extra gap).
        if (!small) { frame.setBackgroundColor(th(Color.BLACK)); frame.setPadding(0, statusBarHeightPx(), 0, 0) }
        // Hidden from the start, and fully see-through to Android too: on Android 12+ an untouchable
        // window that isn't see-through still swallows touches meant for the apps underneath
        // ("isn't optimised for the latest version of Android. Screen touches may be delayed...").
        lp.alpha = 0f
        pinToScreen(lp)  // Station 3's window sits exactly on the corner, like the button
        // Station 3's windows sit in STORY's top layer; the Pages sit in the normal overlay layer under the
        // phone's status bar, notification panel and keyboard -- all of which then just work, as on any app.
        // Profile's main window sits in the Pages' layer (under the status bar, like the Pages) so the keyboard can
        // show above it when you type your name / username; Station 3, the gap and the strip stay in the top layer.
        val inLayer = small && !(profile && !gap)
        val paneWm = if (inLayer) layerWm else wm
        if (inLayer) lp.type = layerType
        val p = Pane(frame, wv, lp, small, paneWm, dial, profile, gap, lock)
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
        if (pagesPane == null) pagesPane = buildPane(false)  // first, so Station 3 stays above the Pages
        if (profilePane == null) profilePane = buildPane(true, profile = true)  // under Station 3's bar, button and dial pad
        if (profileGapPane == null) { profileGapPane = buildPane(true, profile = true, gap = true); addProfileStrip() }
        // The lock screen dial pad sits UNDER Station 3's windows: Station 3, extended, goes on top of it (owner).
        if (lockDialPane == null) lockDialPane = buildPane(true, dial = true, lock = true).also { lp ->
            lp.frame.setOnTouchListener { _, e ->
                // Android zeroes the position of touches in other apps' / the system's windows; STORY's own windows
                // (Station 3, the Always On Display) report a real one - those never move the dial pad.
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && e.x == 0f && e.y == 0f) lockScreenTouched()
                false
            }
        }
        if (s3Pane == null) { addBarBg(); s3Pane = buildPane(true) }  // the white goes in first, so it sits under Station 3's page
        if (dialPane == null) dialPane = buildPane(true, dial = true)
        if (fresh) raiseBubble()  // done while Station 3 is still hidden, so nothing visibly changes later
        addBar()
    }

    private fun setShown(p: Pane, shown: Boolean) {
        p.shown = shown
        // Profile's main window takes the keyboard like the Pages do (so typing a name / username / bio just works).
        p.lp.flags = if (p.gap) flagsFor(true, false) else if (p.profile) flagsFor(false, shown) else flagsFor(p.small, shown)
        // Lock screen dial pad: hear touches anywhere else on the lock screen (swipe up to unlock...) to step away.
        if (p.lock && shown) p.lp.flags = p.lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        // Android 12+ throws away touches that pass through another app's fully opaque window,
        // even an untouchable one. A hidden pane is fully see-through, so apps underneath get every touch.
        p.lp.alpha = if (shown) 1f else 0f
        if (p.small && shown) { p.lp.width = p.openW; p.lp.height = p.openH }
        p.frame.alpha = if (shown && !(p.small && p.loading)) 1f else 0f
        runCatching { p.wm.updateViewLayout(p.frame, p.lp) }
        if (p === s3Pane) { syncBarBg(); syncBarTouch(); tellProfileStation3() }
        if (p === profilePane) {
            syncCreate()
            profileGapPane?.let { g -> if (g.shown != shown) setShown(g, shown) }
            showProfileStrip(shown)
            if (!shown) profileGapPane?.web?.evaluateJavascript("window.storyProfileS3&&window.storyProfileS3(true)", null)
        }
        if (shown && (!p.small || (p.profile && !p.gap))) p.web.requestFocus()
        if (p === pagesPane) { quietApps(shown); updateBarLine(); if (!shown && pagesTyping) pagesBackToTop(); updateBubble() }
    }

    // While Pages is open, STORY silences the app underneath itself (a TikTok video, YouTube...), like the
    // phone would: apps that listen pause, and any that don't are muted. Music is never touched -- if music is
    // playing (or starts), STORY leaves the sound on. The app never closes; its sound is back when Pages closes.
    private var quietFocus: android.media.AudioFocusRequest? = null
    private var quietWatch: android.media.AudioManager.AudioPlaybackCallback? = null
    // Music = audio marked as music, or anything playing from an app other than the one on screen
    // (a music app playing in the background). That is never paused or muted.
    private fun musicPlaying(am: android.media.AudioManager) = StoryAccessibilityService.backgroundMusicPlaying(this) || runCatching {
        am.activePlaybackConfigurations.any { it.audioAttributes.contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC }
    }.getOrDefault(false)
    private fun setMuted(am: android.media.AudioManager, on: Boolean) {
        val prefs = getSharedPreferences("story", MODE_PRIVATE)
        if (on == prefs.getBoolean("muted_by_story", false)) return
        runCatching { am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, if (on) android.media.AudioManager.ADJUST_MUTE else android.media.AudioManager.ADJUST_UNMUTE, 0) }
        prefs.edit().putBoolean("muted_by_story", on).apply()
    }
    private fun quietApps(on: Boolean) {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        if (!on) {
            quietWatch?.let { runCatching { am.unregisterAudioPlaybackCallback(it) } }; quietWatch = null
            setMuted(am, false)
            quietFocus?.let { runCatching { am.abandonAudioFocusRequest(it) } }; quietFocus = null
            return
        }
        if (quietWatch != null || musicPlaying(am)) return
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener { }
            .build()
        if (runCatching { am.requestAudioFocus(req) }.getOrNull() == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) quietFocus = req
        setMuted(am, true)
        // Music starts while Pages is open: give the sound back straight away.
        val watch = object : android.media.AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                if (configs.any { it.audioAttributes.contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC } || StoryAccessibilityService.backgroundMusicPlaying(this@OverlayService)) setMuted(am, false)
            }
        }
        runCatching { am.registerAudioPlaybackCallback(watch, ui) }
        quietWatch = watch
    }

    private fun showPanel(station3Only: Boolean) {
        warmUp()
        if (station3Only) {
            // Station 3 opens exactly the same over the Pages as over any app -- the Pages stay put.
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

        }
        updateBubble()
        addBar() // re-add last so the STORY bar stays on top of everything, including the pages
    }

    /** The Station 3 button is always there and always the same -- over apps, over the Pages, open or closed. */
    /** The Station 3 button is always there and always the same -- except where Settings > QUICK ACCESS says
     *  otherwise: only on the Pages / only on apps / not at all (Dial pad only), and on the Always On Display
     *  only when its Always On Display is on. Hidden = Station 3 closes. */
    private fun updateBubble() {
        val pagesUp = pagesPane?.shown == true
        val where = QuickAccess.where(this)
        var on = QuickAccess.station3On(this) && when (where) { "pages" -> pagesUp; "apps" -> !pagesUp; else -> true }
        if (aodShowing() && !QuickAccess.s3Aod(this)) on = false
        if (!on && s3Pane?.shown == true) closeStation3Now()
        bubble?.visibility = if (on) View.VISIBLE else View.INVISIBLE
        bubbleLp?.let { lp ->
            val nt = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            val f = if (on) lp.flags and nt.inv() else lp.flags or nt
            if (f != lp.flags) { lp.flags = f; runCatching { (bubbleWm ?: wm).updateViewLayout(bubble, lp) } }
        }
        syncLockDial()
    }

    private fun hideOne(p: Pane) {
        // Station 3 and the dial pad keep their page exactly as it is while hidden, so showing them
        // again changes nothing on screen (no re-layout, no resize, no jump).
        if (!p.small) p.web.evaluateJavascript("window.storyReset&&window.storyReset()", null)
        setShown(p, false)
        if (p === profilePane) { p.web.evaluateJavascript("window.storyProfileReset&&window.storyProfileReset()", null); profileGapPane?.takeIf { it.stale }?.let { reload(it) } }
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
                setColor(th(Color.WHITE))
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
            fitProfileGap()
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
            updateBubble()
            syncLockDial()  // the lock screen dial pad joins (or leaves) the Always On Display right away
            ui.removeCallbacks(aodSwap)
            if (on) ui.postDelayed(aodSwap, 60_000)
            applyAodMask()
        }
    }

    /** Burn-in protection: switches OFF every other pixel of what a window draws (and swaps which half each
     *  minute). It cuts pixels out instead of painting black dots, so see-through parts of a window stay fully
     *  see-through (e.g. Station 3's window over the lock screen dial pad never shows a dark block). */
    private fun checker(phase: Boolean): android.graphics.drawable.Drawable {
        val bmp = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val cut = Color.BLACK; val keep = Color.TRANSPARENT
        bmp.setPixel(0, 0, if (phase) cut else keep); bmp.setPixel(1, 1, if (phase) cut else keep)
        bmp.setPixel(1, 0, if (phase) keep else cut); bmp.setPixel(0, 1, if (phase) keep else cut)
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.BitmapShader(bmp, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
            isFilterBitmap = false; isAntiAlias = false
        }
        return object : android.graphics.drawable.Drawable() {
            override fun draw(c: android.graphics.Canvas) { c.drawRect(bounds, paint) }
            override fun setAlpha(a: Int) {}
            override fun setColorFilter(f: android.graphics.ColorFilter?) {}
            @Deprecated("Deprecated in Java")
            override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun applyAodMask() {
        val views = listOfNotNull(bubble, barBg, s3Pane?.frame, dialPane?.frame, profilePane?.frame, profileGapPane?.frame, profileStrip, lockDialPane?.frame)
        for (v in views) {
            // No layer switching (that made Station 3 / the dial pad redraw late): each is its own window, so the
            // cut-out only ever affects that window's own pixels anyway.
            v.foreground = if (aodOn) checker(aodPhase) else null
        }
    }

    private fun destroyPanes() {
        quietApps(false)
        removeBarBg()
        for (p in listOfNotNull(s3Pane, pagesPane, dialPane, profilePane, profileGapPane, lockDialPane)) { runCatching { p.wm.removeView(p.frame) }; p.web.destroy() }
        s3Pane = null; pagesPane = null; dialPane = null; profilePane = null; profileGapPane = null; lockDialPane = null
        removeProfileStrip()
    }

    /** Gets the Pages out of the way (Home, Apps, opening an app). Station 3 is left exactly as it is. */
    /**
     * The install prompt is up: only the Pages and the bottom bar step aside so "Update" can be pressed.
     * Station 3 (button, bar, dial pad) stays exactly where and how it is -- it never moves or goes away.
     * The update restarts STORY by itself; if you cancel instead, the bottom bar comes back after 90 seconds.
     */
    fun pauseForInstall() {
        ui.post {
            ui.removeCallbacks(resumeAfterInstall)
            hidePicker()
            pagesPane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble()
            bar?.let { runCatching { (barWm ?: wm).removeView(it) } }; bar = null
            ui.postDelayed(resumeAfterInstall, 90_000)
        }
    }
    private val resumeAfterInstall = Runnable { if (bar == null) addBar() }

    /** The install prompt is gone (installed, cancelled or failed): bring STORY straight back. */
    fun resumeNow() { ui.post { ui.removeCallbacks(resumeAfterInstall); resumeAfterInstall.run() } }

    override fun hidePanel() { ui.post { pagesPane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble() } }

    /** Station 3's corner arrow was tapped: the one way Station 3 closes. */
    override fun closeStation3() { ui.post { closeStation3Now() } }

    /** Closes Station 3 (and its dial pad) right now, on the spot. */
    private fun closeStation3Now() { dialPane?.takeIf { it.shown }?.let { hideOne(it) }; s3Pane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble() }

    override fun toggleDialpad() { ui.post {
        val d = dialPane ?: return@post
        if (d.shown) hideOne(d) else if (s3Pane?.shown == true) { profilePane?.takeIf { it.shown }?.let { hideOne(it) }; setShown(d, true) }
    } }

    // ---- System theme (Settings): Black or White. Only black and white swap -- on the Pages, Station 3
    //      (button, bar), the dial pad, Profile, the message bubble and the Apps | Pages switch. ----
    private fun th(c: Int) = StoryTheme.swap(this, c)
    override fun setTheme(t: String) { ui.post {
        if (StoryTheme.get(this) == t) return@post
        StoryTheme.set(this, t)
        (bubble as? TextView)?.let { b -> b.setTextColor(th(Color.WHITE)); (b.background as? GradientDrawable)?.setColor(th(Color.parseColor("#FF000000"))) }
        (barBg?.background as? GradientDrawable)?.setColor(th(Color.WHITE))
        (barLine?.background as? GradientDrawable)?.setColor(th(Color.parseColor("#B3FFFFFF")))
        pagesPane?.let { it.frame.setBackgroundColor(th(Color.BLACK)); it.web.setBackgroundColor(th(Color.BLACK)) }
        profileStrip?.setBackgroundColor(th(Color.BLACK))
        for (p in listOfNotNull(pagesPane, s3Pane, dialPane, profilePane, profileGapPane, lockDialPane))
            p.web.evaluateJavascript("window.storySetTheme&&window.storySetTheme('$t')", null)
        hidePicker()
    } }

    /** The plain strip to the gap's left, in Profile's colour: it takes touches, so Profile never leaks a tap there. */
    @SuppressLint("ClickableViewAccessibility")
    private fun addProfileStrip() {
        removeProfileStrip()
        val v = View(this).apply { setBackgroundColor(th(Color.BLACK)); setOnTouchListener { _, _ -> true } }
        val lp = overlayParams(resources.displayMetrics.widthPixels - profileGapWPx(), profileBandPx(), Gravity.BOTTOM or Gravity.START, 0, 0).also {
            it.type = layerType
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            it.alpha = 0f
        }
        v.visibility = View.INVISIBLE
        profileStrip = v; profileStripLp = lp; profileStripWm = layerWm
        runCatching { layerWm.addView(v, lp) }
    }
    private fun removeProfileStrip() { profileStrip?.let { runCatching { (profileStripWm ?: layerWm).removeView(it) } }; profileStrip = null; profileStripLp = null; profileStripWm = null }
    private fun showProfileStrip(on: Boolean) {
        val v = profileStrip ?: return; val lp = profileStripLp ?: return
        v.visibility = if (on) View.VISIBLE else View.INVISIBLE
        lp.alpha = if (on) 1f else 0f
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { (profileStripWm ?: layerWm).updateViewLayout(v, lp) }
    }
    /** Station 3's bar changed width: the gap window and the strip follow it exactly. */
    private fun fitProfileGap() {
        val gw = profileGapWPx()
        profileGapPane?.let { g -> if (g.lp.width != gw) { g.lp.width = gw; g.openW = gw; runCatching { g.wm.updateViewLayout(g.frame, g.lp) } } }
        profileStripLp?.let { lp -> val sw = resources.displayMetrics.widthPixels - gw; if (lp.width != sw) { lp.width = sw; runCatching { (profileStripWm ?: layerWm).updateViewLayout(profileStrip, lp) } } }
        for (p in listOfNotNull(profilePane, profileGapPane)) p.web.evaluateJavascript(profileGeometryJs(), null)
    }

    // ---- Settings > QUICK ACCESS: the lock screen dial pad (always open, flush to the bottom, centred), with
    //      Station 3's gap line around the button when Station 3 is there too; on the Always On Display too if set. ----
    private var lockDialPane: Pane? = null
    /** Locked = Android says so AND you haven't unlocked since the screen last went off (some phones keep saying
     *  "locked" for a while after you unlock - your unlock wins until the screen goes off again). */
    @Volatile private var screenOffAt = 0L
    private fun keyguardLocked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true &&
        !(userPresentAt > screenOffAt)
    /** STORY's Always On Display is up only while the phone is really locked. If it's somehow still around
     *  after you unlock, it's ended right here (it must never hide Station 3 or grey anything on an unlocked phone). */
    @Volatile private var userPresentAt = 0L
    private fun aodShowing(): Boolean {
        val a = AodActivity.instance ?: return false
        if (userPresentAt <= aodStartedAt) return true
        runCatching { a.finish() }  // you unlocked after it came up: it's over
        return false
    }
    private val lockWatch = object : Runnable { override fun run() { syncLockDial() } }
    /** The lock screen dial pad steps away (until the screen next goes off) the moment you touch the lock screen
     *  anywhere else (e.g. swipe up to unlock) or the phone asks for your PIN / password / pattern. It must
     *  NEVER sit over the phone's own PIN pad. */
    @Volatile private var lockDialAway = false
    fun lockBouncerShown(sure: Boolean) { ui.post {
        if (lockDialPane?.shown != true && !keyguardLocked()) return@post
        // A weak sign while STORY can see the screen: trust what's actually showing (checked now and again as the
        // PIN page slides in) - e.g. a failed fingerprint's "use PIN" hint must not send the dial pad away.
        if (!sure && StoryAccessibilityService.unlockEntryShowing() != null) {
            syncLockDial(); for (d in longArrayOf(300, 800)) ui.postDelayed({ syncLockDial() }, d); return@post
        }
        lockDialAway = true; lockDialPeek = false; syncLockDial()
    } }
    /** A touch on the lock screen (e.g. starting to swipe up): the dial pad steps aside for that moment and comes
     *  back if the PIN page didn't show (you let go, or dragged back down). If the PIN page shows, it stays away.
     *  Touching again soon after it came back means you're heading for the PIN page: it stays away too. */
    @Volatile private var lockDialPeek = false
    private var lockDialBackAt = 0L
    private val lockDialBack = Runnable {
        if (lockDialPeek && !lockDialAway) { lockDialPeek = false; lockDialBackAt = System.currentTimeMillis(); syncLockDial() }
    }
    private fun lockScreenTouched() {
        // Can't see the screen: touching again soon after it came back means you're heading for the PIN page.
        // (When STORY can see the screen it simply checks whether the PIN page is there - see syncLockDial.)
        if (StoryAccessibilityService.unlockEntryShowing() == null && System.currentTimeMillis() - lockDialBackAt < 6000) { lockDialAway = true; lockDialPeek = false; syncLockDial(); return }
        lockDialPeek = true; syncLockDial()
        ui.removeCallbacks(lockDialBack); ui.postDelayed(lockDialBack, 1500)
    }
    private var lastLocked: Boolean? = null
    private fun syncLockDial() {
        ui.removeCallbacks(lockWatch)
        // Station 3's dial pad button (dial pad separate): only while unlocked. Locked, the dial pad closes too.
        val locked = keyguardLocked()
        if (aodOn && !aodShowing()) { aodOn = false; applyAodMask() }  // unlocked: nothing stays greyed
        if (locked != lastLocked) {
            lastLocked = locked
            s3Pane?.web?.evaluateJavascript("window.storyLockState&&window.storyLockState($locked)", null)
            if (locked && QuickAccess.mode(this) == "separate") dialPane?.takeIf { it.shown }?.let { hideOne(it) }
        }
        val p = lockDialPane ?: return
        val aodUp = aodShowing()
        // The phone's own PIN / password / pattern page is on screen right now: never on top of it.
        val awake = getSystemService(android.os.PowerManager::class.java).isInteractive
        val entry = if (QuickAccess.lockDial(this) && locked && awake) StoryAccessibilityService.unlockEntryShowing() else null
        // The phone's own quick settings / notifications pulled down over the lock screen: out of the way (owner).
        val shade = if (QuickAccess.lockDial(this) && locked && awake && entry != true) StoryAccessibilityService.shadeShowing() == true else false
        // It never moves (owner): it simply stays put through screen off / Always On Display / lock screen, so it's
        // already there the instant anything shows. Only hidden on the Always On Display if that's switched off for it.
        val onAod = aodUp || (!awake && AodActivity.enabled(this))
        val want = QuickAccess.lockDial(this) && locked && !lockDialAway && !lockDialPeek && entry != true && !shade &&
            (if (onAod) QuickAccess.dialAod(this) else true)
        if (want != p.shown) {
            if (!want) p.web.evaluateJavascript("window.storyDialClear&&window.storyDialClear()", null)
            setShown(p, want)
            if (!want && p.stale) reload(p)  // new screens were published while it was up: pick them up now it's hidden
        }
        // While it's up, keep checking: the moment the phone unlocks (by any route) it goes.
        // (and while the PIN page is up, it comes back as soon as you drag that away).
        if (awake && (want || aodOn || entry == true || shade)) ui.postDelayed(lockWatch, if (shade) 400L else 1000L)
    }
    override fun setLockDialSize(w: Int, h: Int) { ui.post {
        val p = lockDialPane ?: return@post
        val full = p.web.layoutParams
        p.openW = ((w + 2) * dp).toInt().coerceAtMost(full.width)
        p.openH = ((h + 2) * dp).toInt().coerceAtMost(full.height)
        val x = (resources.displayMetrics.widthPixels - (w * dp).toInt()) / 2
        if (p.lp.width != p.openW || p.lp.height != p.openH || p.lp.x != x) {
            p.lp.width = p.openW; p.lp.height = p.openH; p.lp.x = x
            runCatching { p.wm.updateViewLayout(p.frame, p.lp) }
        }
    } }
    /** The dial pad's call button (lock screen and Station 3): calls straight away, even locked. */
    override fun callNumber(number: String) { ui.post {
        val n = number.filter { it.isDigit() || it in "+*#" }
        if (n.isEmpty()) return@post
        val uri = android.net.Uri.fromParts("tel", n, null)
        val allowed = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val ok = allowed && runCatching { getSystemService(android.telecom.TelecomManager::class.java).placeCall(uri, android.os.Bundle()) }.isSuccess
        if (!ok) {
            // Not allowed to call yet: the phone's own dialer with the number in it (and ask once for next time).
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            if (!allowed && !keyguardLocked()) askPermissions(arrayOf(android.Manifest.permission.CALL_PHONE))
        }
    } }
    /** Settings > QUICK ACCESS changed. */
    override fun setQuickAccess(json: String) { ui.post {
        QuickAccess.save(this, json)
        s3Pane?.web?.evaluateJavascript("window.storyQuickAccessChanged&&window.storyQuickAccessChanged()", null)
        updateBubble()
        // The lock screen dial pad calls straight away: that needs Android's "make phone calls" once.
        if (QuickAccess.lockDial(this) && checkSelfPermission(android.Manifest.permission.CALL_PHONE) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            askPermissions(arrayOf(android.Manifest.permission.CALL_PHONE))
    } }
    private fun askPermissions(perms: Array<String>) {
        if (!stepAsideForSystem()) return
        runCatching { startActivity(Intent(this, AskActivity::class.java).putExtra(AskActivity.EXTRA_PERMS, perms).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { comeBackFromSystem() }
    }
    fun permissionsDone() { ui.post { comeBackFromSystem() } }

    // ---- Profile and Messages: Station 3's profile / messages buttons open them in ONE window attached to Station 3
    //      (same gap, line and scene). Its own button closes it; the other button switches straight over. ----
    private var s3Page = "profile"
    override fun toggleProfile() = toggleS3Page("profile")
    override fun toggleMessages() = toggleS3Page("messages")
    override fun toggleCreate() = toggleS3Page("create")
    private fun toggleS3Page(which: String) { ui.post {
        val p = profilePane ?: return@post
        if (p.shown && s3Page == which) { hideOne(p); return@post }
        if (p.shown) { s3Page = which; p.web.evaluateJavascript("window.storyS3Page&&window.storyS3Page('$which')", null); syncCreate(); return@post }
        if (s3Pane?.shown != true) return@post
        s3Page = which
        p.web.evaluateJavascript("window.storyS3Page&&window.storyS3Page('$which')", null)
        dialPane?.takeIf { it.shown }?.let { hideOne(it) }
        p.web.evaluateJavascript(profileGeometryJs(), null)
        profileGapPane?.web?.evaluateJavascript(profileGeometryJs(), null)
        p.web.evaluateJavascript("window.storyProfileRefresh&&window.storyProfileRefresh();window.storyProfilePhotoChanged&&window.storyProfilePhotoChanged()", null)  // always the latest real numbers and picture
        setShown(p, true)
        tellProfileStation3()
    } }
    /** Profile stays open when Station 3 closes (owner's call): it then shows Station 3's gap and the little scene in it. */
    private fun tellProfileStation3() {
        if (profilePane?.shown != true) return
        profileGapPane?.web?.evaluateJavascript("window.storyProfileS3&&window.storyProfileS3(${s3Pane?.shown == true})", null)
    }
    // ---- Profile picture: Edit profile > Change photo opens the phone's own photo picker. STORY's windows sit
    //      above every app, so Profile (and the Pages, if open) step aside while you pick, then come straight back. ----
    private var pickerHid = listOf<Pane>()
    override fun pickProfilePhoto() { ui.post {
        if (!stepAsideForSystem()) return@post
        runCatching { startActivity(Intent(this, ProfilePhotoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { profilePhotoPicked(false, true) }
    } }
    fun profilePhotoPicked(changed: Boolean, failed: Boolean) { ui.post {
        comeBackFromSystem()
        if (failed) showToast("Couldn't use that picture - try another one")
        if (changed) profilePane?.web?.evaluateJavascript("window.storyProfilePhotoChanged&&window.storyProfilePhotoChanged()", null)
    } }
    /** One time: Android's own "allow STORY to see your photos" (it sits under STORY's windows, so they step aside). */
    override fun requestPhotoAccess() { ui.post {
        if (!stepAsideForSystem()) return@post
        runCatching { startActivity(Intent(this, ProfilePhotoActivity::class.java).putExtra(ProfilePhotoActivity.EXTRA_ACCESS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { photoAccessDone(ProfilePhotoActivity.photoAccess(this)) }
    } }
    fun photoAccessDone(access: String) { ui.post {
        comeBackFromSystem()
        profilePane?.web?.evaluateJavascript("window.storyPhotoAccessDone&&window.storyPhotoAccessDone('$access')", null)
    } }
    /** A picture tapped in STORY's own grid: saved in the background, Profile shows it already. */
    override fun useProfilePhoto(id: Long) {
        Thread {
            val ok = ProfilePhotoActivity.saveFromPhone(this, id)
            ui.post {
                if (!ok) showToast("Couldn't use that picture - try another one")
                profilePane?.web?.evaluateJavascript("window.storyProfilePhotoChanged&&window.storyProfilePhotoChanged()", null)
            }
        }.start()
    }
    /** Pictures for the Profile windows, straight from the phone: the saved profile picture and the grid's previews. */
    private fun photoResponse(r: WebResourceRequest): WebResourceResponse {
        val seg = r.url.pathSegments  // story-photo / profile | thumb / <id> | thumbv / <id> | capture | post / <id>
        // Pictures / videos kept as files (what Create made, your posts): videos are served in pieces so they play.
        val file = when (seg.getOrNull(1)) {
            "capture" -> CreateCameraActivity.currentCapture(this)
            "post" -> seg.getOrNull(2)?.let { CreateCameraActivity.postFile(this, it) }
            "postthumb" -> seg.getOrNull(2)?.let { CreateCameraActivity.postThumb(this, it) }
            else -> null
        }
        if (file != null) return fileResponse(file, r.requestHeaders)
        val bytes = runCatching {
            when (seg.getOrNull(1)) {
                "profile" -> ProfilePhotoActivity.file(this).takeIf { it.exists() }?.readBytes()
                "thumb" -> seg.getOrNull(2)?.toLongOrNull()?.let { ProfilePhotoActivity.thumbBytes(this, it) }
                "thumbv" -> seg.getOrNull(2)?.toLongOrNull()?.let { ProfilePhotoActivity.videoThumbBytes(this, it) }
                else -> null
            }
        }.getOrNull()
        return if (bytes != null) WebResourceResponse("image/jpeg", null, java.io.ByteArrayInputStream(bytes)).apply {
            responseHeaders = mapOf("Cache-Control" to "max-age=86400", "Access-Control-Allow-Origin" to "*")
        } else WebResourceResponse("image/jpeg", null, 404, "Not Found", mapOf(), java.io.ByteArrayInputStream(ByteArray(0)))
    }
    private fun fileResponse(f: java.io.File, headers: Map<String, String>): WebResourceResponse {
        val mime = if (f.extension == "mp4") "video/mp4" else "image/jpeg"
        val len = f.length()
        val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
        val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").find(it) }
        if (m != null && len > 0) {
            val start = m.groupValues[1].toLongOrNull() ?: 0L
            val end = (m.groupValues[2].toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
            if (start <= end) {
                val input = java.io.FileInputStream(f).apply { skip(start) }
                val part = object : java.io.FilterInputStream(input) {
                    var left = end - start + 1
                    override fun read(): Int { if (left <= 0) return -1; val b = super.read(); if (b >= 0) left--; return b }
                    override fun read(b: ByteArray, off: Int, n: Int): Int { if (left <= 0) return -1; val k = super.read(b, off, minOf(n.toLong(), left).toInt()); if (k > 0) left -= k; return k }
                }
                return WebResourceResponse(mime, null, 206, "Partial Content", mapOf(
                    "Content-Range" to "bytes $start-$end/$len", "Content-Length" to (end - start + 1).toString(),
                    "Accept-Ranges" to "bytes", "Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), part)
            }
        }
        return WebResourceResponse(mime, null, 200, "OK", mapOf("Content-Length" to len.toString(), "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), java.io.FileInputStream(f))
    }

    // ---- Create (Station 3's + button): the same window as Profile / Messages; the real camera runs underneath
    //      (CreateCameraActivity) while Create shows, and the Pages step aside so the camera is what you see. ----
    private var createActive = false
    private var pagesHiddenForCreate = false
    private fun syncCreate() {
        if (pickerHid.isNotEmpty()) return  // a system screen is up for a moment; Create carries on after
        val want = profilePane?.shown == true && s3Page == "create"
        if (want == createActive) return
        createActive = want
        if (want) {
            pagesHiddenForCreate = pagesPane?.shown == true
            pagesPane?.takeIf { it.shown }?.let { setShown(it, false) }
            createEvent("launching", "")
            val i = Intent(this, CreateCameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { startActivity(i) }.onFailure { createEvent("error", "camera screen: " + (it.message ?: "")) }
            // Some phones quietly block an app from opening its own screen from the background: ask again the
            // way Android 14+ allows explicitly, and if it still hasn't opened, say so.
            ui.postDelayed({
                if (createActive && CreateCameraActivity.instance == null) runCatching {
                    val pi = android.app.PendingIntent.getActivity(this, 7, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                    if (Build.VERSION.SDK_INT >= 34) pi.send(android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle())
                    else pi.send()
                }
            }, 1500)
            ui.postDelayed({ if (createActive && CreateCameraActivity.instance == null) createEvent("error", "the phone didn't let STORY open its camera screen") }, 4500)
        } else {
            CreateCameraActivity.instance?.let { it.finish(); it.overridePendingTransition(0, 0) }
            if (pagesHiddenForCreate) { pagesHiddenForCreate = false; pagesPane?.takeIf { !it.shown }?.let { setShown(it, true) } }
        }
    }
    fun stepAsideForCamera() { ui.post { stepAsideForSystem() } }
    fun comeBackFromCamera() { ui.post { comeBackFromSystem() } }
    fun closeS3WindowFromCamera() { ui.post { profilePane?.takeIf { it.shown }?.let { hideOne(it) } } }
    /** The camera tells Create what happened: ready / recording / photo / video / denied / error. */
    fun createEvent(kind: String, extra: String) { ui.post {
        // A video plays on the phone's own player under Create; anything else stops it.
        if (kind == "video") CreateCameraActivity.instance?.playCapture()
        else if (kind == "photo") CreateCameraActivity.instance?.stopPlayback()
        profilePane?.web?.evaluateJavascript("window.storyCreateEvent&&window.storyCreateEvent('$kind'," + org.json.JSONObject.quote(extra) + ")", null)
    } }
    override fun createShutter() { ui.post { CreateCameraActivity.instance?.takePhoto() ?: createEvent("error", "") } }
    override fun createVideo(on: Boolean) { ui.post { if (on) CreateCameraActivity.instance?.startVideo() ?: createEvent("error", "") else CreateCameraActivity.instance?.stopVideo() } }
    override fun createUseFromPhone(id: Long, video: Boolean) {
        Thread {
            val ok = CreateCameraActivity.useFromPhone(this, id, video)
            createEvent(if (!ok) "error" else if (video) "video" else "photo", "")
        }.start()
    }
    override fun createSwitchCamera() { ui.post { CreateCameraActivity.instance?.switchCamera() } }
    override fun createDiscard() { ui.post { CreateCameraActivity.instance?.stopPlayback() }; CreateCameraActivity.clearCapture(this) }
    override fun createKeep(): String { ui.post { CreateCameraActivity.instance?.stopPlayback() }; return CreateCameraActivity.keepCapture(this) }

    private fun stepAsideForSystem(): Boolean {
        if (pickerHid.isNotEmpty()) return false
        pickerHid = listOfNotNull(profilePane, pagesPane).filter { it.shown }
        for (p in pickerHid) setShown(p, false)
        return true
    }
    private fun comeBackFromSystem() {
        val back = pickerHid; pickerHid = listOf()
        for (p in back) if (!p.shown) setShown(p, true)
        if (profilePane in back) tellProfileStation3()
    }
    override fun removeProfilePhoto() { ui.post {
        runCatching { ProfilePhotoActivity.file(this).delete() }
        profilePane?.web?.evaluateJavascript("window.storyProfilePhotoChanged&&window.storyProfilePhotoChanged()", null)
    } }
    /** Typing in Profile (Edit profile > Name / Username / Bio): the keyboard comes up as the box opens. */
    override fun profileTyping(on: Boolean) { ui.post {
        val p = profilePane?.takeIf { it.shown } ?: return@post
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (on) {
            p.web.requestFocus()
            ui.postDelayed({ runCatching { imm.showSoftInput(p.web, 0) } }, 60)
        } else runCatching { imm.hideSoftInputFromWindow(p.web.windowToken, 0) }
    } }
    override fun closeProfile() { ui.post { profilePane?.takeIf { it.shown }?.let { hideOne(it) } } }

    /** Where Station 3's white bar is (in the page's px), so Profile's line traces it exactly; plus the status bar. */
    private fun profileGeometryJs(): String {
        val barW = (barBgLp?.width ?: getSharedPreferences("story", MODE_PRIVATE).getInt("bar_w", (220 * dp).toInt())) / dp
        val barH = (40 * dp).toInt() / dp
        val top = statusBarHeightPx() / dp
        return "window.__pfBarW=$barW;window.__pfBarH=$barH;window.__pfTop=$top;window.storyProfileGeom&&window.storyProfileGeom();"
    }

    // Android draws the keyboard below STORY's top layer, so while something is being typed in the
    // Pages they drop to the normal overlay layer (keyboard on top); they go back up when the Pages close.
    private var pagesTyping = false
    override fun setTyping(on: Boolean) { ui.post {
        val p = pagesPane ?: return@post
        if (!on || !lowerPages()) return@post
        p.web.requestFocus()
        ui.postDelayed({ runCatching { getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(p.web, 0) } }, 120)
    } }

    /** The Pages (showing) drop to the normal overlay layer, below the keyboard and the notification panel. */
    private fun lowerPages(): Boolean {
        val p = pagesPane ?: return false
        if (pagesTyping || !p.shown || p.wm === wm) return false
        runCatching { p.wm.removeView(p.frame) }
        p.lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; p.wm = wm
        runCatching { wm.addView(p.frame, p.lp) }
        pagesTyping = true
        return true
    }

    /** Swipe down from the top over the Pages: the phone's own notification panel, as anywhere else. */
    /** The Pages (hidden now) go back to the top layer, with Station 3, the bar and the line above them. */
    private fun pagesBackToTop() {
        val p = pagesPane ?: return
        pagesTyping = false
        if (p.wm === layerWm) return
        runCatching { p.wm.removeView(p.frame) }
        p.lp.type = layerType; p.wm = layerWm
        runCatching { layerWm.addView(p.frame, p.lp) }
        barBg?.let { v -> barBgLp?.let { lp -> runCatching { (barBgWm ?: wm).removeView(v); (barBgWm ?: wm).addView(v, lp) } } }
        for (q in listOfNotNull(s3Pane, dialPane)) runCatching { q.wm.removeView(q.frame); q.wm.addView(q.frame, q.lp) }
        raiseBubble()
        addBar()
    }

    // ---- STORY's message bubble: one look everywhere (Pages, Station 3, over apps), above everything ----
    private var toastView: View? = null
    private var toastWm: WindowManager? = null
    private val hideToast = Runnable { toastView?.let { runCatching { (toastWm ?: wm).removeView(it) } }; toastView = null }
    override fun showToast(msg: String) { ui.post {
        ui.removeCallbacks(hideToast); hideToast.run()
        val tv = TextView(this).apply {
            text = msg; setTextColor(th(Color.parseColor("#F3F2EE"))); textSize = 11.5f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { cornerRadius = 20 * dp; setColor(th(Color.parseColor("#232326"))); setStroke(maxOf(1, dp.toInt()), th(Color.parseColor("#24FFFFFF"))) }
        }
        val lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (84 * dp).toInt())
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.type = layerType
        toastWm = layerWm
        runCatching { layerWm.addView(tv, lp); toastView = tv }
        ui.postDelayed(hideToast, 2200)
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
