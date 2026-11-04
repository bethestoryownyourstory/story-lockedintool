package com.story.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Lockedintool checks it is itself (owner, 4 Nov 2026: no free paid plans, no mods): the app's signing certificate must
 *  be Lockedintool's own key (the same SHA-256 the build checks in android.yml). A repackaged / modded copy is signed with
 *  another key, so it reads as not genuine - the page then gives it no paid parts, and it's reported once. Honest limit:
 *  this raises the bar a lot, but someone who also strips this check out could still change what runs on their own
 *  phone - everything the server provides (calls, plans, payments) is checked on the server as well. */
object Genuine {
    private const val KEY = "fc583323c0e7c386ff9388fbf1a033c0b1dd5962ceb523caee58cc5dadbb3569"
    @Volatile private var cached: Boolean? = null
    fun ok(ctx: Context): Boolean {
        cached?.let { return it }
        val r = runCatching {
            val pm = ctx.packageManager
            val sigs = if (Build.VERSION.SDK_INT >= 28) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (info == null) emptyArray() else if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            } else @Suppress("DEPRECATION") pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
            (sigs ?: emptyArray()).any { sig ->
                MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } == KEY
            }
        }.getOrDefault(true)   // the check itself failing (a phone quirk) never punishes a real install
        cached = r
        if (!r) ProblemLog.record(ctx, "tamper", "app not signed with Lockedintool's key", "genuine")
        return r
    }
}
