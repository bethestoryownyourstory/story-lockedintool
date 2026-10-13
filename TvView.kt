package com.story.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * The Trading page's chart is the REAL TradingView (owner: "tradingview is real tradingview"): tradingview.com's own
 * chart in its own view inside the Pages window, laid exactly over the page's chart box (the page tells it where -
 * StoryNative.tvPlace). Sign in to your TradingView account right there (it stays signed in - the phone keeps the
 * cookie), and everything is yours: your layouts, drawings, indicators, watchlists, any pair. One view, never
 * reloaded by STORY's page redraws; going full screen just moves and resizes it. Holes keep the page's own buttons on
 * the box (SL/TP, full screen) on top and tappable; outside the box every touch goes straight to the page.
 * The pair you pick in TradingView becomes STORY's FOCUS symbol (read from TradingView's title).
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class TvView(ctx: Context, private val onSymbol: (String) -> Unit) : FrameLayout(ctx) {
    val web = WebView(ctx)
    private val box = RectF()        // the chart's visible part, in this view's pixels
    private var radius = 0f
    private val holes = ArrayList<RectF>()
    private var dim = 0f
    private var tracking = false
    private var loaded = false
    var symbol = ""                   // TradingView's pair right now (e.g. "EURUSD"), from its title
        private set
    var fullSymbol = ""               // with its exchange (e.g. "FX:EURUSD") when TradingView tells us
        private set

    init {
        setWillNotDraw(false)
        visibility = View.GONE
        web.setBackgroundColor(Color.BLACK)
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = true
            loadWithOverviewMode = true; useWideViewPort = true
            // A plain Chrome name (no "wv") so signing in with Google / Apple is allowed like in the phone's browser.
            userAgentString = userAgentString.replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            // TradingView and the sign-in pages it uses stay inside; anything else isn't opened here.
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean = !allowed(r.url.host ?: "")
            override fun onPageFinished(v: WebView, url: String) { CookieManager.getInstance().flush(); readSymbol() }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(v: WebView, title: String?) { titleSymbol(title ?: "")?.let { s -> if (s != symbol) { symbol = s; onSymbol(s); readSymbol() } } }
        }
        addView(web, FrameLayout.LayoutParams(1, 1))
    }

    private fun allowed(host: String): Boolean {
        val h = host.lowercase()
        return h == "tradingview.com" || h.endsWith(".tradingview.com") || h.endsWith("google.com") || h.endsWith("gstatic.com") ||
            h.endsWith("googleusercontent.com") || h == "appleid.apple.com" || h.endsWith(".apple.com") || h.endsWith("facebook.com") ||
            h.endsWith("twitter.com") || h.endsWith("x.com") || h.endsWith("linkedin.com") || h.endsWith("yahoo.com")
    }

    /** "EURUSD 1.08432 ▲ +0.40% Unnamed" -> "EURUSD". */
    private fun titleSymbol(t: String): String? {
        val parts = t.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val s = parts[0]; val price = parts[1].replace(",", "")
        if (!Regex("^[A-Z0-9][A-Z0-9._!/-]{1,24}$").matches(s) || price.toDoubleOrNull() == null) return null
        return s.substringAfter(':')
    }

    private fun readSymbol() {
        web.evaluateJavascript("(function(){try{return TradingViewApi.activeChart().symbol()}catch(e){return ''}})()") { v ->
            val s = v?.trim('"').orEmpty(); if (s.contains(':')) fullSymbol = s
        }
    }

    /** Shows `sym` (e.g. "FX:EURUSD") at `interval` ("60", "240", "D", "W"): straight in TradingView's chart when it
     *  can (no reload), otherwise by opening the chart on it. */
    fun open(sym: String, interval: String) {
        val short = sym.substringAfter(':').uppercase()
        if (!loaded) { loaded = true; load(sym, interval); return }
        val wantSym = short != symbol.uppercase()
        val js = "(function(){try{var c=TradingViewApi.activeChart();" +
            (if (wantSym) "c.setSymbol(" + JSONObject.quote(sym) + ");" else "") +
            "c.setResolution(" + JSONObject.quote(interval) + ");return 'ok'}catch(e){return 'no'}})()"
        web.evaluateJavascript(js) { r -> if (r?.contains("ok") != true) load(if (wantSym) sym else (fullSymbol.ifEmpty { symbol.ifEmpty { sym } }), interval) }
    }

    private fun load(sym: String, interval: String) {
        web.loadUrl("https://www.tradingview.com/chart/?symbol=" + android.net.Uri.encode(sym) + "&interval=" + android.net.Uri.encode(interval))
    }

    /** Where the chart goes (from the page, in CSS px; dpr = the page's pixels per CSS px). */
    fun place(o: JSONObject) {
        // Cooling (owner): TradingView rests while it isn't on screen and wakes when it is (same chart, no reload).
        if (!o.optBoolean("show")) { if (visibility != View.GONE) { visibility = View.GONE; runCatching { web.onPause() } }; return }
        val k = o.optDouble("dpr", 1.0).toFloat()
        val x = (o.optDouble("x") * k).toFloat(); val y = (o.optDouble("y") * k).toFloat()
        val w = Math.round(o.optDouble("w") * k).toInt().coerceAtLeast(1); val h = Math.round(o.optDouble("h") * k).toInt().coerceAtLeast(1)
        val lp = web.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; web.layoutParams = lp }
        web.translationX = x; web.translationY = y
        box.set(x, y + (o.optDouble("ct") * k).toFloat(), x + w, y + (o.optDouble("cb") * k).toFloat())
        radius = (o.optDouble("r") * k).toFloat()
        holes.clear()
        o.optJSONArray("holes")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { b ->
            holes.add(RectF((b.optDouble(0) * k).toFloat() + x, (b.optDouble(1) * k).toFloat() + y, ((b.optDouble(0) + b.optDouble(2)) * k).toFloat() + x, ((b.optDouble(1) + b.optDouble(3)) * k).toFloat() + y))
        } }
        dim = o.optDouble("dim", 0.0).toFloat()
        if (visibility != View.VISIBLE) { runCatching { web.onResume() }; visibility = View.VISIBLE }
        invalidate()
    }

    private fun inside(px: Float, py: Float) = box.contains(px, py) && holes.none { it.contains(px, py) }

    override fun dispatchDraw(c: Canvas) {
        if (box.isEmpty) return
        c.save()
        c.clipPath(Path().apply { addRoundRect(box, radius, radius, Path.Direction.CW) })
        holes.forEach { c.clipOutRect(it) }
        super.dispatchDraw(c)
        if (dim > 0f) c.drawColor(Color.argb((dim * 255).toInt().coerceIn(0, 255), 0, 0, 0))
        c.restore()
    }

    // Touches outside the chart (and on the page's own buttons over it) aren't ours: the page underneath gets them.
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) tracking = visibility == View.VISIBLE && inside(e.x, e.y)
        if (!tracking) return false
        return super.dispatchTouchEvent(e)
    }
}
