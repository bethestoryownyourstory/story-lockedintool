package com.story.launcher

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Media output (owner: tapping 'This Phone' while earphones are on must really switch): Android lets only the phone's
 *  own output switcher move other apps' sound, so this see-through screen brings it up - asked from the front, where
 *  Android allows it (Android 14+), else SystemUI's media output dialog, else (phones that show neither, e.g. Samsung)
 *  the phone's quick panel and its own Media output. Once the phone's list is up, Lockedintool system actions tap the
 *  output picked in Lockedintool (StoryAccessibilityService.pickOutput) and close it; Lockedintool comes straight back
 *  when it's gone. Without system actions: the phone's Bluetooth screen (nothing is tapped there). */
class OutputSwitchActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var started = false
    private var away = false       // the phone's own switcher / panel is over this screen
    private var viaPanel = false   // it's the quick panel: its Media output still has to be opened
    private var noPick = false     // the Bluetooth screen: never tap anything there
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) done()
    }

    override fun onResume() {
        super.onResume()
        if (started || reported) return
        started = true
        ui.postDelayed(ask, 120)   // this screen is fully in front by then (Android checks that)
        ui.postDelayed(giveUp, 90_000)   // never left hanging
    }

    private val ask = Runnable {
        if (reported) return@Runnable
        val shown = Build.VERSION.SDK_INT >= 34 &&
            runCatching { android.media.MediaRouter2.getInstance(this).showSystemOutputSwitcher() }.getOrDefault(false)
        if (!shown) runCatching {
            sendBroadcast(Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG").setPackage("com.android.systemui")
                .putExtra("package_name", packageName))
        }
        ui.postDelayed(noSwitcher, 900)
    }

    private val noSwitcher = Runnable {
        if (away || reported) return@Runnable
        val s = StoryAccessibilityService.instance
        if (s != null && s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) { viaPanel = true; return@Runnable }
        noPick = true
        SystemMode.grant()
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.onFailure { done() }
    }

    private val giveUp = Runnable { done() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!started || reported) return
        if (!hasFocus) {
            if (away) return
            away = true
            ui.removeCallbacks(noSwitcher)
            if (!noPick) StoryAccessibilityService.pickOutput(intent.getStringArrayListExtra(EXTRA_NAMES) ?: arrayListOf(), viaPanel)
        } else if (away) done()   // the phone's list closed: back to Lockedintool
    }

    override fun onStop() {
        super.onStop()
        if (away && !noPick && !isChangingConfigurations) done()   // gone home from the phone's panel (the Bluetooth screen comes back here)
    }

    private fun done() {
        ui.removeCallbacks(ask); ui.removeCallbacks(noSwitcher); ui.removeCallbacks(giveUp)
        if (!reported) { reported = true; OverlayService.instance?.systemScreenDone() }
        if (!isFinishing) { finish(); overridePendingTransition(0, 0) }
    }

    override fun onDestroy() {
        if (!reported) { reported = true; OverlayService.instance?.systemScreenDone() }
        ui.removeCallbacks(ask); ui.removeCallbacks(noSwitcher); ui.removeCallbacks(giveUp)
        super.onDestroy()
    }

    companion object { const val EXTRA_NAMES = "names" }
}
