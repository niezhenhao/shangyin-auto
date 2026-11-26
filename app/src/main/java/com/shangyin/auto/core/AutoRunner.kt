package com.shangyin.auto.core

import android.graphics.Bitmap
import com.shangyin.auto.util.LogBus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.pow

/**
 * 运行状态，供主界面与悬浮窗订阅。
 */
object RunnerState {
    enum class Mode { IDLE, RUNNING, SIMULATING }

    @Volatile var mode: Mode = Mode.IDLE
    /** 当前运行的业务入口：悬浮条与主界面据此只展示对应那一项 */
    @Volatile var platform: Platform = Platform.QISHUI
    @Volatile var rounds: Int = 0
    @Volatile var clicks: Int = 0
    @Volatile var lastHit: String = "-"
    @Volatile var paused: Boolean = false
    @Volatile var message: String = "待命"

    fun reset(m: Mode, p: Platform = Platform.QISHUI) {
        mode = m
        platform = p
        rounds = 0
        clicks = 0
        lastHit = "-"
        paused = false
        message = if (m == Mode.SIMULATING) "模拟中" else "运行中"
    }

    fun stop() {
        mode = Mode.IDLE
        paused = false
        message = "已停止"
    }
}

/**
 * 自动看广告主循环：截图 → OCR → 顶部直播间判定 → 按优先级匹配 → ROI 校验 → 排序取最优 → 点击 → 冷却。
 *
 * v2.0.0 行为约定：
 * - 连续 N 次未匹配到目标【只写一条日志提示】，绝不自动返回、不暂停、不关闭任何页面，循环继续跑；
 * - 日志预览按内容变化节流，空闲时自动放慢轮询，降低 CPU 与耗电；
 * - 帧缓冲由 ScreenCapture 复用，本类只负责归还，不再 recycle。
 */
class AutoRunner(
    private val capture: ScreenCapture,
    private val ocr: OcrEngine,
    private val dryRun: Boolean
) {

    private var missCount = 0
    private var missNoticed = false
    private var lastPreview = ""
    private var sameScreenRounds = 0
    private val maxRounds = if (dryRun) AutoConfig.simulateRounds else Int.MAX_VALUE
    private val startAt = System.currentTimeMillis()

    suspend fun run() {
        LogBus.ok(
            if (dryRun) "开始模拟：只识别不点击，最多 ${AutoConfig.simulateRounds} 轮"
            else "开始运行：目标 ${AutoConfig.targets.joinToString(" / ")}"
        )
        LogBus.info("点击通道：${Clicker.channelName()}｜轮询 ${AutoConfig.pollIntervalMs}ms")

        while (currentCoroutineContext().isActive) {
            if (dryRun && (RunnerState.rounds >= maxRounds ||
                        System.currentTimeMillis() - startAt > AutoConfig.simulateTimeoutMs)
            ) {
                LogBus.ok("模拟结束：共 ${RunnerState.rounds} 轮，识别到候选 ${RunnerState.clicks} 次")
                break
            }
            // 用户主动暂停（悬浮条/主界面）时才等待
            while (RunnerState.paused && currentCoroutineContext().isActive) delay(300)
            if (!currentCoroutineContext().isActive) break

            val frame = capture.grab()
            if (frame == null) {
                LogBus.warn("截图失败，检查屏幕录制授权后重试")
                delay(1500)
                continue
            }

            var consumed = false
            try {
                consumed = step(frame)
            } catch (t: Throwable) {
                LogBus.error("本轮异常：${t.message}")
            } finally {
                capture.releaseFrame(frame)
            }

            RunnerState.rounds++
            val wait = when {
                consumed -> 0L
                sameScreenRounds >= 3 -> AutoConfig.idlePollIntervalMs
                else -> AutoConfig.pollIntervalMs
            }
            if (wait > 0) delay(wait)
        }
    }

    /** 返回 true 表示本轮已处理（并自带等待），false 表示需要外部等待 */
    private suspend fun step(frame: Bitmap): Boolean {
        val items = ocr.recognize(frame)
        if (items.isEmpty()) {
            onMiss("OCR 无结果")
            return false
        }

        // 识别预览只在内容变化时输出，避免刷屏与字符串分配
        val preview = items.take(10)
            .joinToString(" | ") { "${it.text}@(${it.cx.toInt()},${it.cy.toInt()})" }
        if (preview != lastPreview) {
            lastPreview = preview
            sameScreenRounds = 0
            LogBus.info("· 识别: $preview")
        } else {
            sameScreenRounds++
        }

        // 1. 直播间误入判定
        val live = items.any { TextMatch.isLiveTop(it.text, it.cy, frame.height) }
        if (live) {
            LogBus.warn("⚡ 检测到顶部[关注/更多直播]，退直播间")
            if (!dryRun) Clicker.back()
            RunnerState.lastHit = "退直播间"
            delay(1500)
            return true
        }

        // 2. 按优先级匹配目标
        for (target in AutoConfig.targets) {
            val roi = AutoConfig.roiFor(target)
            val candidates = items.filter { it.text.let { t -> TextMatch.match(t, target) } }
                .filter {
                    it.cx >= roi.x0 * frame.width && it.cx <= roi.x1 * frame.width &&
                        it.cy >= roi.y0 * frame.height && it.cy <= roi.y1 * frame.height
                }

            if (candidates.isEmpty()) continue

            val sorted = if (target in AutoConfig.centerTargets) {
                val midX = frame.width * 0.5
                val midY = frame.height * 0.5
                candidates.sortedWith(
                    compareByDescending<OcrItem> { it.score }
                        .thenBy { (it.cx - midX).pow(2) + (it.cy - midY).pow(2) }
                )
            } else {
                candidates.sortedWith(
                    compareByDescending<OcrItem> { it.score }
                        .thenBy { it.cy }
                        .thenByDescending { it.cx }
                )
            }

            val best = sorted.first()
            // 小图坐标 → 真实屏幕坐标
            val x = (best.cx * capture.scaleX).toInt()
            val y = (best.cy * capture.scaleY).toInt()

            if (dryRun) {
                RunnerState.clicks++
                RunnerState.lastHit = target
                LogBus.ok(
                    "模拟命中「$target」←「${best.text}」 @($x,$y) 候选 ${sorted.size}（未点击）"
                )
                delay(400)
                return false
            }

            LogBus.add(
                "—— 点击「$target」←「${best.text}」 置信度 ${"%.2f".format(best.score)}",
                LogBus.LEVEL_OK
            )
            val done = Clicker.tap(x, y)
            if (!done) {
                LogBus.error("点击未生效：${Clicker.lastError()}")
                delay(600)
                return true
            }

            RunnerState.clicks++
            RunnerState.lastHit = target
            missCount = 0
            missNoticed = false

            if (target in AutoConfig.noCooldownTargets) {
                delay(AutoConfig.clickCooldownMs / 2)
                return true
            }
            delay(AutoConfig.clickCooldownMs)
            return true
        }

        onMiss("未找到目标 ${AutoConfig.targets.joinToString(" / ")}")
        return false
    }

    /**
     * 连续未命中的处理：只提示一次，随后清零继续。
     * 不返回、不暂停、不关闭任何页面——避免把用户自己的界面顶掉。
     */
    private fun onMiss(reason: String) {
        missCount++
        if (missCount >= AutoConfig.maxMisses) {
            if (!missNoticed) {
                LogBus.warn(
                    "连续 ${AutoConfig.maxMisses} 次未识别到目标（$reason），继续保持在当前页面轮询，不会退出"
                )
                missNoticed = true
            }
            missCount = 0
        }
    }
}
