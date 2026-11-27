package com.shangyin.auto.core

/**
 * 网易云音乐自动看广告的全部可调参数（v3.9.2 新增）。
 *
 * 结构与 QqConfig 保持一致，但坐标来自网易云真机截图（1200×2652）OCR 实测，
 * 与 QQ音乐完全不同，不可互相套用：
 * - 入口不是顶部右侧的「加时长」，而是屏幕中部偏下的奖励弹窗按钮「观看领奖励」；
 * - 弹窗内并排两个按钮：左侧「赢全天畅听」（含付费危险词，永不点击）、
 *   右侧红底「观看领奖励」（唯一入口）；
 * - 广告落地页（京东）底部有「进入落地页查看详情」CTA，点击后进入真正的落地页。
 */
object CloudConfig {

    const val PKG = "com.netease.cloudmusic"

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
     * 目标规则。name 目标词，roi 允许点击区域，cooldownMs 命中后冷却，
     * corner=true 时同分优先偏右上。
     */
    data class Rule(
        val name: String,
        val roi: Roi,
        val cooldownMs: Long,
        val enabled: Boolean = true,
        val corner: Boolean = false
    )

    // ---------------- 区域预设（比例坐标，基于 1200×2652 真机截图） ----------------
    val FULL = Roi(0f, 1f, 0f, 1f)
    val CENTER = Roi(0.25f, 0.75f, 0.30f, 0.72f)
    val CENTER_LOW = Roi(0.20f, 0.80f, 0.45f, 0.85f)
    val BOTTOM = Roi(0.10f, 0.90f, 0.78f, 0.96f)

    /**
     * 奖励弹窗整体区域（真机实测）：
     * 标题「再看一个视频，额外畅听」y≈0.464~0.484、红字「最高90分钟」y≈0.52、
     * 两枚按钮 y≈0.599~0.617、弹窗下方「×」y≈0.77~0.82（不在本区域内）。
     */
    val REWARD_CARD = Roi(0.12f, 0.88f, 0.40f, 0.72f)

    /**
     * 「观看领奖励」按钮热区。真机文字框 x 0.5675~0.7792、y 0.5988~0.6173，
     * 取安全外扩（左右各留约 3%，上下各留约 3%）后落在红底按钮内部。
     * 左邻「赢全天畅听」（x 0.18~0.46）不在本区域内，两者不会混淆。
     */
    val CLOUD_ENTRY = Roi(0.53f, 0.82f, 0.570f, 0.645f)

    /** 任务列表行右侧的「去观看 / 观看领奖励」按钮列（网易云任务页为左右两栏行结构） */
    val TASK_ROW_RIGHT = Roi(0.50f, 0.92f, 0.20f, 0.90f)

    /** 列表左栏通用入口（任务页整行可点区域，避开右侧按钮与关闭钮） */
    val LEFT_LIST = Roi(0.05f, 0.60f, 0.18f, 0.92f)

    /** 任务页整页范围（用于任务页特征判定，不用于点击） */
    val TASK_LIST = Roi(0.08f, 0.92f, 0.16f, 0.94f)

    /** 页面顶部右侧「跳过 / 关闭」通用区 */
    val TOP_RIGHT = Roi(0.66f, 1.00f, 0.020f, 0.120f)

    /** 右上角「关闭 / ×」热区：普通目标落点进入即放弃本次点击 */
    val CLOSE_HOTSPOT = Roi(0.88f, 1.00f, 0.030f, 0.200f)

    /** 广告页右上角「×」：网易云广告位关闭钮通常紧贴状态栏下方右侧 */
    val AD_CLOSE = Roi(0.90f, 1.00f, 0.050f, 0.140f)

    /** 中部弹窗区（通用确认框） */
    val DIALOG = Roi(0.05f, 0.95f, 0.30f, 0.80f)

