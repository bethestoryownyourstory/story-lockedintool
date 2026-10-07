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
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Looks for a newer STORY build on this phone's channel (see Release), for everyone, whether or
 * not STORY is switched on: Android runs UpdateWorker every 15 minutes, and STORY also checks
 * every minute while it runs, on unlock and whenever the setup screen opens.
 *  - Test (owner): installs it by itself, straight away.
 *  - Live (everyone else): shows "Update available" (Update now / Later); required after 3 days.
 * What it's doing is shown on the setup screen ("Updates: ...").
 */
object Updater {
    @Volatile private var busy = false
    @Volatile private var promptedAt = 0L
    @Volatile private var lastCheck = 0L
    // The version Android is already asking to install, so the prompt doesn't pop up again every minute.
    @Volatile private var offered = 0L; @Volatile private var offeredAt = 0L

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true; useCaches = false
    }

    // ---- What the setup screen shows ----
    fun setStatus(ctx: Context, s: String) {
        ctx.getSharedPreferences("story", Context.MODE_PRIVATE).edit().putString("upd_status", s).putLong("upd_status_at", System.currentTimeMillis()).apply()
    }
    fun status(ctx: Context): Pair<String, Long> {
        val p = ctx.getSharedPreferences("story", Context.MODE_PRIVATE)
        return (p.getString("upd_status", null) ?: "Not checked yet") to p.getLong("upd_status_at", 0)
    }

    /** Every 15 minutes with internet, even when STORY is off. Safe to call often. */
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        runCatching { WorkManager.getInstance(ctx.applicationContext).enqueueUniquePeriodicWork("story-update", ExistingPeriodicWorkPolicy.KEEP, req) }
    }

    /** force = you opened STORY yourself: always ask again, even if an install prompt was just shown and missed. */
    fun check(ctx: Context, force: Boolean = false) {
        if (busy || (!force && System.currentTimeMillis() - lastCheck < 30_000)) return
        Thread { checkNow(ctx.applicationContext, force) }.start()
    }

    /** Blocking version (UpdateWorker runs this). */
    fun checkNow(ctx: Context, force: Boolean = false) {
        if (busy) return
        busy = true; lastCheck = System.currentTimeMillis()
        try {
            val base = Release.base(ctx)
            val latest = JSONObject(open(base + "version.json").inputStream.bufferedReader().use { it.readText() }).getLong("versionCode")
            val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
            if (latest <= mine) {  // up to date: clear any leftover update notifications
                setStatus(ctx, "Up to date (build $mine)")
                Release.clearPending(ctx)
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.cancel(InstallResultReceiver.UPDATE_NOTIFICATION); nm.cancel(UpdateActivity.NOTIFICATION)
                return
            }
            if (!Release.isOwner(ctx)) { setStatus(ctx, "Build $latest is available"); offerToUser(ctx, latest, force); return }
            // Test channel (owner): install by itself. Needs the one-time "allow STORY to update itself" switch.
            if (!ctx.packageManager.canRequestPackageInstalls()) { setStatus(ctx, "Build $latest is ready - tap \"Allow STORY to update itself\""); return }
            if (!force && latest == offered && System.currentTimeMillis() - offeredAt < 10 * 60_000) return
            offered = latest; offeredAt = System.currentTimeMillis()
            setStatus(ctx, "Downloading build $latest…")
            val apk = download(base, ctx)
            setStatus(ctx, "Installing build $latest…")
            install(ctx, apk)
        } catch (e: Throwable) {
            setStatus(ctx, "Couldn't check for updates (no internet?)")
        } finally { busy = false }
    }

    /** Live users: a new version is out. Ask (Update now / Later); after 3 days it's required. */
    private fun offerToUser(ctx: Context, latest: Long, force: Boolean) {
        Release.notePending(ctx, latest)
        val required = Release.isRequired(ctx)
        UpdateActivity.notify(ctx, required)
        val now = System.currentTimeMillis()
        if (!required && now < Release.laterUntil(ctx)) return          // they tapped Later: wait a day (or until required)
        if (!force && now - promptedAt < 30 * 60_000) return              // don't pop up more than every 30 minutes
        promptedAt = now
        runCatching { ctx.startActivity(Intent(ctx, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** "Update now" in the Update available window. Returns a message if it couldn't start. Run off the main thread. */
    fun installNow(ctx: Context): String? {
        if (!ctx.packageManager.canRequestPackageInstalls()) return "allow"
        val apk = runCatching { download(Release.base(ctx), ctx) }.getOrNull() ?: return "Couldn't download the update. Check your connection and try again."
        return runCatching { install(ctx, apk); null }.getOrElse { "Couldn't start the update: ${it.message}" }
    }

    private fun apkFile(ctx: Context) = File(ctx.cacheDir, "story-update.apk")

    private fun download(base: String, ctx: Context): File {
        val apk = apkFile(ctx)
        open(base + "app-debug.apk").inputStream.use { i -> apk.outputStream().use { o -> i.copyTo(o) } }
        return apk
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        // STORY becomes the owner of its own updates, so from then on they install with no prompt.
        if (Build.VERSION.SDK_INT >= 34) params.setRequestUpdateOwnership(true)
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

    /**
     * Plan B when the quiet install fails (some phones, e.g. Xiaomi, refuse it): open the already
     * downloaded update in Android's normal installer -- the same screen as installing it by hand.
     */
    fun installWithSystemInstaller(ctx: Context): Boolean = runCatching {
        val apk = apkFile(ctx)
        if (!apk.exists()) return false
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        OverlayService.instance?.pauseForInstall()
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/** Android runs this every 15 minutes (with internet), even when STORY is switched off. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        KeepAlive.ensureRunning(applicationContext)
        Updater.checkNow(applicationContext)
        return Result.success()
    }
}

/** Once you tap Start, STORY stays on until you tap Stop -- after restarts, updates and app kills. */
object KeepAlive {
    fun ensureRunning(c: Context) {
        val wanted = c.getSharedPreferences("story", Context.MODE_PRIVATE).getBoolean("enabled", false)
        if (wanted && !OverlayService.running && android.provider.Settings.canDrawOverlays(c)) {
            runCatching { c.startForegroundService(Intent(c, OverlayService::class.java)) }
        }
    }
}

/**
 * The result of an update. Android sometimes needs one tap to approve it: this brings that prompt
 * up, and leaves a "STORY update ready" notification until it's installed. If the quiet install
 * fails, it says why on the setup screen and opens Android's normal installer instead.
 */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                Updater.setStatus(c, "Waiting for you to tap Update")
                showUpdateNotification(c)
                OverlayService.instance?.pauseForInstall()  // so the Update button can be pressed
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.setStatus(c, "Updated")
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Updater.setStatus(c, "Update cancelled - it will ask again")
                OverlayService.instance?.resumeNow()
            }
            else -> {
                val why = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                Updater.setStatus(c, "Quiet update failed ($why) - opening Android's installer")
                OverlayService.instance?.resumeNow()
                if (!Updater.installWithSystemInstaller(c)) Updater.setStatus(c, "Update failed: $why")
            }
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
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.cancel(InstallResultReceiver.UPDATE_NOTIFICATION); nm.cancel(UpdateActivity.NOTIFICATION)
            val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0)
            Updater.setStatus(c, "Updated to build $v")
        }
        Updater.schedule(c)
        KeepAlive.ensureRunning(c)
    }
}
