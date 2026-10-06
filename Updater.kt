package com.story.launcher

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

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
    }

    fun check(ctx: Context) {
        // Called often (every 15 min, on unlock, on opening STORY); asking more than once a minute is pointless.
        if (busy || System.currentTimeMillis() - lastCheck < 60_000) return
        busy = true; lastCheck = System.currentTimeMillis()
        Thread {
            try {
                // Needs the one-time "allow STORY to install updates" switch from the setup screen.
                if (!ctx.packageManager.canRequestPackageInstalls()) return@Thread
                val latest = JSONObject(open(BASE + "version.json").inputStream.bufferedReader().use { it.readText() }).getLong("versionCode")
                val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
                if (latest <= mine) return@Thread
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

/** Android sometimes needs one tap to approve an update; this brings that prompt up. */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        if (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Brings STORY back after a phone restart or after an update, so nothing needs setting up again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val wanted = c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean("enabled", false)
        if (wanted && android.provider.Settings.canDrawOverlays(c)) {
            runCatching { c.startForegroundService(Intent(c, OverlayService::class.java)) }
        }
    }
}
