package com.story.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import java.io.File

/** Edit profile > Change photo: opens the phone's own photo picker (any picture you want) and saves the one you
 *  pick as STORY's profile picture. See-through, so you only ever see the picker itself. */
class ProfilePhotoActivity : Activity() {

    private val accessMode get() = intent.getBooleanExtra(EXTRA_ACCESS, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return  // the picker is already up
        if (accessMode) {
            // One time only: let STORY see the phone's pictures, so Change photo shows them instantly in STORY itself.
            requestPermissions(accessPermissions(), 2)
            return
        }
        val pick = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(pick, 1) }.onFailure {
            runCatching { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 1) }.onFailure { done(false) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { done(false); return }  // backed out: nothing changes
        Thread {
            val ok = runCatching { save(this, uri) }.getOrDefault(false)
            runOnUiThread { done(ok, failed = !ok) }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!reported) { reported = true; OverlayService.instance?.photoAccessDone(photoAccess(this)) }
        finish()
        overridePendingTransition(0, 0)
    }

    private var reported = false
    private fun done(changed: Boolean, failed: Boolean = false) {
        if (!reported) { reported = true; OverlayService.instance?.profilePhotoPicked(changed, failed) }
        finish()
        overridePendingTransition(0, 0)
    }

    // Closed any other way (the phone took it away): Profile still comes back.
    override fun onDestroy() {
        if (!reported && isFinishing) {
            reported = true
            if (accessMode) OverlayService.instance?.photoAccessDone(photoAccess(this)) else OverlayService.instance?.profilePhotoPicked(false, false)
        }
        super.onDestroy()
    }

