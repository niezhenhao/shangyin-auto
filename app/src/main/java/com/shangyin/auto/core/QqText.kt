package com.shangyin.auto.core

/**
 * QQ音乐场景的文本归一化与判定，移植自 qqmusic_auto.py 的
 * normalize_text / match_target / is_ad_page / is_task_list_page / parse_dwell_seconds。
 */
object QqText {

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
        s = s.replace("奨", "奖").replace("奬", "奖").replace("勵", "励")
        s = s.replace("関", "关").replace("閉", "闭").replace("矙", "看")
        return s
    }

    /** 模糊匹配：完全相等 / 目标被包含（长度相近）/ 目标每个字都出现 */
    fun match(recognized: String, target: String): Boolean {
        val a = norm(recognized)
        val b = norm(target)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a.length > 12) return false
        // 付费 / 开通会员类按钮永远不能成为点击目标（防止误点扣费按钮导致卡死）；
        // 例外：识别文本与目标词完全一致时放行，保证「免费畅听」这类正常入口不被误杀
        if (a != b && isPayDanger(a)) return false
        if (QqConfig.excludeKeys.any { it in a && it !in b }) return false
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

    /** 从「点击跳转后浏览25秒」里解析出需要停留的秒数，解析不到返回 0 */
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
    fun taskPageHit(items: List<OcrItem>): String? = hitMarker(items, QqConfig.taskPageMarkers)

  /**
   * 任务列表页特征计分：命中「加时长 / 试玩应用 / 去浏览 / 浏览N秒」等 ≥2 次即判定为
   * QQ音乐「免费听歌」任务列表页。该页顶部右侧是页面自身的关闭钮，
   * 与「加时长」入口仅相隔约 3% 屏高，任何关闭类点击/图形兜底都可能误关该页。
   */
  fun taskListScore(items: List<OcrItem>): Int {
    var score = 0
    for (it in items) {
      val s = norm(it.text)
      if (s.isEmpty() || s.length > 24) continue
      if (QqConfig.taskListKeys.any { it in s }) {
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
    return items.filter { it.cy <= screenHeight * 0.22f && it.text.any { c -> c in QqConfig.closeGlyphChars } }
      .filter { it.hasBox }
      .maxByOrNull { it.right }
  }

  /** OCR 原文含 × 符号（norm 会把尾部 × 当噪声剥掉，故此处用原文判定） */
  fun hasGlyphChar(text: String?): Boolean =
    !text.isNullOrEmpty() && text.any { c -> c in QqConfig.closeGlyphChars }

    /** 奖励到账提示 */
    fun successHit(items: List<OcrItem>): OcrItem? {
        for (it in items) {
            if (hitMarker(it.text, QqConfig.successMarkers) != null) return it
        }
        return null
    }

    /** 系统跳转弹窗文案特征 */
    fun sysDialogHit(items: List<OcrItem>): String? = hitMarker(items, QqConfig.sysDialogMarkers)

    /** 广告页「点击跳转后浏览N秒」CTA */
    fun jumpCtaHit(text: String): Boolean {
        val s = norm(text)
        if (s.isEmpty()) return false
        for (m in QqConfig.jumpCtaMarkers) {
            if (norm(m) in s) return true
        }
        return false
    }

    /** 直播间误入判定：顶部区域出现「关注 / 更多直播」 */
    fun isLiveTop(text: String, cy: Float, screenHeight: Int): Boolean {
        if (screenHeight <= 0) return false
        if (cy > screenHeight * QqConfig.LIVE_TOP_RATIO) return false
        val n = norm(text)
        return QqConfig.liveKeys.any { it in n }
    }

    // ------------------------------------------------------------------
    // 额度耗尽终止态（v3.2.0）：只点「取消」，绝不点任何付费/开通会员按钮
    // ------------------------------------------------------------------

    /** 付费 / 开通会员类文案：命中即视为危险目标，任何规则都不得点击 */
    fun isPayDanger(raw: String?): Boolean {
        val n = norm(raw)
        if (n.isEmpty()) return false
        return QqConfig.payDangerKeys.any { it in n }
    }

    /**
     * 「今日免费听歌额度已用完」会员付费弹窗命中。
     * 判据：出现弹窗标题（今日免费听歌额度已用完 / 额度已用完）或副标题特征（每日限量体验），
     * 且该文案位于弹窗区域 [QqConfig.QUOTA_DIALOG] 内，避免任务列表页文案误触发。
     */
    fun quotaHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        for (it in items) {
            val n = norm(it.text)
            if (n.isEmpty()) continue
            if (QqConfig.quotaMarkers.none { it in n }) continue
            if (!QqConfig.QUOTA_DIALOG.contains(it.cx, it.cy, w, h)) continue
            return it
        }
        return null
    }

    /**
     * 额度耗尽弹窗里唯一允许点击的「取消」按钮：
     * 必须落在 [QqConfig.QUOTA_CANCEL] 热区内，文案匹配「取消」，且不含任何付费文案。
     * 付费按钮（「1.88元开会员畅听VIP曲库」）与其它任何按钮都不会被返回。
     */
    fun quotaCancelHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        return items.asSequence()
            .filter { QqConfig.QUOTA_CANCEL.contains(it.cx, it.cy, w, h) }
            .filter { !isPayDanger(it.text) }
            .filter { item -> QqConfig.quotaCancelTargets.any { t -> match(item.text, t) } }
            .maxByOrNull { it.cy }
    }
}
