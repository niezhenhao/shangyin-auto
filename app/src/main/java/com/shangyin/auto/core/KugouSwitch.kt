package com.shangyin.auto.core

import android.graphics.Bitmap

/**
 * v3.6.0：任务页顶部「自动看广告续时长」开关状态的像素判定。
 *
 * OCR 只能读到开关标签文字，读不到开关的开/关状态，因此这里用真机截图校准的
 * 视觉特征做判定：[KugouConfig.SWITCH] 区域内「高饱和彩色像素」的占比。
 *
 * 实测数据（1200×2652 两张真机截图，同一 ROI x 0.842~0.938 / y 0.252~0.282）：
 *  - 开启态：轨迹填充品牌彩色，彩色占比 0.309，平均色 (177,227,251)；
 *  - 关闭态：灰底白圆点，彩色占比 0.000，平均色 (52,67,75)。
 *
 * 判定结果三态：ON / OFF / UNKNOWN。落在阈值中间区间一律视为 UNKNOWN，
 * 调用方（KugouRunner）遇到 UNKNOWN 不点击，宁可跳过也不把用户已开启的开关误关掉。
 */
object KugouSwitch {

    const val UNKNOWN = -1
    const val OFF = 0
    const val ON = 1

    /** 视为「彩色」的最小饱和度与最小亮度（HSV 中的 S / V） */
    private const val SAT_MIN = 0.30f
    private const val VAL_MIN = 0.35f

    /** 采样步长（像素），2 表示隔点取样，兼顾精度与耗时 */
    private const val SAMPLE_STEP = 2

    /**
     * 计算 ROI 内「彩色像素」占比。
     * 逐行 getPixels 复用同一缓冲区，避免每像素调用 getPixel 造成的大量 JNI 开销。
     */
    fun colorRatio(frame: Bitmap, roi: KugouConfig.Roi): Float {
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return 0f
        val x0 = (roi.x0 * w).toInt().coerceIn(0, w - 1)
        val x1 = (roi.x1 * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (roi.y0 * h).toInt().coerceIn(0, h - 1)
        val y1 = (roi.y1 * h).toInt().coerceIn(y0 + 1, h)
        val rowLen = x1 - x0
        if (rowLen <= 0) return 0f
        val pixels = IntArray(rowLen)
        var total = 0
        var colorful = 0
        var y = y0
        while (y < y1) {
            val ok = runCatching { frame.getPixels(pixels, 0, rowLen, x0, y, rowLen, 1) }.isSuccess
            if (!ok) return 0f
            var i = 0
            while (i < rowLen) {
                val p = pixels[i]
                val r = (p shr 16 and 0xFF) / 255f
                val g = (p shr 8 and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                val sat = if (mx <= 0f) 0f else (mx - mn) / mx
                total++
                if (sat >= SAT_MIN && mx >= VAL_MIN) colorful++
                i += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return if (total <= 0) 0f else colorful.toFloat() / total
    }

    /** 开关状态：开启 / 关闭 / 不确定 */
    fun state(frame: Bitmap, roi: KugouConfig.Roi = KugouConfig.SWITCH): Int {
        val ratio = colorRatio(frame, roi)
        return when {
            ratio >= KugouConfig.SWITCH_COLOR_ON -> ON
            ratio <= KugouConfig.SWITCH_COLOR_OFF -> OFF
            else -> UNKNOWN
        }
    }

    /** 便于日志输出的三态名称 */
    fun stateName(state: Int): String = when (state) {
        ON -> "已开启"
        OFF -> "未开启"
        else -> "不确定"
    }
}
