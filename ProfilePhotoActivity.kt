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
import android.util.Base64
import java.io.File

/** Edit profile > Change photo: opens the phone's own photo picker (any picture you want) and saves the one you
 *  pick as STORY's profile picture. See-through, so you only ever see the picker itself. */
class ProfilePhotoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return  // the picker is already up
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

    private var reported = false
    private fun done(changed: Boolean, failed: Boolean = false) {
        if (!reported) { reported = true; OverlayService.instance?.profilePhotoPicked(changed, failed) }
        finish()
        overridePendingTransition(0, 0)
    }

    // Closed any other way (the phone took it away): Profile still comes back.
    override fun onDestroy() {
        if (!reported && isFinishing) { reported = true; OverlayService.instance?.profilePhotoPicked(false, false) }
        super.onDestroy()
    }

    companion object {
        private const val SIZE = 1080  // saved square, sharp enough to view full screen

        fun file(c: Context) = File(c.filesDir, "profile_photo.jpg")

        /** The saved picture as a data URL for the page, or "" when there is none. */
        fun dataUrl(c: Context): String {
            val f = file(c)
            if (!f.exists()) return ""
            return "data:image/jpeg;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        }

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
