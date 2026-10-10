package com.story.launcher

import android.app.Activity
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/** Notes > Print: the one thing STORY can't do inside itself - paper printing goes through the phone's own print
 *  screen (owner: everything else in Notes happens inside STORY). See-through; the Pages step aside while it's up. */
class NoteMediaActivity : Activity() {

    private var reported = false
    private var printStarted = false
    private var pausedOnce = false
    private var printView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val html = runCatching { File(cacheDir, "note_print.html").readText() }.getOrNull()
        if (html == null) { done("error"); return }
        val title = NoteTools.safeName(intent.getStringExtra(EXTRA_TITLE) ?: "Note")
        val wv = WebView(this)
        printView = wv
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (!u.startsWith(ProfilePhotoActivity.PHOTO_BASE + "note/")) return null
                val f = r.url.lastPathSegment?.let { NoteTools.file(this@NoteMediaActivity, it) } ?: return null
                return WebResourceResponse(NoteTools.mimeOf(f), null, f.inputStream())
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (printStarted) return
                printStarted = true
                runCatching {
                    getSystemService(PrintManager::class.java).print(title, v.createPrintDocumentAdapter(title),
                        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                }.onFailure { done("error") }
            }
        }
        wv.loadDataWithBaseURL(ProfilePhotoActivity.PHOTO_BASE, html, "text/html", "utf-8", null)
    }

    // The print screen closed (printed or backed out): back to the note.
    override fun onPause() { super.onPause(); if (printStarted) pausedOnce = true }
    override fun onResume() { super.onResume(); if (printStarted && pausedOnce) done("ok") }

    private fun done(status: String) {
        if (!reported) { reported = true; OverlayService.instance?.noteMediaDone("print", status, "{}") }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.noteMediaDone("print", "cancel", "{}") }
        printView?.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
    }
}
