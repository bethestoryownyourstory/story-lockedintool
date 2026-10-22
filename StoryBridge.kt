package com.story.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** What the overlay panel (which shows the STORY screens) can ask the phone to do. JS name: window.StoryNative */
class StoryBridge(private val ctx: Context, private val host: Host, private val station3: Boolean = false, private val dial: Boolean = false, private val profile: Boolean = false, private val lock: Boolean = false,
                  /** A home-screen widget's out-of-sight page: reads only; its answers go back to it. */
                  private val widget: Boolean = false, private val evalTo: ((String) -> Unit)? = null,
                  private val onTyping: ((Boolean) -> Unit)? = null) {

    interface Host {
        fun hidePanel()
        fun closeStation3()
        fun setStation3Size(w: Int, h: Int)
        fun toggleDialpad()
        fun closeDialpad()
        fun setDialSize(w: Int, h: Int)
        fun setBarWidth(w: Double)
        fun setTyping(on: Boolean)
        fun showToast(msg: String)
        fun toggleProfile()
        fun toggleMessages()
        fun setLockDialSize(w: Int, h: Int)
        fun callNumber(number: String)
        fun callNumberVideo(number: String)
        fun appPageOpen(pkg: String)
        fun openPages(page: String)
        fun newContact(number: String)
        fun setQuickAccess(json: String)
        fun toggleCreate()
        fun toggleCalls()
        fun toggleUpdates()
        fun pagesAction(action: String)
        fun hidePanelFor(pkg: String?, what: String)
        fun dashShown(on: Boolean)
        fun controlToggle(id: String)
        fun outputSwitch()
        fun getCalls(): String
        fun createShutter()
        fun createVideo(on: Boolean)
        fun createUseFromPhone(id: Long, video: Boolean)
        fun createDiscard()
        fun createSwitchCamera()
        fun createKeep(): String
        fun liveMode(on: Boolean)
        fun closeProfile()
        fun setTheme(t: String)
        fun pickProfilePhoto()
        fun removeProfilePhoto()
        fun requestPhotoAccess()
        fun chatPick(kind: String)
        fun confirmIdentity(title: String)
        fun secUnlock()
        fun walletButtonOk(on: Boolean)
        fun wipeAll()
        fun chatOpenFile(url: String, key: String, iv: String, name: String, mime: String, fromProfile: Boolean)
        fun useProfilePhoto(id: Long)
        fun profileTyping(on: Boolean)
        fun noteAsk(kind: String)
        fun noteResult(kind: String, status: String, json: String)
        fun noteSavePdf(title: String, pages: List<String>)
        fun noteShareTo(pkg: String, cls: String, title: String, text: String, files: List<java.io.File>)
        fun notePrint(title: String, html: String)
        fun evalPages(js: String)
        fun tvPlace(json: String)
        fun tvOpen(sym: String, interval: String)
        fun brightness(): String
        fun setBrightness(mode: String, value: Int)
        fun callUi(on: Boolean)
        fun callRing(on: Boolean, name: String, video: Boolean)
        fun callAudio(on: Boolean, speaker: Boolean)
        fun callAsk(video: Boolean): String
        fun msgNotify(title: String, text: String)
        fun setActivityDot(on: Boolean)
        fun releaseNow()
    }

    /** The phone's real launchable apps, with their real icons -- kept ready, so the Apps area opens instantly. */
    @JavascriptInterface
    fun getApps(): String = appsCache ?: buildApps(ctx).also { appsCache = it }

    /** Dashboard music card (owner): any music player on the phone (MusicPlayer.kt). */
    @JavascriptInterface fun musicPlayers(): String = MusicPlayer.players(ctx)
    @JavascriptInterface fun musicState(): String = MusicPlayer.state(ctx)
    @JavascriptInterface fun musicChoose(pkg: String) { MusicPlayer.choose(ctx, pkg) }
    @JavascriptInterface fun musicCmd(cmd: String) { MusicPlayer.command(ctx, cmd) }
    /** A window's page hit a problem (an error, something that didn't work): sent to Lockedintool's server, scrubbed of anything personal. */
    @JavascriptInterface fun problem(json: String) {
        runCatching { val o = JSONObject(json); ProblemLog.record(ctx, o.optString("kind", "js"), o.optString("msg"), o.optString("place")) }
    }
    /** The card's button for the card's own player; the answer (did the player react?) comes back to this page. */
    @JavascriptInterface fun musicCmdFor(cmd: String, pkg: String) {
        MusicPlayer.commandFor(ctx, cmd, pkg) { res -> val js = "window.storyMusicResult&&window.storyMusicResult($res)"; evalTo?.invoke(js) ?: host.evalPages(js) }
    }
    /** Hold the music card: opens the music player app (STORY steps aside like opening any app). */
    @JavascriptInterface fun musicOpen() { val p = MusicPlayer.appToOpen(ctx); if (p.isNotEmpty()) launchApp(p) }

    /** Opens the real app exactly as the phone's own launcher would, and gets STORY out of the way. */
    @JavascriptInterface
    fun launchApp(pkg: String) {
        if (SystemMode.refuses(ctx)) return
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        openFirst(sequenceOf(i))
    }

    /** Opens the first of these the phone has. The app opens UNDER the Pages, and the Pages step aside the moment it is
     *  in front (owner: you saw the background changing while it took long) - the same on every phone. */
    private fun openFirst(tries: Sequence<Intent>, what: String = "an app"): Boolean {
        for (i in tries) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(i) }.isSuccess) {
                host.hidePanelFor(i.component?.packageName ?: runCatching { i.resolveActivity(ctx.packageManager)?.packageName }.getOrNull(), what)
                return true
            }
        }
        return false
    }

    /** Information page: this STORY app's version ("1.0 (build 233)"). */
    @JavascriptInterface
    fun appVersion(): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        (pi.versionName ?: "") + " (build " + code + ")"
    }.getOrDefault("")

    /** Dashboard / Apps > Camera and Gallery: the phone's own Camera app and Gallery app (owner: the real ones).
     *  Camera on the lock screen opens the phone's lock-screen camera, like the phone's own shortcut. */
    @JavascriptInterface
    fun openCamera() {
        val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        val i = Intent(if (locked) android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE else android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
        if (!openFirst(sequenceOf(i, Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)), "CAMERA")) host.showToast("No camera app on this phone")
    }
    /** Dashboard > ALARM (owner): the phone's own alarms (its Clock app); STORY steps aside like opening any app. */
    @JavascriptInterface
    fun openAlarms() {
        // Not every Clock app answers "show alarms" (some phones' own clocks don't): then the phone's Clock app itself -
        // the known ones by name, else any app called Clock / Alarm - and last the "set an alarm" screen. Each is only
        // looked up if the one before isn't there (looking through every app first made it slow).
        val pm = ctx.packageManager
        val known = listOf("com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock",
            "com.oneplus.deskclock", "com.coloros.alarmclock", "com.oplus.alarmclock", "com.huawei.deskclock", "com.hihonor.deskclock",
            "com.miui.clock", "com.transsion.deskclock", "com.motorola.timeweatherwidget", "com.lge.clock", "com.sonymobile.alarmclock")
        val tries = sequence {
            val show = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
            if (runCatching { show.resolveActivity(pm) }.getOrNull() != null) yield(show)
            for (k in known) pm.getLaunchIntentForPackage(k)?.let { yield(it) }
            runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .firstOrNull { r -> r.loadLabel(pm).toString().let { it.equals("Clock", true) || it.contains("Alarm", true) } }?.activityInfo?.packageName
            }.getOrNull()?.let { pm.getLaunchIntentForPackage(it) }?.let { yield(it) }
            yield(Intent(android.provider.AlarmClock.ACTION_SET_ALARM))
        }
        if (!openFirst(tries, "ALARM")) { host.showToast("No clock app on this phone"); ProblemLog.record(ctx, "alarm", "no clock app found", "dashboard") }
    }
    @JavascriptInterface
    fun openGallery() {
        val tries = sequence {
            yield(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY))
            yield(Intent(Intent.ACTION_VIEW).setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*"))
            ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")?.let { yield(it) }
        }
        if (!openFirst(tries, "GALLERY")) host.showToast("No gallery app on this phone")
    }

    /** An app made into a page: tapping its card / Open opens the REAL app (STORY steps aside), with a thin strip on the
     *  left edge to swipe your pages back. Landing on the page never opens it (owner). A live widget's card opens it too. */
    @JavascriptInterface
    fun appPageOpen(pkg: String) { if (pages || widget) host.appPageOpen(pkg) }

    /** A live home screen widget's '‹ Dashboard': STORY opens full screen (owner). */
    @JavascriptInterface
    fun widgetOpenPages(page: String) { if (widget) host.openPages(page) }

    @JavascriptInterface
    fun uninstallApp(pkg: String) {
        host.hidePanel()
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Money transfer: your own bank's approval page (Stitch) opens in the phone's browser; STORY steps aside. */
    @JavascriptInterface
    fun openUrl(url: String) {
        if (station3 || !url.startsWith("https://")) return
        host.hidePanel()
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { host.showToast("No browser on this phone") }
    }

    /** "Apps" in STORY's Apps | Pages switch: back to the app you were on (or the home screen), untouched. */
    @JavascriptInterface
    fun showApps() { host.hidePanel() }

    /** The Pages report when a text box is being typed in (so the keyboard can show above them). */
    @JavascriptInterface
    fun setTyping(on: Boolean) { if (widget) onTyping?.invoke(on) else if (profile) host.profileTyping(on) else if (!station3) host.setTyping(on) }

    /** STORY's one message bubble ("... - coming soon"), the same on the Pages, Station 3 and over apps. */
    @JavascriptInterface
    fun toast(msg: String) { host.showToast(msg) }

    /** STORY's System theme (Settings): "black" (default) or "white". Only black and white swap. */
    @JavascriptInterface
    fun getTheme(): String = StoryTheme.get(ctx)

    @JavascriptInterface
    fun setTheme(t: String) { host.setTheme(if (t == "white") "white" else "black") }

    /** Profile picture: the saved one (data URL, "" = none); Edit profile > Change photo / Remove photo. */
    @JavascriptInterface
    fun getProfilePhoto(): String = runCatching { ProfilePhotoActivity.photoUrl(ctx) }.getOrDefault("")

    /** STORY's own photo grid (instant, no app switch): "full" / "partial" / "none", the pictures (newest first),
     *  asking once for access, and using the one you tap. Thumbnails load from ProfilePhotoActivity.PHOTO_BASE. */
    @JavascriptInterface
    fun photoAccess(): String = ProfilePhotoActivity.photoAccess(ctx)

    @JavascriptInterface
    fun listPhotos(): String = ProfilePhotoActivity.listPhotos(ctx)

    @JavascriptInterface
    fun requestPhotoAccess() { if (profile || pages) host.requestPhotoAccess() }

    /** Chats, groups and community posts (owner): pick a document ("doc") or an audio file ("audio") with the phone's
     *  own picker - answer: window.storyChatPicked(json) in the Pages and Profile windows. */
    /** The phone's own fingerprint / face / PIN check (Delete account) - answer: window.storyIdentity('ok'|'fail'|'nolock'). */
    /** The account was deleted: everything Lockedintool keeps on this phone goes (only after the server said so). */
    @JavascriptInterface
    fun wipeAll(sure: String) { if ((profile || pages) && sure == "ACCOUNT-DELETED") host.wipeAll() }

    /** Lockedintool as your system (owner): on from the Subscription page (signed in); off ONLY by logging out or deleting
     *  your account - the page passes "LOGGED-OUT" then. */
    @JavascriptInterface
    fun systemModeState(): String = (if (SystemMode.on(ctx)) "on" else "off") + "|" + (if (StoryAccessibilityService.instance != null) "ready" else "noaccess")
    /** Only use widgets (Settings): on from the Pages; off (or on) from the Lockedintool app's screen. */
    @JavascriptInterface
    fun widgetsOnlyState(): String = if (WidgetMode.on(ctx)) "on" else "off"
    @JavascriptInterface
    fun widgetsOnlySet(on: Boolean): Boolean = if (station3 || dial || widget) false else WidgetMode.set(ctx, on)
    @JavascriptInterface
    fun systemModeSet(on: Boolean, sure: String) {
        if (station3 || dial) return
        if (on) { if (StoryAccessibilityService.instance == null || WidgetMode.on(ctx)) return; SystemMode.set(ctx, true) }
        else if (sure == "LOGGED-OUT") SystemMode.set(ctx, false) else return
        OverlayService.instance?.systemModeChanged()
    }

    @JavascriptInterface
    fun confirmIdentity(title: String) { if (profile || pages) host.confirmIdentity(title) }

    /** Settings > SECURITY (owner): {mode: phone | lit, secure: the phone has a screen lock, open: Lockedintool is unlocked
     *  right now}. Set only from the Pages (Settings); Unlock from the Pages, Profile's window or a live widget. */
    @JavascriptInterface
    fun securityState(): String = JSONObject().put("mode", if (Security.lit(ctx)) "lit" else "phone").put("secure", Security.phoneSecure(ctx))
        .put("open", OverlayService.instance?.secIsOpen() ?: true).toString()
    @JavascriptInterface
    fun securitySet(mode: String): Boolean = if (!pages) false else Security.set(ctx, mode == "lit")
    @JavascriptInterface
    fun secUnlock() { if (pages || profile || widget) host.secUnlock() }
    /** The lock screen's Wallet page button only shows when the Wallet page can open (signed in, a plan with the Pages,
     *  Wallet not removed) - the Pages tell it. */
    @JavascriptInterface
    fun walletButtonOk(on: Boolean) { if (pages) host.walletButtonOk(on) }

    @JavascriptInterface
    fun chatPick(kind: String) { if ((profile || pages) && (kind == "doc" || kind == "audio")) host.chatPick(kind) }

    /** A file someone sent: downloaded, unlocked on the phone and opened with the phone's own app. */
    @JavascriptInterface
    fun chatOpenFile(url: String, key: String, iv: String, name: String, mime: String) { if (profile || pages) host.chatOpenFile(url, key, iv, name, mime, profile) }

    @JavascriptInterface
    fun useProfilePhoto(id: String) { if (profile) id.toLongOrNull()?.let { host.useProfilePhoto(it) } }

    @JavascriptInterface
    fun pickProfilePhoto() { if (profile) host.pickProfilePhoto() }

    @JavascriptInterface
    fun removeProfilePhoto() { if (profile) host.removeProfilePhoto() }

    // ---- Trading (the Pages only): real broker servers, talked to by the phone itself (no browser limits), and the
    //      sign-in tokens kept in STORY's private storage on the phone (never a password). ----
    /** Answer: window.storyHttp(id, status, body). status 0 = no connection. */
    @JavascriptInterface
    fun http(id: String, method: String, url: String, headersJson: String, body: String) {
        if (station3 || profile) return
        if (!url.startsWith("https://")) { answerHttp(id, 0, ""); return }
        Thread {
            var code = 0; var text = ""
            runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.requestMethod = method.uppercase()
                c.connectTimeout = 15000; c.readTimeout = 20000
                c.setRequestProperty("Accept", "application/json")
                runCatching { val h = JSONObject(headersJson); h.keys().forEach { k -> c.setRequestProperty(k, h.getString(k)) } }
                if (body.isNotEmpty() && method.uppercase() != "GET") {
                    c.doOutput = true
                    if (c.getRequestProperty("Content-Type") == null) c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                code = c.responseCode
                text = (if (code in 200..399) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                c.disconnect()
            }
            answerHttp(id, code, text)
        }.start()
    }
    private fun answerHttp(id: String, code: Int, text: String) {
        val js = "window.storyHttp&&window.storyHttp(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")"
        evalTo?.invoke(js) ?: host.evalPages(js)
    }

    @JavascriptInterface
    fun secretSet(key: String, value: String) {
        if (station3 || profile) return
        ctx.getSharedPreferences("trade_secrets", Context.MODE_PRIVATE).edit().apply { if (value.isEmpty()) remove(key) else putString(key, value) }.apply()
    }

    @JavascriptInterface
    fun secretGet(key: String): String =
        if (station3 || profile) "" else ctx.getSharedPreferences("trade_secrets", Context.MODE_PRIVATE).getString(key, "") ?: ""

    /** Opens an app on the phone by its package (e.g. MetaTrader 5); false if it isn't installed. */
    @JavascriptInterface
    fun openPackage(pkg: String): Boolean {
        if (SystemMode.refuses(ctx)) return true
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); host.hidePanel(); ctx.startActivity(i); return true
    }

    /** Money / Wallet: every payment STORY read from your bank / payment apps' notifications (MoneyLog, JSON, newest first). */
    @JavascriptInterface
    fun moneyList(): String = if (pages) MoneyLog.list(ctx) else "[]"

    /** Whether STORY may read notifications (needed to see payments). */
    @JavascriptInterface
    fun moneyAccess(): Boolean = runCatching {
        (Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: "").contains(ctx.packageName + "/")
    }.getOrDefault(false)

    /** The phone's notifications right now (STORY's Notifications Panel). Pages only. */
    @JavascriptInterface fun notifList(): String = if (pages) StoryMediaListener.current(ctx) else "[]"
    /** Any app's real icon (also apps with no icon in the app list, e.g. system ones), as a base64 PNG ('' = none). */
    @JavascriptInterface fun appIcon(pkg: String): String = runCatching {
        val d = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 96, 96); d.draw(Canvas(bmp))
        val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }.getOrDefault("")
    /** Opens a notification the way tapping it in the phone's own panel does (its app, at that message). */
    @JavascriptInterface fun notifOpen(key: String) {
        if (!pages) return
        val sbn = runCatching { StoryMediaListener.instance?.activeNotifications?.firstOrNull { it.key == key } }.getOrNull() ?: return
        val pi = sbn.notification?.contentIntent
        val ok = pi != null && runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val o = android.app.ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(ctx, 0, null, null, null, null, o.toBundle())
            } else pi.send()
        }.isSuccess
        if (ok) host.hidePanelFor(sbn.packageName, "a notification")
        else ctx.packageManager.getLaunchIntentForPackage(sbn.packageName)?.let { openFirst(sequenceOf(it), "a notification") }
    }

    /** Opens Android's own notification-access switch for STORY. */
    @JavascriptInterface
    fun moneyAccessOpen() {
        if (!pages) return
        val cn = android.content.ComponentName(ctx, StoryMediaListener::class.java).flattenToString()
        val i = if (android.os.Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn)
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); host.hidePanel()
        runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Wallet > Tap to Pay: opens the phone's own tap-to-pay app (the one set under Contactless payments) so you pay
     *  for real; "none" if the phone has no tap-to-pay app set (then its Contactless payments screen opens), "nonfc"
     *  if the phone has no NFC. (Kept for older screens - Tap to Pay now stays in Lockedintool, nfcState.) */
    /** Tap to Pay (owner: no other app opens): is there NFC, is it on, and which wallet pays when you tap -
     *  "nonfc" | "off" | "on|<package>|<name>" (package / name empty when the phone doesn't say). */
    @JavascriptInterface
    fun nfcState(): String {
        if (!pages) return "nonfc"
        val nfc = android.nfc.NfcAdapter.getDefaultAdapter(ctx) ?: return "nonfc"
        if (!nfc.isEnabled) return "off"
        val comp = runCatching { Settings.Secure.getString(ctx.contentResolver, "nfc_payment_default_component") }.getOrNull()
        val pkg = comp?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }.orEmpty()
        val name = if (pkg.isEmpty()) "" else runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
        return "on|$pkg|$name"
    }
    /** Only to switch NFC on (Android doesn't let an app do it itself). */
    @JavascriptInterface
    fun nfcSettings() {
        if (!pages) return
        host.hidePanel()
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @JavascriptInterface
    fun payApp(): String {
        if (!pages) return ""
        val nfc = android.nfc.NfcAdapter.getDefaultAdapter(ctx) ?: return "nonfc"
        val comp = runCatching { Settings.Secure.getString(ctx.contentResolver, "nfc_payment_default_component") }.getOrNull()
        val pkg = comp?.let { android.content.ComponentName.unflattenFromString(it)?.packageName }
        val launch = pkg?.let { ctx.packageManager.getLaunchIntentForPackage(it) }
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); host.hidePanel(); ctx.startActivity(launch)
            return if (nfc.isEnabled) "ok" else "nfcoff"
        }
        val i = Intent(if (nfc.isEnabled) Settings.ACTION_NFC_PAYMENT_SETTINGS else Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        host.hidePanel(); runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        return if (nfc.isEnabled) "none" else "nfcoff"
    }

    /** Trading: the real TradingView (TvView) - which version of it this app has, where the page's chart box is,
     *  and which pair / timeframe to show. Pages only. */
    @JavascriptInterface fun tvVersion(): String = if (pages) "1" else ""
    @JavascriptInterface fun tvPlace(json: String) { if (pages) host.tvPlace(json) }
    @JavascriptInterface fun tvOpen(sym: String, interval: String) { if (pages) host.tvOpen(sym, interval) }

    /** The brightness slider sets the phone's REAL screen brightness (no grey veil over STORY). brightness() = the
     *  phone's brightness now (0-100), whether STORY may change the whole phone's, and whether auto is on. */
    @JavascriptInterface fun brightness(): String = if (pages || widget) host.brightness() else "{}"
    @JavascriptInterface fun setBrightness(mode: String, value: Int) { if (pages || widget) host.setBrightness(mode, value) }  // the Dashboard widget's slider too

    /** Messages + calls (Profile's window, where STORY's message connection runs): show the call screen even when
     *  Station 3 is closed, ring / vibrate for an incoming call, the phone's call audio (earpiece / speaker), the
     *  microphone (+ camera) question, and a notification for a new message while Messages isn't on screen. */
    @JavascriptInterface fun callUi(on: Boolean) { if (profile) host.callUi(on) }
    @JavascriptInterface fun callRing(on: Boolean, name: String, video: Boolean) { if (profile) host.callRing(on, name, video) }
    @JavascriptInterface fun callAudio(on: Boolean, speaker: Boolean) { if (profile) host.callAudio(on, speaker) }
    @JavascriptInterface fun callAsk(video: Boolean): String = if (profile) host.callAsk(video) else "ok"
    @JavascriptInterface fun msgNotify(title: String, text: String) { if (profile) host.msgNotify(title, text) }
    /** Missed activity (unread messages / unseen statuses): a dot on Station 3's button (owner). */
    @JavascriptInterface fun setActivityDot(on: Boolean) { if (profile) host.setActivityDot(on) }
    /** The server says a new version just went live: look for it now (screens + app). */
    @JavascriptInterface fun releaseNow() { if (profile) host.releaseNow() }

    /** Wallet: reads a card photo on the phone (CardScan); answers window.storyCardScan(id, json). */
    @JavascriptInterface
    fun cardScan(id: String, dataUrl: String) {
        if (!pages) return
        CardScan.scan(dataUrl) { json -> host.evalPages("window.storyCardScan&&window.storyCardScan(" + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")") }
    }

    /** Drawings: saves each picture (PNG data URLs, JSON list) straight into the phone's photos (Pictures/STORY).
     *  Returns how many were saved. */
    @JavascriptInterface
    fun saveImages(json: String, title: String): Int {
        if (station3 || profile) return 0
        val list = runCatching { JSONArray(json) }.getOrNull() ?: return 0
        val base = title.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(40).ifBlank { "Drawing" }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        var saved = 0
        for (i in 0 until list.length()) {
            val bytes = runCatching { Base64.decode(list.getString(i).substringAfter("base64,"), Base64.DEFAULT) }.getOrNull() ?: continue
            val name = "$base $stamp" + (if (list.length() > 1) " (${i + 1})" else "") + ".png"
            val ok = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/Lockedintool")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
                    values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    true
                } else {
                    val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "Lockedintool").apply { mkdirs() }
                    val f = java.io.File(dir, name); f.writeBytes(bytes)
                    android.media.MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf("image/png"), null)
                    true
                }
            }.getOrDefault(false)
            if (ok) saved++
        }
        return saved
    }

    // ---- Notes, all inside STORY (owner: real actions, never leaving STORY). The Pages only. ----
    private val pages get() = !station3 && !profile && !widget

    /** Asks Android once (its own question) for what a note needs: "photos", "audio" or "camera". */
    @JavascriptInterface
    fun noteAsk(kind: String) { if (pages) host.noteAsk(kind) }

    @JavascriptInterface
    fun noteAudioAccess(): Boolean = NoteTools.audioAccess(ctx)

    @JavascriptInterface
    fun noteListAudio(): String = NoteTools.listAudio(ctx)

    @JavascriptInterface
    fun noteCameraAccess(): Boolean = ctx.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** A picture tapped in STORY's grid: copied into the note (answer: window.storyNoteMedia('picture', ...)). */
    @JavascriptInterface
    fun noteUsePhoto(id: String) {
        if (!pages) return
        val n = id.toLongOrNull() ?: return
        Thread { val f = NoteTools.usePhoto(ctx, n); host.noteResult("picture", if (f != null) "ok" else "error", JSONObject().put("file", f ?: "").toString()) }.start()
    }

    /** An audio file tapped in STORY's list: copied into the note (answer: window.storyNoteMedia('audio', ...)). */
    @JavascriptInterface
    fun noteUseAudio(id: String) {
        if (!pages) return
        val n = id.toLongOrNull() ?: return
        Thread { val j = NoteTools.useAudio(ctx, n); host.noteResult("audio", if (j != null) "ok" else "error", (j ?: JSONObject()).toString()) }.start()
    }

    /** A photo from STORY's own camera (JPEG data URL): saved as the note's picture; returns its file name ("" = failed). */
    @JavascriptInterface
    fun noteSavePhoto(dataUrl: String): String = if (pages) NoteTools.savePhotoData(ctx, dataUrl) ?: "" else ""

    /** Save As > Text file: straight into the phone's Documents/STORY. Returns the folder ("" = failed). */
    @JavascriptInterface
    fun noteSaveText(title: String, text: String): String =
        if (pages) NoteTools.saveToDocuments(ctx, NoteTools.safeName(title) + ".txt", "text/plain", text.toByteArray()) ?: "" else ""

    /** PDF / Save As > PDF: STORY makes the PDF itself (pages = JSON list of laid-out page HTML) and saves it in
     *  Documents/STORY (answer: window.storyNoteMedia('pdf', ...)). */
    @JavascriptInterface
    fun noteSavePdf(title: String, pagesJson: String) {
        if (!pages) return
        val list = runCatching { JSONArray(pagesJson) }.getOrNull() ?: return
        host.noteSavePdf(title, (0 until list.length()).map { list.getString(it) })
    }

    /** STORY's own list of apps to share to ("text", "image" or "images"), with their real icons. */
    @JavascriptInterface
    fun noteShareTargets(type: String): String = if (pages) runCatching { NoteTools.shareTargets(ctx, type) }.getOrDefault("[]") else "[]"

    /** Opens the app you picked in STORY's share list with the note (text + pictures as PNG data URLs). */
    @JavascriptInterface
    fun noteShareTo(pkg: String, cls: String, title: String, text: String, imagesJson: String) {
        if (!pages) return
        val dir = java.io.File(ctx.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val files = ArrayList<java.io.File>()
        val list = runCatching { JSONArray(imagesJson) }.getOrNull() ?: JSONArray()
        val base = title.replace(Regex("[^A-Za-z0-9 _-]"), " ").trim().take(40).ifBlank { "Drawing" }
        for (i in 0 until list.length()) {
            val bytes = runCatching { Base64.decode(list.getString(i).substringAfter("base64,"), Base64.DEFAULT) }.getOrNull() ?: continue
            val f = java.io.File(dir, base + (if (list.length() > 1) " ${i + 1}" else "") + ".png")
            if (runCatching { f.writeBytes(bytes) }.isSuccess) files.add(f)
        }
        host.noteShareTo(pkg, cls, title, text, files)
    }

    /** Print (paper): the phone's own print screen - the one thing that can't happen inside STORY. */
    @JavascriptInterface
    fun notePrint(title: String, html: String) { if (pages) host.notePrint(title, html) }

    @JavascriptInterface
    fun openRecents() {
        host.hidePanel()
        StoryAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    /** From Station 3's own window this is its corner arrow: the only thing that closes Station 3. */
    @JavascriptInterface
    fun closePanel() { if (station3) host.closeStation3() else host.hidePanel() }

    /** Station 3 tells us how much of the corner it really covers, so its window is no bigger than that. */
    @JavascriptInterface
    fun setStation3Size(w: Int, h: Int) { if (station3 && !dial) host.setStation3Size(w, h) }

    /** Station 3's dial pad button: show / hide the dial pad's own window (it never moves Station 3). */
    @JavascriptInterface
    fun toggleDialpad() { if (station3) host.toggleDialpad() }

    /** Station 3's profile button: Profile opens attached to Station 3 (tap again to close it). */
    @JavascriptInterface
    fun toggleProfile() { if (station3 || profile) host.toggleProfile() }   // Profile's window: your own profile from a search

    @JavascriptInterface
    fun closeProfile() { if (station3) host.closeProfile() }

    /** Station 3's messages button: Messages opens in the same window as Profile, attached to Station 3. */
    @JavascriptInterface
    fun toggleMessages() { if (station3) host.toggleMessages() }

    /** Station 3's + (Create & post): Create opens in the same window, the real camera underneath it. */
    @JavascriptInterface
    fun toggleCreate() { if (station3 || profile) host.toggleCreate() }

    /** Station 3's call history button: Call history in the same window; its list (every call made from STORY). */
    @JavascriptInterface
    fun toggleCalls() { if (station3) host.toggleCalls() }

    /** Station 3's Updates button: Updates (statuses, profiles, search) in the same window. */
    @JavascriptInterface
    fun toggleUpdates() { if (station3 || profile) host.toggleUpdates() }

    /** Station 3's Rows / Page Styles buttons (on the Pages): do it on the Pages. */
    @JavascriptInterface
    fun pagesAction(action: String) { if (station3) host.pagesAction(action) }
    /** The Pages say whether the Dashboard is on screen (Station 3 stays open at its bottom - Settings > QUICK ACCESS). */
    @JavascriptInterface
    fun dashShown(on: Boolean) { if (!station3 && !widget) host.dashShown(on) }

    /** The Dashboard's Controls are the phone's real ones (PhoneControls): their state now, and changing one. */
    @JavascriptInterface
    fun controlsState(): String { PhoneControls.watch(ctx); return PhoneControls.state(ctx).toString() }
    @JavascriptInterface
    fun controlToggle(id: String) { if (pages || widget) host.controlToggle(id) }
    /** Media output: the phone's real outputs and where media plays; another output = the phone's own switcher. */
    @JavascriptInterface
    fun audioOutputs(): String = PhoneControls.outputs(ctx).toString()
    @JavascriptInterface
    fun outputSwitch() { if (pages || widget) host.outputSwitch() }

    @JavascriptInterface
    fun getCalls(): String = if (profile) host.getCalls() else "[]"

    /** Create: shutter (picture), video on / off, + (from the phone), throw away, ✓ keep as a post. */
    @JavascriptInterface
    fun createShutter() { if (profile) host.createShutter() }

    @JavascriptInterface
    fun createVideo(on: Boolean) { if (profile) host.createVideo(on) }

    @JavascriptInterface
    fun listMedia(): String = ProfilePhotoActivity.listMedia(ctx)

    @JavascriptInterface
    fun mediaAccess(): String = ProfilePhotoActivity.mediaAccess(ctx)

    @JavascriptInterface
    fun createUseFromPhone(id: String, video: Boolean) { if (profile) id.toLongOrNull()?.let { host.createUseFromPhone(it, video) } }

    @JavascriptInterface
    fun createDiscard() { if (profile) host.createDiscard() }

    @JavascriptInterface
    fun createSwitchCamera() { if (profile) host.createSwitchCamera() }

    /** Returns "<postId>:<photo|video>" ("" if nothing was made). */
    @JavascriptInterface
    fun createKeep(): String = if (profile) host.createKeep() else ""
    /** Live (owner): Create's camera screen steps away while you're live (the live picture comes from this window). */
    @JavascriptInterface
    fun liveMode(on: Boolean) { if (profile) host.liveMode(on) }

    /** The X beside the dial pad. */
    @JavascriptInterface
    fun closeDialpad() { if (station3) host.closeDialpad() }

    /** Station 3's page reports how wide its white bar is; the app draws that white itself, pixel-exact with the button. */
    @JavascriptInterface
    fun setBarWidth(w: Double) { if (station3 && !dial) host.setBarWidth(w) }

    /** The dial pad page reports its size once, so its window fits it before it's ever shown. */
    @JavascriptInterface
    fun setDialSize(w: Int, h: Int) { if (lock) host.setLockDialSize(w, h) else if (dial) host.setDialSize(w, h) }

    /** The dial pad's call button: calls straight away (even on the lock screen). From Station 3's dial pad, the dial
     *  pad and Station 3 close the moment the call is placed (owner); the lock screen dial pad always stays. */
    @JavascriptInterface
    fun callNumber(number: String) { if (station3) { host.callNumber(number); if (!lock) host.closeStation3() } }
    /** Call history > Video call on a phone number: the phone's own video call through the mobile network. */
    @JavascriptInterface
    fun callNumberVideo(number: String) { if (station3) { host.callNumberVideo(number); if (!lock) host.closeStation3() } }
    /** Dial pad > New contact (with the number you typed). */
    @JavascriptInterface fun newContact(number: String) { if (station3) host.newContact(number) }

    /** Settings > QUICK ACCESS (JSON: mode, where, s3Aod, lockDial, dialAod). */
    @JavascriptInterface
    fun getQuickAccess(): String = QuickAccess.json(ctx)

    @JavascriptInterface
    fun setQuickAccess(json: String) { if (!station3) host.setQuickAccess(json) }

    companion object {
        @Volatile private var appsCache: String? = null

        /** Builds the app list (names + icons). Slow-ish, so it's done ahead of time, off screen. */
        fun buildApps(ctx: Context): String {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val out = JSONArray()
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { it to it.loadLabel(pm).toString() }
                .sortedBy { it.second.lowercase() }
                .distinctBy { it.first.activityInfo.packageName }
                .forEach { (info, label) ->
                    val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    val d = info.loadIcon(pm)
                    d.setBounds(0, 0, 96, 96)
                    d.draw(Canvas(bmp))
                    val bos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
                    out.put(JSONObject()
                        .put("pkg", info.activityInfo.packageName)
                        .put("label", label)
                        .put("icon", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)))
                }
            return out.toString()
        }

        /** Rebuilds the list in the background (at start, and whenever an app is installed or removed). */
        fun refreshApps(ctx: Context, then: (() -> Unit)? = null) {
            val app = ctx.applicationContext
            Thread { runCatching { appsCache = buildApps(app) }; then?.invoke() }.start()
        }
    }
}
