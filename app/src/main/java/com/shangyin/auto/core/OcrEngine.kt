package com.shangyin.auto.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrItem(
    val text: String,
    val score: Float,
    val cx: Float,
    val cy: Float
)

/**
 * 离线中文 OCR（ML Kit）。
 * v2.0.0：采集端已直接输出小图，这里仅在必要时才二次缩放，避免每帧一次额外大图分配。
 */
class OcrEngine {

    private val recognizer =
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    suspend fun recognize(screen: Bitmap): List<OcrItem> {
        val w = screen.width
        val h = screen.height
        if (w <= 0 || h <= 0) return emptyList()

        val maxW = AutoConfig.ocrMaxWidth
        val scale = if (w > maxW) maxW.toFloat() / w else 1f
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(screen, (w * scale).toInt(), (h * scale).toInt(), true)
        } else screen

        val result = try {
            process(input)
        } catch (t: Throwable) {
            return emptyList()
        } finally {
            if (input !== screen) input.recycle()
        }

        val inv = 1f / scale
        val out = ArrayList<OcrItem>(16)
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val score = line.confidence ?: 0.85f
                if (score < AutoConfig.ocrScore) continue
                out.add(
                    OcrItem(
                        text = line.text,
                        score = score,
                        cx = box.exactCenterX() * inv,
                        cy = box.exactCenterY() * inv
                    )
                )
            }
        }
        return out
    }

    private suspend fun process(bitmap: Bitmap) = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}
