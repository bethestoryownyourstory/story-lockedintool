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
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Users sign once: "Automatic updates - I agree". After that STORY updates itself, automatically,
 * and never asks or reminds them again. Until they agree, a waiting update becomes required after
 * 3 days (this window can't be closed then).
 */
class UpdateActivity : Activity() {
    private var required = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Release.agreedAuto(this) || Release.isOwner(this)) { finish(); return }
        required = Release.isRequired(this)
        setFinishOnTouchOutside(!required)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(Color.parseColor("#FF151517")) }
        }
        fun text(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, 0, 0, pad / 2) }
        card.addView(text("Automatic updates", 22f))
        card.addView(text(
            "STORY keeps itself up to date. Whenever a new version comes out, it installs by itself, " +
            "automatically. You won't be asked or reminded again.", 15f, Color.parseColor("#FFCCCCCC")))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(Button(this).apply {
            text = "I agree"; isAllCaps = false
            setOnClickListener {
                Release.setAgreedAuto(this@UpdateActivity)
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                Updater.check(applicationContext, force = true)  // the waiting update (if any) installs now
                finish()
            }
        })
        card.addView(row)
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.parseColor("#B3000000"))
            addView(card, LinearLayout.LayoutParams(-1, -2))
            setOnClickListener { if (!required) finish() }
        })
    }

    // Back closes it only while the update isn't required yet.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (!required) super.onBackPressed() }

    companion object {
        const val NOTIFICATION = 3

        /** Until the user agrees: a notification that opens the agreement. */
        fun notify(c: Context, required: Boolean) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("story_update", "STORY updates", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(c, NOTIFICATION, Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "story_update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(if (required) "STORY update required" else "STORY update available")
                .setContentText("Tap to turn on automatic updates")
                .setContentIntent(open)
                .setOngoing(required)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(NOTIFICATION, n) }
        }
    }
}
