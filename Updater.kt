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
    /** Download progress 0-100 while downloading, -1 while installing (no percentage), null otherwise. */
    fun progress(ctx: Context): Int? {
        val p = ctx.getSharedPreferences("story", Context.MODE_PRIVATE)
        return if (p.contains("upd_progress")) p.getInt("upd_progress", 0) else null
    }
    private fun setProgress(ctx: Context, pct: Int?) {
        val e = ctx.getSharedPreferences("story", Context.MODE_PRIVATE).edit()
        if (pct == null) e.remove("upd_progress") else e.putInt("upd_progress", pct)
        e.apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (pct == null) { nm.cancel(PROGRESS_NOTIFICATION); return }
        nm.createNotificationChannel(NotificationChannel("story_progress", "STORY update progress", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(ctx, "story_progress")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (pct >= 0) "Updating STORY…" else "Installing STORY update…")
            .setContentText(if (pct >= 0) "Downloading $pct%" else "Almost done")
            .setProgress(100, maxOf(pct, 0), pct < 0)
            .setOngoing(true).setOnlyAlertOnce(true).build()
        runCatching { nm.notify(PROGRESS_NOTIFICATION, n) }
    }
    /** The download is done and handed to Android's installer (or it failed): the loading bar goes away. */
    fun doneProgress(ctx: Context) = setProgress(ctx, null)
    const val PROGRESS_NOTIFICATION = 4

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
            val apk = download(base, ctx, latest)
            setStatus(ctx, "Installing build $latest…")
            setProgress(ctx, -1)
            install(ctx, apk)
        } catch (e: Throwable) {
            doneProgress(ctx)
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
        val v = ctx.getSharedPreferences("story", Context.MODE_PRIVATE).getLong("upd_version", 0)
        val apk = runCatching { download(Release.base(ctx), ctx, v) }.getOrNull() ?: run { doneProgress(ctx); return "Couldn't download the update. Check your connection and try again." }
        setProgress(ctx, -1)
        return runCatching { install(ctx, apk); null }.getOrElse { doneProgress(ctx); "Couldn't start the update: ${it.message}" }
    }

    private fun apkFile(ctx: Context) = File(ctx.cacheDir, "story-update.apk")

    /** Downloads the update, showing a loading bar (notification + setup screen) as it goes. */
    private fun download(base: String, ctx: Context, version: Long): File {
        val apk = apkFile(ctx)
        val label = if (version > 0) "build $version" else "the update"
        val c = open(base + "app-debug.apk")
        val total = c.contentLengthLong
        var done = 0L; var shown = -1
        setStatus(ctx, "Downloading $label…"); setProgress(ctx, 0)
        c.inputStream.use { i -> apk.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); done += n
                val pct = if (total > 0) (done * 100 / total).toInt() else 0
                if (pct != shown && (pct - shown >= 2 || pct == 100)) { shown = pct; setStatus(ctx, "Downloading $label… $pct%"); setProgress(ctx, pct) }
            }
        } }
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
                // Android wants a tap. Its background-install prompt silently fails on some phones
                // (Xiaomi), so drop it and open Android's normal installer instead -- the same screen
                // as installing the file by hand, which works everywhere.
                val id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                if (id >= 0) runCatching { c.packageManager.packageInstaller.abandonSession(id) }
                Updater.doneProgress(c)
                Updater.setStatus(c, "Waiting for you to tap Update")
                showUpdateNotification(c)
                if (!Updater.installWithSystemInstaller(c)) {
                    // Plan B failed too: fall back to Android's own prompt.
                    OverlayService.instance?.pauseForInstall()
                    val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    runCatching { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { Updater.doneProgress(c); Updater.setStatus(c, "Updated") }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Updater.doneProgress(c)
                Updater.setStatus(c, "Update cancelled - it will ask again")
                OverlayService.instance?.resumeNow()
            }
            else -> {
                Updater.doneProgress(c)
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
            nm.cancel(InstallResultReceiver.UPDATE_NOTIFICATION); nm.cancel(UpdateActivity.NOTIFICATION); nm.cancel(Updater.PROGRESS_NOTIFICATION)
            c.getSharedPreferences("story", Context.MODE_PRIVATE).edit().remove("upd_progress").apply()
            val v = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode }.getOrDefault(0)
            Updater.setStatus(c, "Updated to build $v")
        }
        Updater.schedule(c)
        KeepAlive.ensureRunning(c)
    }
}
