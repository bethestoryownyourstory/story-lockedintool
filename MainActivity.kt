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
import android.widget.ScrollView
import android.widget.TextView

/** One-time setup. STORY floats over your phone as it already is -- nothing about your home screen changes. */
class MainActivity : Activity() {
    private var updateLine: TextView? = null
    private var updateBar: android.widget.ProgressBar? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    // Keeps the "Updates: ..." line current while this screen is open.
    private val refresh = object : Runnable { override fun run() { showUpdateStatus(); ui.postDelayed(this, 1000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.schedule(this)          // updates keep coming every 15 minutes, even when STORY is off
        KeepAlive.ensureRunning(this)   // switched on but not running (killed by the phone)? bring it back
        if (!Release.agreedAuto(this)) UpdateActivity.ask(this, force = true)  // using STORY = agreeing to automatic updates
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
            if (!Release.agreedAuto(this)) { UpdateActivity.ask(this, force = true); return@btn }  // agreeing starts STORY
            startForegroundService(Intent(this, OverlayService::class.java))
            finish()
        })
        col.addView(btn("Stop STORY") { getSharedPreferences("story", MODE_PRIVATE).edit().putBoolean("enabled", false).apply(); stopService(Intent(this, OverlayService::class.java)) })
        // So the phone never shuts STORY down to save battery, and lets it start by itself after a restart.
        col.addView(btn("4. Keep STORY always on") { keepAlwaysOn() })
        // STORY's Always On Display (switch Samsung's own Always On Display off when using this).
        val prefs = getSharedPreferences("story", MODE_PRIVATE)
        fun aodLabel() = if (prefs.getBoolean("aod", false)) "Always On Display: ON" else "Always On Display: off"
        col.addView(Button(this).apply {
            text = aodLabel(); isAllCaps = false
            setOnClickListener { prefs.edit().putBoolean("aod", !prefs.getBoolean("aod", false)).apply(); text = aodLabel() }
        })
        // Updates: what's happening, at a glance.
        updateLine = title("", 14f).apply { setTextColor(Color.parseColor("#FFCCCCCC")); setPadding(0, pad, 0, pad / 2) }
        col.addView(updateLine)
        // Loading bar while an update downloads / installs.
        updateBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = android.view.View.GONE; setPadding(0, 0, 0, pad / 2) }
        col.addView(updateBar)
        col.addView(btn("Check for updates now") { Updater.setStatus(this, "Checking…"); showUpdateStatus(); Updater.check(applicationContext, force = true) })
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
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    private fun showUpdateStatus() {
        val (s, at) = Updater.status(this)
        val ago = if (at == 0L) "" else android.text.format.DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), android.text.format.DateUtils.SECOND_IN_MILLIS).toString()
        updateLine?.text = "Updates: $s" + if (ago.isEmpty()) "" else "  ·  $ago"
        val pct = Updater.progress(this)
        updateBar?.apply {
            visibility = if (pct == null) android.view.View.GONE else android.view.View.VISIBLE
            isIndeterminate = pct != null && pct < 0
            if (pct != null && pct >= 0) progress = pct
        }
    }

    /** Ask Android not to shut STORY down for battery, then (on Xiaomi phones) open the Autostart switch. */
    private fun keepAlwaysOn() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            return
        }
        val xiaomi = Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        if (Build.MANUFACTURER.equals("Xiaomi", true) && runCatching { startActivity(xiaomi) }.isSuccess) return
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
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
    override fun onResume() { super.onResume(); Updater.check(applicationContext, force = true); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }
}
