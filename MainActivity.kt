package com.story.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** One-time setup. STORY floats over your phone as it already is -- nothing about your home screen changes. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); setPadding(pad, pad * 2, pad, pad); gravity = Gravity.TOP }
        fun title(t: String, size: Float = 22f) = TextView(this).apply { text = t; setTextColor(Color.WHITE); textSize = size; setPadding(0, 0, 0, pad / 2) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
        col.addView(title("STORY", 32f))
        col.addView(title("Floats over your phone. Your home screen, lock screen and apps stay exactly as they are.", 15f))
        col.addView(btn("1. Allow display over other apps") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        col.addView(btn("2. Turn on STORY system actions") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("Allow STORY to update itself (one time)") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        })
        col.addView(btn("3. Start STORY") {
            if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return@btn }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", true).apply()
            startForegroundService(Intent(this, OverlayService::class.java))
            finish()
        })
        col.addView(btn("Stop STORY") { getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", false).apply(); stopService(Intent(this, OverlayService::class.java)) })
        // Owner only: you see the newest version (Test channel) and can publish it to everyone.
        if (Release.isOwner(this)) {
            col.addView(title("Owner - Test channel: you always get the newest version first.", 15f).apply { setTextColor(Color.parseColor("#FFE8B23D")); setPadding(0, pad, 0, pad / 2) })
            col.addView(btn("Publish live update") { askPin("Publish live update", "Everyone gets this version.") { pin -> publish(pin) } })
            col.addView(btn("Leave owner mode") { Release.setOwner(this, false); channelChanged() })
        }
        val info = packageManager.getPackageInfo(packageName, 0)
        col.addView(title("Build ${info.versionName} (code ${info.longVersionCode}) - built for Android API ${applicationInfo.targetSdkVersion} - this phone runs API ${Build.VERSION.SDK_INT}", 12f).apply {
            setTextColor(0xFF888888.toInt()); setPadding(0, pad, 0, 0)
            // Hidden owner unlock: tap this line 7 times. Users never see a PIN or the Publish button.
            var taps = 0; var firstTap = 0L
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - firstTap > 4000) { taps = 0; firstTap = now }
                if (++taps >= 7 && !Release.isOwner(this@MainActivity)) { taps = 0; askPin("Owner", "Enter your PIN.") { pin -> unlockOwner(pin) } }
            }
        })
        setContentView(col)
    }

    /** PIN box. The PIN is only sent to the STORY release server, which checks it -- never stored or checked here. */
    private fun askPin(heading: String, message: String, then: (String) -> Unit) {
        val box = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; hint = "PIN" }
        AlertDialog.Builder(this).setTitle(heading).setMessage(message).setView(box)
            .setPositiveButton("OK") { _, _ -> then(box.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun say(heading: String, message: String) = AlertDialog.Builder(this).setTitle(heading).setMessage(message).setPositiveButton("OK", null).show()

    private fun unlockOwner(pin: String) = Thread {
        val a = Release.askServer("verify", pin)
        runOnUiThread {
            if (a.ok) { Release.setOwner(this, true); Release.clearPending(this); channelChanged() }
            else say("Owner", a.message)
        }
    }.start()

    private fun publish(pin: String) = Thread {
        val a = Release.askServer("publish", pin)
        runOnUiThread {
            if (a.ok) say("Publishing", "Done. Everyone gets this version in about a minute.")
            else say("Not published", a.message)
        }
    }.start()

    /** Switched between Test and Live: reload the screens and look for that channel's newest version. */
    private fun channelChanged() {
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_REBUILD))
        Updater.check(applicationContext, force = true)
        recreate()
    }

    // Opening STORY also checks for a newer version, and brings back an install prompt you missed.
    override fun onResume() { super.onResume(); Updater.check(applicationContext, force = true) }
}
