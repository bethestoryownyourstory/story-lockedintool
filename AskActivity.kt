package com.story.launcher

import android.app.Activity
import android.os.Bundle

/** Asks Android once for permissions STORY needs (e.g. "make phone calls" for the lock screen dial pad).
 *  See-through; STORY's windows step aside while Android's question is up and come straight back. */
class AskActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val all = intent.getStringArrayExtra(EXTRA_PERMS)?.toList()
        // force: ask even if allowed (Android 14 "selected photos" - choose more pictures)
        val perms = if (intent.getBooleanExtra(EXTRA_FORCE, false)) all else all?.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (perms.isNullOrEmpty()) { done(); return }
        requestPermissions(perms.toTypedArray(), 4)
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

    companion object { const val EXTRA_PERMS = "perms"; const val EXTRA_FORCE = "force" }
}
