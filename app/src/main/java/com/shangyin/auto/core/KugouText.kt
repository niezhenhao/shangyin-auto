package com.shangyin.auto.core

/**
 * 酷狗音乐场景的文本归一化与判定，移植自 kugou_auto.py 的
 * normalize_text / match_target / is_ad_page / is_task_list_page / parse_dwell_seconds。
 */
object KugouText {

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
        if (KugouConfig.excludeKeys.any { it in a && it !in b }) return false
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
    fun taskPageHit(items: List<OcrItem>): String? = hitMarker(items, KugouConfig.taskPageMarkers)

  /**
   * 任务列表页特征计分：命中「加时长 / 试玩应用 / 去浏览 / 浏览N秒」等 ≥2 次即判定为
   * 酷狗音乐「免费听歌」任务列表页。该页顶部右侧是页面自身的关闭钮，
   * 与「加时长」入口仅相隔约 3% 屏高，任何关闭类点击/图形兜底都可能误关该页。
   */
  fun taskListScore(items: List<OcrItem>): Int {
    var score = 0
    for (it in items) {
      val s = norm(it.text)
      if (s.isEmpty() || s.length > 24) continue
      if (KugouConfig.taskListKeys.any { it in s }) {
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
    return items.filter { it.cy <= screenHeight * 0.22f && it.text.any { c -> c in KugouConfig.closeGlyphChars } }
      .filter { it.hasBox }
      .maxByOrNull { it.right }
  }

  /** OCR 原文含 × 符号（norm 会把尾部 × 当噪声剥掉，故此处用原文判定） */
  fun hasGlyphChar(text: String?): Boolean =
    !text.isNullOrEmpty() && text.any { c -> c in KugouConfig.closeGlyphChars }

    /** 奖励到账提示 */
    fun successHit(items: List<OcrItem>): OcrItem? {
        for (it in items) {
            if (hitMarker(it.text, KugouConfig.successMarkers) != null) return it
        }
        return null
    }

    /** 系统跳转弹窗文案特征 */
    fun sysDialogHit(items: List<OcrItem>): String? = hitMarker(items, KugouConfig.sysDialogMarkers)

    // ------------------------------------------------------------------
    // v3.6.0：奖励浮层底部「点击浏览N秒，送惊喜奖励」续做按钮
    // ------------------------------------------------------------------

    /** 奖励浮层内部可能出现的关闭符号（只认 × 类，不认英文字母 x，避免误杀正文） */
    private val glyphStrict = listOf('×', '✕', '✖')

    /**
     * 奖励浮层是否存在（免费听歌模式页看完一条广告后弹出的奖励面板）。
     * 判据：文案命中 [KugouConfig.rewardPanelMarkers]，且中心落在 [KugouConfig.REWARD_DIALOG] 内。
     * 限定 ROI 是为了避免任务列表页同名词条（如「做任务免费听」）误触发。
     */
    fun rewardPanelHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        for (it in items) {
            if (!KugouConfig.REWARD_DIALOG.contains(it.cx, it.cy, w, h)) continue
            val n = norm(it.text)
            if (n.isEmpty()) continue
            if (KugouConfig.rewardPanelMarkers.any { it in n }) return it
        }
        return null
    }

    /**
     * 奖励浮层最底部的「点击浏览N秒，送惊喜奖励」按钮。
     *
     * 必须同时满足：
     * 1) 同帧存在奖励浮层特征（杜绝把任务列表页任务行当按钮）；
     * 2) 文字中心落在 [KugouConfig.REWARD_BROWSE] 内（浮层最底部一条，已避开吸底任务条与会员横幅下沿）；
     * 3) 文案含「点击浏览 / 点击观看」且含收益描述（送 / 再得 / 分钟 / 时长 …）；
     * 4) 不含任何付费危险文案（￥ / 开通 / 连续包月 …），也不含任务行右侧按钮「去浏览 / 去观看」；
     * 5) 原文不含 × 类关闭符号。
     * 多条命中时取最靠下的一条。
     */
    fun rewardBrowseHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        if (rewardPanelHit(items, w, h) == null) return null
        return items.asSequence()
            .filter { KugouConfig.REWARD_BROWSE.contains(it.cx, it.cy, w, h) }
            .filter { !isPayDanger(it.text) }
            .filter { item -> item.text.none { c -> c in glyphStrict } }
            .filter { item ->
                val n = norm(item.text)
                n.isNotEmpty() &&
                    KugouConfig.rewardBrowseKeys.any { it in n } &&
                    KugouConfig.rewardBrowseGainKeys.any { it in n } &&
                    "去浏览" !in n && "去观看" !in n
            }
            .maxByOrNull { it.cy }
    }

