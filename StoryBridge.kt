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
class StoryBridge(private val ctx: Context, private val host: Host, private val station3: Boolean = false, private val dial: Boolean = false, private val profile: Boolean = false, private val lock: Boolean = false) {

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
        fun setQuickAccess(json: String)
        fun toggleCreate()
        fun toggleCalls()
        fun pagesAction(action: String)
        fun getCalls(): String
        fun createShutter()
        fun createVideo(on: Boolean)
        fun createUseFromPhone(id: Long, video: Boolean)
        fun createDiscard()
        fun createSwitchCamera()
        fun createKeep(): String
        fun closeProfile()
        fun setTheme(t: String)
        fun pickProfilePhoto()
        fun removeProfilePhoto()
        fun requestPhotoAccess()
        fun useProfilePhoto(id: Long)
        fun profileTyping(on: Boolean)
        fun noteAsk(kind: String)
        fun noteResult(kind: String, status: String, json: String)
        fun noteSavePdf(title: String, pages: List<String>)
        fun noteShareTo(pkg: String, cls: String, title: String, text: String, files: List<java.io.File>)
        fun notePrint(title: String, html: String)
        fun evalPages(js: String)
    }

    /** The phone's real launchable apps, with their real icons -- kept ready, so the Apps area opens instantly. */
    @JavascriptInterface
    fun getApps(): String = appsCache ?: buildApps(ctx).also { appsCache = it }

    /** Opens the real app exactly as the phone's own launcher would, and gets STORY out of the way. */
    @JavascriptInterface
    fun launchApp(pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        host.hidePanel()
        ctx.startActivity(i)
    }

    @JavascriptInterface
    fun uninstallApp(pkg: String) {
        host.hidePanel()
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Apps" in STORY's Apps | Pages switch: back to the app you were on (or the home screen), untouched. */
    @JavascriptInterface
    fun showApps() { host.hidePanel() }

    /** The Pages report when a text box is being typed in (so the keyboard can show above them). */
    @JavascriptInterface
    fun setTyping(on: Boolean) { if (profile) host.profileTyping(on) else if (!station3) host.setTyping(on) }

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
    fun requestPhotoAccess() { if (profile) host.requestPhotoAccess() }

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
        host.evalPages("window.storyHttp&&window.storyHttp(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")")
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
     *  if the phone has no NFC. */
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
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/STORY")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = ctx.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching false
                    values.clear(); values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, values, null, null)
                    true
                } else {
                    val dir = java.io.File(ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "STORY").apply { mkdirs() }
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
    private val pages get() = !station3 && !profile

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
    fun toggleProfile() { if (station3) host.toggleProfile() }

    @JavascriptInterface
    fun closeProfile() { if (station3) host.closeProfile() }

    /** Station 3's messages button: Messages opens in the same window as Profile, attached to Station 3. */
    @JavascriptInterface
    fun toggleMessages() { if (station3) host.toggleMessages() }

    /** Station 3's + (Create & post): Create opens in the same window, the real camera underneath it. */
    @JavascriptInterface
    fun toggleCreate() { if (station3) host.toggleCreate() }

    /** Station 3's call history button: Call history in the same window; its list (every call made from STORY). */
    @JavascriptInterface
    fun toggleCalls() { if (station3) host.toggleCalls() }

    /** Station 3's Rows / Page Styles buttons (on the Pages): do it on the Pages. */
    @JavascriptInterface
    fun pagesAction(action: String) { if (station3) host.pagesAction(action) }

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

    /** The X beside the dial pad. */
    @JavascriptInterface
    fun closeDialpad() { if (station3) host.closeDialpad() }

    /** Station 3's page reports how wide its white bar is; the app draws that white itself, pixel-exact with the button. */
    @JavascriptInterface
    fun setBarWidth(w: Double) { if (station3 && !dial) host.setBarWidth(w) }

    /** The dial pad page reports its size once, so its window fits it before it's ever shown. */
    @JavascriptInterface
    fun setDialSize(w: Int, h: Int) { if (lock) host.setLockDialSize(w, h) else if (dial) host.setDialSize(w, h) }

    /** The dial pad's call button: calls straight away (even on the lock screen). */
    @JavascriptInterface
    fun callNumber(number: String) { if (station3) host.callNumber(number) }

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
