package com.shangyin.auto.core

import android.content.Context
import android.content.Intent
import com.shangyin.auto.util.LogBus
import com.shangyin.auto.util.ShizukuUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/**
 * v3.9.3 新增：一键顺序启动。
 *
 * 按用户 5 张截图圈注的顺序依次把各音乐 App 拉到前台、点开看广告任务入口，然后交给该平台的
 * Runner 接管；单平台到时限或被跳过时自动切下一个，任何一步失败只跳过并在日志标注，不中断整条链路。
 *
 * 顺序与入口依据截图圈注：
 * 1) 汽水音乐：底部「福利」/「去签到」→「免费听」任务页
 * 2) QQ音乐：底部「免」图标 → 免费听歌任务页
 * 3) 酷狗音乐：底部「免」图标 → 看广告续时长页
 * 4) 网易云音乐：底部「首页」→「免费听」/「领30分钟」入口
 */
class SequenceRunner(
    private val context: Context,
    private val capture: ScreenCapture,
    private val ocr: OcrEngine,
    private val dry: Boolean
) {

    companion object {
        /** 顺序：与用户截图圈注顺序一致（汽水音乐 → QQ音乐 → 酷狗音乐 → 网易云音乐） */
        val ORDER: List<Platform> = listOf(
            Platform.QISHUI,
            Platform.QQ,
            Platform.KUGOU,
            Platform.CLOUD
        )

        /** 底部功能 tab 的圈注入口（截图里圈出的「福利」「免」等） */
        private val TAB_KEYS: Map<Platform, List<String>> = mapOf(
            Platform.QISHUI to listOf("福利", "去签到", "免"),
            Platform.QQ to listOf("免", "免费", "会员"),
            Platform.KUGOU to listOf("免", "免费", "会员"),
            Platform.CLOUD to listOf("免费", "会员")
        )

        /** 页面内的看广告任务入口文案 */
        private val PAGE_KEYS: Map<Platform, List<String>> = mapOf(
            Platform.QISHUI to listOf("免费听", "去签到", "立即领取", "看视频"),
            Platform.QQ to listOf("免费听歌", "看广告", "看视频", "去浏览"),
            Platform.KUGOU to listOf("看广告", "免费听", "看视频", "去浏览"),
            Platform.CLOUD to listOf("免费听", "领30分钟", "看广告", "去观看")
        )

        /** 底部 tab 判定：命中文本位于屏幕下方该比例以下，且长度很短 */
        private const val TAB_MIN_Y = 0.80f
        private const val TAB_MAX_CHARS = 6

        /** 找不到入口按钮时的重试次数与间隔 */
        private const val ENTRY_TRIES = 4
        private const val ENTRY_RETRY_MS = 2500L

        /** 点进入口后等待任务页渲染 */
        private const val ENTRY_SETTLE_MS = 1800L
    }

    /** 顺序启动主流程 */
    suspend fun run() {
        LogBus.ok("一键顺序启动：${ORDER.joinToString(" → ") { it.label }}")
        ORDER.forEachIndexed { index, target ->
            if (!currentCoroutineContext().isActive) return
            val tag = "${index + 1}/${ORDER.size}"
            RunnerState.platform = target

            LogBus.info("【$tag】拉起 ${target.label}（${target.pkg}）")
            if (!launch(target)) {
                LogBus.warn("【$tag】${target.label} 拉起失败（未安装或启动被拦截），跳过该平台")
                return@forEachIndexed
            }
            delay(AutoConfig.seqLaunchWaitMs)

            val fg = ForegroundApp.current()
            if (fg != null && fg != target.pkg) {
                LogBus.warn("【$tag】前台仍为 $fg，${target.label} 未到前台，跳过该平台")
                return@forEachIndexed
            }

            if (!openEntry(target, tag)) {
                LogBus.warn("【$tag】未定位到 ${target.label} 看广告入口，交由 Runner 自行寻找")
            }
            delay(ENTRY_SETTLE_MS)

            if (dry) {
                LogBus.info("【$tag】模拟模式：只识别不点击，跳过 ${target.label} 实际运行")
                return@forEachIndexed
            }

            RunnerState.reset(RunnerState.Mode.RUNNING, target)
            LogBus.ok("【$tag】${target.label} 开始自动看广告（单平台上限 ${AutoConfig.seqPerPlatformMs / 60_000} 分钟）")
            val finished = withTimeoutOrNull(AutoConfig.seqPerPlatformMs) {
                runOne(target)
            }
            if (finished == null) {
                LogBus.ok("【$tag】${target.label} 到达单平台上限，切换到下一个平台")
            } else {
                LogBus.info("【$tag】${target.label} 自动流程已结束")
            }
            delay(AutoConfig.seqSwitchDelayMs)
        }
        LogBus.ok("一键顺序启动流程结束")
    }

    /** 交给对应平台 Runner 接管 */
    private suspend fun runOne(target: Platform) {
        when (target) {
            Platform.QQ -> QqRunner(context, capture, ocr, false).run()
            Platform.KUGOU -> KugouRunner(context, capture, ocr, false).run()
            Platform.CLOUD -> CloudRunner(context, capture, ocr, false).run()
            else -> AutoRunner(capture, ocr, false).run()
        }
    }

    /** 按包名拉起 App 主 Activity：Shizuku 可用走 monkey，否则走包管理器启动入口 Activity */
    private fun launch(target: Platform): Boolean {
        if (Clicker.channel() == Clicker.Channel.SHIZUKU) {
            if (ShizukuUtil.exec("monkey -p ${target.pkg} -c android.intent.category.LAUNCHER 1", 6000)) {
                return true
            }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(target.pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * 直达看广告任务入口：先找底部圈注 tab（「福利」/「免」），再找页面内任务按钮，
     * 命中即点击；多次未命中返回 false 由 Runner 兜底。
     */
    private suspend fun openEntry(target: Platform, tag: String): Boolean {
        repeat(ENTRY_TRIES) { attempt ->
            val frame = capture.grab() ?: return false
            val h = frame.height.toFloat()
            val items = try {
                ocr.recognize(frame)
            } catch (t: Throwable) {
                emptyList()
            } finally {
                capture.releaseFrame(frame)
            }
            if (items.isEmpty()) {
                LogBus.info("【$tag】第 ${attempt + 1} 次识别无结果，稍后重试")
                delay(ENTRY_RETRY_MS)
                return@repeat
            }

            val tab = items.firstOrNull { item ->
                item.cy >= h * TAB_MIN_Y &&
                    TextMatch.normalize(item.text).length <= TAB_MAX_CHARS &&
                    TAB_KEYS[target].orEmpty().any { TextMatch.match(item.text, it) }
            }
            val page = items
                .filter { item -> PAGE_KEYS[target].orEmpty().any { TextMatch.match(item.text, it) } }
                .minByOrNull { it.cy }
            val hit = tab ?: page

            if (hit != null) {
                val x = hit.cx.toInt()
                val y = hit.cy.toInt()
                if (dry) {
                    LogBus.ok("【$tag】模拟命中入口「${hit.text}」@($x,$y)")
                    return true
                }
                val ok = Clicker.tap(x, y)
                LogBus.ok("【$tag】点击入口「${hit.text}」@($x,$y) → ${if (ok) "成功" else "失败"}")
                if (ok) return true
            } else {
                LogBus.info("【$tag】第 ${attempt + 1} 次未找到看广告入口，稍后重试")
            }
            delay(ENTRY_RETRY_MS)
        }
        return false
    }
}
