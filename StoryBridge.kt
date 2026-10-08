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
class StoryBridge(private val ctx: Context, private val host: Host, private val station3: Boolean = false, private val dial: Boolean = false, private val profile: Boolean = false) {

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
        fun toggleCreate()
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
    fun setDialSize(w: Int, h: Int) { if (dial) host.setDialSize(w, h) }

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
