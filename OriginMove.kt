package com.story.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * Lockedintool's screens move to Lockedintool's own address (owner, Oct 2026: 'anything that is about lockedintool will be
 * about lockedintool and will not have story in it' - and Lockedintool gets its own GitHub home, apart from Story).
 * A phone keeps a page's saved things (settings, notes, drawings...) under the address the page came from, so before the
 * windows load from the new address, everything kept under the old one is copied across ONCE:
 *  1. a small file is opened (out of sight) on the OLD address and its saved things are read out,
 *  2. a small file is opened on the NEW address and they are written in,
 *  3. done - from then on every window loads from the new address.
 * If it can't finish (no signal...), nothing is lost: this run keeps the old address and it tries again next start.
 * Message keys can't be copied (the phone never lets them out), so you log in once afterwards - your chats come back
 * from your locked backup, exactly like logging in on a new phone.
 */
object OriginMove {
    const val OLD = "https://bethestoryownyourstory.github.io/story-lockedintool/"
    const val NEW = "https://lockedintool.com/app/"
    private const val PREF = "origin_moved"
    private const val FILE = "origin-move.json"

    fun moved(c: Context) = c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean(PREF, false)

    /** Runs the move (if still needed), then calls done(movedNow) on the main thread. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    fun run(c: Context, done: (Boolean) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        if (moved(c)) { ui.post { done(true) }; return }
        val f = File(c.filesDir, FILE)
        var finished = false
        var wv: WebView? = null
        fun finish(ok: Boolean, why: String = "") {
            if (finished) return; finished = true
            if (ok) { c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putBoolean(PREF, true).apply(); f.delete() }
            else ProblemLog.record(c, "move", "screens move waiting: $why".take(140), "origin")
            ui.post { runCatching { wv?.destroy() }; wv = null; done(ok) }
        }
        ui.postDelayed({ finish(false, "timeout") }, 25_000)
        val web = WebView(c.applicationContext); wv = web
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true; web.settings.databaseEnabled = true
        var step = 0   // 0 = reading on the old address, 1 = writing on the new one
        web.addJavascriptInterface(object {
            @JavascriptInterface fun out(json: String) {
                runCatching { f.writeText(json) }.onFailure { finish(false, "save"); return }
                ui.post { step = 1; web.loadUrl(NEW + "live-source.txt?t=" + System.currentTimeMillis()) }
            }
            @JavascriptInterface fun data(): String = runCatching { f.readText() }.getOrDefault("{}")
            @JavascriptInterface fun imported() { finish(true) }
            @JavascriptInterface fun fail(why: String) { finish(false, why) }
        }, "LitMove")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) {
                if (finished) return
                if (step == 0 && url.startsWith(OLD)) v.evaluateJavascript(EXPORT, null)
                else if (step == 1 && url.startsWith(NEW)) v.evaluateJavascript(IMPORT, null)
            }
            override fun onReceivedError(v: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                if (r.isForMainFrame) finish(false, "load ${e.errorCode}")
            }
            override fun onReceivedHttpError(v: WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceResponse) {
                if (r.isForMainFrame) finish(false, "http ${e.statusCode}")
            }
        }
        // A file that has already been copied out (an earlier try reached step 2) is written in straight away.
        if (f.exists() && f.length() > 2) { step = 1; web.loadUrl(NEW + "live-source.txt?t=" + System.currentTimeMillis()) }
        else web.loadUrl(OLD + "live-source.txt?t=" + System.currentTimeMillis())
    }

    // Everything the windows keep: every saved setting / chat / page (localStorage) and the notes + drawings (IndexedDB).
    const val EXPORT = """(async()=>{
      const ls = {}; for(let i = 0; i < localStorage.length; i++){ const k = localStorage.key(i); ls[k] = localStorage.getItem(k); }
      const notes = await new Promise(res=>{ try{ const r = indexedDB.open('story-notes', 1);
        r.onupgradeneeded = ()=>r.result.createObjectStore('kv');
        r.onsuccess = ()=>{ try{ const g = r.result.transaction('kv', 'readonly').objectStore('kv').get('notes'); g.onsuccess = ()=>res(g.result || null); g.onerror = ()=>res(null); }catch(e){ res(null); } };
        r.onerror = ()=>res(null); }catch(e){ res(null); } });
      LitMove.out(JSON.stringify({ ls, notes }));
    })().catch(e=>LitMove.fail('export ' + e));"""

    // The sign-in itself isn't copied (its keys can't leave the phone's key store), so the app asks you to log in once.
    private const val IMPORT = """(async()=>{
      const d = JSON.parse(LitMove.data() || '{}'), skip = ['story-msg-account', 'story-auth-locked'];
      for(const k in (d.ls || {})) if(!skip.includes(k)) localStorage.setItem(k, d.ls[k]);
      if(d.notes) await new Promise(res=>{ try{ const r = indexedDB.open('story-notes', 1);
        r.onupgradeneeded = ()=>r.result.createObjectStore('kv');
        r.onsuccess = ()=>{ const tx = r.result.transaction('kv', 'readwrite'); tx.objectStore('kv').put(d.notes, 'notes'); tx.oncomplete = ()=>res(); tx.onerror = ()=>res(); };
        r.onerror = ()=>res(); }catch(e){ res(); } });
      LitMove.imported();
    })().catch(e=>LitMove.fail('import ' + e));"""
}
