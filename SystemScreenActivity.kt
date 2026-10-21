package com.story.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** One of the phone's own screens opened for a Lockedintool feature (the Wi-Fi panel, Bluetooth, airplane mode, the
 *  output switcher, a permission switch) - see-through trampoline: Lockedintool steps aside while it's up and comes
 *  straight back when it's closed. */
class SystemScreenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        @Suppress("DEPRECATION")
        val i = intent.getParcelableExtra<Intent>(EXTRA_INTENT)
        if (i == null) { done(); return }
        runCatching { startActivityForResult(i, 9) }.onFailure { done() }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        done()
    }

    private var reported = false
    private fun done() {
        if (!reported) { reported = true; OverlayService.instance?.systemScreenDone() }
        finish(); overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.systemScreenDone() }
        super.onDestroy()
    }

    companion object { const val EXTRA_INTENT = "intent" }
}
