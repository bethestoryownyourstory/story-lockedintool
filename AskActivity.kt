package com.story.launcher

import android.app.Activity
import android.os.Bundle

/** Asks Android once for permissions STORY needs (e.g. "make phone calls" for the lock screen dial pad).
 *  See-through; STORY's windows step aside while Android's question is up and come straight back. */
class AskActivity : Activity() {
    private var callFgVideo = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        // A call: turn on the microphone (/ camera) for Lockedintool's service while this see-through screen shows. On
        // Android 14+ a microphone / camera foreground service can only START while an activity is actually VISIBLE, so
        // this waits for onResume (not onCreate, which is too early and fails silently -> getUserMedia 'busy').
        val fg = intent.getIntExtra(EXTRA_CALL_FG, 0)
        if (fg > 0) { callFgVideo = fg; return }
        val all = intent.getStringArrayExtra(EXTRA_PERMS)?.toList()
        // force: ask even if allowed (Android 14 "selected photos" - choose more pictures)
        val perms = if (intent.getBooleanExtra(EXTRA_FORCE, false)) all else all?.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (perms.isNullOrEmpty()) { done(); return }
        requestPermissions(perms.toTypedArray(), 4)
    }

    override fun onResume() {
        super.onResume()
        if (callFgVideo > 0) {
            val v = callFgVideo == 2; callFgVideo = 0
            tryCallFg(v, 0)   // the translucent window is now shown, so the mic / camera foreground service may start
        }
    }
    // Keep this see-through screen up and retry while it's visible - Android 14+ only lets the mic / camera service start
    // while an activity is actually shown, and the first try can be a frame too early. Then tell the page the result.
    private fun tryCallFg(video: Boolean, attempt: Int) {
        val ok = OverlayService.instance?.callFg(true, video) ?: false
        if (ok || attempt >= 8) {
            OverlayService.instance?.callFgReady(ok)
            finish(); overridePendingTransition(0, 0)
        } else window.decorView.postDelayed({ tryCallFg(video, attempt + 1) }, 150)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        done()
    }

    private var reported = false
    private fun done() {
        if (!reported) { reported = true; OverlayService.instance?.permissionsDone() }
        finish(); overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.permissionsDone() }
        super.onDestroy()
    }

    companion object { const val EXTRA_PERMS = "perms"; const val EXTRA_FORCE = "force"; const val EXTRA_CALL_FG = "callfg" }
}
