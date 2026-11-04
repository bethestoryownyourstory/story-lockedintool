package com.story.launcher

import android.annotation.SuppressLint
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Lockedintool gets its own Android ID (owner, 4 Nov 2026: 'Nah let's do them' - plan B step 4: nothing named Story,
 * not even the hidden app ID com.story.launcher). A new ID is a NEW app on the phone, and Android never lets one app read
 * another's private data - so the old app hands everything over through a locked door (MoveProvider) that only an app
 * signed with Lockedintool's own key can open (the signature permission com.lockedintool.permission.MOVE):
 *  1. the old app (com.story.launcher) is updated with this file first - it can now hand over;
 *  2. the new app (com.lockedintool.app) is installed; on its first start it asks the old one to pack up
 *     (prepare = the screens' saved things: settings, chats list, notes + drawings), then copies the pack:
 *     every native setting, the money / calls history, trading logins, card details (unlocked in the old app,
 *     locked again with the new app's own key), and every file (pictures, posts, note files, chat files);
 *  3. the old app is told to stop, and you're asked to remove it.
 * The old app keeps everything until you remove it, so nothing can be lost on the way. Message keys can't leave the
 * phone's key store (like after the address move), so you log in once - chats come back from the locked backup.
 */
object AppMove {
    const val OLD_PKG = "com.story.launcher"
    const val NEW_PKG = "com.lockedintool.app"
    const val PERM = "com.lockedintool.permission.MOVE"
    private const val PREF = "app_moved"
    private const val WEB = "app-move-web.json"
    private val SKIP_PREFS = setOf("card_vault", "problems")
    private val SKIP_FILES = setOf(WEB, "origin-move.json", "widgets")

    fun installed(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    /** The old app, once the new Lockedintool is on the phone: it no longer updates or starts - it only hands over. */
    fun handedOver(c: Context) = c.packageName == OLD_PKG && installed(c, NEW_PKG)
    /** The new app, the first time it starts with the old one still on the phone. */
    fun needed(c: Context) = c.packageName != OLD_PKG && installed(c, OLD_PKG) &&
        !c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean(PREF, false)
    /** The new app has copied everything and the old one should be removed (asked once on the setup screen). */
    fun removeOld(c: Context) = c.packageName != OLD_PKG && installed(c, OLD_PKG) &&
        c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean(PREF, false)

    // ---------------- old app: pack everything up ----------------

    /** The screens' saved things (localStorage + notes / drawings), read in a WebView on the address they live on. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun exportWeb(c: Context): Boolean {
        val outFile = File(c.filesDir, WEB); outFile.delete()
        val latch = CountDownLatch(1)
        var ok = false
        val ui = Handler(Looper.getMainLooper())
        ui.post {
            val web = WebView(c.applicationContext)
            web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true; web.settings.databaseEnabled = true
            web.addJavascriptInterface(object {
                @JavascriptInterface fun out(json: String) { ok = runCatching { outFile.writeText(json) }.isSuccess; ui.post { runCatching { web.destroy() } }; latch.countDown() }
                @JavascriptInterface fun fail(why: String) { ProblemLog.record(c, "move", "app move export: $why".take(140), "appmove"); ui.post { runCatching { web.destroy() } }; latch.countDown() }
            }, "LitMove")
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, url: String) { v.evaluateJavascript(OriginMove.EXPORT, null) }
            }
            web.loadUrl((if (OriginMove.moved(c)) OriginMove.NEW else OriginMove.OLD) + "live-source.txt?t=" + System.currentTimeMillis())
        }
        latch.await(40, TimeUnit.SECONDS)
        return ok
    }

    private fun prefsJson(c: Context): JSONObject {
        val all = JSONObject()
        val dir = File(c.applicationInfo.dataDir, "shared_prefs")
        for (f in dir.listFiles().orEmpty()) {
            val name = f.name.removeSuffix(".xml")
            if (name in SKIP_PREFS || name.startsWith("WebView") || name.startsWith("androidx") || name.startsWith("com.google")) continue
            val o = JSONObject()
            for ((k, v) in c.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                when (v) {
                    is String -> o.put(k, JSONArray().put("s").put(v))
                    is Boolean -> o.put(k, JSONArray().put("b").put(v))
                    is Int -> o.put(k, JSONArray().put("i").put(v))
                    is Long -> o.put(k, JSONArray().put("l").put(v))
                    is Float -> o.put(k, JSONArray().put("f").put(v.toDouble()))
                    is Set<*> -> o.put(k, JSONArray().put("set").put(JSONArray(v.map { it.toString() })))
                }
            }
            all.put(name, o)
        }
        return all
    }

    /** The whole pack: prefs.json, vault.json (card details, unlocked here), web.json and files/... */
    fun bundle(c: Context): File {
        val z = File(c.cacheDir, "app-move.zip"); z.delete()
        ZipOutputStream(z.outputStream().buffered()).use { zip ->
            fun text(name: String, s: String) { zip.putNextEntry(ZipEntry(name)); zip.write(s.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            text("prefs.json", prefsJson(c).toString())
            text("vault.json", CardVault.all(c).toString())
            File(c.filesDir, WEB).takeIf { it.exists() }?.let { text("web.json", it.readText()) }
            val root = c.filesDir
            fun walk(d: File) {
                for (f in d.listFiles().orEmpty()) {
                    val rel = f.relativeTo(root).path
                    if (rel.substringBefore('/') in SKIP_FILES) continue
                    if (f.isDirectory) walk(f)
                    else runCatching { zip.putNextEntry(ZipEntry("files/$rel")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
                }
            }
            walk(root)
        }
        return z
    }

    /** The old app steps down: no windows, no system actions, never restarts (the new app runs from now on). */
    fun stopHere(c: Context) {
        c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).putBoolean("moved_out", true).commit()
        Handler(Looper.getMainLooper()).post {
            runCatching { StoryAccessibilityService.instance?.disableSelf() }
            runCatching { OverlayService.instance?.stopSelf() }
            runCatching { c.stopService(Intent(c, OverlayService::class.java)) }
        }
    }

    // ---------------- new app: copy it in ----------------

    @Volatile private var running = false

    /** Copies everything from the old app; done("ok") / done(<why not>) on the main thread. Blocking work on its own thread. */
    fun run(c: Context, done: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        if (!needed(c)) { ui.post { done("ok") }; return }
        if (running) return
        running = true
        Thread {
            val r = runCatching { copyIn(c) }.getOrElse { "error: ${it.message}".take(160) }
            running = false
            if (r != "ok") ProblemLog.record(c, "move", "app move: $r".take(140), "appmove")
            ui.post { done(r) }
        }.start()
    }

    private fun copyIn(c: Context): String {
        val cr = c.contentResolver
        val base = Uri.parse("content://$OLD_PKG.move")
        val prep = runCatching { cr.call(base, "prepare", null, null) }.getOrNull() ?: return "old"   // the old app can't hand over yet
        if (!prep.getBoolean("ok")) return "prepare"
        val files = c.filesDir
        var vault: JSONObject? = null
        cr.openInputStream(Uri.withAppendedPath(base, "bundle"))?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.name == "prefs.json" -> applyPrefs(c, JSONObject(zip.readBytes().toString(Charsets.UTF_8)))
                        e.name == "vault.json" -> vault = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        e.name == "web.json" -> File(files, "origin-move.json").writeBytes(zip.readBytes())
                        e.name.startsWith("files/") && !e.isDirectory -> {
                            val f = File(files, e.name.removePrefix("files/"))
                            if (!f.canonicalPath.startsWith(files.canonicalPath + File.separator)) continue
                            f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
        } ?: return "bundle"
        vault?.let { v -> for (k in v.keys()) CardVault.put(c, k, v.getString(k)) }
        // The screens' saved things go in through the address move's own import (it finds origin-move.json and writes it
        // in on lockedintool.com/app/ before any window loads).
        c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putBoolean("origin_moved", false).putBoolean(PREF, true).commit()
        runCatching { cr.call(base, "stop", null, null) }
        return "ok"
    }

    private fun applyPrefs(c: Context, all: JSONObject) {
        for (name in all.keys()) {
            val o = all.getJSONObject(name)
            val ed = c.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            for (k in o.keys()) {
                if (name == "story" && (k.startsWith("upd_") || k == PREF || k == "moved_out" || k == "session_confirm_tried")) continue
                val a = o.getJSONArray(k)
                when (a.getString(0)) {
                    "s" -> ed.putString(k, a.getString(1))
                    "b" -> ed.putBoolean(k, a.getBoolean(1))
                    "i" -> ed.putInt(k, a.getInt(1))
                    "l" -> ed.putLong(k, a.getLong(1))
                    "f" -> ed.putFloat(k, a.getDouble(1).toFloat())
                    "set" -> { val s = a.getJSONArray(1); ed.putStringSet(k, (0 until s.length()).map { s.getString(it) }.toSet()) }
                }
            }
            ed.commit()
        }
    }

    /** The old app's remove screen (Android asks you to confirm). */
    fun askRemoveOld(c: Context) {
        runCatching { c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$OLD_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** The locked door (see AppMove): only an app signed with Lockedintool's own key may call it. */
class MoveProvider : ContentProvider() {
    override fun onCreate() = true
    private fun allowed(): Boolean {
        val c = context ?: return false
        return c.checkCallingPermission(AppMove.PERM) == PackageManager.PERMISSION_GRANTED
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        if (!allowed()) throw SecurityException("not allowed")
        return when (method) {
            "prepare" -> Bundle().apply { putBoolean("ok", AppMove.exportWeb(c)) }
            "stop" -> { AppMove.stopHere(c); Bundle().apply { putBoolean("ok", true) } }
            else -> null
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val c = context ?: return null
        if (!allowed() || uri.lastPathSegment != "bundle") throw SecurityException("not allowed")
        return ParcelFileDescriptor.open(AppMove.bundle(c), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<String>?) = 0
}
