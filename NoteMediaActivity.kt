package com.story.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/** Notes: everything a note asks of the phone itself, see-through so you only see the phone's own screen for it.
 *  picture = pick a picture, camera = take a photo, audio = pick an audio file (each copied into STORY's own
 *  notes folder and put in the note), print = the phone's print screen (also "Save as PDF"), savetext = save the
 *  note as a text file wherever you choose. */
class NoteMediaActivity : Activity() {

    private val kind get() = intent.getStringExtra(EXTRA_KIND) ?: ""
    private var reported = false
    private var cameraFile: File? = null
    private var printStarted = false
    private var printView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { cameraFile = savedInstanceState.getString("cam")?.let { File(it) }; return }
        when (kind) {
            "picture" -> {
                val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
                    else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                start(pick, Intent(Intent.ACTION_GET_CONTENT).setType("image/*"))
            }
            "camera" -> {
                // STORY has the camera permission in its manifest, so Android only lets the phone's camera app take
                // the photo once STORY itself is allowed to use the camera.
                if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 9)
                else openCamera()
            }
            "audio" -> start(Intent(Intent.ACTION_GET_CONTENT).setType("audio/*").addCategory(Intent.CATEGORY_OPENABLE), null)
            "savetext" -> {
                val title = (intent.getStringExtra(EXTRA_TITLE) ?: "Note").ifBlank { "Note" }
                start(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                    .putExtra(Intent.EXTRA_TITLE, safeName(title) + ".txt"), null)
            }
            "print" -> startPrint()
            else -> done("cancel", null)
        }
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); cameraFile?.let { out.putString("cam", it.path) } }

    private fun start(i: Intent, fallback: Intent?) {
        runCatching { startActivityForResult(i, 1) }.onFailure {
            if (fallback != null) runCatching { startActivityForResult(fallback, 1) }.onFailure { done("error", null) }
            else done("error", null)
        }
    }

    private fun openCamera() {
        val f = File(dir(this), newId() + ".jpg")
        cameraFile = f
        val uri = runCatching { FileProvider.getUriForFile(this, "com.story.launcher.files", f) }.getOrNull()
        if (uri == null) { done("error", null); return }
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(i, null)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) openCamera() else done("denied", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) { cameraFile?.delete(); done("cancel", null); return }
        Thread {
            val out: JSONObject? = runCatching {
                when (kind) {
                    "picture" -> data?.data?.let { savePicture(it) }
                    "camera" -> cameraFile?.takeIf { it.exists() && it.length() > 0 }?.let { f -> savePicture(Uri.fromFile(f), f) }
                    "audio" -> data?.data?.let { saveAudio(it) }
                    "savetext" -> data?.data?.let { uri ->
                        contentResolver.openOutputStream(uri)?.use { it.write((intent.getStringExtra(EXTRA_TEXT) ?: "").toByteArray()) }
                        JSONObject().put("saved", true)
                    }
                    else -> null
                }
            }.getOrNull()
            runOnUiThread { done(if (out != null) "ok" else "error", out) }
        }.start()
    }

    /** Saved upright, at most 1600px on its longest side, as STORY's own copy (the note never depends on the original). */
    private fun savePicture(uri: Uri, src: File? = null): JSONObject? {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = runCatching {
            when (android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        if (rot != 0f || scale < 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        val name = (src?.nameWithoutExtension ?: newId()) + ".jpg"
        val f = File(dir(this), name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return JSONObject().put("file", name).put("w", bmp.width).put("h", bmp.height)
    }

    private fun saveAudio(uri: Uri): JSONObject? {
        var display = "Audio"
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) display = c.getString(0) ?: display }
        }
        val mime = contentResolver.getType(uri) ?: "audio/mpeg"
        val ext = display.substringAfterLast('.', "").lowercase().takeIf { it in AUDIO_EXT }
            ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it in AUDIO_EXT } ?: "mp3"
        val name = newId() + "." + ext
        val f = File(dir(this), name)
        contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } } ?: return null
        return JSONObject().put("file", name).put("name", display.substringBeforeLast('.').ifBlank { "Audio" })
    }

    /** The note, laid out as pages, handed to the phone's own print screen (which can also save it as a PDF). */
    private fun startPrint() {
        val html = runCatching { File(cacheDir, "note_print.html").readText() }.getOrNull()
        if (html == null) { done("error", null); return }
        val title = (intent.getStringExtra(EXTRA_TITLE) ?: "Note").ifBlank { "Note" }
        val wv = WebView(this)
        printView = wv
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                val u = r.url.toString()
                if (!u.startsWith(ProfilePhotoActivity.PHOTO_BASE + "note/")) return null
                val f = r.url.lastPathSegment?.let { File(dir(this@NoteMediaActivity), it) }?.takeIf { it.exists() } ?: return null
                return WebResourceResponse(mimeOf(f), null, f.inputStream())
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (printStarted) return
                printStarted = true
                runCatching {
                    getSystemService(PrintManager::class.java).print(safeName(title), v.createPrintDocumentAdapter(safeName(title)),
                        PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                }.onFailure { done("error", null) }
            }
        }
        wv.loadDataWithBaseURL(ProfilePhotoActivity.PHOTO_BASE, html, "text/html", "utf-8", null)
    }

    // The print screen closed (printed, saved or backed out): back to the note.
    private var pausedOnce = false
    override fun onPause() { super.onPause(); if (printStarted) pausedOnce = true }
    override fun onResume() {
        super.onResume()
        if (kind == "print" && printStarted && pausedOnce) done("ok", null)
    }

    private fun done(status: String, data: JSONObject?) {
        if (!reported) { reported = true; OverlayService.instance?.noteMediaDone(kind, status, data?.toString() ?: "{}") }
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.noteMediaDone(kind, "cancel", "{}") }
        printView?.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3gp", "weba", "webm")
        fun dir(c: Context) = File(c.filesDir, "notes").apply { mkdirs() }
        fun file(c: Context, name: String): File? =
            name.takeIf { Regex("[A-Za-z0-9_.-]+").matches(it) && !it.startsWith(".") }?.let { File(dir(c), it) }?.takeIf { it.exists() }
        fun mimeOf(f: File) = when (f.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
            "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "ogg", "oga", "opus" -> "audio/ogg"; "wav" -> "audio/wav"
            "flac" -> "audio/flac"; "amr" -> "audio/amr"; "3gp" -> "audio/3gpp"; "weba", "webm" -> "audio/webm"
            else -> "application/octet-stream"
        }
        private fun newId() = "n" + System.currentTimeMillis().toString(36) + (100..999).random()
        private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifBlank { "Note" }
    }
}
