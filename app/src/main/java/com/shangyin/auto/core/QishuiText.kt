package com.shangyin.auto.core

import kotlin.math.abs

/**
 * 汽水音乐（免费听歌模式）页面的文字判定（v3.8.0 新增）。
 *
 * 录屏实测特征（576x1280，采集帧归一化坐标）：
 * - 广告页顶部左侧固定为「广告」（x≈0.14, y≈0.059），中间为「反馈」，
 *   右侧为「领取成功 ×」整行（OCR 会把 × 和文字粘在一起，框中心落在 0.849，
 *   真正的 × 字形在 x≈0.908~0.927、y≈0.054~0.063，即整行右端）。
 *   旧逻辑把「领取成功」当普通目标点击框中心，等于点在「领取」二字上，
 *   广告永远关不掉——这正是「广告看完右上角 X 没被点击」的直接原因。
 * - 「领取成功」只是广告 SDK 的奖励角标，不代表奖励面板已经弹出，
 *   因此收尾必须分两步：先看有没有面板可领，再决定点 × 关闭。
 */
object QishuiText {

    /** 顶部广告条特征词 */
    private val adKeys = listOf("广告", "反馈")

    /** 广告已结束 / 奖励已到账的角标文字（不含 × 时由 closeTextHit 单独处理 ×） */
    private val adDoneKeys = listOf(
        "领取成功", "已领取", "已获得", "广告已结束", "广告结束", "观看完成", "浏览完成", "恭喜获得"
    )

    /** 关闭类文字 */
    private val closeKeys = listOf("关闭广告", "关闭", "跳过广告", "跳过", "知道了", "完成", "收下")

    /** 奖励面板可点击文案，按优先级排列 */
    private val panelClaims = listOf(
        "领取奖励", "立即领取", "点击领取", "开心收下", "立即收下", "收下", "领取"
    )

    /** 面板里表示「继续/再看一次」的次要按钮 */
    private val panelContinue = listOf("继续观看", "再看一个", "继续收听")

    /** 面板线索词（用于判定「有没有浮层」） */
    private val panelHints = listOf(
        "领取奖励", "立即领取", "点击领取", "开心收下", "收下", "继续观看",
        "再看一个", "恭喜", "已获得", "免费听歌", "免费畅听", "分钟"
    )

    private val glyphChars = charArrayOf('×', '✕', '✖', '╳', 'x', 'X', '✗', '☓')

    /** 顶部广告条区域（占屏幕高度的比例） */
    private const val TOP_RATIO = 0.12f

    fun isTop(cy: Float, frameHeight: Int): Boolean =
        frameHeight > 0 && cy <= frameHeight * TOP_RATIO

    /** 是否处于广告页（顶部出现广告条特征） */
    fun isAdPage(items: List<OcrItem>, frameHeight: Int): Boolean = items.any { item ->
        isTop(item.cy, frameHeight) && adKeys.any { k -> TextMatch.match(item.text, k) }
    }

    /** 是否出现「广告已结束 / 奖励已到账」角标 */
    fun adDoneHit(items: List<OcrItem>, frameHeight: Int): Boolean = items.any { item ->
        isTop(item.cy, frameHeight) && adDoneKeys.any { k -> TextMatch.match(item.text, k) }
    }

    /** 原始文本里是否直接带 × 字形 */
    fun hasGlyphChar(text: String): Boolean = text.any { c -> glyphChars.contains(c) }

    /**
     * 顶部关闭文字命中。
     * 优先级：带 × 字形的行 > 「关闭广告 / 跳过广告」 > 「关闭 / 知道了 / 完成」。
     * 只允许顶部区域，避免误点内容区的同名字样。
     */
    fun closeTextHit(items: List<OcrItem>, frameHeight: Int): OcrItem? {
        val top = items.filter { isTop(it.cy, frameHeight) }
        if (top.isEmpty()) return null
        val byGlyph = top.filter { hasGlyphChar(it.text) }.maxByOrNull { it.score }
        if (byGlyph != null) return byGlyph
        for (key in closeKeys) {
            val hit = top.filter { TextMatch.match(it.text, key) }.maxByOrNull { it.score }
            if (hit != null) return hit
        }
        return null
    }

