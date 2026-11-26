package com.shangyin.auto.core

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * 纯图形「×」关闭按钮的像素级定位（v3.8.0 新增）。
 *
 * 为什么需要：激励广告的关闭按钮经常是「暗底 + 白色细 ×」纯图形，
 * OCR 完全认不出（或只把前面的文案识别出来），文字兜底会直接落空。
 *
 * 做法（不引入 OpenCV，纯 Kotlin + 积分图）：
 * 1. 在给定的右上角 ROI 内取灰度；
 * 2. 分别按「亮笔画 / 暗笔画」两个极性做二值化（阈值由 ROI 的均值与标准差决定）；
 * 3. 用滑动窗口在该极性图上量两条对角线的覆盖率，覆盖率高的窗口即 × 候选；
 * 4. 用积分图算窗口填充率，剔除「大片实心」（文字块、图标底）的伪候选；
 * 5. 多个窗口命中时取最靠右上角的一个（广告关闭按钮的固定位置特征）。
 */
object CloseGlyphFinder {

    data class Spot(
        /** 归一化坐标（相对整帧） */
        val cx: Float,
        val cy: Float,
        val score: Float,
        val polarity: String,
        val size: Int
    )

    private val windowSizes = intArrayOf(10, 14, 18, 22, 26, 30)

    private const val MIN_COVERAGE = 0.68f
    private const val MAX_FILL = 0.55f

    /**
     * @param x0/x1/y0/y1 ROI 归一化范围，默认右上角广告关闭区
     */
    fun find(
        frame: Bitmap,
        x0: Float = 0.78f,
        x1: Float = 1.0f,
        y0: Float = 0.02f,
        y1: Float = 0.12f
    ): Spot? {
        val w = frame.width
        val h = frame.height
        if (w <= 8 || h <= 8) return null

        val left = (x0 * w).toInt().coerceIn(0, w - 2)
        val right = (x1 * w).toInt().coerceIn(left + 2, w)
        val top = (y0 * h).toInt().coerceIn(0, h - 2)
        val bottom = (y1 * h).toInt().coerceIn(top + 2, h)
        val rw = right - left
        val rh = bottom - top
        if (rw < 12 || rh < 12) return null

        val pixels = IntArray(rw * rh)
        runCatching { frame.getPixels(pixels, 0, rw, left, top, rw, rh) }.getOrNull() ?: return null

        val lum = FloatArray(pixels.size)
        var sum = 0.0
        var sumSq = 0.0
        for (i in pixels.indices) {
            val c = pixels[i]
            val v = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000f
            lum[i] = v
            sum += v
            sumSq += v.toDouble() * v
        }
        val n = lum.size.toDouble()
        val mean = sum / n
        val variance = max(0.0, sumSq / n - mean * mean)
        val sd = kotlin.math.sqrt(variance).toFloat()
        val delta = max(24f, sd * 0.55f)

        val bright = scan(lum, rw, rh, (mean + delta).toFloat(), true)
        val dark = scan(lum, rw, rh, (mean - delta).toFloat(), false)

        val candidates = listOfNotNull(bright, dark)
        if (candidates.isEmpty()) return null
        // 取最靠右上角（x+y 最大）的候选，同分取覆盖率更高的
        val best = candidates.maxWithOrNull(
            compareBy<Spot> { it.cx + it.cy }.thenBy { it.score }
        ) ?: return null
        if (best.score < MIN_COVERAGE) return null
        // ROI 内归一化 → 整帧归一化
        return best.copy(
            cx = (left + best.cx * rw) / w,
            cy = (top + best.cy * rh) / h
        )
    }

    private fun scan(
        lum: FloatArray,
        rw: Int,
        rh: Int,
        threshold: Float,
        brightOnDark: Boolean
    ): Spot? {
        val bin = BooleanArray(lum.size)
        for (i in lum.indices) {
            bin[i] = if (brightOnDark) lum[i] >= threshold else lum[i] <= threshold
        }
        // 积分图：快速求窗口填充率
        val integral = IntArray((rw + 1) * (rh + 1))
        for (y in 0 until rh) {
            var rowSum = 0
            for (x in 0 until rw) {
                if (bin[y * rw + x]) rowSum++
                integral[(y + 1) * (rw + 1) + (x + 1)] =
                    integral[y * (rw + 1) + (x + 1)] + rowSum
            }
        }

        var best: Spot? = null
        for (size in windowSizes) {
            if (size + 2 > rw || size + 2 > rh) continue
            val stride = max(3, size / 3)
            var y = 0
            while (y + size <= rh) {
                var x = 0
                while (x + size <= rw) {
                    val fill = windowSum(integral, rw, x, y, size).toFloat() / (size * size).toFloat()
                    if (fill in 0.04f..MAX_FILL) {
                        val cover = diagonalCoverage(bin, rw, rh, x, y, size)
                        if (cover >= MIN_COVERAGE) {
                            val score = cover - fill * 0.15f
                            val ranked = Spot(
                                cx = (x + size / 2f) / rw,
                                cy = (y + size / 2f) / rh,
                                score = score,
                                polarity = if (brightOnDark) "bright" else "dark",
                                size = size
                            )
                            val cur = best
                            if (cur == null ||
                                (ranked.cx + ranked.cy) > (cur.cx + cur.cy) + 1e-6f ||
                                (kotlin.math.abs(ranked.cx + ranked.cy - cur.cx - cur.cy) <= 1e-6f && ranked.score > cur.score)
                            ) {
                                best = ranked
                            }
                        }
                    }
                    x += stride
                }
                y += stride
            }
        }
        // ROI 内归一化 → 需要换算回整帧，由调用方处理
        return best
    }

    private fun windowSum(integral: IntArray, rw: Int, x: Int, y: Int, size: Int): Int {
        val x1 = x
        val y1 = y
        val x2 = x + size
        val y2 = y + size
        val w = rw + 1
        return integral[y2 * w + x2] - integral[y1 * w + x2] - integral[y2 * w + x1] + integral[y1 * w + x1]
    }

    /** 窗口内两条对角线的覆盖率（笔画允许 ±1 像素抖动） */
    private fun diagonalCoverage(
        bin: BooleanArray,
        rw: Int,
        rh: Int,
        ox: Int,
        oy: Int,
        size: Int
    ): Float {
        var main = 0
        var anti = 0
        for (i in 0 until size) {
            if (px(bin, rw, rh, ox + i, oy + i) ||
                px(bin, rw, rh, ox + i - 1, oy + i) ||
                px(bin, rw, rh, ox + i + 1, oy + i)
            ) main++
            if (px(bin, rw, rh, ox + size - 1 - i, oy + i) ||
                px(bin, rw, rh, ox + size - 2 - i, oy + i) ||
                px(bin, rw, rh, ox + size - i, oy + i)
            ) anti++
        }
        return (main + anti) / (2f * size)
    }

    private fun px(bin: BooleanArray, rw: Int, rh: Int, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= rw || y >= rh) return false
        return bin[y * rw + x]
    }

    /** 距离辅助：用于校验点是否落在合理区域 */
    fun sanitize(x: Float, lo: Float = 0.0f, hi: Float = 1.0f): Float = min(hi, max(lo, x))
}
