package com.story.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

/** Station 3 > Create: the real camera, full screen, UNDER STORY's Create window (which is see-through where the
 *  camera shows). Create's own buttons (shutter, +, ✓...) drive it through OverlayService. Full-quality pictures
 *  and video, straight from the phone's camera. */
class CreateCameraActivity : AppCompatActivity() {

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; setBackgroundColor(Color.BLACK) }
        setContentView(FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(preview, FrameLayout.LayoutParams(-1, -1)) })
        previewView = preview
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startCamera()
        else {
            // One time only: Android's own "allow camera / microphone" sits under STORY's windows, so they step aside.
            OverlayService.instance?.stepAsideForCamera()
            requestPermissions(need.toTypedArray(), 3)
        }
    }

    private var previewView: PreviewView? = null

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        OverlayService.instance?.comeBackFromCamera()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else OverlayService.instance?.createEvent("denied", "")
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            runCatching {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView?.surfaceProvider) }
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(recorder)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic, vc)
                imageCapture = ic; videoCapture = vc
                OverlayService.instance?.createEvent("ready", "")
            }.onFailure { OverlayService.instance?.createEvent("error", "") }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Shutter (tap): a picture. */
    fun takePhoto() {
        val ic = imageCapture ?: run { OverlayService.instance?.createEvent("error", ""); return }
        val f = captureFile(this, "jpg"); clearCapture(this)
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { OverlayService.instance?.createEvent("photo", "") }
                override fun onError(e: ImageCaptureException) { OverlayService.instance?.createEvent("error", "") }
            })
    }

    /** Shutter in Video (tap to start / stop) or held down: a video, with sound when the microphone is allowed. */
    fun startVideo() {
        val vc = videoCapture ?: run { OverlayService.instance?.createEvent("error", ""); return }
        if (recording != null) return
        clearCapture(this)
        val f = captureFile(this, "mp4")
        val pending = vc.output.prepareRecording(this, FileOutputOptions.Builder(f).build())
        val withSound = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runCatching { pending.withAudioEnabled() }.getOrDefault(pending) else pending
        recording = withSound.start(ContextCompat.getMainExecutor(this)) { e ->
            when (e) {
                is VideoRecordEvent.Start -> OverlayService.instance?.createEvent("recording", "")
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    if (e.hasError() && !f.exists()) OverlayService.instance?.createEvent("error", "")
                    else OverlayService.instance?.createEvent("video", "")
                }
            }
        }
    }

    fun stopVideo() { recording?.stop() }

    override fun onBackPressed() {
        // Back on the camera closes Create (the window it belongs to), like tapping Create again.
        OverlayService.instance?.closeS3WindowFromCamera()
    }

    override fun onDestroy() {
        recording?.stop(); recording = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: CreateCameraActivity? = null

        private fun dir(c: android.content.Context) = File(c.filesDir, "create").apply { mkdirs() }
        fun captureFile(c: android.content.Context, ext: String) = File(dir(c), "capture.$ext")
        /** What was just made (picture or video), or null. */
        fun currentCapture(c: android.content.Context): File? =
            listOf("jpg", "mp4").map { captureFile(c, it) }.firstOrNull { it.exists() && it.length() > 0 }
        fun clearCapture(c: android.content.Context) { listOf("jpg", "mp4").forEach { captureFile(c, it).delete() } }

        /** Your posts and highlight items, kept on the phone. */
        fun postsDir(c: android.content.Context) = File(c.filesDir, "posts").apply { mkdirs() }
        fun postFile(c: android.content.Context, id: String): File? =
            listOf("jpg", "mp4").map { File(postsDir(c), "$id.$it") }.firstOrNull { it.exists() }

        /** ✓: keeps what was made as a post; returns "<id>:<photo|video>" or "". */
        fun keepCapture(c: android.content.Context): String {
            val f = currentCapture(c) ?: return ""
            val id = "p" + System.currentTimeMillis()
            val kind = if (f.extension == "mp4") "video" else "photo"
            val to = File(postsDir(c), "$id.${f.extension}")
            if (!f.renameTo(to)) { f.copyTo(to, true); f.delete() }
            return "$id:$kind"
        }

        /** + : a picture or video from the phone becomes what was made (copied in, untouched). */
        fun useFromPhone(c: android.content.Context, id: Long, video: Boolean): Boolean = runCatching {
            clearCapture(c)
            val uri = android.content.ContentUris.withAppendedId(
                if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val to = captureFile(c, if (video) "mp4" else "jpg")
            if (video) {
                c.contentResolver.openInputStream(uri)?.use { input -> to.outputStream().use { input.copyTo(it) } } ?: return@runCatching false
            } else {
                // Any picture the phone can show (HEIC included) becomes a normal, upright JPEG.
                val bmp = ProfilePhotoActivity.decodeUpright(c, uri, 2160) ?: return@runCatching false
                to.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) }
            }
            to.length() > 0
        }.getOrDefault(false)
    }
}
