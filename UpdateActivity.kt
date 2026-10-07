package com.story.launcher

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * "Update available" for Live users: Update now / Later. Once the update has waited 3 days
 * there is no Later any more -- the window can't be closed and STORY stays locked until it's updated.
 */
class UpdateActivity : Activity() {
    private lateinit var status: TextView
    private var required = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        required = Release.isRequired(this)
        setFinishOnTouchOutside(!required)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(Color.parseColor("#FF151517")) }
        }
        fun text(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, 0, 0, pad / 2) }
        card.addView(text(if (required) "Update required" else "Update available", 22f))
        card.addView(text(
            if (required) "This STORY update has waited 3 days. Update now to keep using STORY."
            else "A new version of STORY is ready.", 15f, Color.parseColor("#FFCCCCCC")))
        status = text("", 13f, Color.parseColor("#FF8B8B92"))
        card.addView(status)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        if (!required) row.addView(Button(this).apply {
            text = "Later"; isAllCaps = false
            setOnClickListener { Release.later(this@UpdateActivity); finish() }
        })
        row.addView(Button(this).apply {
            text = "Update now"; isAllCaps = false
            setOnClickListener { updateNow(this) }
        })
        card.addView(row)
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.parseColor("#B3000000"))
            addView(card, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { if (!required) finish() }
        })
    }

    private fun updateNow(b: Button) {
        b.isEnabled = false; status.text = "Downloading the update…"
        Thread {
            val err = Updater.installNow(applicationContext)
            runOnUiThread {
                b.isEnabled = true
                when (err) {
                    null -> status.text = "Installing… STORY restarts by itself when it's done."
                    "allow" -> {
                        status.text = "Allow STORY to update itself, then come back and tap Update now."
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    }
                    else -> status.text = err
                }
            }
        }.start()
    }

    // Back closes it only while the update isn't required yet.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (!required) super.onBackPressed() }

    companion object {
        const val NOTIFICATION = 3

        /** A notification that stays until the update is installed; tapping it opens this window. */
        fun notify(c: Context, required: Boolean) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("story_update", "STORY updates", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(c, NOTIFICATION, Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "story_update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (required) "STORY update required" else "STORY update available")
                .setContentText("Tap to update")
                .setContentIntent(open)
                .setOngoing(required)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(NOTIFICATION, n) }
        }
    }
}