    /**
     * 计算关闭文字的点击点：含 × 的宽框取框内右端（× 字形所在处），
     * 纯文字按钮取框中心。避免「领取成功 ×」把点击点落在「领取」二字上。
     */
    fun closePointX(item: OcrItem, frameWidth: Int): Float {
        if (frameWidth <= 0) return item.cx
        if (hasGlyphChar(item.text) && item.hasBox) {
            return item.rightAnchor(0.90f)
        }
        return item.cx
    }

    /**
     * 奖励面板：中央浮层里出现领取/收下/继续观看类文案。
     * 排除倒计时类（含「秒」「后可领取」「第」）与任务行（「去浏览」「去观看」）。
     */
    fun rewardPanelHit(items: List<OcrItem>, frameWidth: Int, frameHeight: Int): Boolean {
        if (frameWidth <= 0 || frameHeight <= 0) return false
        return items.any { item ->
            val cy = item.cy / frameHeight
            val cx = item.cx / frameWidth
            cy in 0.15f..0.92f && cx in 0.05f..0.95f && isPanelLine(item.text)
        }
    }

    /** 面板里优先点击的领取按钮 */
    fun panelClaimHit(items: List<OcrItem>, frameWidth: Int, frameHeight: Int): OcrItem? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val inPanel = items.filter {
            val cy = it.cy / frameHeight
            val cx = it.cx / frameWidth
            cy in 0.15f..0.92f && cx in 0.05f..0.95f
        }
        for (key in panelClaims) {
            val hit = inPanel.filter { TextMatch.match(it.text, key) }.maxByOrNull { it.score }
            if (hit != null) return hit
        }
        for (key in panelContinue) {
            val hit = inPanel.filter { TextMatch.match(it.text, key) }.maxByOrNull { it.score }
            if (hit != null) return hit
        }
        return null
    }

    private fun isPanelLine(raw: String): Boolean {
        val n = TextMatch.normalize(raw)
        if (n.isEmpty()) return false
        if (AutoConfig.excludeKeys.any { it in n }) return false
        if (n.startsWith("去浏览") || n.startsWith("去观看") || n.startsWith("去下载")) return false
        if (hasGlyphChar(raw) && n.length <= 2) return false
        return panelHints.any { it in n }
    }

    /** 画面签名：抽样像素，用于判定广告是否还在播放（画面在动）*/
    fun frameSignature(frame: android.graphics.Bitmap, gridX: Int = 12, gridY: Int = 20): Long {
        var sig = 0L
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0) return 0L
        var bit = 0
        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val px = (w - 1).coerceAtLeast(0) * gx / gridX
                val py = (h - 1).coerceAtLeast(0) * gy / gridY
                val c = runCatching { frame.getPixel(px, py) }.getOrDefault(0)
                val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                if (lum > 128) sig = sig or (1L shl bit)
                bit++
                if (bit >= 62) return sig
            }
        }
        return sig
    }

    /** 两帧签名差异度（0~1） */
    fun signatureDiff(a: Long, b: Long): Float {
        if (a == 0L && b == 0L) return 0f
        var changed = 0
        for (i in 0 until 62) {
            val x = (a shr i) and 1L
            val y = (b shr i) and 1L
            if (x != y) changed++
        }
        return changed / 62f
    }

    /** 面板文案是否「离点击点很近」（容差校验，避免跨区域误点） */
    fun near(x: Float, y: Float, targetX: Float, targetY: Float, tolerance: Float): Boolean =
        abs(x - targetX) <= tolerance && abs(y - targetY) <= tolerance
}
