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
            if (!ok) { finish(); overridePendingTransition(0, 0); return }
            // Stay up (see-through, never touched) until the call has really opened the mic / camera: on Samsung /
            // Android 16 the CAMERA still said 'busy' once this screen was gone (phone report: gum NotReadableError
            // fg=true v), so Lockedintool stays the app in front for that moment. The page says when (callMediaDone);
            // 6 s at most.
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            holding = this
            window.decorView.postDelayed({ release() }, 6000)
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
        if (holding === this) holding = null
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.permissionsDone() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PERMS = "perms"; const val EXTRA_FORCE = "force"; const val EXTRA_CALL_FG = "callfg"
        private var holding: AskActivity? = null
        /** The call has opened (or given up on) the mic / camera: the held see-through screen can go. */
        fun release() { holding?.let { holding = null; if (!it.isFinishing) { it.finish(); it.overridePendingTransition(0, 0) } } }
    }
}
