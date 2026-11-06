package com.story.launcher

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/** The adult-content filter's lists + matchers (owner: adult content is blocked across the whole phone - part of
 *  Lockedintool, not a choice). Matches on the APP PACKAGE and the browser's ADDRESS BAR (host) only - never on page
 *  text - so it blocks the normal ways adult content is reached without false-blocking ordinary sites.
 *  Honest: the strongest a phone app can do, not a perfect wall. The browser's own SafeSearch (forced by Lockedintool's
 *  search) handles plain searches; this catches adult sites and apps. */
object ContentGuard {
    // Hostname tokens: a domain containing one of these is treated as adult. Clearly-adult words only.
    private val HOST_TOKENS = listOf(
        "porn", "xvideos", "xnxx", "xhamster", "pornhub", "redtube", "youporn", "spankbang", "eporner",
        "hqporner", "tnaflix", "hentai", "nhentai", "rule34", "onlyfans", "fansly", "brazzers", "chaturbate",
        "stripchat", "camsoda", "bongacams", "livejasmin", "motherless", "fapello", "nudevista", "xxx",
        "adultfriendfinder", "ashleymadison", "fetlife")
    // App-package tokens: very unlikely to appear in a normal app's package name. (No short/ambiguous tokens.)
    private val APP_TOKENS = listOf(
        "porn", "pornhub", "xvideos", "xnxx", "xhamster", "redtube", "youporn", "spankbang",
        "hentai", "onlyfans", "brazzers", "chaturbate", "stripchat")
    // Known browsers and the view id of their address bar.
    private val BROWSER_URL_IDS = mapOf(
        "com.android.chrome" to "com.android.chrome:id/url_bar",
        "com.chrome.beta" to "com.chrome.beta:id/url_bar",
        "com.chrome.dev" to "com.chrome.dev:id/url_bar",
        "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        "org.mozilla.focus" to "org.mozilla.focus:id/mozac_browser_toolbar_url_view",
        "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
        "com.brave.browser" to "com.brave.browser:id/url_bar",
        "com.opera.browser" to "com.opera.browser:id/url_field",
        "com.opera.mini.native" to "com.opera.mini.native:id/url_field",
        "com.opera.gx" to "com.opera.gx:id/url_field",
        "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
        "com.UCMobile.intl" to "com.UCMobile.intl:id/address",
        "com.kiwibrowser.browser" to "com.kiwibrowser.browser:id/url_bar",
        "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar")

    fun isBrowser(pkg: String) = BROWSER_URL_IDS.containsKey(pkg)

    // 'This is a mistake' (owner, 5 Nov 2026 - report only, no viewing): websites / apps Lockedintool unblocked for
    // everyone after reviewing a report. Fetched at most every 6 hours (api.lockedintool.com/v1/blockallow), kept in
    // prefs 'story' block_allow so it works offline. Only EXACT sites / apps - everything else stays blocked.
    @Volatile private var allowSites: Set<String> = emptySet()
    @Volatile private var allowApps: Set<String> = emptySet()
    private const val API = "https://api.lockedintool.com"
    fun load(ctx: Context) {
        val raw = ctx.getSharedPreferences("story", Context.MODE_PRIVATE).getString("block_allow", null) ?: return
        runCatching { applyAllow(JSONObject(raw)) }
    }
    private fun applyAllow(j: JSONObject) {
        fun set(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() }.filter { it.isNotEmpty() }.toSet() } ?: emptySet()
        allowSites = set("sites"); allowApps = set("apps")
    }
    fun refresh(ctx: Context) {
        val prefs = ctx.getSharedPreferences("story", Context.MODE_PRIVATE)
        load(ctx)
        if (System.currentTimeMillis() - prefs.getLong("block_allow_at", 0) < 6 * 3600_000L) return
        Thread {
            runCatching {
                val c = (java.net.URL("$API/v1/blockallow").openConnection() as java.net.HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
                if (c.responseCode == 200) {
                    val body = c.inputStream.bufferedReader().readText()
                    applyAllow(JSONObject(body))
                    prefs.edit().putString("block_allow", body).putLong("block_allow_at", System.currentTimeMillis()).apply()
                }
                c.disconnect()
            }
        }.start()
    }
    /** The block screen's 'This is a mistake': what was blocked goes to Lockedintool to check (no account, no name). */
    fun reportMistake(kind: String, value: String) {
        if (value.isBlank()) return
        Thread {
            runCatching {
                val c = (java.net.URL("$API/v1/blockfix").openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("content-type", "application/json")
                }
                c.outputStream.use { it.write(JSONObject().put("kind", kind).put("value", value).toString().toByteArray()) }
                c.responseCode; c.disconnect()
            }
        }.start()
    }
    fun siteOf(raw: String) = hostOf(raw)
    // 'This is a mistake' lets you carry on (owner, 6 Nov 2026: 'Is a report but then you will be able to continue viewing
    // what you where viewing'): that one site / app isn't blocked again until the screen next goes off.
    @Volatile private var nowSites: Set<String> = emptySet()
    @Volatile private var nowApps: Set<String> = emptySet()
    fun allowForNow(kind: String, value: String) {
        val v = value.lowercase(); if (v.isEmpty()) return
        if (kind == "app") nowApps = nowApps + v else if (kind == "site") nowSites = nowSites + v
    }
    fun clearForNow() { nowSites = emptySet(); nowApps = emptySet() }

    private fun hostOf(raw: String): String {
        var u = raw.trim().lowercase()
        if (u.isEmpty()) return ""
        val sp = u.indexOf(' '); if (sp >= 0) u = u.substring(0, sp)   // "porn - Google Search" -> first token only
        u = u.removePrefix("https://").removePrefix("http://")
        val slash = u.indexOf('/'); if (slash >= 0) u = u.substring(0, slash)
        return u.removePrefix("www.")
    }

    /** True only when the address bar holds an adult URL (a plain search term, no dot, is left to SafeSearch). */
    fun hostBlocked(raw: String): Boolean {
        val h = hostOf(raw)
        if (h.isEmpty() || !h.contains('.')) return false
        if (allowSites.any { h == it || h.endsWith(".$it") } || nowSites.any { h == it || h.endsWith(".$it") }) return false
        return HOST_TOKENS.any { h.contains(it) }
    }

    fun appBlocked(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (p in allowApps || p in nowApps) return false
        return APP_TOKENS.any { p.contains(it) }
    }

    /** The text in a browser's address bar, if Lockedintool can read it. */
    fun browserUrl(root: AccessibilityNodeInfo?, pkg: String): String? {
        root ?: return null
        val id = BROWSER_URL_IDS[pkg] ?: return null
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull() ?: return null
        for (n in nodes) { val t = n.text?.toString(); if (!t.isNullOrBlank()) return t }
        return null
    }
}
