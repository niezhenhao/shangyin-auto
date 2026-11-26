package com.shangyin.auto.core

/**
 * QQ音乐自动看广告的全部可调参数，逐条移植自 PC 端 qqmusic-auto/qqmusic_auto.py：
 * 目标规则表（含 ROI / 冷却 / 角落优先）、页面特征文案、流程时间参数。
 */
object QqConfig {

    const val PKG = "com.tencent.qqmusic"

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

    // ---------------- 区域预设（比例坐标，基于真实屏幕） ----------------
    val FULL = Roi(0f, 1f, 0f, 1f)
    val CENTER = Roi(0.25f, 0.75f, 0.30f, 0.72f)
    val CENTER_LOW = Roi(0.20f, 0.80f, 0.45f, 0.85f)
    val BOTTOM = Roi(0.10f, 0.90f, 0.78f, 0.96f)

    /**
     * 页面右上角「关闭 / ×」热区。真机实测：QQ音乐「免费听歌」任务列表页的关闭钮
     * 与「加时长」入口在垂直方向仅相距约 3% 屏高，一旦点击落点漂移就会命中关闭钮，
     * 触发「关闭后将失去免费畅听权限，剩余时长将清零」确认弹窗。
     * 因此该区域只允许关闭类目标进入，普通目标（加时长 / 领取 / 去浏览等）一律禁点。
     */
    val CLOSE_HOTSPOT = Roi(0.88f, 1.00f, 0.030f, 0.200f)

    /** 「加时长」按钮：真机文字框 x 0.7645~0.8839、y 0.1361~0.1557，取安全外扩并左移避开右侧关闭热区 */
    val TIME_ADD = Roi(0.70f, 0.90f, 0.105f, 0.185f)

    /** 顶部右侧「跳过 / 关闭」通用区，垂直上限收到 y=0.12，避免扫到「加时长」那一行 */
    val TOP_RIGHT = Roi(0.66f, 1.00f, 0.020f, 0.120f)

    /** 广告/已获得页右上角「×」：真机「广告丨×」框 x 0.7589~0.9554、y 0.0953~0.1154，× 字形在右端 */
    val AD_CLOSE = Roi(0.90f, 1.00f, 0.060f, 0.140f)

    /** 中部弹窗区（「关闭后将失去免费畅听权限」确认框所在范围） */
    val DIALOG = Roi(0.05f, 0.95f, 0.30f, 0.75f)

    /**
     * 「今日免费听歌额度已用完」会员付费弹窗（真机 1200×2652 实测）：
     * 标题「今日免费听歌额度已用完」cy≈0.424、副标题「每日限量体验16次」cy≈0.468、
     * 绿色付费按钮「1.88元开会员畅听VIP曲库」cy≈0.528、灰色「取消」cy≈0.580。
     * 该弹窗属于额度耗尽终止态：只允许点「取消」，任何付费/开通会员按钮一律禁点。
     */
    val QUOTA_DIALOG = Roi(0.10f, 0.90f, 0.380f, 0.640f)

    /** 额度耗尽弹窗内唯一允许点击的「取消」热区（真机文字框 x 0.4598~0.5391、y 0.5696~0.5902） */
    val QUOTA_CANCEL = Roi(0.30f, 0.70f, 0.545f, 0.615f)

    /** 点完会直接切到第三方广告页（无 QQ音乐内过渡页）的入口，用于包名变化后接管停留计时 */
    val directJumpTargets: Set<String> = setOf("加时长", "去浏览", "去完成", "试玩应用", "做任务得", "领取时长")

    /** 保住剩余时长的弹窗按钮：出现即点，绝不点「确认关闭」 */
    val keepListeningTargets: List<String> = listOf("继续免费听", "继续听歌")
    val AD_CTA = Roi(0.05f, 0.95f, 0.70f, 0.96f)
    val CTA_JUMP = Roi(0.20f, 0.80f, 0.855f, 0.945f)
    val SYS_ALLOW = Roi(0.55f, 0.99f, 0.895f, 0.995f)
    val LEFT_LIST = Roi(0.55f, 0.98f, 0.18f, 0.92f)
    val TASK_LIST = Roi(0.10f, 0.90f, 0.18f, 0.92f)

    /** 关闭类目标名：只有这些目标允许落到右上角关闭热区，其余目标被二次校验拦下 */
    val closeRuleNames: Set<String> = setOf("×", "关闭", "关闭广告")

