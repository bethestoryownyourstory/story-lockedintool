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
import android.graphics.Rect
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
 *      tap = Apps | Pages, swipe up = real home screen, swipe up and hold (pause at the top) = real recents.
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
            widgetLiveEvent(false)  // unlocked onto the home screen: live widgets come back
            if (SystemMode.on(c)) { ui.removeCallbacks(systemCheckRun); ui.postDelayed(systemCheckRun, 500) }  // Lockedintool as your system: unlocked onto another app / the home screen
            AodActivity.instance?.finish()  // unlocked (e.g. fingerprint) while the Always On Display was up
            ui.post { syncLockDial(); profileLayer(false)   // unlocked: Station 3's window back where the keyboard works
                // Lockedintool security: something of Lockedintool's is already on screen (the Dashboard as your home...) - unlock it.
                if (!secIsOpen() && !callActive && pickerHid.isEmpty() && (pagesPane?.shown == true || profilePane?.shown == true)) ui.postDelayed({ if (!secIsOpen() && !walletOpening && pickerHid.isEmpty()) secPrompt(null) }, 350) }
            for (d in longArrayOf(300, 1000, 2500)) ui.postDelayed({ syncLockDial(); updateBubble() }, d)
            Updater.check(this@OverlayService); checkScreens()
        }
    }
    // Always On Display: when the screen goes off, STORY's dim always-on screen comes up instead
    // (if switched on). Pressing power while it's up turns the screen truly off.
    private val screenOff = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOffAt = System.currentTimeMillis(); ui.post { hideLiveWidgets(); secLock() }; lockDialAway = false; lockDialPeek = false; lockDialBackAt = 0L; onScreenOff(); ui.post { syncLockDial() }
            if (!callActive) Updater.installWaiting(applicationContext)    // updates install while nobody's using the phone (owner)
            // New screens reach the Pages when they hide - but someone whose home is the Dashboard may never hide them.
            // The screen going off is also a moment nobody is looking: bring the Pages up to date then.
            ui.post { pagesPane?.takeIf { it.shown && it.stale && !callActive }?.let { reload(it) } } }
    }
    // Screen on: the lock screen may be showing -> the lock screen dial pad (Settings > QUICK ACCESS).
    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { ui.post { syncLockDial(); updateBubble() }; ui.postDelayed({ syncLockDial(); updateBubble() }, 400) }   // on the lock screen the Dashboard band must step out (keyguardLocked)
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
        ProblemLog.catchCrashes(this); ProblemLog.flush(this)   // a crash is written down, and sent the next time
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
        ui.postDelayed(topLayerCheck, 15_000)   // still no accessibility layer by then: say so (Station 3 can't stay on top)
        ui.post { warmUp() }  // build both panes now so Station 3 and Pages open instantly
        ui.postDelayed(updateLoop, 15_000)  // then look for a new STORY version in the background
        Updater.schedule(this)              // and keep checking every 15 minutes even if STORY is off
        StoryBridge.refreshApps(this)  // the Apps area's list is ready before it's ever opened
        ui.postDelayed({ PageWidgets.refreshPlaced(this) }, 20_000)  // home screen widgets get a fresh picture once STORY is up
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
        runCatching { contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callLogWatch) }
        Thread { if (CallHistory.finishFromLog(this)) ui.post { tellCalls() } }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (gated) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) { getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", false).apply(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_REBUILD) ui.post { rebuildLayer() }
        if (intent?.action == PageWidgets.ACTION_OPEN_PAGE) intent.getStringExtra("page")?.let { pg -> ui.post { openPageFromWidget(pg) } }
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
        runCatching { contentResolver.unregisterContentObserver(callLogWatch) }
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
        nm.createNotificationChannel(NotificationChannel("story", "Lockedintool", NotificationManager.IMPORTANCE_MIN))
        // Tapping the notification opens STORY; it never switches STORY off (only "Stop Lockedintool" does).
        val openApp = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "story").setContentTitle("Lockedintool is on").setContentText("Stays on until you tap Stop Lockedintool")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentIntent(openApp).setOngoing(true).build()
        fgNote = n
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        running = true
    }
    private var fgNote: Notification? = null
    /** Calls (Messages / groups): Android only lets an app use the microphone / camera from the background while its
     *  foreground service says so (type microphone / camera) - without it the call's microphone fails ('busy').
     *  On for a call, back to the normal type when the call ends. False = Android refused (no screen of ours showing). */
    /** AskActivity has tried to switch the microphone / camera on for the call's foreground service: tell the call page
     *  whether it worked (ok), so it opens them only now (not on a blind timer that could run before Android allowed it). */
    fun callFgReady(ok: Boolean) { ui.post { profilePane?.web?.evaluateJavascript("window.storyCallFgReady&&window.storyCallFgReady($ok)", null) } }
    fun callFg(on: Boolean, video: Boolean): Boolean {
        val n = fgNote ?: return false
        if (Build.VERSION.SDK_INT < 30) return true
        val ok = android.content.pm.PackageManager.PERMISSION_GRANTED
        var t = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (on && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == ok) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (on && video && checkSelfPermission(android.Manifest.permission.CAMERA) == ok) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return runCatching { if (t == 0) startForeground(1, n) else startForeground(1, n, t); true }
            // Diagnostic (owner's calls still say 'busy'): record WHY Android refused to start the mic/camera service.
            .onFailure { if (on) runCatching { ProblemLog.record(this, "callfg", (it.javaClass.simpleName + ": " + (it.message ?: "")).take(140), "sdk" + Build.VERSION.SDK_INT + if (video) " v" else " a") } }
            .getOrDefault(false)
    }

    /** Live screens for everyone; the owner and reviewer phones (Test channel) get the newest copy from /preview/. */
    private fun remoteBase(): String =
        REMOTE + if (Release.testChannel(this)) "preview/" else ""

    private fun pickLayer() {
        val a = StoryAccessibilityService.instance
        val awm = a?.let { runCatching { it.getSystemService(Context.WINDOW_SERVICE) as WindowManager }.getOrNull() }
        if (awm != null) { layerWm = awm; layerType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        else { layerWm = wm; layerType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
    }

    /** Asks the STORY site which screens are current; if they changed, the panes reload (when closed). */
    private fun checkScreens() {
        val url = remoteBase() + (if (Release.testChannel(this)) "source.txt" else "live-source.txt") + "?t=" + System.currentTimeMillis()
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
                getSharedPreferences("story", MODE_PRIVATE).edit().putString("screens_seen", v.take(40)).apply()
                ProblemLog.flush(this)   // problems waiting to be sent (nothing happens when there are none)
                val now = System.currentTimeMillis()
                for (p in listOfNotNull(s3Pane, pagesPane, dialPane, profilePane, profileGapPane, lockDialPane)) {  // every STORY window, the dial pad included
                    // Loaded from the site just before this first check: it already has these screens.
                    if (p.ver == null && before == null && now - p.loadedAt < 60_000) { p.ver = v; continue }
                    if (p.ver == v) continue
                    // Stuck on the built-in copy (no signal when it loaded) or on older screens: get the current ones.
                    if (p.shown || (p === profilePane && callActive)) p.stale = true
                    else if (p.ver != "local" || now - p.loadedAt > 60_000) reload(p)
                }
            }
        }.start()
    }

    /** Fresh copy of the screens, skipping the website's cache. */
    private fun reload(p: Pane) {
        p.stale = false; p.loadedAt = System.currentTimeMillis(); p.ver = screensSeen
        p.web.loadUrl(remoteBase() + "?" + (if (p.lock) "s3=1&dial=1&lock=1&" else if (p.gap) "s3=1&profile=1&gap=1&" else if (p.profile) "s3=1&profile=1&" else if (p.dial) "s3=1&dial=1&" else if (p.small) "s3=1&" else "") + "v=" + System.currentTimeMillis())
    }

    private var relayerPending = false
    /** For the setup screen: where Station 3 is right now (so a hidden Station 3 can be explained). */
    fun station3State(): String = when {
        WidgetMode.on(this) -> "off - Only use widgets is on"
        bubble == null -> "not started"
        bubble?.visibility != View.VISIBLE -> "hidden by Settings > Quick Access"
        layerType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY -> "on top of everything"
        else -> "NOT on top - the keyboard can cover it (Lockedintool system actions isn't connected)"
    }
    private val topLayerCheck = Runnable { topLayerWarn() }
    /** Without Lockedintool system actions (the accessibility switch) Station 3 sits in the normal layer, where the
     *  keyboard and some screens cover it - say so once, with a tap straight to the switch. */
    private fun topLayerWarn() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (StoryAccessibilityService.instance != null) { runCatching { nm.cancel(7) }; return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (nm.getNotificationChannel("story_alerts") == null) nm.createNotificationChannel(NotificationChannel("story_alerts", "Alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = android.app.PendingIntent.getActivity(this, 7, Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = android.app.Notification.Builder(this, "story_alerts").setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Station 3 can't stay on top").setContentText("Turn Lockedintool system actions back on - tap here")
            .setContentIntent(open).setAutoCancel(true).build()
        runCatching { nm.notify(7, n) }
    }
    private fun rebuildLayer() {
        screensSeen = null
        val s3WasOpen = s3Pane?.shown == true
        tearDownPanes()
        pickLayer(); addBubble(); warmUp()
        if (s3WasOpen) showPanel(station3Only = true)
        ui.removeCallbacks(topLayerCheck); ui.postDelayed(topLayerCheck, if (layerType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) 0L else 15000L)
    }
    private fun tearDownPanes() {
        removePanelNow()
        dashGeom = false; s3Held = false; s3Opening = false; lastOnPages = null; lastOnDash = null
        bubble?.let { runCatching { (bubbleWm ?: wm).removeView(it) } }; bubble = null
        removeBarBg()
        s3Pane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; s3Pane = null
        quietApps(false)
        pagesPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; pagesPane = null
        dialPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; dialPane = null
        profilePane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; profilePane = null
        profileGapPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; profileGapPane = null
        lockDialPane?.let { runCatching { it.wm.removeView(it.frame) }; it.web.destroy() }; lockDialPane = null
        removeProfileStrip(); removeWalletBtn()
    }
    /** Delete account (owner): EVERYTHING that comes with Lockedintool on this phone goes - the Pages, notes, money
     *  and wallet history, cards, trading logins, profile, chats, settings - like deleting the app. Nothing outside
     *  Lockedintool is touched (the bank, payments, broker accounts, the phone). Lockedintool stays on and starts fresh. */
    override fun wipeAll() { ui.post {
        tearDownPanes()   // no window can write anything back
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        runCatching { android.webkit.CookieManager.getInstance().removeAllCookies(null); android.webkit.CookieManager.getInstance().flush() }
        Thread {
            val keep = Regex("^(enabled|owner|review|auto_agreed|upd_.*)$")   // only what keeps Lockedintool running and updating
            runCatching {
                val dir = java.io.File(applicationInfo.dataDir, "shared_prefs")
                for (f in dir.listFiles() ?: arrayOf()) {
                    val name = f.name.removeSuffix(".xml")
                    if (name.startsWith("androidx") || name.contains("WorkManager", true)) continue
                    val p = getSharedPreferences(name, MODE_PRIVATE)
                    val e = p.edit()
                    for (k in p.all.keys) if (name != "story" || !keep.matches(k)) e.remove(k)
                    e.commit()
                }
            }
            runCatching { filesDir.listFiles()?.forEach { it.deleteRecursively() } }
            runCatching { cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
            ui.postDelayed({ rebuildLayer(); updateBubble() }, 400)
        }.start()
    } }

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
        // STORY never animates (owner: "it should be still" / "nothing moves" / "no animation anywhere in Station 3").
        // Android otherwise slides / fades an overlay window when it's shown or when updateViewLayout moves / resizes it
        // (the Dashboard band appeared to "animate to the area" sliding from the corner to full width). windowAnimations = 0
        // turns that off, so every STORY window just appears in place.
        lp.windowAnimations = 0
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
        activityOn = false
        showActivityDot(getSharedPreferences("story", MODE_PRIVATE).getBoolean("activity_dot", false))
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
        // Only use widgets (owner): the bottom line that switches Apps | Lockedintool doesn't exist at all. (Only system
        // KEEPS the line now - owner, 28 Oct 2026: apps fully work there, so the switch / swipe-up stay.)
        pagesPane?.web?.evaluateJavascript(dashPadJs(), null)   // with or without the line, the Dashboard keeps the right room
        if (WidgetMode.on(this)) { bar = null; barLine = null; hidePicker(); dashLineSync(); return }
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
        // The bottom bar is the PHONE'S (owner, 29 Oct 2026: 'the bottom system switch button - lockedintool shouldn't be in
        // it at all or control it at all. We just only want it for us to tap it and show us our system switch options').
        // Lockedintool only answers a plain TAP (the Apps | Lockedintool switch). Any swipe is ignored here - no Recents /
        // Home of our own, no hold tricks - so the phone's own gesture nav does exactly what it always does; when its Home
        // or Recents comes up, recentsShown just steps the Pages aside so it shows.
        var downX = 0f; var downY = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> if (Math.hypot((e.rawX - downX).toDouble(), (e.rawY - downY).toDouble()) > touch) moved = true
                MotionEvent.ACTION_UP -> if (!moved) showPicker()       // tap = Apps | Lockedintool switch; a swipe does nothing here
            }
            true
        }
        bar = v
        val bottom = if (hasLine) 0 else navBarHeightPx() + (6 * dp).toInt()
        barWm = layerWm
        barLp = overlayParams(w, h, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, bottom).also { it.type = layerType }
        barLp!!.flags = barFlags(barLp!!.flags)
        layerWm.addView(v, barLp)
        dashLineSync()
    }
    private var barLp: WindowManager.LayoutParams? = null
    // While Station 3 is open its own buttons come first: the bottom line's tap area (which sits under
    // Station 3's bar) stops catching taps, so a tap on Station 3 is only ever Station 3's.
    private fun barFlags(f: Int): Int {
        val s3Open = s3Pane?.shown == true && !dashGeom
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
    // On the Dashboard Station 3's background covers the line's place, so the line is drawn on it there (dashLineSync).
    private fun updateBarLine() { barLine?.visibility = if (!barHasLine && !(dashGeom && s3Pane?.shown == true)) View.VISIBLE else View.INVISIBLE; dashLineSync() }

    /** Android's gesture line size (width, height, corner radius in px), from the phone's own System UI. */
    private fun phoneLineSize(): Triple<Int, Int, Float> {
        val w = sysUiDim("navigation_home_handle_width", 108f)
        val radius = sysUiDim("navigation_handle_radius", 2f)
        val h = maxOf(sysUiDim("navigation_handle_height", 0f), radius * 2)
        return Triple(Math.round(w), maxOf(1, Math.round(h)), radius)
    }
    private fun sysUiDim(name: String, fallbackDp: Float): Float {
        val r = runCatching { packageManager.getResourcesForApplication("com.android.systemui") }.getOrNull()
        val id = r?.getIdentifier(name, "dimen", "com.android.systemui") ?: 0
        return if (id != 0) runCatching { r!!.getDimension(id) }.getOrDefault(fallbackDp * dp) else fallbackDp * dp
    }

    // ---- Apps | Lockedintool switch (owner: 'rename the word Pages on the switch to Lockedintool') ----
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
        row.addView(word("Lockedintool") { showPanel(station3Only = false) })  // the only way into Lockedintool itself (owner: it says Lockedintool, not Pages)
        picker = row; pickerWm = layerWm
        layerWm.addView(row, overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, if (dashGeom) dashBandYPx() + (48 * dp).toInt() else (64 * dp).toInt() + (if (phoneHasGestureLine()) 0 else navBarHeightPx())).also { it.type = layerType })
        ui.postDelayed({ hidePicker() }, 4000)
    }

    private var pickerWm: WindowManager? = null
    private fun hidePicker() { picker?.let { runCatching { (pickerWm ?: wm).removeView(it) } }; picker = null }

    // ---- STORY panes (built once, kept warm, so they open instantly) ----
    private class Pane(val frame: FrameLayout, val web: WebView, val lp: WindowManager.LayoutParams, val small: Boolean, var wm: WindowManager, val dial: Boolean = false, val profile: Boolean = false, val gap: Boolean = false, val lock: Boolean = false) {
        var shown = false; var loadedAt = System.currentTimeMillis(); var stale = false
        // Which screens it has: the version from source.txt when it loaded ("local" = the copy built into the app,
        // used when the site couldn't be reached; null = loaded before the first check). checkScreens compares it.
        var ver: String? = null
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
    private fun profileGapWPx() = if (dashGeom) resources.displayMetrics.widthPixels
        else (barBgLp?.width ?: getSharedPreferences("story", MODE_PRIVATE).getInt("bar_w", (220 * dp).toInt())) + Math.round(2 * dp)

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
        // Profile / Messages: tell the page how much of it the keyboard covers, so a chat's typing box sits on top
        // of the keyboard (these windows don't shrink for it on their own).
        if (profile && !gap) {
            var lastIme = -1
            frame.setOnApplyWindowInsetsListener { v, insets ->
                val ime = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(android.view.WindowInsets.Type.ime()).bottom
                    else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                if (ime != lastIme) { lastIme = ime; wv.evaluateJavascript("window.storyImeInset&&window.storyImeInset(${ime / dp})", null) }
                v.onApplyWindowInsets(insets)
            }
        }
        // Notes' camera runs inside STORY's own page (owner: never leaving STORY): the page may use the camera once
        // Android has allowed STORY to.
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onPermissionRequest(r: android.webkit.PermissionRequest) { ui.post {
                // Camera (Notes' camera, video calls) and microphone (calls) - each only once Android has allowed STORY.
                val ok = android.content.pm.PackageManager.PERMISSION_GRANTED
                val grant = r.resources.filter {
                    (it == android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE && checkSelfPermission(android.Manifest.permission.CAMERA) == ok) ||
                    (it == android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == ok)
                }
                if (grant.isNotEmpty()) r.grant(grant.toTypedArray()) else r.deny()
            } }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (small) paneRef?.let { it.loading = true; it.frame.alpha = 0f; syncBarBg() }
                if (!small && !dial && !profile) tvView?.place(org.json.JSONObject().put("show", false))  // the page tells it again once loaded
                if (!small && !dial && !profile && dashBandInPage) { dashBandInPage = false; updateBubble() }   // the new page says again whether it draws the Dashboard's bar
            }
            // Android sometimes shuts the page down to free memory, which leaves a blank white window:
            // build STORY's windows again instead (and don't let it take the app down).
            override fun onRenderProcessGone(v: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                ProblemLog.record(this@OverlayService, "window", "page stopped (" + (if (detail.didCrash()) "crashed" else "closed by Android for memory") + ")",
                    if (gap) "profile gap" else if (profile) "profile" else if (lock) "lock dial pad" else if (dial) "dial pad" else if (small) "station3" else "pages")
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
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) { paneRef?.ver = "local"; v.loadUrl(LOCAL + query) }
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) { paneRef?.ver = "local"; v.loadUrl(LOCAL + query) }
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
                    // s3-dash-follow: this app draws the Dashboard's band in the theme's own colour (barBgColor), so the page's icons may follow it too.
                    v.evaluateJavascript("document.documentElement.classList.add('s3-dash-follow');window.__storyLocked=${keyguardLocked()};window.__storyOnPages=${lastOnPages ?: (pagesPane?.shown == true)};window.__storyOnDash=${lastOnDash == true};window.storyStation3Only&&window.storyStation3Only()", null); v.evaluateJavascript(S3_JS, null)
                    // Show it again once Station 3 has actually drawn (a moment after the page is ready).
                    ui.postDelayed({ paneRef?.let { it.loading = false; if (it.shown) it.frame.alpha = 1f }; syncBarBg() }, 200)
                } else {
                    // The Pages: the room the Dashboard keeps at its bottom for Station 3 (and the bottom line under it).
                    v.evaluateJavascript(dashPadJs(), null)
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
        paneRef = p; p.ver = screensSeen
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
        if (walletBtn == null) addWalletBtn()   // the lock screen's Wallet page button: under Station 3's windows
        if (s3Pane == null) { addBarBg(); s3Pane = buildPane(true) }  // the white goes in first, so it sits under Station 3's page
        if (dialPane == null) dialPane = buildPane(true, dial = true)
        if (fresh) raiseBubble()  // done while Station 3 is still hidden, so nothing visibly changes later
        addBar()
    }

    private fun setShown(p: Pane, shown: Boolean) {
        p.shown = shown
        if (p === pagesPane) { if (shown) SystemMode.reset() else if (SystemMode.on(this)) { ui.removeCallbacks(systemCheckRun); ui.postDelayed(systemCheckRun, 700) } }
        // Cooling (owner): a hidden Pages / Profile window rests (its page counts as hidden: no drawing, no animation
        // frames, timers slowed) and wakes the moment it shows. Never during a call (Profile carries the call), and
        // Station 3 / the dial pads are left exactly as they are.
        if (p === pagesPane || p === profilePane || p === profileGapPane) {
            if (shown) runCatching { p.web.onResume() }
            else if (!(p !== pagesPane && callActive)) runCatching { p.web.onPause() }
        }
        // Profile's main window takes the keyboard like the Pages do (so typing a name / username / bio just works).
        p.lp.flags = if (p.gap) flagsFor(true, false) else if (p.profile) flagsFor(false, shown) else flagsFor(p.small, shown)
        // Lock screen dial pad: hear touches anywhere else on the lock screen (swipe up to unlock...) to step away.
        if (p.lock && shown) p.lp.flags = p.lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        // Android 12+ throws away touches that pass through another app's fully opaque window,
        // even an untouchable one. A hidden pane is fully see-through, so apps underneath get every touch.
        p.lp.alpha = if (shown) 1f else 0f
        if (p.small && shown) { p.lp.width = p.openW; p.lp.height = p.openH }
        p.frame.alpha = if (shown && !(p.small && p.loading)) 1f else 0f
        // The Pages' own brightness (the slider's "system" mode) only while they're up - never over other apps.
        if (p === pagesPane) p.lp.screenBrightness = if (shown && pagesBright >= 0f) pagesBright else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        runCatching { p.wm.updateViewLayout(p.frame, p.lp) }
        if (p === s3Pane) { syncBarBg(); syncBarTouch(); tellProfileStation3() }
        if (p === s3Pane || p === profilePane) ui.post { syncLockDial() }   // the lock screen's Wallet page button steps aside / comes back
        if (p === profilePane) {
            syncCreate()
            profileGapPane?.let { g -> if (g.shown != shown) setShown(g, shown) }
            showProfileStrip(shown)
            if (!shown) profileGapPane?.web?.evaluateJavascript("window.storyProfileS3&&window.storyProfileS3(true)", null)
        }
        if (shown && (!p.small || (p.profile && !p.gap))) p.web.requestFocus()
        if (p === pagesPane && shown && appEdge != null) { appPagePkg = null; removeAppEdge() }
        if (p === pagesPane && !shown) ui.postDelayed({ PageWidgets.refreshPlaced(this) }, 1500)  // home screen widgets show what you just saw
        if (shown && (p === pagesPane || p === profilePane)) hideLiveWidgets() else if (!shown && (p === pagesPane || p === profilePane)) widgetLiveEvent(false)
        if (p === pagesPane) { quietApps(shown); updateBarLine(); if (!shown && pagesTyping) pagesBackToTop(); updateBubble()
            if (!shown) tvView?.place(org.json.JSONObject().put("show", false)) }
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
    /** The music card asked to play: let go of the quiet put on the phone's sound when the Pages opened (music must play). */
    fun musicWanted() { ui.post { quietApps(false) } }
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

    /** back = coming back from an app page (its left-edge strip): the Pages return where you were; otherwise opening
     *  Lockedintool lands on your Home Screen (Layouts > Home Screen, owner). */
    private fun showPanel(station3Only: Boolean, back: Boolean = false) {
        if (WidgetMode.on(this)) return   // Only use widgets: only the widgets
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
            secGate(pagesPane) {
                val p = pagesPane ?: return@secGate
                showPagesOn(p, "window.storyShow&&window.storyShow(null,false," + back + ")") { updateBubble(); addBar() }
            }
            return
        }
        updateBubble()
        addBar() // re-add last so the STORY bar stays on top of everything, including the pages
    }

    /** Opens the Pages on a screen WITHOUT first flashing the screen they were on (owner: "it glitches and shows where I
     *  was at first"): the page switches while the window is still hidden, and the window shows once that frame is
     *  drawn (postVisualStateCallback; at most 300 ms). Already showing: just switch. */
    private fun showPagesOn(p: Pane, js: String, after: () -> Unit) {
        if (p.shown) { p.web.evaluateJavascript(js, null); after(); return }
        runCatching { p.web.onResume() }
        var done = false
        val go = Runnable { if (!done) { done = true; if (!p.shown) setShown(p, true); after() } }
        p.web.evaluateJavascript(js) {
            runCatching { p.web.postVisualStateCallback(1L, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { ui.post(go) } }) }
                .onFailure { ui.post(go) }
        }
        ui.postDelayed(go, 300)
    }

    // ---- Home screen widgets (PageWidgets.kt): tapping one opens its page; each widget's picture is the real page,
    //      drawn by a STORY page kept out of sight in its own 1px window (a WebView only draws inside a window),
    //      one page at a time, then thrown away - nothing stays running. ----
    override fun openPages(page: String) { ui.post { openPageFromWidget(page) } }
    private fun openPageFromWidget(page: String) {
        if (WidgetMode.on(this)) { showToast("Only use widgets is on - open the Lockedintool app to turn it off"); return }
        hideLiveWidgets()
        warmUp()
        secGate(pagesPane) {
            val p = pagesPane ?: return@secGate
            showPagesOn(p, "window.storyShow&&window.storyShow(" + org.json.JSONObject.quote(page) + ",false)") { updateBubble(); addBar() }
        }
    }
    private val snapQueue = ArrayDeque<String>()
    private var snapping = false
    fun widgetSnap(page: String) { ui.post { if (page !in snapQueue) snapQueue.addLast(page); nextSnap() } }
    @SuppressLint("SetJavaScriptEnabled")
    private fun nextSnap() {
        if (snapping) return
        val page = snapQueue.removeFirstOrNull() ?: return
        snapping = true
        // The Pages' height, and as wide as the widget's shape needs, so the picture fills the WHOLE widget (owner).
        val H = pagesPane?.web?.height?.takeIf { it > 0 } ?: realHeightPx()
        val W = PageWidgets.aspect(this, page)?.let { a -> (H * a).toInt().coerceIn(H / 4, H * 3) }
            ?: pagesPane?.web?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val themed = ContextThemeWrapper(this, R.style.Theme_Story)
        val holder = FrameLayout(themed)
        val wv = WebView(themed)
        val hlp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        var done = false
        fun finish(bmp: android.graphics.Bitmap?) {
            if (done) return
            done = true
            runCatching { holder.removeView(wv) }; runCatching { wm.removeView(holder) }; runCatching { wv.destroy() }
            bmp?.let { b -> Thread { PageWidgets.save(this, page, b) }.start() }
            snapping = false
            ui.postDelayed({ nextSnap() }, 300)
        }
        wv.setBackgroundColor(th(Color.BLACK))
        wv.settings.javaScriptEnabled = true; wv.settings.domStorageEnabled = true
        wv.isVerticalScrollBarEnabled = false
        wv.addJavascriptInterface(StoryBridge(this, this, widget = true, evalTo = { js -> ui.post { if (!done) wv.evaluateJavascript(js, null) } }), "StoryNative")
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val query = "?widget=" + page
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (u.startsWith(ProfilePhotoActivity.PHOTO_BASE)) return photoResponse(r)
                return loader.shouldInterceptRequest(r.url)
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = true
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) v.loadUrl(LOCAL + query)
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) v.loadUrl(LOCAL + query)
            }
            override fun onPageFinished(v: WebView, url: String) {
                v.evaluateJavascript("window.storyWidget&&window.storyWidget(" + org.json.JSONObject.quote(page) + ")", null)
                // Live numbers (and Focus's TradingView chart) get a moment to arrive, then the page is drawn.
                ui.postDelayed({
                    if (done) return@postDelayed
                    val bmp = runCatching {
                        v.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
                        v.layout(0, 0, W, H)
                        android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888).also { v.draw(android.graphics.Canvas(it)) }
                    }.getOrNull()
                    finish(bmp)
                }, if (page == "trading") 7000L else 3000L)
            }
        }
        wv.translationX = -4f * W   // never on screen
        holder.addView(wv, FrameLayout.LayoutParams(W, H))
        if (runCatching { wm.addView(holder, hlp) }.isFailure) { finish(null); return }
        ui.postDelayed({ finish(null) }, 25_000L)   // never hangs
        wv.loadUrl(remoteBase() + query + "&v=" + System.currentTimeMillis())
    }

    // ---- LIVE home screen widgets (owner: "a real working widget - Lockedintool works in it"). Android only lets a widget
    //      show pictures, so while the home screen is up STORY lays the REAL page, working, exactly over each STORY
    //      widget (found with the accessibility service: the widget's root says "Lockedintool <page> widget #<id>").
    //      Anything that could move the home screen (a touch outside it, scrolling, another screen) puts it away for
    //      a moment - the widget's picture shows meanwhile - and it comes back once the widget has stood still. ----
    private class LiveW(val id: Int, val page: String, val holder: FrameLayout, val web: WebView, val lp: WindowManager.LayoutParams) {
        var ready = false; var shown = false; var pw = 0f
    }
    private val liveWidgets = HashMap<Int, LiveW>()
    private var homePkgCache: String? = null
    private fun homePkg(): String? = homePkgCache ?: runCatching {
        packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()?.also { homePkgCache = it }
    private var lastFound: Map<Int, Rect> = emptyMap()
    private val widgetScanRun = Runnable { widgetScanNow() }
    /** Something changed on screen. moving = it may be sliding right now (scroll / touch): step aside at once. */
    fun widgetLiveEvent(moving: Boolean) { ui.post {
        if (moving) hideLiveWidgets()
        ui.removeCallbacks(widgetScanRun); ui.postDelayed(widgetScanRun, if (moving) 450L else 200L)
    } }
    private fun hideLiveWidgets() { liveWidgets.values.forEach { hideLive(it) }; lastFound = emptyMap(); ui.removeCallbacks(widgetScanRun) }
    // Typing in a widget (a search box...): its window takes the keyboard only while a box is being typed in.
    private var liveTyping: LiveW? = null
    private fun liveTypingSet(w: LiveW, on: Boolean) {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        if (on) {
            if (!w.shown) return
            liveTyping = w
            w.lp.flags = w.lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            runCatching { wm.updateViewLayout(w.holder, w.lp) }
            w.web.requestFocus()
            ui.postDelayed({ runCatching { imm.showSoftInput(w.web, 0) } }, 120)
        } else if (liveTyping === w) {
            liveTyping = null
            runCatching { imm.hideSoftInputFromWindow(w.web.windowToken, 0) }
            w.lp.flags = w.lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            runCatching { wm.updateViewLayout(w.holder, w.lp) }
        }
    }
    private fun hideLive(w: LiveW) {
        if (liveTyping === w) liveTypingSet(w, false)
        if (!w.shown) return
        w.shown = false
        w.holder.visibility = View.GONE
        w.lp.flags = w.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(w.holder, w.lp) }
        runCatching { w.web.onPause() }
    }
    private fun widgetScanNow() {
        val home = homePkg()
        val onHome = home != null && StoryAccessibilityService.foregroundPkg == home && !keyguardLocked() &&
            pagesPane?.shown != true && profilePane?.shown != true &&
            getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        if (!onHome) { hideLiveWidgets(); return }
        val found = StoryAccessibilityService.storyWidgets(home!!)
        // Widgets that are gone from the home screen for good: their live page goes too.
        val placed = if (found.isEmpty() && liveWidgets.isEmpty()) emptySet() else PageWidgets.allIds(this)
        liveWidgets.keys.filter { it !in placed }.forEach { id -> liveWidgets.remove(id)?.let { w -> runCatching { wm.removeView(w.holder) }; runCatching { w.web.destroy() } } }
        liveWidgets.values.filter { it.id !in found }.forEach { hideLive(it) }
        if (found.isEmpty()) { lastFound = emptyMap(); return }
        // Only once the widget has stood still (same place twice in a row) - never mid-swipe.
        if (found != lastFound) { lastFound = found; ui.postDelayed(widgetScanRun, 220); return }
        found.forEach { (id, r) -> PageWidgets.pageOf(this, id)?.let { page -> placeLive(id, page, r) } }
    }
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun placeLive(id: Int, page: String, r: Rect) {
        // The page fills the WHOLE widget (owner: no gaps at the sides): it keeps the Pages' height and lays out as wide
        // as the widget's shape needs (pwCss), then the phone shrinks it to the widget - exactly over its picture.
        val PH = pagesPane?.web?.height?.takeIf { it > 0 } ?: realHeightPx()
        val w = r.width().coerceAtLeast(1); val h = r.height().coerceAtLeast(1)
        val x = r.left; val y = r.top
        val pwCss = Math.round(PH / dp * w / h).toFloat()
        val lw = liveWidgets[id]?.takeIf { it.page == page && Math.abs(it.pw - pwCss) < 2f } ?: run {
            liveWidgets.remove(id)?.let { old -> runCatching { wm.removeView(old.holder) }; runCatching { old.web.destroy() } }
            (buildLive(id, page, pwCss) ?: return).also { it.pw = pwCss }
        }
        val moved = lw.lp.x != x || lw.lp.y != y || lw.lp.width != w || lw.lp.height != h
        lw.lp.x = x; lw.lp.y = y; lw.lp.width = w; lw.lp.height = h
        if (!lw.ready) { if (moved) runCatching { wm.updateViewLayout(lw.holder, lw.lp) }; return }
        // Only touch the window when something really changed (each change makes Android announce new windows,
        // which would otherwise start another look round - never a loop).
        if (lw.shown && !moved) return
        lw.lp.flags = lw.lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(lw.holder, lw.lp) }
        if (!lw.shown) { lw.shown = true; runCatching { lw.web.onResume() }; lw.holder.visibility = View.VISIBLE }
    }
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun buildLive(id: Int, page: String, pwCss: Float): LiveW? {
        val themed = ContextThemeWrapper(this, R.style.Theme_Story)
        val holder = FrameLayout(themed).apply { visibility = View.GONE }
        val web = WebView(themed)
        val lp = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        pinToScreen(lp)
        val lw = LiveW(id, page, holder, web, lp)
        web.setBackgroundColor(th(Color.BLACK))
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true
        web.settings.useWideViewPort = true; web.settings.loadWithOverviewMode = true
        web.isVerticalScrollBarEnabled = false; web.isHorizontalScrollBarEnabled = false
        web.addJavascriptInterface(StoryBridge(this, this, widget = true, evalTo = { js -> ui.post { if (liveWidgets[id] === lw) web.evaluateJavascript(js, null) } },
            onTyping = { on -> ui.post { liveTypingSet(lw, on) } }), "StoryNative")
        // Back while typing in a widget: the typing ends and the home screen gets its keys back.
        web.setOnKeyListener { _, code, e -> if (code == android.view.KeyEvent.KEYCODE_BACK && e.action == android.view.KeyEvent.ACTION_UP && liveTyping === lw) {
            web.evaluateJavascript("document.activeElement&&document.activeElement.blur&&document.activeElement.blur()", null); liveTypingSet(lw, false); true } else false }
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        val query = "?widget=" + page + "&live=1&pw=" + pwCss
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (u.startsWith(ProfilePhotoActivity.PHOTO_BASE)) return photoResponse(r)
                return loader.shouldInterceptRequest(r.url)
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString(); return !(u.startsWith(REMOTE) || u.startsWith(LOCAL))
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) v.loadUrl(LOCAL + query)
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, e: WebResourceResponse) {
                if (r.isForMainFrame && r.url.toString().startsWith(REMOTE)) v.loadUrl(LOCAL + query)
            }
            override fun onRenderProcessGone(v: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                ui.post { liveWidgets.remove(id)?.let { runCatching { wm.removeView(it.holder) } } }
                return true
            }
            override fun onPageFinished(v: WebView, url: String) {
                v.evaluateJavascript("window.storyWidget&&window.storyWidget(" + org.json.JSONObject.quote(page) + ")", null)
                ui.postDelayed({ lw.ready = true; widgetLiveEvent(false) }, 300)
            }
        }
        // A touch anywhere else on the home screen may start a swipe: step aside, come back once it's still.
        holder.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && liveTyping !== lw) widgetLiveEvent(true); false }
        holder.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // The page fills the whole widget, so it takes the widget's own rounded corners (the phone's widget radius).
        val radius = if (Build.VERSION.SDK_INT >= 31) runCatching { resources.getDimension(android.R.dimen.system_app_widget_background_radius) }.getOrDefault(16 * dp) else 16 * dp
        holder.outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, radius) } }
        holder.clipToOutline = true
        if (runCatching { wm.addView(holder, lp) }.isFailure) { web.destroy(); return null }
        liveWidgets[id] = lw
        web.loadUrl(remoteBase() + query + "&v=" + System.currentTimeMillis())
        return lw
    }

    // ---- App pages (owner: "make any app into a page"): landing on an app's page shows its card (owner: it never opens
    //      by itself); tapping it / Open opens the real app full screen - like any app opened from Lockedintool, the app
    //      starts under the Pages and they step aside once it's in front (hidePanelFor, no flash). A thin see-through strip
    //      on the LEFT edge (middle part only, so the phone's own back gesture still works above and below it) swipes your
    //      pages back. It goes away once the Pages show or you leave that app. ----
    private var appPagePkg: String? = null
    private var appEdge: View? = null
    override fun appPageOpen(pkg: String) { ui.post {
        if (SystemMode.refuses(this)) return@post
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: run { showToast("That app isn't on this phone any more"); return@post }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        s3Pane?.takeIf { it.shown }?.let { closeStation3Now() }
        runCatching { startActivity(i) }.onFailure { showToast("Couldn't open that app"); return@post }
        hidePanelFor(pkg, "an app page")
        appPagePkg = pkg
        addAppEdge()
    } }
    private fun addAppEdge() {
        removeAppEdge()
        val h = (resources.displayMetrics.heightPixels * 0.42).toInt()
        val v = View(this)
        val lp = WindowManager.LayoutParams((14 * dp).toInt(), h, layerType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        var downX = 0f; var done = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; done = false }
                MotionEvent.ACTION_MOVE -> if (!done && e.rawX - downX > 36 * dp) { done = true; removeAppEdge(); appPagePkg = null; showPanel(station3Only = false, back = true) }
            }
            true
        }
        runCatching { layerWm.addView(v, lp) }.onSuccess { appEdge = v }
    }
    private fun removeAppEdge() { appEdge?.let { runCatching { layerWm.removeView(it) } }; appEdge = null }
    /** The app on screen changed (accessibility): leaving the app page's app takes the strip away. */
    fun foregroundChanged(pkg: String) { widgetLiveEvent(pkg != homePkg()); ui.post {
        openingPkg?.let { w -> if (w.isNotEmpty() && pkg == w) { ui.removeCallbacks(openingHide); openingHide.run() } }   // the app just opened is in front
        if (appPagePkg != null && pkg != appPagePkg && pkg != packageName) { appPagePkg = null; removeAppEdge() }; systemEnforce(pkg) } }

    // ---- Lockedintool as your system (SystemMode.kt): the DASHBOARD is your home screen, so the phone's own bare home
    //      screen sends you back to the Dashboard. Your apps all still work (owner, 28 Oct 2026: 'apps on dashboard for
    //      only system are not available please fix') - nothing else is sent away; Recents, opening apps and sharing
    //      all work. ----
    // Only system = Lockedintool is your phone's HOME app now (owner, 28 Oct 2026: 'Make it my real Home app'), so the
    // SYSTEM takes home to the Dashboard (LockedintoolHome) and the phone's OWN nav - recents, app-switching, the whole
    // phone - works 100% normally. Lockedintool no longer redirects anything itself (the old overlay trick fought the
    // phone's nav, so the bottom nav only half-worked). Kept for its callers; it does nothing now.
    private fun systemEnforce(pkg: String) {}
    private fun systemShowPages() {
        val p = pagesPane ?: return
        hideLiveWidgets()
        // The Dashboard is your home (owner: the Dashboard becomes your home screen): coming back always lands on it. Pages
        // already up (e.g. back from Lockedintool's own picture picker) stay where they are.
        if (!p.shown) { warmUp(); secGate(p) { if (!p.shown) showPagesOn(p, "window.storyShow&&window.storyShow('dashboard',false)") { updateBubble(); addBar() } }; return }
        updateBubble(); addBar()
    }
    /** The Pages went away while nothing allowed is in front (the phone's home screen showing): they come back. */
    private val systemCheckRun = Runnable {
        if (!SystemMode.on(this) || pagesPane?.shown == true || profilePane?.shown == true || pickerHid.isNotEmpty() || createActive || keyguardLocked() || appPagePkg != null) return@Runnable
        val front = StoryAccessibilityService.frontPkg ?: return@Runnable
        if (front == packageName) return@Runnable
        systemEnforce(front)
    }
    /** Only use widgets switched: everything but the widgets goes away (or comes back). */
    fun widgetModeChanged() { ui.post {
        if (WidgetMode.on(this)) {
            listOfNotNull(dialPane, s3Pane, profilePane, pagesPane).filter { it.shown && !(it === profilePane && callActive) }.forEach { hideOne(it) }
            closeStation3Now()
            // The system closes and you're on your phone's own home screen and apps (owner).
            StoryAccessibilityService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        }
        addBar(); updateBubble(); syncLockDial()
    } }
    fun systemModeChanged() { ui.post { if (SystemMode.on(this)) { SystemMode.reset(); addBar(); systemShowPages() } else addBar() } }
    // Every launch Lockedintool makes is one of its own features: the app it opens is let through (SystemMode.grant).
    override fun startActivity(intent: Intent?) { SystemMode.grant(); super.startActivity(intent) }
    override fun startActivity(intent: Intent?, options: android.os.Bundle?) { SystemMode.grant(); super.startActivity(intent, options) }

    /** The Station 3 button is always there and always the same -- over apps, over the Pages, open or closed. */
    /** The Station 3 button is always there and always the same -- except where Settings > QUICK ACCESS says
     *  otherwise: only on the Pages / only on apps / not at all (Dial pad only), and on the Always On Display
     *  only when its Always On Display is on. Hidden = Station 3 closes. */
    private var lastOnPages: Boolean? = null
    // Station 3 on the Dashboard (owner's full layout; Settings > QUICK ACCESS): while the Dashboard is on screen
    // Station 3 is held open at its bottom - no button there (nothing opens / closes it), no Rows / Page Styles.
    // It closes again when you leave the Dashboard or the Pages step aside.
    private var dashUp = false
    private var s3Held = false
    // Owner: on the Dashboard Station 3 runs the whole bottom, side to side, just above the bottom line (the Apps | Pages
    // switch / the phone's own gesture line), which stays visible and working under it. dashGeom = Station 3's windows
    // (its page, the white bar, the dial pad, Profile's gap) are in that Dashboard place; they change only while hidden.
    private var dashGeom = false
    private var lastOnDash: Boolean? = null
    private var s3Opening = false
    /** The Dashboard page draws Station 3's bar itself, as part of the Dashboard (owner, 29 Oct 2026: 'make the dashboard
     *  station 3 be attached to the dashboard now its part of the dashboard design'): one picture, so it can never come in
     *  late or from the side. Our own Station 3 window then stays hidden on the Dashboard; it's only used when the Pages
     *  step aside (Create's camera) or on a page from before this change (which never says so). Reset when the Pages reload. */
    private var dashBandInPage = false
    override fun dashInPage(on: Boolean) { ui.post { if (dashBandInPage != on) { dashBandInPage = on; updateBubble() } } }
    override fun dashShown(on: Boolean) { ui.post { if (dashUp != on) { dashUp = on
        // Pre-lay the band in its Dashboard layout the instant the Dashboard page renders (while the band is still
        // hidden), so when it's revealed it appears WITH the Dashboard instead of ~0.2 s later while it re-lays-out
        // (owner: the band 'wasn't there, then it's there ... comes in'). No visual effect here - the band stays hidden
        // until updateBubble's dash branch actually shows it; this only gives its layout a head start.
        if (on && QuickAccess.s3Dash(this) && !WidgetMode.on(this)) tellS3(onPages = false, onDash = true)
        updateBubble() } } }
    /** The Pages stepped aside for a moment for Lockedintool's own screens (Create's camera, a system picker): the
     *  Dashboard's Station 3 stays where it is. Not for the phone's own switch screens opened from the Dashboard's
     *  Controls / Media output (sysFromPages) - held there, Station 3 covered their bottom buttons (Wi-Fi panel's Done...). */
    private fun pagesAside() = pagesHiddenForCreate || (pickerHid.any { it === pagesPane } && !sysFromPages)
    private var sysFromPages = false
    /** The phone's own navigation area at the bottom (gesture line / buttons), in px. */
    private fun navInsetPx(): Int = if (Build.VERSION.SDK_INT >= 30)
        runCatching { wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom }.getOrDefault(0)
        else if (phoneHasGestureLine()) 0 else navBarHeightPx()
    /** How far above the screen's bottom edge the Dashboard's Station 3 sits: right above the bottom line's area. */
    private fun dashBandYPx(): Int {
        val line = if (WidgetMode.on(this)) 0
            else (if (phoneHasGestureLine()) 0 else navBarHeightPx() + (6 * dp).toInt()) + (26 * dp).toInt()
        return maxOf(line, navInsetPx())
    }
    /** Station 3's bottom edge above the screen's bottom: 0 (the corner) everywhere but the Dashboard. */
    fun s3BandYPx(): Int = if (dashGeom) dashBandYPx() else 0
    /** On the Dashboard Station 3's background runs on to the bottom (owner), under the bottom line's place: down to
     *  the screen's edge, or on 3-button phones down to the phone's own buttons (never over them). */
    private fun dashUnderPx(): Int = maxOf(0, dashBandYPx() - (if (phoneHasGestureLine()) 0 else navBarHeightPx()))
    /** The bottom line stays visible on that background (owner: the line that switches Apps | Pages): drawn on it in
     *  the line's own place and size - the phone's gesture line (only covered in Lockedintool's top layer; in the
     *  normal layer the phone draws its own over it) or, on 3-button phones, Lockedintool's line. */
    private fun dashLineSync() {
        val line = barBgLine ?: return
        val gesture = phoneHasGestureLine()
        // Gesture-nav phones draw their OWN gesture line on top of everything (it's SystemUI, above our overlays), so
        // drawing ours too put TWO lines of different sizes on the Dashboard - ours (narrow, white) over the phone's real
        // one (wider, grey) - while the Pages showed only the phone's (owner, 29 Oct 2026: 'the difference in sizes of the
        // bottom button ... my phone's original one is underneath the one Lockedintool uses in the dashboard ... in pages
        // it's my phone's original one'). So only draw our own line on 3-button phones, which have no gesture line at all.
        val show = dashGeom && dashUnderPx() > 0 && !gesture && bar != null
        if (!show) { line.visibility = View.GONE; return }
        val (w, h, r) = phoneLineSize()
        val barH = (26 * dp).toInt()
        val bottom = if (gesture) Math.round(sysUiDim("navigation_handle_bottom", 6f)) else (6 * dp).toInt() + barH - h - (barH - h) / 2
        // The phone's own line colours: dark on a light background, light on a dark one (works for a custom band colour too).
        val bg = barBgColor()
        val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
        val c = if (lum > 160) Color.parseColor("#99000000") else Color.parseColor("#EBFFFFFF")
        line.background = GradientDrawable().apply { cornerRadius = r; setColor(c) }
        line.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = bottom }
        line.visibility = View.VISIBLE
    }
    /** The Dashboard band's custom colour from a colour system (Layouts > Colour customization), set by the page; null = none. */
    private var dashBandColor: Int? = null
    /** Station 3's bar colour: the opposite of the system theme (white in Black) - except on the Dashboard, where it FOLLOWS
     *  the theme (owner: 'make it go with the system theme...'), or takes the Dashboard colour system's background when one is on. */
    private fun barBgColor() = if (dashGeom) (dashBandColor ?: th(Color.BLACK)) else th(Color.WHITE)
    /** The Station 3 page tells us the Dashboard band's custom background (owner: 'the colour under station 3 didn't change').
     *  '' / null clears it and the band goes back to the plain Black / White theme. Only ever affects the Dashboard band. */
    fun setDashBandColor(hex: String?) { ui.post {
        val c = try { if (hex.isNullOrBlank()) null else Color.parseColor(hex.trim()) } catch (e: Exception) { null }
        if (c == dashBandColor) return@post
        dashBandColor = c
        if (dashGeom) { (barBg?.background as? GradientDrawable)?.setColor(barBgColor()); dashLineSync() }
    } }
    /** The room (CSS px) the Dashboard keeps at its bottom: the bottom line's area, Station 3's 40dp band and 6dp. */
    private fun dashPadJs(): String {
        val pagesBottom = if (phoneHasGestureLine()) 0 else navBarHeightPx()   // the Pages stop above 3-button navigation
        val pad = Math.round((maxOf(0, dashBandYPx() - pagesBottom) + 46 * dp) / dp)
        return "window.__s3DashPad=$pad;window.storyDashPad&&window.storyDashPad($pad)"
    }
    /** Tells Station 3's page where it is: on the Pages (Rows / Page Styles column) and / or on the Dashboard (full width). */
    private fun tellS3(onPages: Boolean, onDash: Boolean) {
        if (onPages == lastOnPages && onDash == lastOnDash) return
        lastOnPages = onPages; lastOnDash = onDash
        s3Pane?.web?.evaluateJavascript("window.__storyOnPages=$onPages;window.__storyOnDash=$onDash;" +
            "if(window.storyS3Context)window.storyS3Context($onPages,$onDash);else if(window.storyOnPages)window.storyOnPages($onPages)", null)
    }
    /** Moves Station 3's windows to the Dashboard's place (or back to the corner). Only while they're hidden. */
    private fun applyS3Geom(dash: Boolean) {
        if (dashGeom == dash) return
        dashGeom = dash
        val y = s3BandYPx()
        s3Pane?.let { p -> p.lp.y = y - (10 * dp).toInt(); runCatching { p.wm.updateViewLayout(p.frame, p.lp) } }
        barBg?.let { v -> barBgLp?.let { lp ->
            val under = if (dash) dashUnderPx() else 0
            lp.y = y - under
            lp.height = (40 * dp).toInt() + under
            lp.width = if (dash) resources.displayMetrics.widthPixels else getSharedPreferences("story", MODE_PRIVATE).getInt("bar_w", (220 * dp).toInt())
            (v.background as? GradientDrawable)?.cornerRadii = if (dash) FloatArray(8) else floatArrayOf(20 * dp, 20 * dp, 0f, 0f, 0f, 0f, 0f, 0f)
            (v.background as? GradientDrawable)?.setColor(barBgColor())
            runCatching { (barBgWm ?: wm).updateViewLayout(v, lp) }
        } }
        dialPane?.let { p -> p.lp.y = y + (52 * dp).toInt(); runCatching { p.wm.updateViewLayout(p.frame, p.lp) } }
        fitProfileGap()
        syncBarTouch()
        updateBarLine()
    }
    /** Opens the Dashboard's Station 3 only once its page has drawn the Dashboard layout (owner: Rows / Page Styles
     *  flashed for a moment): the page switches while the window is still see-through, then the window shows. */
    private fun openHeldS3(p: Pane) {
        if (s3Opening) return
        s3Opening = true
        if (!p.loading) p.frame.alpha = 1f   // draws, but the window itself is still fully see-through
        var done = false
        val go = Runnable {
            if (done) return@Runnable; done = true
            if (!s3Opening) return@Runnable
            s3Opening = false
            if (s3Held && !p.shown) setShown(p, true)
        }
        // Size the window for the Dashboard band BEFORE it shows (owner, 29 Oct 2026: 'the dashboard station 3 still moves'
        // - 'it comes from the right then sits in place'). Station 3's window is cut down to what's showing, and the page
        // used to report its new size a frame later, so the band could be revealed still cut to the corner bar's width
        // (anchored bottom-right: Messages / Profile first) and then widen. Ask for the size NOW: the report calls
        // setStation3Size synchronously, whose resize is queued on this thread before this callback - so by the time the
        // band shows, its window is already the full Dashboard size.
        p.web.evaluateJavascript("window.__storyS3Report&&window.__storyS3Report(true);0") {
            runCatching { p.web.postVisualStateCallback(2L, object : WebView.VisualStateCallback() { override fun onComplete(requestId: Long) { ui.post(go) } }) }
                .onFailure { ui.post(go) }
        }
        ui.postDelayed(go, 300)
    }
    // The phone's own panel (notifications / quick settings) pulled down while unlocked: Station 3 steps aside so it never
    // covers it (owner, Oct 2026: 'it also overlaps other things - the panel') and comes back the moment it's closed.
    // Checked only when windows change (StoryAccessibilityService), and every second only while the panel is down.
    private var panelOver = false
    fun windowsMoved() { ui.removeCallbacks(panelCheck); ui.postDelayed(panelCheck, 120) }
    private val panelCheck = Runnable {
        val over = !keyguardLocked() && StoryAccessibilityService.panelInFront() == true
        if (over != panelOver) { panelOver = over; updateBubble() }
        if (panelOver) { ui.removeCallbacks(panelRecheck); ui.postDelayed(panelRecheck, 1000) }
        // The phone's Recents (overview) is a SystemUI screen, so it never counted as "an app" and the Pages stayed on
        // top of it (owner: swiping up to see opened apps showed nothing). When Recents appears, step the Pages aside so
        // the swipe-up actually shows the opened apps - like Lockedintool already steps aside when you open an app. The
        // owner comes back to Lockedintool the usual way (the Apps | Lockedintool switch), same as leaving for any app.
        if (!keyguardLocked() && pagesPane?.shown == true && StoryAccessibilityService.recentsInFront()) hidePanel()
    }
    private val panelRecheck = Runnable { windowsMoved() }
    private fun updateBubble() {
        val pagesUp = pagesPane?.shown == true
        // The Dashboard's Station 3 band shows only while the Dashboard itself is up and the phone is unlocked - never on
        // the lock screen / AOD / under the phone's own panel (owner, 28 Oct 2026: 'the dashboard station 3 pops up
        // everywhere ... is not fixed'). The lock screen has its own Station 3 rules.
        val dash = (pagesUp || (s3Held && pagesAside())) && dashUp && QuickAccess.s3Dash(this) && !WidgetMode.on(this) && !aodShowing() && !panelOver && !keyguardLocked()
        if (dash) {
            s3Held = true
            val p = s3Pane
            // Already open in the corner (opened on another page): put it away first, then it opens in the Dashboard's place.
            if (p != null && p.shown && !dashGeom) { dialPane?.takeIf { it.shown }?.let { hideOne(it) }; hideOne(p) }
            tellS3(onPages = false, onDash = true)
            applyS3Geom(true)
            if (pagesUp && dashBandInPage) {
                // The Dashboard draws the bar itself: our window stays out of the way (the dial pad / Profile still open
                // from its buttons, placed for the Dashboard by applyS3Geom).
                s3Opening = false
                if (p != null && p.shown) hideOne(p)
                updateBarLine()
            } else if (p != null && !p.shown) openHeldS3(p)
        } else {
            if (s3Held) {
                s3Held = false; s3Opening = false
                s3Pane?.let { p -> dialPane?.takeIf { it.shown }?.let { hideOne(it) }; if (p.shown) hideOne(p); if (!p.shown && !p.loading) p.frame.alpha = 0f }
            }
            // Station 3 is hidden now: back to the corner, and on the Pages it has Rows and Page Styles again.
            if (s3Pane?.shown != true) applyS3Geom(false)
            tellS3(onPages = pagesUp, onDash = false)
        }
        val where = QuickAccess.where(this)
        // The Station 3 button over apps / on the Pages: owner's on/off toggle (s3Apps) + where it shows. The Dashboard band
        // is separate (s3Dash, always on) and overrides this below (dash -> on = false).
        var on = QuickAccess.s3Apps(this) && when (where) { "pages" -> pagesUp; "apps" -> !pagesUp; else -> true }
        if (aodShowing() && !QuickAccess.s3Aod(this)) on = false
        if (WidgetMode.on(this)) on = false   // Only use widgets: no Station 3
        if (dash) on = false                  // the Dashboard's Station 3 has no button
        if (panelOver) on = false             // the phone's own panel is pulled down over everything
        // Station 3 must stay on top everywhere (owner): it can only sit above the keyboard and every app's screen in
        // the accessibility layer. If that layer came back (the system re-connected Lockedintool system actions) while
        // Station 3 was still in the normal layer, move everything up at once.
        if (StoryAccessibilityService.instance != null && layerType != WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY && !relayerPending) {
            relayerPending = true; ui.post { relayerPending = false; rebuildLayer() }
        }
        if (!on && !dash && s3Pane?.shown == true) { closeStation3Now(); return }
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
        if ((p.stale || (!p.small && System.currentTimeMillis() - p.loadedAt > 5 * 60_000)) && !(p === profilePane && callActive)) reload(p)
        // Lockedintool security: you left Lockedintool (the Pages and Station 3's window both closed) - it locks.
        if ((p === pagesPane || p === profilePane) && pagesPane?.shown != true && profilePane?.shown != true && pickerHid.isEmpty()) secLock()
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
    private var barBgLine: View? = null   // the bottom line, drawn on the Dashboard's Station 3 background (dashLineSync)

    private fun addBarBg() {
        removeBarBg()
        val line = View(this).apply { visibility = View.GONE }
        val v = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 0f, 0f, 0f, 0f, 0f, 0f)  // like the button: rounded top-left
                setColor(th(Color.WHITE))
            }
            addView(line, FrameLayout.LayoutParams(0, 0))
        }
        barBgLine = line
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
        barBg = null; barBgLp = null; barBgLine = null
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
            if (dashGeom) return@post   // the Dashboard's Station 3: always the whole width
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
        val views = listOfNotNull(bubble, barBg, s3Pane?.frame, dialPane?.frame, profilePane?.frame, profileGapPane?.frame, profileStrip, lockDialPane?.frame, walletBtn)
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
        removeProfileStrip(); removeWalletBtn()
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

    /** The phone's OWN navigation (gesture or buttons) took the owner Home or to Recents while they were on the Pages.
     *  Home / Recents are SystemUI / launcher screens that float UNDER Lockedintool's Pages overlay, so the owner saw
     *  nothing happen (owner: 'i swipe up to see my opened apps and its not working' / 'that bottom button should work
     *  like how your phone set it to normally work'). Step the Pages aside - exactly like Lockedintool already steps
     *  aside when an app opens - so the home screen / opened apps show; the owner comes back the usual way (the
     *  Apps | Lockedintool switch). Not in Only system (the Dashboard IS your home there) or Only use widgets, and not
     *  while Lockedintool is opening one of its own screens / apps. */
    fun recentsShown(pkg: String, cls: String) { ui.post {
        if (pagesPane?.shown != true || keyguardLocked() || SystemMode.on(this) || WidgetMode.on(this)) return@post
        if (pickerHid.isNotEmpty() || openingPkg != null || createActive || appPagePkg != null) return@post
        val recents = StoryAccessibilityService.RECENTS_CLASS.containsMatchIn(cls)
        val home = pkg == homePkg()          // One UI's Recents is the launcher itself, so this catches Recents too
        if (recents || home) hidePanel()
    } }

    /** One of the phone's apps was opened from the Pages (an app, ALARM, CAMERA, GALLERY): it opens UNDER the Pages, and
     *  the Pages step aside the moment it is in front - never a flash of whatever was underneath while it starts (owner).
     *  At most 1.5 s (0.4 s when Lockedintool can't see which app is in front). */
    private var openingPkg: String? = null
    private var openingWhat = ""
    private val openingHide = Runnable { if (openingPkg != null) {
        // It never came to the front in 1.5 s: slow on this phone - reported so it gets looked at (which button, never which app).
        if (!openingPkg.isNullOrEmpty() && StoryAccessibilityService.instance != null) ProblemLog.record(this, "slow", "$openingWhat took over 1.5 s to open", "pages")
        openingPkg = null; pagesPane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble() } }
    override fun hidePanelFor(pkg: String?, what: String) { ui.post {
        ui.removeCallbacks(openingHide)
        if (pagesPane?.shown != true) { openingPkg = null; updateBubble(); return@post }
        openingWhat = what
        openingPkg = pkg ?: ""
        ui.postDelayed(openingHide, if (pkg.isNullOrEmpty() || StoryAccessibilityService.instance == null) 400L else 1500L)
    } }

    /** Station 3's corner arrow was tapped: the one way Station 3 closes. */
    override fun closeStation3() { ui.post { closeStation3Now() } }

    /** Closes Station 3 (and its dial pad) right now, on the spot. */
    private fun closeStation3Now() { dialPane?.takeIf { it.shown }?.let { hideOne(it) }; s3Pane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble() }

    override fun toggleDialpad() { ui.post {
        val d = dialPane ?: return@post
        // Owner: the dial pad shows ON TOP of whatever is open (Call history, Messages, Profile...) - it never closes it.
        if (d.shown) hideOne(d) else if (s3Pane?.shown == true || (s3Held && dashGeom)) setShown(d, true)
    } }

    // ---- System theme (Settings): Black or White. Only black and white swap -- on the Pages, Station 3
    //      (button, bar), the dial pad, Profile, the message bubble and the Apps | Pages switch. ----
    private fun th(c: Int) = StoryTheme.swap(this, c)
    override fun setTheme(t: String) { ui.post {
        if (StoryTheme.get(this) == t) return@post
        StoryTheme.set(this, t)
        (bubble as? TextView)?.let { b -> b.setTextColor(th(Color.WHITE)); (b.background as? GradientDrawable)?.setColor(th(Color.parseColor("#FF000000"))) }
        (barBg?.background as? GradientDrawable)?.setColor(barBgColor())
        (barLine?.background as? GradientDrawable)?.setColor(th(Color.parseColor("#B3FFFFFF")))
        dashLineSync()
        pagesPane?.let { it.frame.setBackgroundColor(th(Color.BLACK)); it.web.setBackgroundColor(th(Color.BLACK)) }
        profileStrip?.setBackgroundColor(th(Color.BLACK))
        (walletBtn as? android.widget.TextView)?.let { b -> b.setTextColor(th(Color.BLACK)); (b.background as? GradientDrawable)?.setColor(th(Color.WHITE)) }
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
    private fun showProfileStrip(on0: Boolean) {
        val v = profileStrip ?: return; val lp = profileStripLp ?: return
        val on = on0 && !dashGeom   // the Dashboard's Station 3 is the whole width: no strip beside its gap
        v.visibility = if (on) View.VISIBLE else View.INVISIBLE
        lp.alpha = if (on) 1f else 0f
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { (profileStripWm ?: layerWm).updateViewLayout(v, lp) }
    }
    /** Station 3's bar changed width: the gap window and the strip follow it exactly. */
    private fun fitProfileGap() {
        val gw = profileGapWPx(); val y = s3BandYPx()
        profileGapPane?.let { g -> if (g.lp.width != gw || g.lp.y != y) { g.lp.width = gw; g.openW = gw; g.lp.y = y; runCatching { g.wm.updateViewLayout(g.frame, g.lp) } } }
        profileStripLp?.let { lp -> val sw = maxOf(1, resources.displayMetrics.widthPixels - gw); if (lp.width != sw || lp.y != y) { lp.width = sw; lp.y = y; runCatching { (profileStripWm ?: layerWm).updateViewLayout(profileStrip, lp) } } }
        showProfileStrip(profilePane?.shown == true)
        // Profile itself ends just above the gap's line, wherever Station 3 is.
        profilePane?.let { p -> val h = realHeightPx() - y - profileBandPx(); if (p.lp.height != h) { p.lp.height = h; p.openH = h; runCatching { p.wm.updateViewLayout(p.frame, p.lp) } } }
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
        // The Wallet page button could show (lock screen): it gets the very same care as the lock screen dial pad.
        val walletCan = walletBtnCan(locked)
        val watch = QuickAccess.lockDial(this) || walletCan
        // (Not while the Always On Display is up - nothing of the phone's can show under it; cooling, owner.)
        val entry = if (watch && locked && awake && !aodUp) StoryAccessibilityService.unlockEntryShowing() else null
        // The phone's own quick settings / notifications pulled down over the lock screen: out of the way (owner).
        val shade = if (watch && locked && awake && entry != true && !aodUp) StoryAccessibilityService.shadeShowing() == true else false
        // It never moves (owner): it simply stays put through screen off / Always On Display / lock screen, so it's
        // already there the instant anything shows. Only hidden on the Always On Display if that's switched off for it.
        val onAod = aodUp || (!awake && AodActivity.enabled(this))
        val want = !WidgetMode.on(this) && QuickAccess.lockDial(this) && locked && !lockDialAway && !lockDialPeek && entry != true && !shade &&
            (if (onAod) QuickAccess.dialAod(this) else true)
        if (want != p.shown) {
            if (!want) p.web.evaluateJavascript("window.storyDialClear&&window.storyDialClear()", null)
            setShown(p, want)
            if (!want && p.stale) reload(p)  // new screens were published while it was up: pick them up now it's hidden
        }
        // The Wallet page button (Settings > QUICK ACCESS): on the Always On Display only when its own Always On Display is on.
        val wallet = walletCan && !lockDialAway && !lockDialPeek && entry != true && !shade && (if (onAod) QuickAccess.walletAod(this) else true)
        showWalletBtn(wallet)
        // While it's up, keep checking: the moment the phone unlocks (by any route) it goes.
        // (and while the PIN page is up, it comes back as soon as you drag that away).
        if (awake && (want || wallet || aodOn || entry == true || shade)) ui.postDelayed(lockWatch, if (shade) 400L else 1000L)
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
    override fun callNumber(number: String) = placeNumberCall(number, false)
    // A phone number as a VIDEO call (owner: Call history's Video call): the phone's own network video call (VoLTE /
    // ViLTE); where the network or phone can't do video, Android places it as a normal voice call.
    override fun callNumberVideo(number: String) = placeNumberCall(number, true)
    private fun placeNumberCall(number: String, video: Boolean) { ui.post {
        val n = number.filter { it.isDigit() || it in "+*#" }
        if (n.isEmpty()) return@post
        val uri = android.net.Uri.fromParts("tel", n, null)
        CallHistory.placed(this, n, video); tellCalls()  // Call history: every call made from STORY
        val allowed = checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val extras = android.os.Bundle()
        if (video) extras.putInt(android.telecom.TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, android.telecom.VideoProfile.STATE_BIDIRECTIONAL)
        val ok = allowed && runCatching { getSystemService(android.telecom.TelecomManager::class.java).placeCall(uri, extras) }.isSuccess
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
    fun permissionsDone() { ui.post { comeBackFromSystem()
        if (callAsking) { callAsking = false; val ok = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            profilePane?.web?.evaluateJavascript("window.storyCallAccess&&window.storyCallAccess($ok)", null) }
        if (noteAsking.isNotEmpty()) { val k = noteAsking; noteAsking = ""; pagesPane?.web?.evaluateJavascript("window.storyNoteAccessDone&&window.storyNoteAccessDone('$k')", null) }
        Thread { CallHistory.finishFromLog(this); ui.post { tellCalls() } }.start() } }

    // ---- Profile and Messages: Station 3's profile / messages buttons open them in ONE window attached to Station 3
    //      (same gap, line and scene). Its own button closes it; the other button switches straight over. ----
    private var s3Page = "profile"
    override fun toggleProfile() = toggleS3Page("profile")
    override fun toggleMessages() = toggleS3Page("messages")
    override fun toggleCreate() = toggleS3Page("create")
    override fun toggleUpdates() = toggleS3Page("updates")
    /** Station 3's call history button: Call history in the same window. The first time, ask once to read the
     *  phone's call log (for when each STORY call ended and how long it lasted) and contacts (names). */
    override fun toggleCalls() {
        toggleS3Page("calls")
        val perms = arrayOf(android.Manifest.permission.READ_CALL_LOG, android.Manifest.permission.READ_CONTACTS)
        val prefs = getSharedPreferences("story", MODE_PRIVATE)
        if (!prefs.getBoolean("calls_asked", false) && perms.any { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            prefs.edit().putBoolean("calls_asked", true).apply()
            ui.postDelayed({ askPermissions(perms) }, 300)
        }
    }
    override fun getCalls(): String = CallHistory.json(this)
    /** Station 3's Rows / Page Styles (on the Pages): open them on the Pages, and Station 3 closes - as always. */
    override fun pagesAction(action: String) { ui.post {
        if (action != "page-quick-rows" && action != "page-quick-style") return@post
        pagesPane?.takeIf { it.shown }?.web?.evaluateJavascript("window.storyPagesAction&&window.storyPagesAction('$action')", null) ?: return@post
        closeStation3Now()
    } }
    private fun tellCalls() { profilePane?.web?.evaluateJavascript("window.storyCallsChanged&&window.storyCallsChanged()", null) }
    // A call ended (the phone's call log changed): fill in the STORY calls that were waiting for it.
    private val callLogWatch = object : android.database.ContentObserver(ui) {
        override fun onChange(selfChange: Boolean) {
            Thread { if (CallHistory.finishFromLog(this@OverlayService)) ui.post { tellCalls() } }.start()
        }
    }
    private fun toggleS3Page(which: String) { ui.post {
        val p = profilePane ?: return@post
        if (p.shown && s3Page == which) { hideOne(p); return@post }
        if (p.shown) { s3Page = which; p.web.evaluateJavascript("window.storyS3Page&&window.storyS3Page('$which')", null); syncCreate(); return@post }
        if (s3Pane?.shown != true && !(s3Held && dashGeom)) return@post   // Station 3 open, or the Dashboard's own bar
        secGate(profilePane) { openS3Page(which) }
    } }
    private fun openS3Page(which: String) {
        val p = profilePane ?: return
        if (p.shown) return
        s3Page = which
        p.web.evaluateJavascript("window.storyS3Page&&window.storyS3Page('$which')", null)
        p.web.evaluateJavascript(profileGeometryJs(), null)
        profileGapPane?.web?.evaluateJavascript(profileGeometryJs(), null)
        p.web.evaluateJavascript("window.storyProfileRefresh&&window.storyProfileRefresh();window.storyProfilePhotoChanged&&window.storyProfilePhotoChanged()", null)  // always the latest real numbers and picture
        profileLayer(keyguardLocked())   // the same on the lock screen (owner)
        setShown(p, true)
        tellProfileStation3()
    }
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
    /** One time: Android's own "allow Lockedintool to see your photos" (it sits under STORY's windows, so they step aside). */
    override fun requestPhotoAccess() { ui.post {
        if (!stepAsideForSystem()) return@post
        runCatching { startActivity(Intent(this, ProfilePhotoActivity::class.java).putExtra(ProfilePhotoActivity.EXTRA_ACCESS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { photoAccessDone(ProfilePhotoActivity.photoAccess(this)) }
    } }
    fun photoAccessDone(access: String) { ui.post {
        comeBackFromSystem()
        profilePane?.web?.evaluateJavascript("window.storyPhotoAccessDone&&window.storyPhotoAccessDone('$access')", null)
        for (p in listOfNotNull(profilePane, pagesPane)) p.web?.evaluateJavascript("window.storyChatAccessDone&&window.storyChatAccessDone('$access')", null)
    } }
    // ---- Files in chats / groups / community posts (owner): the phone's own picker for documents and audio, and
    //      opening a file someone sent with the phone's own app (STORY steps aside like opening any app). ----
    override fun confirmIdentity(title: String) { ui.post {
        if (!stepAsideForSystem()) return@post
        runCatching { startActivity(Intent(this, IdentityActivity::class.java).putExtra(IdentityActivity.EXTRA_TITLE, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { identityDone("fail") }
    } }
    fun identityDone(result: String) { ui.post {
        comeBackFromSystem()
        for (p in listOfNotNull(profilePane, pagesPane)) p.web?.evaluateJavascript("window.storyIdentity&&window.storyIdentity('$result')", null)
    } }

    // ---- Settings > SECURITY (owner; Security.kt). Phone's security (default): nothing here applies - Lockedintool works
    //      wherever the phone is unlocked. Lockedintool security: "every time I try using anything from Lockedintool I will
    //      have to unlock it" - coming into the Pages or Station 3's window (Messages, Profile, Updates, Create, Call
    //      history), or using a live widget, asks the phone's own fingerprint / face / PIN first (IdentityActivity). It
    //      locks again when you leave Lockedintool (the Pages and Station 3's window both closed) and when the screen goes
    //      off. Cancelled: the window shows locked, with Unlock. Calls and the dial pad's phone calls always work. ----
    @Volatile private var secOpen = false
    private var secThen: (() -> Unit)? = null
    fun secIsOpen(): Boolean = !Security.lit(this) || secOpen
    /** Every window that can show Lockedintool's lock (the Pages, Profile's window, live widgets) is told. */
    private fun secTell() {
        val js = "window.storySecLock&&window.storySecLock(${!secIsOpen()})"
        for (p in listOfNotNull(pagesPane, profilePane)) p.web.evaluateJavascript(js, null)
        liveWidgets.values.forEach { runCatching { it.web.evaluateJavascript(js, null) } }
    }
    private fun secLock() {
        if (!Security.lit(this) || !secOpen) return
        secOpen = false; secTell()
    }
    /** Settings changed it (you're in Lockedintool right now, so it stays open until you leave). */
    fun securityChanged() { ui.post { secOpen = true; secTell() } }
    /** Comes into Lockedintool: unlocked -> straight in; locked -> the phone's own check first. Whatever the answer, it then
     *  opens (locked, with Unlock, if it wasn't unlocked) - never asked twice in a row for the same thing. */
    private fun secGate(target: Pane?, then: () -> Unit) {
        if (secIsOpen() || target?.shown == true) { then(); return }   // unlocked, or that window is already up (unlocked or showing locked)
        secPrompt(then)
    }
    private fun secPrompt(then: (() -> Unit)?) {
        if (secIsOpen()) { then?.invoke(); return }
        if (!stepAsideForSystem()) { then?.invoke(); return }   // another of the phone's screens is up: just show it locked
        secThen = then
        hideLiveWidgets()
        // The phone's own prompt / PIN page shows: Station 3's bar is put away meanwhile (never over its buttons) and comes back after.
        val s3WasOpen = s3Pane?.shown == true && !s3Held
        closeStation3Now()
        if (s3WasOpen) { val t = secThen; secThen = { showPanel(station3Only = true); t?.invoke() } }
        runCatching { startActivity(Intent(this, IdentityActivity::class.java).putExtra(IdentityActivity.EXTRA_TITLE, "Unlock Lockedintool")
            .putExtra(IdentityActivity.EXTRA_FOR, "security").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { secDone("fail") }
    }
    fun secDone(result: String) { ui.post {
        if (result == "ok" || result == "nolock") { secOpen = true; secTell() }
        comeBackFromSystem()
        val t = secThen; secThen = null
        t?.invoke()
        if (result == "nolock") showToast("Set a screen lock on your phone - Lockedintool security uses it")
    } }
    /** The lock's Unlock button (the Pages, Station 3's window) or a tap on a locked live widget. */
    override fun secUnlock() { ui.post { secPrompt(null) } }

    // ---- The Wallet page button (owner): "like Station 3 extended but stay open" - a bar beside Station 3's corner button
    //      at the bottom right, always open, not side to side, saying "Wallet page"; tap -> the Wallet page. It's on the
    //      lock screen ("when Lockedintool is the only system the bottom button will always be visible even on the lock
    //      screen"), where the phone's own unlock comes first (that unlock counts for Lockedintool security too). With the
    //      lock screen dial pad up it sits just above the dial pad on the right, never over its keys. Never over the phone's
    //      PIN page or its pulled-down panel (the same checks as the lock screen dial pad). ----
    private var walletBtn: View? = null
    private var walletBtnLp: WindowManager.LayoutParams? = null
    private var walletBtnWm: WindowManager? = null
    private var walletOpening = false
    private fun walletOk() = getSharedPreferences("story", MODE_PRIVATE).getBoolean("wallet_btn_ok", false)
    override fun walletButtonOk(on: Boolean) { ui.post {
        if (walletOk() == on) return@post
        getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("wallet_btn_ok", on).apply(); syncLockDial()
    } }
    @SuppressLint("ClickableViewAccessibility")
    private fun addWalletBtn() {
        removeWalletBtn()
        val v = android.widget.TextView(this).apply {
            text = "Wallet page"; contentDescription = "Wallet page"
            setTextColor(th(Color.BLACK)); textSize = 13f; gravity = Gravity.CENTER; includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(20 * dp, 20 * dp, 0f, 0f, 0f, 0f, 0f, 0f)   // like Station 3's bar: rounded top-left
                setColor(th(Color.WHITE))
            }
            var downIn = false
            setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    // A touch elsewhere on the lock screen (zeroed position): it steps aside like the lock screen dial pad.
                    MotionEvent.ACTION_OUTSIDE -> if (e.x == 0f && e.y == 0f) lockScreenTouched()
                    MotionEvent.ACTION_DOWN -> downIn = true
                    MotionEvent.ACTION_CANCEL -> downIn = false
                    MotionEvent.ACTION_UP -> { if (downIn && e.x >= 0 && e.y >= 0 && e.x <= view.width && e.y <= view.height) walletTap(); downIn = false }
                }
                true
            }
        }
        val w = walletBtnW(0)
        val lp = overlayParams(w, (40 * dp).toInt(), Gravity.BOTTOM or Gravity.END, 0, 0).also {
            it.type = layerType
            it.flags = it.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            it.alpha = 0f
        }
        v.visibility = View.INVISIBLE
        walletBtn = v; walletBtnLp = lp; walletBtnWm = layerWm
        runCatching { layerWm.addView(v, lp) }
    }
    private fun removeWalletBtn() { walletBtn?.let { runCatching { (walletBtnWm ?: layerWm).removeView(it) } }; walletBtn = null; walletBtnLp = null; walletBtnWm = null }
    /** Its words plus room either side - never so wide that it reaches the middle of the screen (the phone's own line). */
    private fun walletBtnW(x: Int): Int {
        val paint = android.graphics.Paint().apply { textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val natural = (paint.measureText("Wallet page") + 40 * dp).toInt()
        val max = resources.displayMetrics.widthPixels / 2 - (44 * dp).toInt() - x
        return natural.coerceAtMost(maxOf(max, (paint.measureText("Wallet page") + 16 * dp).toInt()))
    }
    /** Could it show now (the lock screen, nothing of Lockedintool's open over it)? */
    private fun walletBtnCan(locked: Boolean) = locked && QuickAccess.walletLock(this) && walletOk() && walletBtn != null && !WidgetMode.on(this) && !walletOpening && !callActive && !createActive &&
        s3Pane?.shown != true && profilePane?.let { it.shown && it.wm === layerWm } != true   // (the Pages / a normal-layer Profile are under the lock screen)
    private fun showWalletBtn(on: Boolean) {
        val v = walletBtn ?: return; val lp = walletBtnLp ?: return
        // Beside Station 3's button (or in the corner when the button isn't there); above the lock screen dial pad when it's up.
        val dialUp = lockDialPane?.shown == true
        val x = if (dialUp || bubble?.visibility != View.VISIBLE) 0 else (40 * dp).toInt()
        val y = if (dialUp) (lockDialPane?.lp?.height ?: 0) + (8 * dp).toInt() else 0
        val w = walletBtnW(if (dialUp) 0 else x)
        val nt = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; val wo = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val flags = if (on) (lp.flags and nt.inv()) or wo else (lp.flags or nt) and wo.inv()
        val a = if (on) 1f else 0f
        v.visibility = if (on) View.VISIBLE else View.INVISIBLE
        if (lp.flags != flags || lp.alpha != a || lp.x != x || lp.y != y || lp.width != w) {
            lp.flags = flags; lp.alpha = a; lp.x = x; lp.y = y; lp.width = w
            runCatching { (walletBtnWm ?: layerWm).updateViewLayout(v, lp) }
        }
    }
    private fun walletTap() {
        if (walletBtn?.visibility != View.VISIBLE) return
        walletOpening = true; syncLockDial()
        if (!keyguardLocked()) { walletDone("ok"); return }
        // The phone's own unlock first (its PIN page / fingerprint) - for both security settings.
        runCatching { startActivity(Intent(this, IdentityActivity::class.java).putExtra(IdentityActivity.EXTRA_TITLE, "Open the Wallet page")
            .putExtra(IdentityActivity.EXTRA_FOR, "wallet").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { walletDone("fail") }
    }
    fun walletDone(result: String) { ui.post {
        walletOpening = false
        if (result == "ok") {
            if (Security.lit(this) && !secOpen) { secOpen = true; secTell() }   // you just unlocked with the phone's own lock
            openPageFromWidget("wallet")
        }
        syncLockDial()
    } }
    override fun chatPick(kind: String) { ui.post {
        if (!stepAsideForSystem()) return@post
        runCatching { startActivity(Intent(this, ChatFileActivity::class.java).putExtra(ChatFileActivity.EXTRA_KIND, kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { chatFilePicked(org.json.JSONObject().put("error", "fail").toString()) }
    } }
    fun chatFilePicked(json: String) { ui.post {
        comeBackFromSystem()
        val q = org.json.JSONObject.quote(json)
        for (p in listOfNotNull(profilePane, pagesPane)) p.web?.evaluateJavascript("window.storyChatPicked&&window.storyChatPicked($q)", null)
    } }
    override fun chatOpenFile(url: String, key: String, iv: String, name: String, mime: String, fromProfile: Boolean) {
        Thread {
            val ok = ChatFiles.open(this, url, key, iv, name, mime)
            ui.post {
                if (!ok) { showToast("Couldn't open that file"); return@post }
                if (fromProfile) profilePane?.takeIf { it.shown }?.let { hideOne(it) } else pagesPane?.takeIf { it.shown }?.let { hideOne(it) }
                updateBubble()
            }
        }.start()
    }
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
            "note" -> seg.getOrNull(2)?.let { NoteTools.file(this, it) }
            "chatfile" -> seg.getOrNull(2)?.let { ChatFiles.file(this, it) }
            else -> null
        }
        if (file != null) return fileResponse(file, r.requestHeaders)
        // A video picked from the phone for a post (Updates > Create post): streamed as it is, for uploading.
        if (seg.getOrNull(1) == "mediav") seg.getOrNull(2)?.toLongOrNull()?.let { id ->
            runCatching {
                val uri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val input = contentResolver.openInputStream(uri) ?: error("gone")
                return WebResourceResponse("video/mp4", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), input)
            }
        }
        val bytes = runCatching {
            when (seg.getOrNull(1)) {
                // A picture picked from the phone for a post: upright, at most 1440px, JPEG.
                "media" -> seg.getOrNull(2)?.toLongOrNull()?.let { id ->
                    ProfilePhotoActivity.decodeUpright(this, android.content.ContentUris.withAppendedId(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id), 1440)?.let { bmp ->
                        java.io.ByteArrayOutputStream().also { o -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, o) }.toByteArray()
                    }
                }
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
        val mime = if (f.parentFile?.name == "chat") "application/octet-stream" else if (f.extension == "mp4") "video/mp4" else if (f.parentFile?.name == "notes") NoteTools.mimeOf(f) else "image/jpeg"
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
            ui.postDelayed({ if (createActive && CreateCameraActivity.instance == null) createEvent("error", "the phone didn't let Lockedintool open its camera screen") }, 4500)
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
        if (kind == "error") ProblemLog.record(this, "camera", extra.ifEmpty { "camera error" }, "create")
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
    override fun liveMode(on: Boolean) { ui.post { if (profilePane?.shown == true) { s3Page = if (on) "live" else "create"; syncCreate() } } }
    override fun createKeep(): String { ui.post { CreateCameraActivity.instance?.stopPlayback() }; return CreateCameraActivity.keepCapture(this) }

    private fun stepAsideForSystem(): Boolean {
        if (pickerHid.isNotEmpty()) return false
        pickerHid = listOfNotNull(profilePane, pagesPane).filter { it.shown }
        for (p in pickerHid) setShown(p, false)
        return true
    }
    private fun comeBackFromSystem() {
        val back = pickerHid; pickerHid = listOf(); sysFromPages = false
        for (p in back) if (!p.shown) setShown(p, true)
        if (profilePane in back) tellProfileStation3()
    }
    // ---- Notes, all inside STORY (owner: real actions): Android's one-time permission questions, results back to the
    //      note, the PDF made by STORY, opening the app picked in STORY's share list, and paper printing. ----
    override fun noteAsk(kind: String) { ui.post {
        val perms = when (kind) {
            "photos" -> when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
                else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            "audio" -> arrayOf(NoteTools.audioPermission())
            "camera" -> arrayOf(android.Manifest.permission.CAMERA)
            else -> return@post
        }
        noteAsking = kind
        // Android 14 "selected photos": asking again lets you choose more pictures, even though access is already partial.
        if (kind == "photos" && Build.VERSION.SDK_INT >= 34 && ProfilePhotoActivity.photoAccess(this) == "partial") {
            if (!stepAsideForSystem()) return@post
            runCatching { startActivity(Intent(this, AskActivity::class.java).putExtra(AskActivity.EXTRA_PERMS, perms).putExtra(AskActivity.EXTRA_FORCE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
                .onFailure { permissionsDone() }
            return@post
        }
        askPermissions(perms)
    } }
    private var noteAsking = ""
    override fun noteResult(kind: String, status: String, json: String) { ui.post {
        pagesPane?.web?.evaluateJavascript("window.storyNoteMedia&&window.storyNoteMedia(" + org.json.JSONObject.quote(kind) + "," +
            org.json.JSONObject.quote(status) + "," + json + ")", null)
    } }
    override fun noteSavePdf(title: String, pages: List<String>) { ui.post {
        NoteTools.makePdf(this, pagesPane?.frame, pages) { bytes ->
            Thread {
                val folder = bytes?.let { NoteTools.saveToDocuments(this, NoteTools.safeName(title) + ".pdf", "application/pdf", it) }
                noteResult("pdf", if (folder != null) "ok" else "error", org.json.JSONObject().put("folder", folder ?: "").toString())
            }.start()
        }
    } }
    /** The app you picked in STORY's share list opens with the note in it; the Pages step back (your note is kept as
     *  it is, like any app you open from STORY). */
    override fun noteShareTo(pkg: String, cls: String, title: String, text: String, files: List<java.io.File>) { ui.post {
        if (NoteTools.shareTo(this, pkg, cls, title, text, files)) { pagesPane?.takeIf { it.shown }?.let { setShown(it, false) }; updateBubble() }
        else showToast("Couldn't open that app - try another one")
    } }
    override fun evalPages(js: String) { ui.post { pagesPane?.web?.evaluateJavascript(js, null) } }

    // ---- STORY calls (Messages): the call screen is Profile's window - shown for a call even with Station 3 closed
    //      (and put away again after, if the call opened it); incoming calls ring and vibrate like the phone's own
    //      (silent / vibrate mode respected) and wake the screen; during a call the phone's call audio is used. ----
    private var callOpenedProfile = false
    // A call is on (never reload Profile's window then - that would cut the call).
    private var callActive = false
    fun inCall() = callActive
    private var callAsking = false
    private var ringtone: android.media.Ringtone? = null
    private var callWake: android.os.PowerManager.WakeLock? = null
    override fun callUi(on: Boolean) { ui.post {
        val p = profilePane ?: return@post
        callActive = on
        if (!on) callFg(false, false)
        if (on) runCatching { p.web.onResume() } else if (!p.shown) runCatching { p.web.onPause() }
        if (on) {
            if (!p.shown) {
                callOpenedProfile = true
                dialPane?.takeIf { it.shown }?.let { hideOne(it) }
                p.web.evaluateJavascript(profileGeometryJs(), null)
                profileGapPane?.web?.evaluateJavascript(profileGeometryJs(), null)
                profileLayer(keyguardLocked())   // an incoming call shows on the lock screen too
                setShown(p, true); tellProfileStation3()
            }
        } else {
            if (callOpenedProfile) { callOpenedProfile = false; if (p.shown) hideOne(p) }
            else if (!p.shown && p.stale) reload(p)   // screens that came out during the call
        }
    } }
    override fun callRing(on: Boolean, name: String, video: Boolean) { ui.post {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val vib = if (Build.VERSION.SDK_INT >= 31) (getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
        runCatching { ringtone?.stop() }; ringtone = null; runCatching { vib.cancel() }
        callWake?.let { runCatching { if (it.isHeld) it.release() } }; callWake = null
        if (!on) return@post
        // Wake the screen so the call shows.
        runCatching {
            @Suppress("DEPRECATION")
            callWake = (getSystemService(POWER_SERVICE) as android.os.PowerManager).newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "story:call").apply { acquire(50_000) }
        }
        val mode = am.ringerMode
        if (mode == android.media.AudioManager.RINGER_MODE_NORMAL) runCatching {
            ringtone = android.media.RingtoneManager.getRingtone(this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))?.apply {
                audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        }
        if (mode != android.media.AudioManager.RINGER_MODE_SILENT) runCatching {
            vib.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 900, 700), 0))
        }
    } }
    override fun callAudio(on: Boolean, speaker: Boolean) { ui.post {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        runCatching {
            if (on) {
                am.mode = android.media.AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    val want = if (speaker) android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    am.availableCommunicationDevices.firstOrNull { it.type == want }?.let { am.setCommunicationDevice(it) }
                } else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = speaker }
            } else {
                if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else @Suppress("DEPRECATION") { am.isSpeakerphoneOn = false }
                am.mode = android.media.AudioManager.MODE_NORMAL
            }
        }
    } }
    override fun callAsk(video: Boolean): String {
        val ok = android.content.pm.PackageManager.PERMISSION_GRANTED
        val need = listOfNotNull(android.Manifest.permission.RECORD_AUDIO, if (video) android.Manifest.permission.CAMERA else null).filter { checkSelfPermission(it) != ok }
        if (need.isEmpty()) {
            if (callFg(true, video)) return "ok"
            // Android only allows it while a screen of ours is showing: a see-through one opens for a moment and does it.
            ui.post { runCatching { startActivity(Intent(this, AskActivity::class.java).putExtra(AskActivity.EXTRA_CALL_FG, if (video) 2 else 1)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) } }
            return "wait"
        }
        ui.post { callAsking = true; askPermissions(need.toTypedArray()) }
        return "asking"
    }
    // ---- Missed activity (owner): a small red dot on the Station 3 button while there are unread messages or
    //      unseen statuses; the numbers show on Station 3's Messages / Updates buttons. The button itself never moves
    //      or changes - the dot is drawn on top of it (its overlay) and only shown / hidden. ----
    // Everything fast (owner): the server says a new version is live -> new screens are fetched now (windows you're
    // looking at refresh once hidden) and a new app version downloads now (it installs when the screen is off).
    override fun releaseNow() { ui.post { checkScreens(); Updater.check(this, force = true) } }
    private var activityOn = false
    private val activityDot by lazy {
        android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply { paint.color = Color.parseColor("#FFFF3B30") }
    }
    override fun setActivityDot(on: Boolean) { ui.post {
        getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("activity_dot", on).apply()
        showActivityDot(on)
    } }
    private fun showActivityDot(on: Boolean) {
        val v = bubble ?: return
        if (on == activityOn) return
        activityOn = on
        val d = (8 * dp).toInt(); val m = (6 * dp).toInt(); val s = (40 * dp).toInt()
        activityDot.setBounds(s - m - d, m, s - m, m + d)
        if (on) v.overlay.add(activityDot) else v.overlay.remove(activityDot)
    }
    override fun msgNotify(title: String, text: String) { ui.post {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@post
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel("story_messages") == null)
            nm.createNotificationChannel(android.app.NotificationChannel("story_messages", "Messages", android.app.NotificationManager.IMPORTANCE_HIGH))
        val n = android.app.Notification.Builder(this, "story_messages").setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text).setAutoCancel(true).setCategory(android.app.Notification.CATEGORY_MESSAGE).build()
        runCatching { nm.notify(("m" + title).hashCode(), n) }
    } }

    // ---- Trading: the REAL TradingView (TvView) inside the Pages window, laid over the page's chart box ----
    private var tvView: TvView? = null
    private fun tvViewNow(): TvView? {
        val p = pagesPane ?: return null
        tvView?.let { if (it.parent === p.frame) return it }
        tvView?.let { runCatching { (it.parent as? android.view.ViewGroup)?.removeView(it); it.web.destroy() } }
        val v = TvView(this) { sym -> evalPages("window.storyTvSymbol&&window.storyTvSymbol(" + org.json.JSONObject.quote(sym) + ")") }
        p.frame.addView(v, FrameLayout.LayoutParams(-1, -1))   // on top of the page; it only takes touches on the chart
        tvView = v
        return v
    }
    override fun tvPlace(json: String) { ui.post {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@post
        if (!o.optBoolean("show")) { tvView?.place(o); return@post }
        tvViewNow()?.place(o)
    } }
    override fun tvOpen(sym: String, interval: String) { ui.post { tvViewNow()?.open(sym, interval) } }

    // ---- The brightness slider: the phone's REAL brightness (no grey veil over STORY's pages). "system" = only while
    //      STORY's Pages are up (the Pages window's own brightness); "device" = the whole phone's brightness setting
    //      (Android asks once to let STORY change it; until then it works like "system"). -1 = leave it to the phone. ----
    private var pagesBright = -1f
    private fun applyPagesBright() {
        val p = pagesPane ?: return
        val want = if (p.shown && pagesBright >= 0f) pagesBright else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (p.lp.screenBrightness != want) { p.lp.screenBrightness = want; runCatching { p.wm.updateViewLayout(p.frame, p.lp) } }
    }
    override fun brightness(): String {
        val cr = contentResolver
        val v = runCatching { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(178)
        val auto = runCatching { android.provider.Settings.System.getInt(cr, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrDefault(0) == 1
        return org.json.JSONObject().put("v", Math.round(v.coerceIn(0, 255) * 100 / 255.0)).put("auto", auto)
            .put("canWrite", android.provider.Settings.System.canWrite(this)).toString()
    }
    override fun setBrightness(mode: String, value: Int) { ui.post {
        if (value < 0) { pagesBright = -1f; applyPagesBright(); return@post }
        val f = (value.coerceIn(0, 100) / 100f).coerceAtLeast(0.01f)
        if (mode == "device") {
            if (android.provider.Settings.System.canWrite(this)) {
                runCatching {
                    android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                    android.provider.Settings.System.putInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, Math.round(f * 255).coerceIn(1, 255))
                }
                pagesBright = -1f
            } else {
                pagesBright = f
                val prefs = getSharedPreferences("story", MODE_PRIVATE)
                if (!prefs.getBoolean("asked_write_settings", false)) {
                    prefs.edit().putBoolean("asked_write_settings", true).apply()
                    pagesPane?.takeIf { it.shown }?.let { hideOne(it) }; updateBubble()
                    runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
        } else pagesBright = f
        applyPagesBright()
    } }
    override fun notePrint(title: String, html: String) {
        runCatching { java.io.File(cacheDir, "note_print.html").writeText(html) }
        ui.post {
            if (!stepAsideForSystem()) return@post
            val i = Intent(this, NoteMediaActivity::class.java).putExtra(NoteMediaActivity.EXTRA_TITLE, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            runCatching { startActivity(i) }.onFailure { noteMediaDone("print", "error", "{}") }
        }
    }
    fun noteMediaDone(kind: String, status: String, json: String) { ui.post {
        comeBackFromSystem()
        noteResult(kind, status, json)
    } }
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
        val barW = (if (dashGeom) resources.displayMetrics.widthPixels else (barBgLp?.width ?: getSharedPreferences("story", MODE_PRIVATE).getInt("bar_w", (220 * dp).toInt()))) / dp
        val barH = (40 * dp).toInt() / dp
        val top = statusBarHeightPx() / dp
        return "window.__pfBarW=$barW;window.__pfBarH=$barH;window.__pfTop=$top;window.__pfDash=$dashGeom;window.storyProfileGeom&&window.storyProfileGeom();"
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

    /** Station 3's window (Messages, Profile, Create, Call history) works the same on the lock screen (owner): while
     *  the phone is locked it sits in STORY's top layer (the normal layer is under the lock screen); unlocked it goes
     *  back to the normal layer (where the keyboard shows above it). The gap, strip, bar, Station 3, dial pad and the
     *  button are put back on top of it, in their usual order. */
    private fun profileLayer(top: Boolean) {
        val p = profilePane ?: return
        val want = if (top) layerWm else wm
        if (p.wm === want) return
        runCatching { p.wm.removeView(p.frame) }
        p.lp.type = if (top) layerType else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY; p.wm = want
        runCatching { want.addView(p.frame, p.lp) }
        if (top) {
            profileGapPane?.let { g -> runCatching { g.wm.removeView(g.frame); g.wm.addView(g.frame, g.lp) } }
            profileStrip?.let { v -> profileStripLp?.let { lp -> runCatching { (profileStripWm ?: layerWm).removeView(v); (profileStripWm ?: layerWm).addView(v, lp) } } }
            barBg?.let { v -> barBgLp?.let { lp -> runCatching { (barBgWm ?: wm).removeView(v); (barBgWm ?: wm).addView(v, lp) } } }
            for (q in listOfNotNull(s3Pane, dialPane)) runCatching { q.wm.removeView(q.frame); q.wm.addView(q.frame, q.lp) }
            raiseBubble(); addBar()
        }
    }

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
    // ---- Dial pad > New contact: the phone's own new-contact screen (number filled in). The dial pad and the Pages
    //      step aside while it's up; STORY comes back when you save or go back. ----
    private var contactDialWasOpen = false
    override fun newContact(number: String) { ui.post {
        contactDialWasOpen = dialPane?.shown == true
        dialPane?.takeIf { it.shown }?.let { hideOne(it) }
        if (!stepAsideForSystem()) return@post
        runCatching { startActivity(Intent(this, ContactAddActivity::class.java).putExtra(ContactAddActivity.EXTRA_NUMBER, number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { contactDone(false) }
    } }
    // ---- The Dashboard's Controls / Media output are the phone's real ones (PhoneControls). What Android lets
    //      Lockedintool change is changed right away; the rest opens the phone's own switch (SystemScreenActivity) with
    //      the Pages stepping aside and coming straight back. ----
    override fun controlToggle(id: String) { ui.post {
        val i = PhoneControls.toggle(this, id)
        if (i != null) openSystemScreen(i) else controlsChanged()
    } }
    override fun outputSwitch() = outputSwitchTo("")
    /** Another output picked (owner: tapping 'This Phone' must really switch): the phone's own switcher, tapped for you. */
    override fun outputSwitchTo(name: String) { ui.post {
        openStepAside(Intent(this, OutputSwitchActivity::class.java).putStringArrayListExtra(OutputSwitchActivity.EXTRA_NAMES, ArrayList(PhoneControls.outputNames(this, name))))
    } }
    override fun separateSound() { ui.post { PhoneControls.separateSound(this)?.let { openSystemScreen(it) } } }
    /** Tells the Pages the controls / outputs as they are now (after a change, the flashlight switched elsewhere...). */
    fun controlsChanged() { ui.post {
        val js = "window.storyControlsChanged&&window.storyControlsChanged(" + PhoneControls.state(this) + "," + PhoneControls.outputs(this) + ")"
        pagesPane?.web?.evaluateJavascript(js, null)
        liveWidgets.values.forEach { runCatching { it.web.evaluateJavascript(js, null) } }   // the Dashboard widget's Controls too
    } }
    private fun openSystemScreen(i: Intent) = openStepAside(Intent(this, SystemScreenActivity::class.java).putExtra(SystemScreenActivity.EXTRA_INTENT, i))
    /** Starts one of Lockedintool's see-through screens for the phone's own screen; the Pages step aside meanwhile. */
    private fun openStepAside(a: Intent) {
        if (pickerHid.isNotEmpty()) return
        // Opened from the Pages themselves (not Station 3's window): Station 3 steps away too while the phone's screen shows.
        sysFromPages = pagesPane?.shown == true && profilePane?.shown != true
        if (!stepAsideForSystem()) { sysFromPages = false; return }
        runCatching { startActivity(a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { comeBackFromSystem() }
    }
    // The outputs again a moment later too: the phone takes a little while to move the sound.
    fun systemScreenDone() { ui.post { comeBackFromSystem(); controlsChanged(); ui.postDelayed({ controlsChanged() }, 1000) } }
    fun contactDone(saved: Boolean) { ui.post {
        comeBackFromSystem()
        if (contactDialWasOpen) { contactDialWasOpen = false; dialPane?.takeIf { !it.shown && s3Pane?.shown == true }?.let { setShown(it, true) } }
        if (saved) showToast("Contact saved")
    } }

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
            val pad = if (dashGeom) 0 else 16  // room for Station 3's shadow (none on the Dashboard: no shadow, and the Dashboard is right above)
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
