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
class StoryBridge(private val ctx: Context, private val host: Host, private val station3: Boolean = false) {

    interface Host {
        fun hidePanel()
        fun closeStation3()
        fun setStation3Size(w: Int, h: Int)
    }

    /** The phone's real launchable apps, with their real icons. */
    @JavascriptInterface
    fun getApps(): String {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val out = JSONArray()
        pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .distinctBy { it.activityInfo.packageName }
            .forEach {
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val d = it.loadIcon(pm)
                d.setBounds(0, 0, 96, 96)
                d.draw(Canvas(bmp))
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
                out.put(JSONObject()
                    .put("pkg", it.activityInfo.packageName)
                    .put("label", it.loadLabel(pm).toString())
                    .put("icon", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)))
            }
        return out.toString()
    }

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

    /** "Apps" in STORY's Apps | Pages switch: back to the phone's own home screen, untouched. */
    @JavascriptInterface
    fun showApps() {
        host.hidePanel()
        StoryAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

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
    fun setStation3Size(w: Int, h: Int) { if (station3) host.setStation3Size(w, h) }
}
