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
 *  one - "one thing everywhere"). #234 (owner, 6 Nov 2026): it never closes the app - it opens on top of it, and
 *  'Continue to view' just takes it away so you carry on where you were (Lockedintool is made for everyone: kids are
 *  told no, adults may continue). Back = leave (the phone's home screen). "Find out more" opens Safety & Rules. */
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
            isClickable = true   // taps on the black screen do nothing - only its buttons act
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
        // 'Continue to view' (#234 - owner, 6 Nov 2026: 'It's should say continue to view'; 'if they want to view it they
        // can'): the app is still right underneath, so this only goes away - that site / app isn't blocked again until the
        // screen next goes off. Nothing is sent to Lockedintool (#235).
        val kind = intent.getStringExtra("kind") ?: ""
        val value = intent.getStringExtra("value") ?: ""
        root.addView(Button(this).apply {
            text = "Continue to view"
            setAllCaps(false); textSize = 13f
            setTextColor(Color.parseColor("#d8d8dc"))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 18 * d; setStroke(px(1), Color.parseColor("#3a3a40")); setColor(Color.TRANSPARENT) }
            setPadding(px(16), 0, px(16), 0)
            setOnClickListener { continueToView(kind, value) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(40)).apply { topMargin = px(10) })
        root.addView(TextView(this).apply {
            text = "Go back to leave"
            setTextColor(Color.parseColor("#5a5a62")); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, px(18), 0, 0)
        })
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
    }
    private fun continueToView(kind: String, value: String) {
        if (value.isNotBlank()) ContentGuard.allowForNow(kind, value)   // #235 (owner: 'Yah stop it'): no report sent
        finish()
    }
    // Back = leave what was blocked: the phone's home screen (Lockedintool's Home in Only system).
    @Deprecated("Back = leave")
    override fun onBackPressed() { leave() }
    private fun leave() {
        runCatching { startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }
}
