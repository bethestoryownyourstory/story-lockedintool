package com.story.launcher

import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject

/**
 * Wallet > Add card > Take a picture: reads the words on the card photo ON THE PHONE (Google's ML Kit text
 * recognition, offline - the picture never leaves the phone) and hands back every line and word with where it sits,
 * so the page can fill in the last 4 / expiry / network and cover the long card number before anything is kept.
 * The un-covered picture is never saved.
 */
object CardScan {
    fun scan(dataUrl: String, done: (String) -> Unit) {
        val bytes = runCatching { Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT) }.getOrNull()
        val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        if (bmp == null) { done("{}"); return }
        val box = { r: android.graphics.Rect? -> JSONArray().apply { put(r?.left ?: 0); put(r?.top ?: 0); put(r?.right ?: 0); put(r?.bottom ?: 0) } }
        runCatching {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { t ->
                    val lines = JSONArray()
                    t.textBlocks.forEach { b -> b.lines.forEach { l ->
                        val els = JSONArray()
                        l.elements.forEach { e -> els.put(JSONObject().put("text", e.text).put("box", box(e.boundingBox))) }
                        lines.put(JSONObject().put("text", l.text).put("box", box(l.boundingBox)).put("els", els))
                    } }
                    done(JSONObject().put("w", bmp.width).put("h", bmp.height).put("lines", lines).toString())
                }
                .addOnFailureListener { done("{}") }
        }.onFailure { done("{}") }
    }
}
