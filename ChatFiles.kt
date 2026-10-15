package com.story.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Files sent in chats, groups and community posts (owner: pictures, videos, documents, audio).
 *  A document / audio file you pick is copied into filesDir/chat (served to the windows at story-photo/chatfile/<name>);
 *  a file someone sent you is downloaded, unlocked on this phone (AES-GCM, the key came inside the locked message)
 *  and opened with the phone's own app. */
object ChatFiles {
    const val MAX = 40L * 1024 * 1024
    private fun dir(c: Context) = File(c.filesDir, "chat").apply { mkdirs() }
    private fun outDir(c: Context) = File(c.filesDir, "chatopen").apply { mkdirs() }

    fun file(c: Context, name: String): File? {
        if (!Regex("^[A-Za-z0-9_.-]{1,80}$").matches(name)) return null
        return File(dir(c), name).takeIf { it.exists() }
    }

    /** Copies what you picked into STORY's chat folder: {file, name, mime, size} (null = failed / too big). */
    fun copyIn(c: Context, uri: Uri): JSONObject? = runCatching {
        var name = "file"; var size = -1L
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
        }
        if (size > MAX) return JSONObject().put("error", "big")
        val mime = c.contentResolver.getType(uri) ?: "application/octet-stream"
        val ext = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
        val f = File(dir(c), "f" + System.currentTimeMillis() + (100..999).random() + if (ext.isNotEmpty()) ".$ext" else "")
        var total = 0L
        c.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { out ->
            val buf = ByteArray(65536)
            while (true) { val n = input.read(buf); if (n < 0) break; total += n; if (total > MAX) break; out.write(buf, 0, n) }
        } } ?: return null
        if (total > MAX) { f.delete(); return JSONObject().put("error", "big") }
        JSONObject().put("file", f.name).put("name", name).put("mime", mime).put("size", total)
    }.getOrNull()

    /** Downloads a file from a chat, unlocks it (key / iv empty = it wasn't locked) and opens it with the phone's app. */
    fun open(c: Context, url: String, key: String, iv: String, name: String, mime: String): Boolean = runCatching {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 60000 }
        val data = conn.inputStream.use { it.readBytes() }
        val bytes = if (key.isNotEmpty()) {
            val k = javax.crypto.spec.SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, k, javax.crypto.spec.GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)))
            cipher.doFinal(data)
        } else data
        outDir(c).listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86400000L) it.delete() }
        val safe = name.replace(Regex("[^A-Za-z0-9 _.()-]"), "_").take(80).ifBlank { "file" }
        val f = File(outDir(c), safe).apply { writeBytes(bytes) }
        val u = androidx.core.content.FileProvider.getUriForFile(c, "com.story.launcher.files", f)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime.ifBlank { "application/octet-stream" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    }.getOrDefault(false)
}

/** See-through: opens the phone's own file picker for a document ("doc") or an audio file ("audio") to send. */
class ChatFileActivity : Activity() {
    private var reported = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val audio = intent.getStringExtra(EXTRA_KIND) == "audio"
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (audio) "audio/*" else "*/*")
        runCatching { startActivityForResult(pick, 1) }.onFailure {
            runCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(if (audio) "audio/*" else "*/*"), 1) }.onFailure { done(null) }
        }
    }
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { done(JSONObject().put("error", "cancel")); return }
        Thread { val j = ChatFiles.copyIn(this, uri) ?: JSONObject().put("error", "fail"); runOnUiThread { done(j) } }.start()
    }
    private fun done(j: JSONObject?) {
        if (!reported) { reported = true; OverlayService.instance?.chatFilePicked((j ?: JSONObject().put("error", "fail")).put("kind", intent.getStringExtra(EXTRA_KIND) ?: "doc").toString()) }
        finish(); overridePendingTransition(0, 0)
    }
    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.chatFilePicked(JSONObject().put("error", "cancel").toString()) }
        super.onDestroy()
    }
    companion object { const val EXTRA_KIND = "kind" }
}
