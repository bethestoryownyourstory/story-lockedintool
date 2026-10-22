package com.story.launcher

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal

/** "Is it really you?" - the phone's own fingerprint / face / PIN / pattern screen. See-through, so only the phone's own
 *  prompt shows. Used by Delete account (owner: asks for the phone's PIN or biometrics), Lockedintool security (unlock
 *  Lockedintool every time you use it) and the lock screen's Wallet page button. On the lock screen it is the phone's
 *  own unlock (Android shows its PIN / fingerprint page and the phone unlocks). Answer: "ok", "fail" (cancelled / wrong)
 *  or "nolock" (the phone has no screen lock to ask for) -> OverlayService (identityDone / secDone / walletDone). */
class IdentityActivity : Activity() {
    private var reported = false
    private val purpose get() = intent.getStringExtra(EXTRA_FOR).orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        // On the lock screen: the phone's own unlock (its PIN page / fingerprint), then Lockedintool goes on.
        if (km.isKeyguardLocked && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { done("ok") }
                override fun onDismissCancelled() { done("fail") }
                override fun onDismissError() { done("fail") }
            })
            return
        }
        if (purpose == "wallet") { done("ok"); return }   // already unlocked: the Wallet page just opens (Lockedintool security asks next)
        if (!km.isDeviceSecure) { done("nolock"); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Confirm it's you"
        if (Build.VERSION.SDK_INT >= 29) {
            val b = BiometricPrompt.Builder(this).setTitle(title).setSubtitle("Use your fingerprint, face, PIN or pattern")
            if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            runCatching {
                b.build().authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { done("ok") }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { done("fail") }
                })
            }.onFailure { credentialScreen(km, title) }
        } else credentialScreen(km, title)
    }

    @Suppress("DEPRECATION")
    private fun credentialScreen(km: KeyguardManager, title: String) {
        val i = km.createConfirmDeviceCredentialIntent(title, "Use your PIN, pattern or password")
        if (i == null) { done("nolock"); return }
        runCatching { startActivityForResult(i, 1) }.onFailure { done("fail") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        done(if (resultCode == RESULT_OK) "ok" else "fail")
    }

    private fun report(result: String) {
        val s = OverlayService.instance ?: return
        when (purpose) { "security" -> s.secDone(result); "wallet" -> s.walletDone(result); else -> s.identityDone(result) }
    }

    private fun done(result: String) {
        if (!reported) { reported = true; report(result) }
        finish(); overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; report("fail") }
        super.onDestroy()
    }

    companion object { const val EXTRA_TITLE = "title"; const val EXTRA_FOR = "for" }
}
