package com.story.launcher

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** Notes, done inside STORY itself (owner: real actions, never leaving STORY): the phone's real pictures and audio
 *  listed in STORY, photos from STORY's own camera, PDF / text files made and saved by STORY, and STORY's own list of
 *  apps to share to. Everything a note holds is STORY's own copy in filesDir/notes. */
object NoteTools {
    fun dir(c: Context) = File(c.filesDir, "notes").apply { mkdirs() }
    fun file(c: Context, name: String): File? =
        name.takeIf { Regex("[A-Za-z0-9_.-]+").matches(it) && !it.startsWith(".") }?.let { File(dir(c), it) }?.takeIf { it.exists() }
    fun mimeOf(f: File) = when (f.extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "ogg", "oga", "opus" -> "audio/ogg"; "wav" -> "audio/wav"
        "flac" -> "audio/flac"; "amr" -> "audio/amr"; "3gp" -> "audio/3gpp"; "weba", "webm" -> "audio/webm"
        else -> "application/octet-stream"
    }
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "amr", "3gp", "weba", "webm")
    private fun newId() = "n" + System.currentTimeMillis().toString(36) + (100..999).random()
    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(60).ifBlank { "Note" }

    // ---- pictures ----
    /** A picture from the phone (its MediaStore id) as the note's own copy: upright, at most 1600px. */
    fun usePhoto(c: Context, id: Long): String? =
        savePicture(c, ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))

    /** A photo taken with STORY's camera (a JPEG data URL from the page). */
    fun savePhotoData(c: Context, dataUrl: String): String? {
        val bytes = runCatching { Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT) }.getOrNull() ?: return null
        val name = newId() + ".jpg"
        return runCatching { File(dir(c), name).writeBytes(bytes); name }.getOrNull()
    }

    private fun savePicture(c: Context, uri: Uri): String? = runCatching {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rot = runCatching {
            when (android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        val scale = minOf(1f, 1600f / maxOf(bmp.width, bmp.height))
        if (rot != 0f || scale < 1f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postScale(scale, scale); postRotate(rot) }, true)
        val name = newId() + ".jpg"
        File(dir(c), name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        name
    }.getOrNull()

    // ---- audio ----
    fun audioAccess(c: Context): Boolean = c.checkSelfPermission(
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun audioPermission(): String = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE

    /** The phone's audio, newest first: id, title, artist, length (ms). */
    fun listAudio(c: Context): String {
        val out = JSONArray()
        if (!audioAccess(c)) return out.toString()
        runCatching {
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DISPLAY_NAME),
                null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC")?.use { cur ->
                while (cur.moveToNext() && out.length() < 3000) {
                    val artist = cur.getString(2)?.takeIf { it != "<unknown>" } ?: ""
                    out.put(JSONObject().put("id", cur.getLong(0)).put("title", cur.getString(1) ?: cur.getString(4) ?: "Audio")
                        .put("artist", artist).put("dur", cur.getLong(3)))
                }
            }
        }
        return out.toString()
    }

    /** An audio file from the phone as the note's own copy. JSON {file, name} or null. */
    fun useAudio(c: Context, id: Long): JSONObject? = runCatching {
        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        var display = "Audio"; var title = ""
        c.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.TITLE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) { display = cur.getString(0) ?: display; title = cur.getString(1) ?: "" }
        }
        val mime = c.contentResolver.getType(uri) ?: "audio/mpeg"
        val ext = display.substringAfterLast('.', "").lowercase().takeIf { it in AUDIO_EXT }
            ?: android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it in AUDIO_EXT } ?: "mp3"
        val name = newId() + "." + ext
        c.contentResolver.openInputStream(uri)?.use { input -> File(dir(c), name).outputStream().use { input.copyTo(it) } } ?: return null
        JSONObject().put("file", name).put("name", title.ifBlank { display.substringBeforeLast('.') }.ifBlank { "Audio" })
    }.getOrNull()

    // ---- files made by STORY, saved straight into the phone's Documents/STORY ----
    /** Returns the folder it went into ("Documents/STORY"), or null. */
    fun saveToDocuments(c: Context, fileName: String, mime: String, bytes: ByteArray): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/STORY")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = c.contentResolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return null
            c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return null
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            c.contentResolver.update(uri, values, null, null)
            "Documents/STORY"
        } else {
            val d = File(c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "STORY").apply { mkdirs() }
            File(d, fileName).writeBytes(bytes)
            "STORY's documents folder"
        }
    }.getOrNull()

    /** The note as a real PDF, made by STORY: each note page drawn onto its own A4 page (the writing, then its drawing).
     *  The page is laid out in a WebView kept out of sight inside STORY's own window (a WebView only draws when it
     *  belongs to a window), 794 px wide = A4 at 96 px per inch, and drawn onto the PDF page at 72 points per inch. */
    fun makePdf(c: Context, host: android.view.ViewGroup?, pagesHtml: List<String>, done: (ByteArray?) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            val W = 794; val H = 1123
            val wv = android.webkit.WebView(c)
            val pdf = android.graphics.pdf.PdfDocument()
            var i = 0
            var finished = false
            fun finish(ok: Boolean) {
                if (finished) return
                finished = true
                val bytes = if (ok) runCatching { ByteArrayOutputStream().also { pdf.writeTo(it) }.toByteArray() }.getOrNull() else null
                runCatching { pdf.close() }
                runCatching { (wv.parent as? android.view.ViewGroup)?.removeView(wv) }
                wv.destroy(); done(bytes)
            }
            fun next() {
                if (i >= pagesHtml.size) { finish(true); return }
                wv.loadDataWithBaseURL(ProfilePhotoActivity.PHOTO_BASE, pagesHtml[i], "text/html", "utf-8", null)
            }
            wv.settings.javaScriptEnabled = false
            wv.settings.useWideViewPort = true
            wv.settings.loadWithOverviewMode = true
            wv.setBackgroundColor(android.graphics.Color.WHITE)
            wv.isVerticalScrollBarEnabled = false
            wv.translationX = -4f * W   // out of sight, never on screen
            host?.addView(wv, android.widget.FrameLayout.LayoutParams(W, H))
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldInterceptRequest(v: android.webkit.WebView, r: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                    val u = r.url.toString()
                    if (!u.startsWith(ProfilePhotoActivity.PHOTO_BASE + "note/")) return null
                    val f = r.url.lastPathSegment?.let { file(c, it) } ?: return null
                    return android.webkit.WebResourceResponse(mimeOf(f), null, f.inputStream())
                }
                override fun onPageFinished(v: android.webkit.WebView, url: String) {
                    // Pictures get a moment to draw; then the page is made as tall as its writing and drawn onto the PDF.
                    main.postDelayed({
                        val h = maxOf(H, (v.contentHeight * (W / 794f)).toInt())
                        v.layoutParams = (v.layoutParams ?: android.widget.FrameLayout.LayoutParams(W, h)).apply { width = W; height = h }
                        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(W, android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
                        v.layout(0, 0, W, h)
                        main.postDelayed({
                            val ok = runCatching {
                                val page = pdf.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                                val cv = page.canvas
                                val s = 0.75f * minOf(1f, H.toFloat() / h)   // a long page is shrunk to fit its sheet
                                cv.scale(s, s)
                                v.draw(cv)
                                pdf.finishPage(page)
                            }.isSuccess
                            if (!ok) { finish(false); return@postDelayed }
                            i++; next()
                        }, 120)
                    }, 400)
                }
            }
            main.postDelayed({ finish(false) }, 30_000L + 4_000L * pagesHtml.size)   // never hangs
            next()
        }
    }

    // ---- STORY's own list of apps to share to ----
    /** Apps that take what's being shared ("text" or "image"), with their real icons, most used kinds first. */
    fun shareTargets(c: Context, type: String): String {
        val pm = c.packageManager
        val probe = Intent(if (type == "images") Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).setType(if (type == "text") "text/plain" else "image/png")
        val out = JSONArray()
        pm.queryIntentActivities(probe, 0)
            .filter { it.activityInfo.packageName != c.packageName }
            .map { it to it.loadLabel(pm).toString() }
            .distinctBy { it.first.activityInfo.packageName + "/" + it.first.activityInfo.name }
            .forEach { (info, label) ->
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                runCatching { info.loadIcon(pm).apply { setBounds(0, 0, 96, 96); draw(Canvas(bmp)) } }
                val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 90, bos)
                val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.activityInfo.packageName, 0)).toString() }.getOrDefault(label)
                out.put(JSONObject().put("pkg", info.activityInfo.packageName).put("cls", info.activityInfo.name)
                    .put("label", label).put("app", app).put("icon", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)))
            }
        return out.toString()
    }

    /** Opens the app you picked with the note in it (the text, and the pictures as files it may read). */
    fun shareTo(c: Context, pkg: String, cls: String, title: String, text: String, images: List<File>): Boolean = runCatching {
        val uris = ArrayList(images.mapNotNull { runCatching { androidx.core.content.FileProvider.getUriForFile(c, "com.story.launcher.files", it) }.getOrNull() })
        val send = when {
            uris.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            uris.size == 1 -> Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uris[0])
            else -> Intent(Intent.ACTION_SEND).setType("text/plain")
        }
        if (text.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, title)
        if (uris.isNotEmpty()) {
            send.clipData = android.content.ClipData.newUri(c.contentResolver, title.ifBlank { "STORY" }, uris[0]).apply { for (u in uris.drop(1)) addItem(android.content.ClipData.Item(u)) }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            for (u in uris) c.grantUriPermission(pkg, u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        send.setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(send)
        true
    }.getOrDefault(false)
}
