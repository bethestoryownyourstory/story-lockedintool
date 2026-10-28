package com.story.launcher

import android.view.accessibility.AccessibilityNodeInfo

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
        return HOST_TOKENS.any { h.contains(it) }
    }

    fun appBlocked(pkg: String): Boolean {
        val p = pkg.lowercase()
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
