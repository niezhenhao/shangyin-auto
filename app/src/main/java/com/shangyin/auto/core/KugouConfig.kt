package com.shangyin.auto.core

/**
 * 酷狗音乐「免费听歌模式 / 自动看广告续时长」的全部可调参数。
 *
 * 结构与 [QqConfig] 完全对齐（目标规则表 / ROI / 冷却 / 页面特征文案 / 流程时间参数），
 * 差异仅在目标词与热区：以下数值取自三张酷狗真机截图（1150×2542）的 OCR 归一化坐标，
 * 手机屏幕尺寸变化时按比例自适应。
 */
object KugouConfig {

    const val PKG = "com.kugou.android"

    /** 前台是这些包时视为「系统跳转/权限弹窗」，而不是第三方广告落地页 */
    val SYS_DIALOG_PKGS: List<String> = listOf(
        "com.hihonor.systemmanager",
        "com.huawei.systemmanager",
        "com.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.iqoo.secure",
        "com.samsung.android.permissioncontroller",
        "com.transsion.systemmanager",
        "android"
    )

    data class Roi(val x0: Float, val x1: Float, val y0: Float, val y1: Float) {
        fun contains(x: Float, y: Float, w: Int, h: Int): Boolean =
            x >= x0 * w && x <= x1 * w && y >= y0 * h && y <= y1 * h

        /** 比例坐标（0~1）判定，用于点击热区二次校验 */
        fun containsRatio(xr: Float, yr: Float): Boolean =
            xr >= x0 && xr <= x1 && yr >= y0 && yr <= y1

        fun overlap(o: Roi): Boolean =
            x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1

        val cx: Float get() = (x0 + x1) / 2f
        val cy: Float get() = (y0 + y1) / 2f
    }

    /**
     * 目标规则。字段含义与 py 版一致：
     * name 目标词，roi 允许点击区域，cooldownMs 命中后冷却，corner=true 时同分优先偏右上。
     */
    data class Rule(
        val name: String,
        val roi: Roi,
        val cooldownMs: Long,
        val enabled: Boolean = true,
        val corner: Boolean = false
    )

    // ---------------- 区域预设（比例坐标，基于酷狗真机截图） ----------------
    val FULL = Roi(0f, 1f, 0f, 1f)
    val CENTER = Roi(0.25f, 0.75f, 0.30f, 0.72f)
    val CENTER_LOW = Roi(0.20f, 0.80f, 0.45f, 0.85f)
    val BOTTOM = Roi(0.10f, 0.90f, 0.78f, 0.96f)

    /**
     * 危险性「退出模式」热区：真机「退出模式」在 (0.922, 0.094)，
     * 点中会直接关闭免费听歌模式并清零剩余免费时长。
     * 该区域只允许真正的关闭类目标进入，且目标词本身已在 [payDangerKeys] 中被全局拦截。
     */
    val CLOSE_HOTSPOT = Roi(0.84f, 1.00f, 0.055f, 0.130f)

    /**
     * 「自动看广告续时长」入口：真机文字中心 (0.758, 0.285)，
     * 位于「退出模式」下方约 19% 屏高，取安全外扩并左移，避免落点漂移到退出模式。
     */
    val TIME_ADD = Roi(0.62f, 0.92f, 0.240f, 0.330f)

    /** 顶部右侧「跳过 / 关闭」通用区，垂直上限收到 y=0.080，避开 y≈0.094 的「退出模式」 */
    val TOP_RIGHT = Roi(0.66f, 1.00f, 0.020f, 0.080f)

    /** 广告落地页右上角「广告丨×」：真机文字中心 (0.836, 0.108)，× 字形在框右端 */
    val AD_CLOSE = Roi(0.76f, 1.00f, 0.085f, 0.140f)

    /** 中部弹窗区（各类二次确认 / 奖励说明框） */
    val DIALOG = Roi(0.05f, 0.95f, 0.25f, 0.80f)

    /**
     * 付费墙弹窗（酷狗「开通会员畅听」「免费额度用尽」一类）：
     * 真机底部会员横幅「￥1立即开通」cy≈0.980、「连续包月1元」cy≈0.966，
     * 中部为标题与副标题，该弹窗属于终止态：只允许点「取消」，任何付费按钮一律禁点。
     */
    val QUOTA_DIALOG = Roi(0.10f, 0.90f, 0.30f, 0.72f)

    /** 付费墙弹窗内唯一允许点击的「取消」热区 */
    val QUOTA_CANCEL = Roi(0.25f, 0.75f, 0.500f, 0.660f)

    /** 广告落地页底部主 CTA「了解详情」：真机文字中心 (0.524, 0.917) */
    val AD_CTA = Roi(0.15f, 0.90f, 0.880f, 0.955f)