    /**
     * 黑胶会员 / 付费开通弹窗区域与唯一允许点击的「取消」热区。
     * 网易云付费弹窗居中显示，取消按钮位于弹窗底部；坐标按同类弹窗保守取值，
     * 一旦识别不到「取消」，执行器会连续 3 轮不点任何按钮后安全终止，绝不乱点。
     */
    val QUOTA_DIALOG = Roi(0.10f, 0.90f, 0.350f, 0.700f)
    val QUOTA_CANCEL = Roi(0.25f, 0.75f, 0.560f, 0.700f)

    /** 广告落地页底部 CTA（真机「进入落地页查看详情」x 0.307~0.694、y 0.923~0.941） */
    val CTA_JUMP = Roi(0.10f, 0.90f, 0.880f, 0.975f)

    /** 广告落地页底部通用 CTA 区（「点击跳转APP停留8秒」y 0.820~0.838 所在带） */
    val AD_CTA = Roi(0.05f, 0.95f, 0.780f, 0.975f)

    /** 系统弹窗「允许」按钮 */
    val SYS_ALLOW = Roi(0.55f, 0.99f, 0.895f, 0.995f)

    /** 关闭类目标名：只有这些目标允许落到右上角关闭热区，其余目标被二次校验拦下 */
    val closeRuleNames: Set<String> = setOf("×", "关闭", "关闭广告")

    // ---------------- 目标规则表（顺序即优先级） ----------------
    val rules: List<Rule> = listOf(
        // 第一优先级：奖励弹窗「观看领奖励」（专用 ROI，不与任务行按钮混淆）
        Rule("观看领奖励", CLOUD_ENTRY, 5000),
        // 第二优先级：任务列表行右侧「去观看 / 去完成」
        Rule("去观看", TASK_ROW_RIGHT, 4000),
        Rule("去完成", TASK_ROW_RIGHT, 4000),
        Rule("看视频", TASK_ROW_RIGHT, 4000),
        Rule("看广告", TASK_ROW_RIGHT, 4000),
        Rule("领取奖励", CENTER, 2500),
        Rule("立即领取", CENTER, 2500),
        Rule("领取", LEFT_LIST, 2500),
        Rule("立即领", CENTER, 2500),
        // 第三优先级：播放中的继续 / 跳过
        Rule("继续观看", CENTER, 2000),
        Rule("继续收听", CENTER, 2000),
        Rule("跳过广告", TOP_RIGHT, 5000, corner = true),
        Rule("跳过", TOP_RIGHT, 5000, corner = true),
        // 第四优先级：结束后的关闭 / 确认
        Rule("关闭广告", TOP_RIGHT, 2000, corner = true),
        Rule("关闭", TOP_RIGHT, 2000, corner = true),
        Rule("×", AD_CLOSE, 2000, corner = true),
        Rule("知道了", CENTER, 2000),
        Rule("完成", CENTER, 2000),
        Rule("收下", CENTER, 2000)
    )

    /**
     * 目标匹配时忽略的长提示文本。
     * 真机广告页顶部有「点击后停留8秒，领取权益」提示语，含「领取」二字，
     * 必须靠这些词排除，避免把提示文字当按钮点。
     */
    val excludeKeys: List<String> = listOf("秒", "停留", "权益", "解锁", "后可领取", "第", "浏览", "活动详情", "实际福利")

    /**
     * 付费 / 黑胶开通类文案：任何识别文本一旦命中这些词，就绝对不能作为点击目标。
     * 真机奖励弹窗左侧「赢全天畅听」含「畅听」，与入口「观看领奖励」仅相隔约 10% 屏宽，
     * 误点会跳转支付页并让任务卡死，因此这里做全局硬拦截（不看规则、不看 ROI）。
     */
    val payDangerKeys: List<String> = listOf(
        "开通", "黑胶", "会员", "元", "SVIP", "VIP", "vip", "畅听", "¥", "￥",
        "续费", "支付", "购买", "特惠", "首月", "立即充值", "充值", "升级"
    )

    /** 黑胶会员 / 付费墙弹窗特征词：命中即进入终止态（只允许点「取消」） */
    val quotaMarkers: List<String> = listOf(
        "黑胶会员", "开通黑胶", "黑胶VIP", "会员已到期", "升级黑胶", "限时特惠", "付费歌曲"
    )

