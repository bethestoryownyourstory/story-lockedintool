package com.story.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract

/** The dial pad's "New contact": the phone's own new-contact screen with the number you typed already filled in
 *  (see-through trampoline). STORY steps aside while it's up and comes straight back when you save or go back. */
class ContactAddActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        // On the lock screen (owner: Station 3 works the same there): Android asks for your PIN / fingerprint first,
        // then the new-contact screen opens.
        val km = getSystemService(android.app.KeyguardManager::class.java)
        if (km != null && km.isKeyguardLocked && android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            km.requestDismissKeyguard(this, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { open() }
                override fun onDismissCancelled() { done(false) }
                override fun onDismissError() { done(false) }
            })
            return
        }
        open()
    }

    private fun open() {
        val i = Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)
            .putExtra(ContactsContract.Intents.Insert.PHONE, intent.getStringExtra(EXTRA_NUMBER).orEmpty())
            .putExtra("finishActivityOnSaveCompleted", true)
        runCatching { startActivityForResult(i, 7) }.onFailure { done(false) }
    }

    @Deprecated("Activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        done(resultCode == RESULT_OK)
    }

    private var reported = false
    private fun done(saved: Boolean) {
        if (!reported) { reported = true; OverlayService.instance?.contactDone(saved) }
        finish(); overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.contactDone(false) }
        super.onDestroy()
    }

    companion object { const val EXTRA_NUMBER = "number" }
}
