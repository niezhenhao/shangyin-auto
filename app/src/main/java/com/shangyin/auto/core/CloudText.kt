package com.shangyin.auto.core

/**
 * 网易云音乐场景的文本归一化与判定（v3.9.2 新增）。
 *
 * 与 QqText 结构一致，但所有阈值/文案取自 CloudConfig（网易云真机截图校准），
 * 并新增奖励弹窗判定 rewardPanelHit：网易云看广告回来后奖励弹窗会再次出现，
 * 弹窗里的「观看领奖励」既代表本轮到账，也是下一轮的入口。
 */
object CloudText {

    private val tailNoise = Regex("[×xX✕✖·•\\|丨\\[\\]【】()（）\\d:：!！.,，。]+$")
    private val headNoise = Regex("^[×xX✕✖·•\\|丨]+")
    private val dwellRegex = Regex("(?:浏览|观看|停留|体验)\\s*(\\d{1,3})\\s*秒")
    private val countdownRegex = Regex("\\d{1,3}\\s*秒")

    fun norm(raw: String?): String {
        if (raw.isNullOrEmpty()) return ""
        var s = raw.trim().replace(" ", "").replace("\u3000", "")
        s = headNoise.replace(s, "")
        s = tailNoise.replace(s, "")
        s = s.replace("领収", "领取").replace("领职", "领取").replace("领敢", "领取")
        s = s.replace("観", "观").replace("看", "看").replace("勵", "励")
        s = s.replace("関", "关").replace("閉", "闭").replace("矙", "看")
        s = s.replace("颇咼", "最高").replace("頒", "领")
        return s
    }

