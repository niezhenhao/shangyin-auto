package com.shangyin.auto.core

import kotlin.math.abs

/**
 * 文本归一化与匹配。移植自 qishui_auto.py，用于抵抗 OCR 常见误识别。
 */
object TextMatch {

    private val tailNoise = Regex("[×xX✕✖·•\\|丨\\[\\]【】()（）\\d:：!！.,，。]+$")
    private val headNoise = Regex("^[×xX✕✖·•\\|丨]+")

    fun normalize(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var s = raw.trim().replace(" ", "").replace("\u3000", "")
        s = tailNoise.replace(s, "")
        s = headNoise.replace(s, "")
        s = s.replace("领収", "领取").replace("领职", "领取").replace("领敢", "领取")
        s = s.replace("奨", "奖").replace("奬", "奖").replace("勵", "励")
        return s
    }

    /** 归一化后：完全相等 / 目标被包含 / 目标每个字都出现在识别结果里 */
    fun match(recognized: String, target: String): Boolean {
        val a = normalize(recognized)
        val b = normalize(target)
        if (a.isEmpty() || b.isEmpty()) return false
        if (AutoConfig.excludeKeys.any { it in a }) return false
        if (a == b) return true
        if (b in a && a.length <= b.length + 4) return true
        val hit = b.count { it in a }
        return hit >= b.length && abs(a.length - b.length) <= 4
    }

    /** 直播间误入判定：顶部区域出现「关注 / 更多直播」 */
    fun isLiveTop(text: String, cy: Float, screenHeight: Int): Boolean {
        if (screenHeight <= 0) return false
        if (cy > screenHeight * AutoConfig.LIVE_TOP_RATIO) return false
        val n = normalize(text)
        return AutoConfig.liveKeys.any { it in n }
    }
}