    /** 付费弹窗里唯一允许点击的按钮文案 */
    val quotaCancelTargets: List<String> = listOf("取消", "以后再说", "暂不")

    /** 付费墙触发后悬浮条 / 日志的统一提示文案 */
    const val QUOTA_STOP_TEXT = "检测到黑胶会员付费弹窗，已停止"

    /** 点完「取消」后保持提示可见的时长，随后安全结束本轮任务 */
    var quotaNoticeMs: Long = 8000L

    /** 弹窗存在但连续多少轮定位不到「取消」后放弃点击、直接终止（防止空转卡死） */
    var quotaCancelMissLimit: Int = 3

    /**
     * 任务列表页特征词：命中 ≥2 个即判定为网易云「任务中心 / 观看领奖励」列表页。
     * 该页存在页面自身的关闭钮，禁止任何关闭类点击与图形「×」兜底。
     */
    val taskListKeys: List<String> = listOf(
        "观看领奖励", "去观看", "看视频", "看广告", "每日任务", "任务中心",
        "领奖励", "赚音符", "做任务"
    )

    /** 图形「×」兜底识别：OCR 原文含这些符号且位于屏幕顶部，才认为是广告/已获得页的关闭钮 */
    val closeGlyphChars: List<Char> = listOf('×', '✕', '✖', 'x', 'X')

    // ---------------- 页面特征文案 ----------------
    /** 广告落地页底部 CTA：「进入落地页查看详情」「点击跳转APP停留8秒」 */
    val jumpCtaMarkers: List<String> = listOf(
        "进入落地页查看详情", "点击跳转APP停留", "点击跳转后浏览", "点击跳转",
        "立即跳转", "去逛逛", "打开看看", "去看看", "查看详情"
    )

    val sysDialogMarkers: List<String> = listOf("想要打开", "是否允许", "应用跳转提醒", "安全提示", "即将离开")

    /**
     * 奖励到账 / 可继续领奖的特征词。
     * 网易云没有 QQ音乐那种「已获得免费听歌」横幅，看广告回来后奖励弹窗会再次出现，
     * 弹窗里的「观看领奖励」即视为本轮奖励已到账，可直接点它继续下一轮。
     */
    val successMarkers: List<String> = listOf("观看领奖励", "再看一个视频", "领取成功", "已领取", "奖励已到账")

    /** 任务页特征文案 */
    val taskPageMarkers: List<String> = listOf("观看领奖励", "去观看", "看视频", "免费畅听", "每日任务", "任务中心", "领奖励")

    val allowTargets: List<String> = listOf("允许", "始终允许", "本次允许", "同意")

    /** 直播间误入判定（网易云「关注 / 更多直播」） */
    val liveKeys: List<String> = listOf("关注", "更多直播")

    /** 点完直接切第三方（无 App 内过渡页）的入口，用于包名变化后接管停留计时 */
    val directJumpTargets: Set<String> = setOf("观看领奖励", "去观看", "去完成", "看视频", "看广告")

    // ---------------- 流程时间参数 ----------------
    var pollIntervalMs: Long = 1200L
    var clickCooldownMs: Long = 2500L
    var maxMissBeforeBack: Int = 6
    var backWaitMs: Long = 2000L

    /**
     * 默认第三方停留秒数。网易云广告文案为「点击跳转APP停留8秒」，
     * 实际取「页面文案要求秒数」与本值的较大者，再叠加 dwellExtra 缓冲。
     */
    val dwellSeconds: Int = 10
    val dwellExtra: Int = 3
    val dwellMin: Int = 5
    val dwellMax: Int = 90

    var jumpWaitMs: Long = 6000L
    var returnWaitMs: Long = 2500L
    var returnRetry: Int = 3

    // ---- v3.9.3：广告结束不再连按返回键，改为按包名直接拉起主 Activity 回前台 ----
    /** 拉起主 Activity 后等待回到前台的时间（毫秒） */
    var bringBackWaitMs: Long = 3000L
    /** 拉起未生效时的重试次数，超限记一次失败并回任务页 */
    var bringBackRetry: Int = 2
    var successWaitMs: Long = 6000L
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
