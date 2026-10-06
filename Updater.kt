package com.story.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Looks for a newer STORY build and installs it by itself (see InstallResultReceiver for the one-time approval). */
object Updater {
    private const val BASE = "https://github.com/bethestoryownyourstory/story-lockedintool/releases/download/latest/"
    @Volatile private var busy = false
    @Volatile private var lastCheck = 0L
    // The version Android is already asking to install, so the prompt doesn't pop up again every minute.
    @Volatile private var offered = 0L; @Volatile private var offeredAt = 0L

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true; useCaches = false
    }

    /** force = you opened STORY yourself: always ask again, even if an install prompt was just shown and missed. */
    fun check(ctx: Context, force: Boolean = false) {
        // Called every minute while the screen is on, on unlock and on opening STORY.
        if (busy || (!force && System.currentTimeMillis() - lastCheck < 30_000)) return
        busy = true; lastCheck = System.currentTimeMillis()
        Thread {
            try {
                // Needs the one-time "allow STORY to install updates" switch from the setup screen.
                if (!ctx.packageManager.canRequestPackageInstalls()) return@Thread
                val latest = JSONObject(open(BASE + "version.json").inputStream.bufferedReader().use { it.readText() }).getLong("versionCode")
                val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
                if (latest <= mine) {  // up to date: clear any leftover "update ready" notification
                    ctx.getSystemService(android.app.NotificationManager::class.java).cancel(InstallResultReceiver.UPDATE_NOTIFICATION)
                    return@Thread
                }
                if (!force && latest == offered && System.currentTimeMillis() - offeredAt < 10 * 60_000) return@Thread
                offered = latest; offeredAt = System.currentTimeMillis()
                val apk = File(ctx.cacheDir, "story-update.apk")
                open(BASE + "app-debug.apk").inputStream.use { i -> apk.outputStream().use { o -> i.copyTo(o) } }
                install(ctx, apk)
            } catch (_: Throwable) {
            } finally { busy = false }
        }.start()
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("story", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
    }
}

/**
 * Android sometimes needs one tap to approve an update; this brings that prompt up, and also
 * leaves a "STORY update ready" notification that stays until the update is installed. Missed
 * the prompt? Tap the notification: it opens STORY, which shows the prompt again.
 */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            showUpdateNotification(c)
            val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    companion object {
        const val UPDATE_NOTIFICATION = 2

        fun showUpdateNotification(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("story_update", "STORY updates", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "story_update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("STORY update ready")
                .setContentText("Tap to install")
                .setContentIntent(open)
                .setOngoing(true)       // can't be swiped away by accident
                .setAutoCancel(false)   // stays after tapping, until the update is really installed
                .build()
            runCatching { nm.notify(UPDATE_NOTIFICATION, n) }
        }
    }
}

/** Brings STORY back after a phone restart or after an update, so nothing needs setting up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // This version is installed now, so the "update ready" notification is done.
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) c.getSystemService(android.app.NotificationManager::class.java).cancel(InstallResultReceiver.UPDATE_NOTIFICATION)
        val wanted = c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean("enabled", false)
        if (wanted && android.provider.Settings.canDrawOverlays(c)) {
            runCatching { c.startForegroundService(Intent(c, OverlayService::class.java)) }
        }
    }
}