    /** 系统跳转弹窗正文「进入详情页或第三方应用」：真机文字中心 (0.520, 0.951) */
    val CTA_JUMP = Roi(0.15f, 0.95f, 0.915f, 0.995f)

    /** 系统弹窗「允许 / 打开」按钮热区（屏幕最底部一条） */
    val SYS_ALLOW = Roi(0.50f, 1.00f, 0.895f, 1.000f)

    /** 任务行左侧说明「点击浏览N秒」所在列，仅用于文本识别，不直接点击 */
    val LEFT_LIST = Roi(0.10f, 0.92f, 0.500f, 0.950f)

    /** 任务列表整体区域 */
    val TASK_LIST = Roi(0.10f, 0.92f, 0.160f, 0.950f)

    /**
     * 任务行右侧操作按钮「去浏览 / 去观看」列：
     * 真机三处按钮中心分别为 (0.867, 0.615)、(0.869, 0.804)、(0.870, 0.883)。
     */
    val BROWSE_BTN = Roi(0.62f, 0.99f, 0.550f, 0.940f)

    /** 奖励到账浮层「恭喜获得40分钟 / 继续做任务送惊喜奖励」区域 */
    val REWARD_DIALOG = Roi(0.05f, 0.95f, 0.620f, 0.780f)

    /**
     * 底部吸底任务条「点击浏览5秒再得30分钟」(cy≈0.990)。
     * 与会员横幅「￥1立即开通」(cy≈0.980) 仅相隔约 1% 屏高，故不设点击规则，
     * 只作为「当前仍在任务列表页」的特征存在。
     */
    val BOTTOM_REWARD = Roi(0.10f, 0.95f, 0.955f, 1.000f)

    /** 关闭类目标名：只有这些目标允许落到右上角关闭热区，其余目标被二次校验拦下 */
    val closeRuleNames: Set<String> = setOf("×", "关闭", "关闭广告")

    // ---------------- 目标规则表（顺序即优先级） ----------------
    val rules: List<Rule> = listOf(
        // 第零优先级：任务列表页「自动看广告续时长」入口（严格 ROI，避开右上角退出模式）
        Rule("自动看广告续时长", TIME_ADD, 4000),
        // 第一优先级：任务行右侧「去浏览 / 去观看 / 去完成」
        Rule("去浏览", BROWSE_BTN, 3000),
        Rule("去观看", BROWSE_BTN, 3000),
        Rule("去完成", BROWSE_BTN, 3000),
        Rule("领取奖励", CENTER, 2500),
        Rule("立即领取", CENTER, 2500),
        Rule("领取", LEFT_LIST, 2500),
        // 第二优先级：播放中的继续 / 跳过
        Rule("继续观看", CENTER, 2000),
        Rule("继续收听", CENTER, 2000),
        Rule("跳过广告", TOP_RIGHT, 5000, corner = true),
        Rule("跳过", TOP_RIGHT, 5000, corner = true),
        // 广告落地页底部主 CTA
        Rule("了解详情", AD_CTA, 5000),
        // 第三优先级：结束后的关闭 / 确认
        Rule("关闭广告", AD_CLOSE, 2000, corner = true),
        Rule("关闭", AD_CLOSE, 2000, corner = true),
        Rule("×", AD_CLOSE, 2000, corner = true),
        Rule("知道了", CENTER, 2000),
        Rule("完成", CENTER, 2000),
        Rule("收下", CENTER, 2000)
    )

    /**
     * 目标匹配时忽略的长提示文本（倒计时、任务说明等）。
     * 「秒」被列入是因为酷狗任务行左侧的「点击浏览5秒」「点击浏览10秒免费听30分钟」只是说明文案，
     * 真正的操作按钮是右侧「去浏览 / 去观看」，只需识别不需要点击，
     * 避免把说明文案当按钮点偏到相邻的会员横幅。同理「退出模式」永久排除。
     */
    val excludeKeys: List<String> = listOf("秒", "解锁", "后可领取", "第", "退出模式", "立即开通")

    /**
     * 危险文案（付费 / 开通会员 / 退出模式）：任何识别文本一旦命中，就绝对不能作为点击目标。
     * 酷狗底部常驻「￥1立即开通」「连续包月1元」「低至￥0.03元/天免看广告」，
     * 顶部有「退出模式」，误点会跳支付页或直接退出免费听歌模式让任务卡死，
     * 因此这里做全局硬拦截（不看规则、不看 ROI）。
     */
    val payDangerKeys: List<String> = listOf(
        "开通", "会员", "元", "1.88", "畅听", "VIP", "vip", "¥", "￥",
        "续费", "支付", "购买", "特惠", "首月", "立即充值", "充值",
        "连续包月", "低至", "免看广告", "退出模式"
    )