    // ---------------- 目标规则表（顺序即优先级） ----------------
    val rules: List<Rule> = listOf(
        // 第零优先级：任务列表页「加时长」入口（严格 ROI，避开右上角关闭热区）
        Rule("加时长", TIME_ADD, 4000),
        // 第一优先级：广告入口
        Rule("看广告", CENTER, 3000),
        Rule("看视频", CENTER, 3000),
        Rule("免费听", BOTTOM, 3000),
        Rule("免费畅听", CENTER_LOW, 3000),
        Rule("点击领取", CENTER, 2500),
        Rule("立即领取", CENTER, 2500),
        Rule("领取奖励", CENTER, 2500),
        Rule("立即领", CENTER, 2500),
        Rule("领取", LEFT_LIST, 2500),
        // 第二优先级：播放中的继续 / 跳过
        Rule("继续观看", CENTER, 2000),
        Rule("继续收听", CENTER, 2000),
        Rule("跳过广告", TOP_RIGHT, 5000, corner = true),
        Rule("跳过", TOP_RIGHT, 5000, corner = true),
        // 第三优先级：结束后的关闭 / 确认
        Rule("关闭广告", TOP_RIGHT, 2000, corner = true),
        Rule("关闭", TOP_RIGHT, 2000, corner = true),
        Rule("×", AD_CLOSE, 2000, corner = true),
        Rule("知道了", CENTER, 2000),
        Rule("完成", CENTER, 2000),
        Rule("收下", CENTER, 2000)
    )

    /** 目标匹配时忽略的长提示文本（倒计时、任务说明等） */
    val excludeKeys: List<String> = listOf("秒", "解锁", "后可领取", "第", "浏览")

    /**
     * 付费 / 开通会员类文案：任何识别文本一旦命中这些词，就绝对不能作为点击目标。
     * 真机弹窗「1.88元开会员畅听VIP曲库」位于 CENTER_LOW，会同时命中「免费畅听」规则，
     * 误点会直接跳转支付页并让任务卡死，因此这里做全局硬拦截（不看规则、不看 ROI）。
     */
    val payDangerKeys: List<String> = listOf(
        "开通", "会员", "元", "1.88", "畅听", "VIP", "vip", "¥", "￥",
        "续费", "支付", "购买", "特惠", "首月", "立即充值", "充值"
    )

    /** 「今日免费听歌额度已用完」弹窗标题 / 副标题特征词：额度耗尽终止态识别依据 */
    val quotaMarkers: List<String> = listOf(
        "今日免费听歌额度已用完", "免费听歌额度已用完", "额度已用完", "每日限量体验"
    )

    /** 额度耗尽弹窗里唯一允许点击的按钮文案 */
    val quotaCancelTargets: List<String> = listOf("取消")

    /** 额度耗尽后悬浮条 / 日志的统一提示文案 */
    const val QUOTA_STOP_TEXT = "今日免费听歌额度已用完，已停止"

    /** 点完「取消」后保持提示可见的时长，随后安全结束本轮任务 */
    var quotaNoticeMs: Long = 8000L

    /** 弹窗存在但连续多少轮定位不到「取消」后放弃点击、直接终止（防止空转卡死） */
    var quotaCancelMissLimit: Int = 3

    /**
     * 任务列表页特征词：命中 ≥2 个即判定「免费听歌」任务列表页。
     * 该页存在页面自身的关闭钮，禁止任何关闭类点击与图形「×」兜底。
     */
    val taskListKeys: List<String> = listOf(
        "加时长", "试玩应用", "做任务得", "去浏览", "去完成", "领取时长", "每日任务", "今日任务", "免费畅听"
    )

    /** 图形「×」兜底识别：OCR 原文含这些符号且位于屏幕顶部，才认为是广告/已获得页的关闭钮 */
    val closeGlyphChars: List<Char> = listOf('×', '✕', '✖', 'x', 'X')

    // ---------------- 页面特征文案 ----------------
    val jumpCtaMarkers: List<String> = listOf("点击跳转后浏览", "点击卡片", "点击跳转", "立即跳转", "去逛逛", "打开看看")
    val sysDialogMarkers: List<String> = listOf("想要打开", "是否允许", "应用跳转提醒", "安全提示")
    val successMarkers: List<String> = listOf("已获得免费听歌", "已获得免费听", "免费听歌时长已", "免费听歌已到账")
    val taskPageMarkers: List<String> = listOf("看广告", "看视频", "免费听歌", "每日任务", "今日任务", "领取")
    val allowTargets: List<String> = listOf("允许", "始终允许", "本次允许", "同意")
    val liveKeys: List<String> = listOf("关注", "更多直播")

    // ---------------- 流程时间参数 ----------------
    var pollIntervalMs: Long = 1200L
    var clickCooldownMs: Long = 2000L
    var maxMissBeforeBack: Int = 6
    var backWaitMs: Long = 2000L

    /** 默认第三方停留秒数；实际取「页面文案要求秒数」与本值的较大者 */
    val dwellSeconds: Int = 25
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
