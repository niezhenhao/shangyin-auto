package com.shangyin.auto.core

/**
 * 自动化的全部可调参数，规则与 PC 端 qishui_auto.py 保持一致：
 * 目标词优先级 / 置信度阈值 / ROI 校验 / 冷却 / 连续未命中提示。
 */
object AutoConfig {

    /** 目标文字，按优先级从高到低排列 */
    var targets: List<String> = listOf("领取奖励", "继续观看", "领取成功")

    /** 需要点击屏幕中央的目标 */
    val centerTargets: Set<String> = setOf("领取奖励", "继续观看")

    /** 点击后无需冷却的目标 */
    val noCooldownTargets: Set<String> = setOf("领取成功")

    /** 出现这些字样的文本一律视为倒计时/提示，不点击 */
    val excludeKeys: List<String> = listOf("秒", "解锁", "后可领取", "第")

    /** 顶部出现这些字样说明误入直播间，立即返回退出 */
    val liveKeys: List<String> = listOf("关注", "更多直播")

    var ocrScore: Float = 0.5f

    /** 正常轮询间隔（毫秒）。命中点击后另有冷却，避免空转占 CPU */
    var pollIntervalMs: Long = 1000L

    /** 点击后的冷却，避免同一按钮被连点 */
    var clickCooldownMs: Long = 2000L

    /** OCR 输入宽度上限，超过则缩放后识别 */
    var ocrMaxWidth: Int = 720

    /** 采集宽度（虚拟屏幕按此宽度等比创建，直接拿到小图，省去每帧缩放与 12MB 大图分配） */
    var captureMaxWidth: Int = 720

    /**
     * 连续未命中多少次只做一次日志提示。
     * 注意：这里只提示，不返回、不暂停、不关闭任何页面，主循环继续轮询。
     */
    var maxMisses: Int = 5

    /** 连续未命中时是否自动执行返回键（默认关闭，且不再由阈值触发） */
    var autoBackOnMiss: Boolean = false

    /** 无命中且画面内容长时间不变时，把轮询放慢到该间隔（毫秒），降低后台占用 */
    var idlePollIntervalMs: Long = 1600L

    /** 模拟模式最多跑多少轮、多少秒 */
    var simulateRounds: Int = 6
    var simulateTimeoutMs: Long = 45_000L

    data class Roi(val x0: Float, val x1: Float, val y0: Float, val y1: Float)

    fun roiFor(target: String): Roi = when (target) {
        "领取奖励", "继续观看" -> Roi(0.25f, 0.75f, 0.35f, 0.70f)
        "领取成功" -> Roi(0.0f, 1.0f, 0.01f, 0.97f)
        else -> Roi(0.0f, 1.0f, 0.0f, 1.0f)
    }

    /** 直播间判定区域：屏幕上方 25% */
    const val LIVE_TOP_RATIO = 0.25f

    // ---------------- v3.8.0 汽水音乐收尾参数（按录屏逐帧校准） ----------------

    /**
     * 广告关闭 X 的固定坐标兜底（归一化）。
     * 录屏实测：广告 SDK 把「领取成功 ×」画成一行，× 字形在
     * x≈0.908~0.927、y≈0.054~0.063，中心约 (0.918, 0.058)。
     */
    var closeFixedX: Float = 0.918f
    var closeFixedY: Float = 0.058f

    /** 图形 × 兜底搜索区域（归一化，右上角） */
    var closeGlyphRoiX0: Float = 0.78f
    var closeGlyphRoiX1: Float = 1.0f
    var closeGlyphRoiY0: Float = 0.02f
    var closeGlyphRoiY1: Float = 0.12f

    /** 判定广告结束前的静置等待（毫秒），避免角标刚弹出时误点 */
    var adEndWaitMs: Long = 800L

    /** 画面连续多少轮无变化即认为广告已播完（视频播放时画面会变） */
    var adStaticRounds: Int = 3

    /**
     * 广告最短观看时长（毫秒）。避免把「广告画面卡顿/暂停」误判为播完而提前关掉广告丢奖励。
     * 若顶部已出现「领取成功」角标，则不受该下限约束。
     */
    var adMinWatchMs: Long = 15_000L

    /** 画面签名差异小于该比例即视为静止 */
    var adStaticDiff: Float = 0.06f

    /** 广告页最长停留时间，超时强制进入收尾，避免永久卡在广告页 */
    var adMaxWaitMs: Long = 90_000L

    /** 点 X 后的观察等待（毫秒） */
    var closeWaitMs: Long = 1200L

    /** 点 X 的最大重试次数（超过则退回键兜底） */
    var maxCloseTries: Int = 3

    /** 领取奖励面板点击后的观察等待（毫秒） */
    var claimWaitMs: Long = 1200L

    /** 领取奖励的最大重试次数 */
    var maxClaimTries: Int = 3

    /** 节点点击的坐标容差（占屏幕高度比例），用于避免同名字样跨区域误点 */
    var nodeClickToleranceRatio: Float = 0.08f

    // ---- v3.9.3 一键顺序启动 ----

    /** 顺序启动时单个平台的最长运行时间（毫秒），到时自动切下一个平台 */
    var seqPerPlatformMs: Long = 8 * 60_000L

    /** 顺序启动时拉起 App 后等待首页渲染的时间（毫秒） */
    var seqLaunchWaitMs: Long = 9000L

    /** 顺序启动时单个平台结束到下一个平台之间的间隔（毫秒） */
    var seqSwitchDelayMs: Long = 3000L
}
