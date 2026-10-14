package com.story.launcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.io.File

/**
 * STORY's pages on the phone's home screen (owner: "like widgets but shows the full page like how it shows in the
 * pages; add and remove them like widgets"). Money, Wallet and Focus are each a real Android widget: added from the
 * phone's own widget list, resized and removed like any widget. Android only lets a widget show a picture, so each
 * one shows a picture of the whole page exactly as it looks on the Pages (made by OverlayService.widgetSnap), and
 * tapping it opens that page in STORY. The picture is made again only when something could have changed (the Pages
 * just closed, a new payment) or every 30 minutes - and only for pages that have a widget on the home screen.
 */
open class PageWidget(private val page: String) : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        PageWidgets.show(c, page)
        PageWidgets.refresh(c, page, minGapMs = 0)
    }
    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, o: Bundle) { PageWidgets.show(c, page) }
    override fun onDisabled(c: Context) { PageWidgets.file(c, page).delete() }
    override fun onDeleted(c: Context, ids: IntArray) { OverlayService.instance?.widgetLiveEvent(false) }
}
class MoneyWidget : PageWidget("money")
class WalletWidget : PageWidget("wallet")
class FocusWidget : PageWidget("trading")
class DashboardWidget : PageWidget("dashboard")
class NotesWidget : PageWidget("notes")
class CommunityWidget : PageWidget("community")

object PageWidgets {
    const val ACTION_OPEN_PAGE = "com.story.launcher.OPEN_PAGE"
    private val providers = mapOf("dashboard" to DashboardWidget::class.java, "money" to MoneyWidget::class.java, "wallet" to WalletWidget::class.java, "trading" to FocusWidget::class.java, "notes" to NotesWidget::class.java, "community" to CommunityWidget::class.java)
    private val names = mapOf("dashboard" to "Dashboard", "money" to "Money", "wallet" to "Home / Wallet", "trading" to "Focus", "notes" to "Notes", "community" to "Community")
    private val lastSnap = HashMap<String, Long>()

    fun file(c: Context, page: String) = File(File(c.filesDir, "widgets").apply { mkdirs() }, "$page.png")
    private fun ids(c: Context, page: String): IntArray =
        runCatching { AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, providers.getValue(page))) }.getOrDefault(IntArray(0))
    fun placed(c: Context, page: String) = ids(c, page).isNotEmpty()
    /** Every STORY widget on the home screen. */
    fun allIds(c: Context): Set<Int> = providers.keys.flatMap { ids(c, it).toList() }.toSet()
    /** Which page a widget shows. */
    fun pageOf(c: Context, id: Int): String? = providers.keys.firstOrNull { p -> ids(c, p).contains(id) }

    /** Make the page's picture again (if it has a widget on the home screen and not within the last minGapMs). */
    fun refresh(c: Context, page: String, minGapMs: Long = 60_000L) {
        if (!placed(c, page)) return
        val now = System.currentTimeMillis()
        if (now - (lastSnap[page] ?: 0L) < minGapMs) return
        val s = OverlayService.instance ?: return
        lastSnap[page] = now
        s.widgetSnap(page)
    }
    fun refreshPlaced(c: Context, pages: Collection<String> = providers.keys, minGapMs: Long = 60_000L) { pages.forEach { refresh(c, it, minGapMs) } }

    /** A fresh picture of the page: kept on the phone, then put on every widget of that page. */
    fun save(c: Context, page: String, bmp: Bitmap) {
        runCatching { file(c, page).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        show(c, page)
    }

    /** Puts the page's latest picture on each of its widgets, sized to the widget (tap = open that page in STORY). */
    fun show(c: Context, page: String) {
        val m = AppWidgetManager.getInstance(c)
        val f = file(c, page)
        val full = if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() else null
        val open = Intent(c, OverlayService::class.java).setAction(ACTION_OPEN_PAGE).putExtra("page", page)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = if (Build.VERSION.SDK_INT >= 26) PendingIntent.getForegroundService(c, page.hashCode(), open, flags)
            else PendingIntent.getService(c, page.hashCode(), open, flags)
        val bg = StoryTheme.swap(c, Color.BLACK)
        ids(c, page).forEach { id ->
            fun views(scale: Float): RemoteViews {
                val rv = RemoteViews(c.packageName, R.layout.widget_page)
                rv.setInt(R.id.widget_root, "setBackgroundColor", bg)
                rv.setTextColor(R.id.widget_label, StoryTheme.swap(c, Color.WHITE))
                rv.setTextViewText(R.id.widget_label, names[page] ?: "Lockedintool")
                rv.setOnClickPendingIntent(R.id.widget_root, pi)
                rv.setContentDescription(R.id.widget_root, "Lockedintool " + (names[page] ?: page) + " widget #" + id)  // how STORY finds it to lay the live page over it
                if (full != null) {
                    rv.setImageViewBitmap(R.id.widget_image, fit(c, full, m.getAppWidgetOptions(id), scale))
                    rv.setViewVisibility(R.id.widget_label, View.GONE)
                } else rv.setViewVisibility(R.id.widget_label, View.VISIBLE)
                return rv
            }
            // A big widget's picture is sent at the widget's own size; if Android says it's too much, half of it.
            if (runCatching { m.updateAppWidget(id, views(1f)) }.isFailure) runCatching { m.updateAppWidget(id, views(0.5f)) }
        }
    }

    /** The whole page, shrunk to fit inside the widget (never stretched, never cropped). */
    private fun fit(c: Context, b: Bitmap, o: Bundle, scale: Float): Bitmap {
        val d = c.resources.displayMetrics.density
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 } ?: 250
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 400
        val k = minOf(wDp * d / b.width, hDp * d / b.height, 1f) * scale
        val w = (b.width * k).toInt().coerceAtLeast(1); val h = (b.height * k).toInt().coerceAtLeast(1)
        return if (w == b.width && h == b.height) b else Bitmap.createScaledBitmap(b, w, h, true)
    }
}
