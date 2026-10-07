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
 * Using STORY means agreeing to automatic updates -- everyone, the owner included. Users sign once
 * ("I agree"); after that STORY updates itself and never asks or reminds them again. Until they
 * agree, STORY stays off and this window can't be skipped.
 */
class UpdateActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Release.agreedAuto(this)) { finish(); return }
        setFinishOnTouchOutside(false)
        val d = resources.displayMetrics.density
        val pad = (24 * d).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(Color.parseColor("#FF151517")) }
        }
        fun text(t: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, 0, 0, pad / 2) }
        card.addView(text("Automatic updates", 22f))
        card.addView(text(
            "To use STORY, you agree that it keeps itself up to date. Whenever a new version comes out, " +
            "it installs by itself, automatically. You won't be asked or reminded again.", 15f, Color.parseColor("#FFCCCCCC")))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(Button(this).apply {
            text = "I agree"; isAllCaps = false
            setOnClickListener {
                Release.setAgreedAuto(this@UpdateActivity)
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
                KeepAlive.ensureRunning(applicationContext)          // STORY starts (if it was switched on)
                Updater.check(applicationContext, force = true)      // the waiting update (if any) installs now
                finish()
            }
        })
        card.addView(row)
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(pad, pad, pad, pad); setBackgroundColor(Color.parseColor("#B3000000"))
            addView(card, LinearLayout.LayoutParams(-1, -2))
        })
    }

    // No way past it except "I agree".
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {}

    companion object {
        const val NOTIFICATION = 3
        @Volatile private var shownAt = 0L

        /** Not agreed yet: a notification that stays, and the agreement window (at most every 30 minutes unless forced). */
        fun ask(c: Context, force: Boolean = false) {
            if (Release.agreedAuto(c)) return
            notify(c)
            val now = System.currentTimeMillis()
            if (!force && now - shownAt < 30 * 60_000) return
            shownAt = now
            runCatching { c.startActivity(Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        fun notify(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("story_update", "STORY updates", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(c, NOTIFICATION, Intent(c, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "story_update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Agree to automatic updates to use STORY")
                .setContentText("Tap to agree")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
            runCatching { nm.notify(NOTIFICATION, n) }
        }
    }
}