    /** 模糊匹配：完全相等 / 目标被包含（长度相近）/ 目标每个字都出现 */
    fun match(recognized: String, target: String): Boolean {
        val a = norm(recognized)
        val b = norm(target)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length > 12) return false
        // 付费 / 黑胶开通类按钮永远不能成为点击目标（防止误点扣费按钮导致卡死）；
        // 例外：识别文本与目标词完全一致时放行
        if (a != b && isPayDanger(a)) return false
        if (CloudConfig.excludeKeys.any { it in a && it !in b }) return false
        if (a == b) return true
        if (b in a && a.length <= b.length + 2) return true
        val hit = b.count { it in a }
        return hit >= b.length && kotlin.math.abs(a.length - b.length) <= 2
    }

    /** 返回命中的特征词，没有返回 null */
    fun hitMarker(text: String, markers: List<String>): String? {
        val n = norm(text)
        if (n.isEmpty()) return null
        for (m in markers) {
            if (norm(m) in n) return m
        }
        return null
    }

    fun hitMarker(items: List<OcrItem>, markers: List<String>): String? {
        for (it in items) {
            val hit = hitMarker(it.text, markers)
            if (hit != null) return hit
        }
        return null
    }

    /** 从「点击跳转APP停留8秒」里解析出需要停留的秒数，解析不到返回 0 */
    fun parseDwellSeconds(items: List<OcrItem>): Int {
        var best = 0
        for (it in items) {
            val s = norm(it.text)
            val m = dwellRegex.find(s)
            if (m != null) {
                val v = m.groupValues[1].toIntOrNull() ?: continue
                if (v in 1..180 && v > best) best = v
            }
        }
        return best
    }

    /** 广告页强度：2=强特征（跳转 CTA / 跳过 / 倒计时），1=弱特征，0=不是广告页 */
    fun adStrength(items: List<OcrItem>): Int {
        var weak = 0
        for (it in items) {
            val s = norm(it.text)
            if (s.isEmpty()) continue
            if (jumpCtaHit(s)) return 2
            if ("广告" in s && ("跳过" in s || "↓" in s || "▽" in s)) return 2
            if (countdownRegex.containsMatchIn(s) || "跳过广告" in s) return 2
            if ("广告" in s || "赞助" in s || "推广" in s) weak = 1
        }
        return weak
    }

    fun isAdPage(items: List<OcrItem>): Boolean = adStrength(items) > 0

    /** 任务列表页特征命中 */
    fun taskPageHit(items: List<OcrItem>): String? = hitMarker(items, CloudConfig.taskPageMarkers)

    /** 任务列表页特征计分：命中「观看领奖励 / 去观看 / 看视频」等 ≥2 次即判定为任务页 */
    fun taskListScore(items: List<OcrItem>): Int {
        var score = 0
        for (it in items) {
            val s = norm(it.text)
            if (s.isEmpty() || s.length > 24) continue
            if (CloudConfig.taskListKeys.any { it in s }) {
                score++
            } else if (countdownRegex.containsMatchIn(s) && ("浏览" in s || "观看" in s)) {
                score++
            }
            if (score >= 3) return score
        }
        return score
    }

    fun isTaskListPage(items: List<OcrItem>): Boolean = taskListScore(items) >= 2

    /**
     * 找广告/已获得页右上角图形「×」：只认原始文本里带 × 符号且位于屏幕顶部 22% 的条目，
     * 因为这些行常被识别成「广告丨×」这种宽框，点击必须落在框内右侧的 × 字形上。
     */
    fun closeGlyphHit(items: List<OcrItem>, screenHeight: Int): OcrItem? {
        if (screenHeight <= 0) return null
        return items.filter { it.cy <= screenHeight * 0.22f && it.text.any { c -> c in CloudConfig.closeGlyphChars } }
            .filter { it.hasBox }
            .maxByOrNull { it.right }
    }

    /** OCR 原文含 × 符号（norm 会把尾部 × 当噪声剥掉，故此处用原文判定） */
    fun hasGlyphChar(text: String?): Boolean =
        !text.isNullOrEmpty() && text.any { c -> c in CloudConfig.closeGlyphChars }

    /** 奖励到账 / 可继续领奖特征命中（任意位置） */
    fun successHit(items: List<OcrItem>): OcrItem? {
        for (it in items) {
            if (hitMarker(it.text, CloudConfig.successMarkers) != null) return it
        }
        return null
    }

    /**
     * 网易云奖励弹窗判定：命中 successMarkers 且落在奖励弹窗区域内、且不含付费危险词。
     * 该弹窗出现即代表「上一轮看广告已到账，可以继续领下一轮」。
     * 左侧「赢全天畅听」含付费危险词，会被 isPayDanger 直接排除，绝不会被当作到账信号。
     */
    fun rewardPanelHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        return items.asSequence()
            .filter { CloudConfig.REWARD_CARD.contains(it.cx, it.cy, w, h) }
            .filter { !isPayDanger(it.text) }
            .filter { item -> hitMarker(item.text, CloudConfig.successMarkers) != null }
            .maxByOrNull { it.cy }
    }

    /** 系统跳转弹窗文案特征 */
    fun sysDialogHit(items: List<OcrItem>): String? = hitMarker(items, CloudConfig.sysDialogMarkers)

    /** 广告页「进入落地页查看详情 / 点击跳转APP停留N秒」CTA */
    fun jumpCtaHit(text: String): Boolean {
        val s = norm(text)
        if (s.isEmpty()) return false
        for (m in CloudConfig.jumpCtaMarkers) {
            if (norm(m) in s) return true
        }
        return false
    }

    /** 直播间误入判定：顶部区域出现「关注 / 更多直播」 */
    fun isLiveTop(text: String, cy: Float, screenHeight: Int): Boolean {
        if (screenHeight <= 0) return false
        if (cy > screenHeight * CloudConfig.LIVE_TOP_RATIO) return false
        val n = norm(text)
        return CloudConfig.liveKeys.any { it in n }
    }

    // ------------------------------------------------------------------
    // 付费墙终止态：只点「取消」，绝不点任何黑胶开通/付费按钮
    // ------------------------------------------------------------------

    /** 付费 / 黑胶开通类文案：命中即视为危险目标，任何规则都不得点击 */
    fun isPayDanger(raw: String?): Boolean {
        val n = norm(raw)
        if (n.isEmpty()) return false
        return CloudConfig.payDangerKeys.any { it in n }
    }

    /** 黑胶会员付费弹窗命中：文案命中 quotaMarkers 且位于弹窗区域内 */
    fun quotaHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        for (it in items) {
            val n = norm(it.text)
            if (n.isEmpty()) continue
            if (CloudConfig.quotaMarkers.none { it in n }) continue
            if (!CloudConfig.QUOTA_DIALOG.contains(it.cx, it.cy, w, h)) continue
            return it
        }
        return null
    }

    /**
     * 付费弹窗里唯一允许点击的「取消」按钮：
     * 必须落在 [CloudConfig.QUOTA_CANCEL] 热区内、文案匹配取消类目标、且不含任何付费文案。
     */
    fun quotaCancelHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        return items.asSequence()
            .filter { CloudConfig.QUOTA_CANCEL.contains(it.cx, it.cy, w, h) }
            .filter { !isPayDanger(it.text) }
            .filter { item -> CloudConfig.quotaCancelTargets.any { t -> match(item.text, t) } }
            .maxByOrNull { it.cy }
    }
}