    /**
     * 任务页顶部「自动看广告续时长」开关标签定位。
     * OCR 只能拿到文字，开关本体在其右侧固定偏移处（见 [KugouConfig.SWITCH]），
     * 这里先确认标签存在且位于 [KugouConfig.SWITCH_LABEL] 内，再允许点击开关。
     */
    fun switchLabelHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        return items.asSequence()
            .filter { KugouConfig.SWITCH_LABEL.contains(it.cx, it.cy, w, h) }
            .filter { "自动看广告" in norm(it.text) }
            .minByOrNull { kotlin.math.abs(it.cy - KugouConfig.SWITCH.cy * h) }
    }

    /** 广告页「点击跳转后浏览N秒」CTA */
    fun jumpCtaHit(text: String): Boolean {
        val s = norm(text)
        if (s.isEmpty()) return false
        for (m in KugouConfig.jumpCtaMarkers) {
            if (norm(m) in s) return true
        }
        return false
    }

    /** 直播间误入判定：顶部区域出现「关注 / 更多直播」 */
    fun isLiveTop(text: String, cy: Float, screenHeight: Int): Boolean {
        if (screenHeight <= 0) return false
        if (cy > screenHeight * KugouConfig.LIVE_TOP_RATIO) return false
        val n = norm(text)
        return KugouConfig.liveKeys.any { it in n }
    }

    // ------------------------------------------------------------------
    // 额度耗尽终止态（v3.2.0）：只点「取消」，绝不点任何付费/开通会员按钮
    // ------------------------------------------------------------------

    /** 付费 / 开通会员类文案：命中即视为危险目标，任何规则都不得点击 */
    fun isPayDanger(raw: String?): Boolean {
        val n = norm(raw)
        if (n.isEmpty()) return false
        return KugouConfig.payDangerKeys.any { it in n }
    }

    /**
     * 「今日免费额度已用完」会员付费弹窗命中。
     * 判据：出现弹窗标题（今日免费额度已用完 / 额度已用完）或副标题特征（每日限量体验），
     * 且该文案位于弹窗区域 [KugouConfig.QUOTA_DIALOG] 内，避免任务列表页文案误触发。
     */
    fun quotaHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        for (it in items) {
            val n = norm(it.text)
            if (n.isEmpty()) continue
            if (KugouConfig.quotaMarkers.none { it in n }) continue
            if (!KugouConfig.QUOTA_DIALOG.contains(it.cx, it.cy, w, h)) continue
            return it
        }
        return null
    }

    /**
     * 额度耗尽弹窗里唯一允许点击的「取消」按钮：
     * 必须落在 [KugouConfig.QUOTA_CANCEL] 热区内，文案匹配「取消」，且不含任何付费文案。
     * 付费按钮（「1.88元开会员畅听VIP曲库」）与其它任何按钮都不会被返回。
     */
    fun quotaCancelHit(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        if (w <= 0 || h <= 0) return null
        return items.asSequence()
            .filter { KugouConfig.QUOTA_CANCEL.contains(it.cx, it.cy, w, h) }
            .filter { !isPayDanger(it.text) }
            .filter { item -> KugouConfig.quotaCancelTargets.any { t -> match(item.text, t) } }
            .maxByOrNull { it.cy }
    }
}
