package com.story.launcher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Important data saved - card details (owner, 3 Nov 2026). A card's full number, expiry and CVV, kept ONLY on this
 *  phone, locked (AES-GCM) with a key that lives in the phone's own key store (it never leaves the phone and can't be
 *  copied off it). Never sent to any server, never in the locked backup. The details are handed to the Wallet page only
 *  for a short moment after the phone's own fingerprint / face / PIN said yes (IdentityActivity purpose "card" ->
 *  OverlayService.cardDone -> open()). Delete account / wipeAll clears the prefs file with the rest. */
object CardVault {
    private const val ALIAS = "lit_card_vault"
    private const val PREFS = "card_vault"
    private const val OPEN_MS = 30_000L
    @Volatile private var openUntil = 0L
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return g.generateKey()
    }

    fun put(ctx: Context, id: String, value: String): Boolean = runCatching {
        if (!ID.matches(id) || value.length > 2000) return false
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        val box = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, box).commit()
    }.getOrDefault(false)

    fun has(ctx: Context, id: String): Boolean = ID.matches(id) && ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(id)

    fun del(ctx: Context, id: String) { if (ID.matches(id)) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply() }

    /** Every card's details, for the locked backup only (owner: 'If log back on Lockedintool, data is saved') - the page
     *  locks them with a key from your password before anything leaves the phone (litBackupNow). */
    fun all(ctx: Context): org.json.JSONObject {
        val o = org.json.JSONObject()
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for ((id, box) in p.all) runCatching {
            val (iv, ct) = (box as String).split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            o.put(id, String(c.doFinal(ct), Charsets.UTF_8))
        }
        return o
    }

    /** The phone's own unlock just said yes. */
    fun open() { openUntil = System.currentTimeMillis() + OPEN_MS }
    fun close() { openUntil = 0L }

    /** The card's details - only right after the phone's own unlock said yes; otherwise null. */
    fun read(ctx: Context, id: String): String? {
        if (System.currentTimeMillis() > openUntil || !ID.matches(id)) return null
        val box = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(id, null) ?: return null
        return runCatching {
            val (iv, ct) = box.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        }.getOrNull()
    }
}
