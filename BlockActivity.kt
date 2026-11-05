package com.story.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** The adult-content block screen shown OVER any app / browser (owner's plain black screen, same words as the in-app
 *  one - "one thing everywhere"). The accessibility service sends the phone home and opens this when adult content is
 *  detected. Tap anywhere (or Back) to leave; "Find out more" opens Lockedintool's Safety & Rules page. */
class BlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(px(28), px(28), px(28), px(28))
            setOnClickListener { finish() }
        }
        root.addView(TextView(this).apply {
            text = "Locked by Lockedintool from viewing this"
            setTextColor(Color.WHITE); textSize = 19f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Context: nudity / adult content"
            setTextColor(Color.parseColor("#9a9aa2")); textSize = 13f; gravity = Gravity.CENTER
            setPadding(0, px(10), 0, 0)
        })
        root.addView(Button(this).apply {
            text = "Find out more about Lockedintool’s privacy and rules"
            setAllCaps(false)
            setTextColor(Color.parseColor("#0a84ff"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 13f
            setOnClickListener { runCatching { OverlayService.instance?.openPages("legal-safety") }; finish() }
        })
        // 'This is a mistake' (owner, 5 Nov 2026 - report only, no viewing): sends what was blocked to Lockedintool to check;
        // it stays blocked until Lockedintool reviews it.
        val kind = intent.getStringExtra("kind") ?: ""
        val value = intent.getStringExtra("value") ?: ""
        if (value.isNotBlank()) {
            val sent = TextView(this).apply {
                text = "Sent to Lockedintool to check. It stays blocked until then."
                setTextColor(Color.parseColor("#9a9aa2")); textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(0, px(12), 0, 0); visibility = android.view.View.GONE
            }
            val btn = Button(this).apply {
                text = "This is a mistake"
                setAllCaps(false); textSize = 13f
                setTextColor(Color.parseColor("#d8d8dc"))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setStroke(px(1), Color.parseColor("#3a3a40")); setColor(Color.TRANSPARENT) }
                setPadding(px(16), 0, px(16), 0)
                setOnClickListener { ContentGuard.reportMistake(kind, value); visibility = android.view.View.GONE; sent.visibility = android.view.View.VISIBLE }
            }
            root.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(40)).apply { topMargin = px(10) })
            root.addView(sent)
        }
        root.addView(TextView(this).apply {
            text = "Tap anywhere to go back"
            setTextColor(Color.parseColor("#5a5a62")); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, px(18), 0, 0)
        })
        setContentView(root)
    }
}
