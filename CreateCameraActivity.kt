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
        val preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER; setBackgroundColor(Color.BLACK)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE  // drawn like a normal view, under STORY's window
        }
        // The video you just made plays here, on the phone's own player (full screen, looping, with sound).
        val player = android.widget.VideoView(this).apply { visibility = android.view.View.GONE }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(player, FrameLayout.LayoutParams(-1, -1, android.view.Gravity.CENTER))
        })
        previewView = preview; videoPlayer = player
        OverlayService.instance?.createEvent("opened", "")
        // The camera picture actually flowing = the camera works (Create hides its status line then).
        preview.previewStreamState.observe(this) { st -> if (st == PreviewView.StreamState.STREAMING) OverlayService.instance?.createEvent("streaming", "") }
        val need = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isEmpty()) startCamera()
        else {
            // One time only: Android's own "allow camera / microphone" sits under STORY's windows, so they step aside.
            OverlayService.instance?.createEvent("asking", "")
            OverlayService.instance?.stepAsideForCamera()
            requestPermissions(need.toTypedArray(), 3)
        }
    }

    private var previewView: PreviewView? = null
    private var videoPlayer: android.widget.VideoView? = null

    /** Plays what was just made (a video) under Create, looping, with sound. */
    fun playCapture() {
        val f = currentCapture(this)?.takeIf { it.extension == "mp4" } ?: return
        val v = videoPlayer ?: return
        v.setOnPreparedListener { mp -> mp.isLooping = true; v.start() }
        v.setOnErrorListener { _, what, extra -> OverlayService.instance?.createEvent("error", "playback $what/$extra"); true }
        v.setVideoPath(f.absolutePath)
        v.visibility = android.view.View.VISIBLE
    }
    fun stopPlayback() { videoPlayer?.let { runCatching { it.stopPlayback() }; it.visibility = android.view.View.GONE } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        OverlayService.instance?.comeBackFromCamera()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else OverlayService.instance?.createEvent("denied", "")
    }

    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val err = runCatching {
                provider = future.get()
                preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView?.surfaceProvider) }
                bindBest()
            }.exceptionOrNull()
            if (err != null) OverlayService.instance?.createEvent("error", "camera: " + (err.message ?: err.javaClass.simpleName))
            else OverlayService.instance?.createEvent("ready", "")
        }, ContextCompat.getMainExecutor(this))
    }

    private fun recorder(q: Quality) = VideoCapture.withOutput(Recorder.Builder()
        .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))).build())

    /** Not every phone can run preview + pictures + video at once: try the best set first, then step down.
     *  Whatever is left out is switched in only when it's needed (see startVideo). */
    private fun bindBest() {
        val p = provider ?: throw IllegalStateException("no camera")
        val pv = preview ?: throw IllegalStateException("no preview")
        val sel = selector()
        var last: Throwable? = null
        for (q in listOf(Quality.FHD, Quality.HD, Quality.SD, null)) {
            val r = runCatching {
                p.unbindAll()
                val ic = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                if (q != null) {
                    val vc = recorder(q)
                    p.bindToLifecycle(this, sel, pv, ic, vc)
                    imageCapture = ic; videoCapture = vc
                } else {
                    p.bindToLifecycle(this, sel, pv, ic)
                    imageCapture = ic; videoCapture = null
                }
            }
            if (r.isSuccess) return
            last = r.exceptionOrNull()
        }
        // Last resort: just the camera picture; pictures and video are switched in when used.
        p.unbindAll(); p.bindToLifecycle(this, sel, pv)
        imageCapture = null; videoCapture = null
        if (last != null && p.availableCameraInfos.isEmpty()) throw last
    }

    /** Back camera unless you switched to the front one (and the phone has the one you want). */
    private var front = false
    private fun selector(): CameraSelector {
        val p = provider ?: return if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val want = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val other = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        return if (runCatching { p.hasCamera(want) }.getOrDefault(false)) want else other
    }

    /** Create's switch-camera button: back <-> front. */
    fun switchCamera() {
        if (recording != null || provider == null) return
        front = !front
        val err = runCatching { bindBest() }.exceptionOrNull()
        if (err != null) { front = !front; runCatching { bindBest() }; OverlayService.instance?.createEvent("error", "switch camera: " + (err.message ?: "")) }
    }

    /** Shutter (tap): a picture. */
    fun takePhoto() {
        var ic = imageCapture
        if (ic == null) {
            // This phone couldn't keep pictures switched on alongside video: switch them in now.
            ic = runCatching {
                val p = provider!!; p.unbindAll()
                ImageCapture.Builder().build().also { p.bindToLifecycle(this, selector(), preview!!, it); imageCapture = it; videoCapture = null }
            }.getOrNull()
        }
        if (ic == null) { OverlayService.instance?.createEvent("error", "pictures aren't available on this camera"); return }
        val f = captureFile(this, "jpg"); clearCapture(this)
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { OverlayService.instance?.createEvent("photo", "") }
                override fun onError(e: ImageCaptureException) { OverlayService.instance?.createEvent("error", "picture: " + (e.message ?: "")) }
            })
    }

    /** Shutter in Video (tap to start / stop) or held down: a video, with sound when the microphone is allowed. */
    fun startVideo() {
        var vc = videoCapture
        if (vc == null) {
            // Video wasn't switched on alongside pictures on this phone: switch it in for this recording.
            vc = runCatching {
                val p = provider!!; p.unbindAll()
                recorder(Quality.HD).also { p.bindToLifecycle(this, selector(), preview!!, it); videoCapture = it; imageCapture = null }
            }.getOrNull()
        }
        if (vc == null) { OverlayService.instance?.createEvent("error", "video isn't available on this camera"); return }
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
                    if (e.hasError() && (!f.exists() || f.length() == 0L)) OverlayService.instance?.createEvent("error", "video: error " + e.error)
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
        fun postThumb(c: android.content.Context, id: String): File? = File(postsDir(c), "${id}_t.jpg").takeIf { it.exists() }
        fun postFile(c: android.content.Context, id: String): File? =
            listOf("jpg", "mp4").map { File(postsDir(c), "$id.$it") }.firstOrNull { it.exists() }

        /** ✓: keeps what was made as a post; returns "<id>:<photo|video>" or "". */
        fun keepCapture(c: android.content.Context): String {
            val f = currentCapture(c) ?: return ""
            val id = "p" + System.currentTimeMillis()
            val kind = if (f.extension == "mp4") "video" else "photo"
            val to = File(postsDir(c), "$id.${f.extension}")
            if (!f.renameTo(to)) { f.copyTo(to, true); f.delete() }
            if (kind == "video") runCatching {
                // A still frame from the video, for covers (e.g. a highlight circle).
                val r = android.media.MediaMetadataRetriever(); r.setDataSource(to.absolutePath)
                val frame = r.getFrameAtTime(100_000); r.release()
                frame?.let { b -> File(postsDir(c), "${id}_t.jpg").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
            }
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
