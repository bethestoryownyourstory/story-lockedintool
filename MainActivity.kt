package com.story.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
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
        col.addView(btn("3. Start STORY") {
            if (!Settings.canDrawOverlays(this)) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); return@btn }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            startForegroundService(Intent(this, OverlayService::class.java))
            finish()
        })
        col.addView(btn("Stop STORY") { stopService(Intent(this, OverlayService::class.java)) })
        val info = packageManager.getPackageInfo(packageName, 0)
        col.addView(title("Build ${info.versionName} (code ${info.longVersionCode}) - built for Android API ${applicationInfo.targetSdkVersion} - this phone runs API ${Build.VERSION.SDK_INT}", 12f).apply { setTextColor(0xFF888888.toInt()); setPadding(0, pad, 0, 0) })
        setContentView(col)
    }
}
