package com.story.launcher

import android.content.Context
import android.graphics.Color

/** STORY's System theme (Settings > System theme): Black (default) or White.
 *  Only black and white swap; every other colour (green, red, gold...) stays exactly as it is. */
object StoryTheme {
    fun get(c: Context): String = c.getSharedPreferences("story", Context.MODE_PRIVATE).getString("theme", "black") ?: "black"
    fun set(c: Context, t: String) { c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putString("theme", t).apply() }
    fun white(c: Context) = get(c) == "white"

    /** In White, a black/near-black or white/near-white grey turns into its opposite (alpha kept). Anything else is left alone. */
    fun swap(c: Context, color: Int): Int {
        if (!white(c)) return color
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val avg = (r + g + b) / 3
        val spread = maxOf(r, g, b) - minOf(r, g, b)
        if (spread > 16 || (avg in 57..199)) return color
        return Color.argb(Color.alpha(color), 255 - r, 255 - g, 255 - b)
    }
}