    /** 付费墙弹窗标题 / 副标题特征词：付费墙终止态识别依据 */
    val quotaMarkers: List<String> = listOf(
        "额度已用完", "免费额度已用完", "今日免费额度", "每日限量体验", "开通会员畅听", "免费时长已用完"
    )

    /** 付费墙弹窗里唯一允许点击的按钮文案 */
    val quotaCancelTargets: List<String> = listOf("取消")

    /** 付费墙弹窗出现后悬浮条 / 日志的统一提示文案 */
    const val QUOTA_STOP_TEXT = "酷狗免费听歌额度已用完，已停止"

    /** 点完「取消」后保持提示可见的时长，随后安全结束本轮任务 */
    var quotaNoticeMs: Long = 8000L

    /** 弹窗存在但连续多少轮定位不到「取消」后放弃点击、直接终止（防止空转卡死） */
    var quotaCancelMissLimit: Int = 3

    /** 点完会直接切到第三方广告页（无酷狗内过渡页）的入口，用于包名变化后接管停留计时 */
    val directJumpTargets: Set<String> = setOf(
        "自动看广告续时长", "去浏览", "去观看", "去完成", "了解详情"
    )

    /** 保住剩余时长的弹窗按钮：出现即点，绝不点「确认退出」 */
    val keepListeningTargets: List<String> = listOf("继续免费听", "继续看广告", "继续任务", "继续免看广告")

    /**
     * 任务列表页特征词：命中 ≥2 个即判定酷狗「免费听歌模式」任务列表页。
     * 该页顶部右侧是「退出模式」，禁止任何关闭类规则落点漂移到那里。
     */
    val taskListKeys: List<String> = listOf(
        "自动看广告续时长", "点击浏览", "去浏览", "去观看", "免费听歌模式",
        "剩余时长", "完成4个任务", "今日到访", "做任务免费听"
    )

    /** 图形「×」兜底识别：OCR 原文含这些符号且位于屏幕顶部，才认为是广告/已获得页的关闭钮 */
    val closeGlyphChars: List<Char> = listOf('×', '✕', '✖', 'x', 'X')

    // ---------------- 页面特征文案 ----------------
    val jumpCtaMarkers: List<String> = listOf(
        "了解详情", "点击跳转", "立即跳转", "点击卡片", "去逛逛", "打开看看", "点击后浏览", "点击后看"
    )
    val sysDialogMarkers: List<String> = listOf(
        "进入详情页或第三方应用", "想要打开", "是否允许", "应用跳转提醒", "安全提示", "打开其他应用"
    )
    val successMarkers: List<String> = listOf(
        "恭喜获得", "已获得", "已到账", "奖励已发放", "获得免费时长"
    )
    val taskPageMarkers: List<String> = listOf(
        "自动看广告续时长", "点击浏览", "去浏览", "去观看", "免费听歌模式", "了解详情", "领取"
    )
    val allowTargets: List<String> = listOf("允许", "始终允许", "本次允许", "同意", "打开")
    val liveKeys: List<String> = listOf("关注", "更多直播")

    // ---------------- 流程时间参数 ----------------
    var pollIntervalMs: Long = 1200L
    var clickCooldownMs: Long = 2000L
    var maxMissBeforeBack: Int = 6
    var backWaitMs: Long = 2000L

    /** 默认第三方停留秒数；实际取「页面文案要求秒数」与本值的较大者（酷狗任务多为 5s / 10s） */
    val dwellSeconds: Int = 12
    val dwellExtra: Int = 3
    val dwellMin: Int = 3
    val dwellMax: Int = 90

    var jumpWaitMs: Long = 6000L
    var returnWaitMs: Long = 2500L
    var returnRetry: Int = 3
    var successWaitMs: Long = 5000L
    var successDedupMs: Long = 12000L
    var doneCloseTries: Int = 3

    /** 连续失败退避阶梯（毫秒） */
    val failBackoffMs: LongArray = longArrayOf(5000, 10000, 20000, 30000)

    var taskSwipeMax: Int = 6
    var taskIdleWaitMs: Long = 15000L
    var idlePollIntervalMs: Long = 1600L
    var autoAllowJump: Boolean = true

    /** 广告页连续多少轮无文字目标后启用像素「×」兜底 */
    var glyphFallbackRounds: Int = 2

    var simulateRounds: Int = 8
    var simulateTimeoutMs: Long = 60000L

    /** 直播间判定区域：屏幕上方 25% */
    const val LIVE_TOP_RATIO = 0.25f
}
