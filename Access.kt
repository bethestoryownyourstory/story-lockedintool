package com.story.launcher

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Who may use Lockedintool (owner, 4 Nov 2026: 'Lockedintool is not public. Even if it becomes public those who have it
 *  won't be able to use it unless they get it on the official website or the Play Store. So only me can use Lockedintool,
 *  no other person. If they open it it will not do anything and nothing will appear - or put a message to notify them
 *  that if they do use this or any modification formed with it they will be breaking the law and action will be taken').
 *  - A modified copy (not signed with Lockedintool's key - Genuine.kt) never works, before or after launch.
 *  - Before launch: only phones unlocked with the owner's PIN (Release.isOwner - the PIN is checked by the server).
 *  - After launch (the owner's launch step puts launched.txt on the screens' site): every genuine copy works.
 *  A phone that may not use it starts nothing: no windows, no Station 3, no Pages - the app shows only the notice. */
object Access {
    private const val LAUNCHED = "https://lockedintool.com/app/launched.txt"
    private fun prefs(c: Context) = c.getSharedPreferences("story", Context.MODE_PRIVATE)
    fun launched(c: Context) = prefs(c).getBoolean("launched", false)
    fun allowed(c: Context) = Genuine.ok(c) && (Release.isOwner(c) || launched(c))

    /** Asks the site whether Lockedintool has launched (200 = yes, 404 = not yet; no answer = keep what we knew). */
    fun refresh(c: Context, then: ((Boolean) -> Unit)? = null) {
        val ctx = c.applicationContext
        Thread {
            runCatching {
                val h = URL("$LAUNCHED?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
                h.connectTimeout = 8000; h.readTimeout = 8000; h.useCaches = false
                val code = h.responseCode
                val body = if (code == 200) h.inputStream.bufferedReader().use { it.readText() } else ""
                h.disconnect()
                if (code == 200 && body.startsWith("launched")) prefs(ctx).edit().putBoolean("launched", true).apply()
                else if (code == 404) prefs(ctx).edit().putBoolean("launched", false).apply()
            }
            then?.invoke(allowed(ctx))
        }.start()
    }
}
