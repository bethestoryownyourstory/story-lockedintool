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
        showBackdrop()
        if (savedInstanceState != null) { cameraFile = savedInstanceState.getString("cam")?.let { File(it) }; return }
        // Your note stays behind the phone's screen (owner: never your apps): its picture is shown first, and only once
        // it is on screen do the Pages step aside and the phone's own screen open over it.
        val root = window.decorView
        if (backdrop == null) { OverlayService.instance?.noteStepAside(); launch(); return }
        root.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            var fired = false
            override fun onDraw() {
                if (fired) return
                fired = true
                root.post {
                    root.viewTreeObserver.removeOnDrawListener(this)
                    root.postDelayed({ OverlayService.instance?.noteStepAside(); launch() }, 16)
                }
            }
        })
    }

    /** The picture of the Pages taken just before this opened, exactly where the Pages were (black around it). */
    private fun showBackdrop() {
        val frame = android.widget.FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        backdrop?.let { bmp ->
            val iv = android.widget.ImageView(this).apply { setImageBitmap(bmp); scaleType = android.widget.ImageView.ScaleType.FIT_XY }
            frame.addView(iv, android.widget.FrameLayout.LayoutParams(bmp.width, bmp.height).apply { leftMargin = backdropX; topMargin = backdropY })
        }
        if (backdrop == null) frame.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setContentView(frame)
        // Drawn over the whole screen in screen coordinates, so the picture lines up with where the Pages were.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    private fun launch() {
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
            "share" -> startShare()
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
        if (kind == "share") {
            // The phone tells STORY which app you picked a moment after the share screen closes.
            window.decorView.postDelayed({ done(if (ShareChosenReceiver.at >= shareAt) "shared" else "cancel", null) }, 450)
            return
        }
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

    /** Share to any app: the phone's own share screen with the note's text and / or the drawing's pictures. */
    private var shareAt = 0L
    private fun startShare() {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
        val uris = ArrayList<Uri>()
        for (path in intent.getStringArrayListExtra(EXTRA_FILES) ?: arrayListOf()) {
            runCatching { FileProvider.getUriForFile(this, "com.story.launcher.files", File(path)) }.getOrNull()?.let { uris.add(it) }
        }
        val send = when {
            uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            uris.size == 1 -> Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uris[0])
            else -> Intent(Intent.ACTION_SEND).setType("text/plain")
        }
        if (text.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title)
        if (uris.isNotEmpty()) {
            send.clipData = android.content.ClipData.newUri(contentResolver, title.ifBlank { "Drawing" }, uris[0]).apply { for (u in uris.drop(1)) addItem(android.content.ClipData.Item(u)) }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Knows whether you picked an app (then you stay in it) or backed out (then the note comes back).
        val chosen = android.app.PendingIntent.getBroadcast(this, 11, Intent(this, ShareChosenReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
        shareAt = System.currentTimeMillis()
        val chooser = Intent.createChooser(send, title.ifBlank { "Share" }, chosen.intentSender)
        runCatching { startActivityForResult(chooser, 2) }.onFailure { done("error", null) }
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
        // The Pages come back first, then this (with your note's picture) goes - so your apps never flash in between.
        window.decorView.postDelayed({ finish(); overridePendingTransition(0, 0); backdrop = null }, if (status == "shared") 0L else 120L)
    }

    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.noteMediaDone(kind, "cancel", "{}") }
        printView?.destroy()
        super.onDestroy()
    }

    companion object {
        /** The Pages as they looked when you tapped (set by OverlayService just before this opens). */
        @Volatile var backdrop: Bitmap? = null
        var backdropX = 0
        var backdropY = 0
        const val EXTRA_KIND = "kind"
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FILES = "files"
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

/** The phone's share screen says an app was picked. */
class ShareChosenReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { at = System.currentTimeMillis() }
    companion object { @Volatile var at = 0L }
}