    companion object {
        private const val SIZE = 1080  // saved square, sharp enough to view full screen
        const val EXTRA_ACCESS = "access"
        /** STORY's own address for pictures on the phone (served straight from the phone by the STORY windows). */
        const val PHOTO_BASE = "https://appassets.androidplatform.net/story-photo/"

        fun file(c: Context) = File(c.filesDir, "profile_photo.jpg")

        /** Where the page loads the saved picture from ("" when there is none). */
        fun photoUrl(c: Context): String {
            val f = file(c)
            return if (f.exists()) PHOTO_BASE + "profile?v=" + f.lastModified() else ""
        }

        private fun accessPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        private fun has(c: Context, p: String) = c.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED

        /** "full" (every picture), "partial" (only the ones you chose to share, Android 14+) or "none". */
        fun photoAccess(c: Context): String = when {
            Build.VERSION.SDK_INT >= 33 && has(c, android.Manifest.permission.READ_MEDIA_IMAGES) -> "full"
            Build.VERSION.SDK_INT >= 34 && has(c, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
            Build.VERSION.SDK_INT < 33 && has(c, android.Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
            else -> "none"
        }

        /** For Create's +: pictures AND videos. "full" only when both are allowed (an older yes to pictures alone
         *  doesn't cover videos, so Create asks again); "partial" = the ones you chose to share (Android 14+). */
        fun mediaAccess(c: Context): String = when {
            Build.VERSION.SDK_INT >= 33 && has(c, android.Manifest.permission.READ_MEDIA_IMAGES) && has(c, android.Manifest.permission.READ_MEDIA_VIDEO) -> "full"
            Build.VERSION.SDK_INT >= 34 && has(c, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> "partial"
            Build.VERSION.SDK_INT < 33 && has(c, android.Manifest.permission.READ_EXTERNAL_STORAGE) -> "full"
            else -> "none"
        }

        /** The phone's pictures, newest first, as a JSON list of ids. */
        fun listPhotos(c: Context): String {
            val ids = org.json.JSONArray()
            if (photoAccess(c) == "none") return ids.toString()
            runCatching {
                c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")?.use { cur ->
                    while (cur.moveToNext() && ids.length() < 5000) ids.put(cur.getLong(0))
                }
            }
            return ids.toString()
        }

        /** Pictures AND videos, newest first: [{id, v}] (v = 1 for a video). For Create's + button. */
        fun listMedia(c: Context): String {
            val out = org.json.JSONArray()
            if (photoAccess(c) == "none") return out.toString()
            val all = ArrayList<Triple<Long, Long, Int>>()
            fun q(uri: android.net.Uri, v: Int) = runCatching {
                c.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED), null, null,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cur ->
                    var n = 0
                    while (cur.moveToNext() && n++ < 5000) all.add(Triple(cur.getLong(0), cur.getLong(1), v))
                }
            }
            q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 0)
            q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 1)
            all.sortByDescending { it.second }
            for (t in all.take(5000)) out.put(org.json.JSONObject().put("id", t.first).put("v", t.third))
            return out.toString()
        }

        /** A video's preview frame, JPEG bytes. */
        fun videoThumbBytes(c: Context, id: Long): ByteArray? = runCatching {
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
            val bmp = if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(uri, android.util.Size(256, 256), null)
                else MediaStore.Video.Thumbnails.getThumbnail(c.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            val out = java.io.ByteArrayOutputStream()
            bmp?.compress(Bitmap.CompressFormat.JPEG, 82, out) ?: return@runCatching null
            out.toByteArray()
        }.getOrNull()

        /** Any picture the phone can show, upright, at most `max` px on its long side. */
        fun decodeUpright(c: Context, uri: Uri, max: Int): Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= 28) android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                val w = info.size.width; val h = info.size.height; val big = maxOf(w, h)
                if (big > max) d.setTargetSize(maxOf(1, w * max / big), maxOf(1, h * max / big))
                d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            } else decodeOld(c, uri)
        }.getOrNull()

        private fun photoUri(id: Long) = android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        /** A small square preview of one picture, JPEG bytes (for the grid). */
        fun thumbBytes(c: Context, id: Long): ByteArray? = runCatching {
            val bmp = if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(photoUri(id), android.util.Size(256, 256), null)
                else MediaStore.Images.Thumbnails.getThumbnail(c.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            val out = java.io.ByteArrayOutputStream()
            bmp?.compress(Bitmap.CompressFormat.JPEG, 82, out) ?: return@runCatching null
            out.toByteArray()
        }.getOrNull()

        /** Uses a picture from the phone's own pictures (STORY's grid) as the profile picture. */
        fun saveFromPhone(c: Context, id: Long): Boolean = runCatching { save(c, photoUri(id)) }.getOrDefault(false)

        /** Decodes the picked picture upright, cuts the middle square out of it and saves it. */
        private fun save(c: Context, uri: Uri): Boolean {
            val upright = if (Build.VERSION.SDK_INT >= 28) decodeModern(c, uri) else decodeOld(c, uri)
            upright ?: return false
            val side = minOf(upright.width, upright.height)
            if (side <= 0) return false
            val square = Bitmap.createBitmap(upright, (upright.width - side) / 2, (upright.height - side) / 2, side, side)
            val out = if (side > SIZE) Bitmap.createScaledBitmap(square, SIZE, SIZE, true) else square
            val tmp = File(c.filesDir, "profile_photo.tmp")
            tmp.outputStream().use { if (!out.compress(Bitmap.CompressFormat.JPEG, 90, it)) return false }
            val f = file(c)
            if (f.exists()) f.delete()
            return tmp.renameTo(f)
        }

        /** Android 9+: reads any photo the phone can show (HEIC included) and turns it upright by itself. */
        @android.annotation.TargetApi(28)
        private fun decodeModern(c: Context, uri: Uri): Bitmap? = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
            val w = info.size.width; val h = info.size.height; val min = minOf(w, h)
            if (min > SIZE) d.setTargetSize(maxOf(1, w * SIZE / min), maxOf(1, h * SIZE / min))
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }

        private fun decodeOld(c: Context, uri: Uri): Bitmap? {
            val cr = c.contentResolver
            // Measure first (this pass returns no picture by design -- only its size).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
            val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
            // Phones save most photos sideways with a note saying which way is up: turn it upright.
            val rot = runCatching { cr.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } }.getOrNull() ?: 1
            val m = Matrix()
            when (rot) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
                android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            }
            return if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        }
    }
}
