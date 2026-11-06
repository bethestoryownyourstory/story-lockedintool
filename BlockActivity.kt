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
        // 'This is a mistake' (owner, 6 Nov 2026: 'Is a report but then you will be able to continue viewing what you where
        // viewing' - replaces 'report only, no viewing'): what was blocked goes to Lockedintool to check, and you go straight
        // back to it - that site / app isn't blocked again until the screen next goes off.
        val kind = intent.getStringExtra("kind") ?: ""
        val value = intent.getStringExtra("value") ?: ""
        if (value.isNotBlank()) {
            val btn = Button(this).apply {
                text = "This is a mistake"
                setAllCaps(false); textSize = 13f
                setTextColor(Color.parseColor("#d8d8dc"))
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setStroke(px(1), Color.parseColor("#3a3a40")); setColor(Color.TRANSPARENT) }
                setPadding(px(16), 0, px(16), 0)
                setOnClickListener { goBackToIt(kind, value) }
            }
            root.addView(btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(40)).apply { topMargin = px(10) })
        }
        root.addView(TextView(this).apply {
            text = "Tap anywhere to go back"
            setTextColor(Color.parseColor("#5a5a62")); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, px(18), 0, 0)
        })
        setContentView(root)
    }
    private fun goBackToIt(kind: String, value: String) {
        ContentGuard.reportMistake(kind, value)
        ContentGuard.allowForNow(kind, value)
        val pkg = intent.getStringExtra("pkg") ?: ""
        val url = intent.getStringExtra("url") ?: ""
        runCatching {
            if (kind == "site" && url.isNotBlank()) {
                val u = if (url.startsWith("http")) url else "https://" + url.trim()
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).apply { if (pkg.isNotBlank()) setPackage(pkg) }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } else if (pkg.isNotBlank()) packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        android.widget.Toast.makeText(this, "Sent to Lockedintool to check", android.widget.Toast.LENGTH_SHORT).show()
        finish()
    }
}
