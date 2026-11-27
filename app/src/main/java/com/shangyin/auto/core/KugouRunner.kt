package com.shangyin.auto.core

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.shangyin.auto.a11y.A11yNode
import com.shangyin.auto.util.LogBus
import com.shangyin.auto.util.ShizukuUtil
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.pow

/** v3.8.0：无障碍节点点击的落点容差（占采集画面高度比例），超出即视为非同一控件 */
private const val KUGOU_A11Y_TOLERANCE_RATIO = 0.07f

/**
 * 酷狗音乐「看广告免费听歌」自动流程，移植自 kugou-auto/kugou_auto.py 的多阶段状态机：
 *
 * IDLE（任务列表 / 广告页）→ 点「点击跳转后浏览N秒」→ JUMP_WAIT（等第三方）→
 * IN_THIRD（第三方停留计时）→ RETURNING（按返回回主 App）→ SUCCESS_WAIT（等「恭喜获得」）→
 * DONE_PAGE（关掉已获得页回任务列表）。
 *
 * 跳转/返回判定以「前台包名是否仍为 酷狗音乐」为准，不硬编码第三方包名，兼容各家广告落地页。
 */
class KugouRunner(
    private val context: Context,
    private val capture: ScreenCapture,
    private val ocr: OcrEngine,
    private val dryRun: Boolean
) {

    private enum class State { IDLE, JUMP_WAIT, IN_THIRD, RETURNING, SUCCESS_WAIT, DONE_PAGE }

    private var state = State.IDLE
    private var successCount = 0
    private var failStreak = 0
    private var failTotal = 0
    private var missCount = 0
    private var taskSwipes = 0
    private var doneCloseTries = 0
    private var glyphTries = 0
    private var adPageRounds = 0

    private var jumpTs = 0L
    /** 刚点了任务列表「加时长」/「去浏览」这类直跳型入口，短时间内前台离开 酷狗音乐即视为跳转成功 */
    private var taskJumpPending = false
    private var taskJumpTs = 0L
    private var thirdEnterTs = 0L
    private var returnTs = 0L
    private var returnTries = 0
    private var succWaitTs = 0L
    private var lastSuccessTs = 0L
    private var dwellPlanSec = KugouConfig.dwellSeconds

    private var lastPreview = ""
    private var sameScreenRounds = 0
    private val lastClickAt = HashMap<String, Long>()

    /** 额度耗尽终止态：一旦置位，本轮不再执行任何点击，主循环直接收尾退出 */
    private var quotaStopped = false

    /** 额度耗尽弹窗存在但「取消」未定位到的连续轮次，达到上限后按终止态处理，避免空转卡死 */
    private var quotaCancelMiss = 0

    // ---------------- v3.6.0：前置开关校验状态 ----------------

    /** 已确认「自动看广告续时长」开关处于开启状态：确认后不再点击，避免反复切换 */
    private var switchOnConfirmed = false

    /** 已点击开关的次数，达到 [KugouConfig.switchMaxTries] 即放弃点击 */
    private var switchTries = 0

    /** 刚点过开关，等下一张新截图做二次确认（不复用点击前的旧帧） */
    private var switchPendingConfirm = false

    /** 上次开关校验时间，配合 [KugouConfig.switchRecheckMs] 做复查冷却 */
    private var switchCheckedAt = 0L

    /** 开关状态不可靠判定或已达尝试上限：本轮彻底放弃点击开关 */
    private var switchGaveUp = false

    /** 奖励浮层底部按钮在点击冷却表里的键名 */
    private val rewardBrowseKey = "点击浏览"

    /** 奖励浮层存在但底部「点击浏览」连续未识别的轮次，超过上限后放行常规流程 */
    private var rewardPanelMiss = 0

    private val maxRounds = if (dryRun) KugouConfig.simulateRounds else Int.MAX_VALUE
    private val startAt = System.currentTimeMillis()

    suspend fun run() {
        LogBus.ok(
            if (dryRun) "开始模拟（酷狗音乐）：只识别不点击，最多 ${KugouConfig.simulateRounds} 轮"
            else "开始运行（酷狗音乐）：目标 ${KugouConfig.rules.filter { it.enabled }.joinToString(" / ") { it.name }}"
        )
        LogBus.info("点击通道：${Clicker.channelName()}｜前台包名识别：${if (ForegroundApp.available()) "可用" else "受限"}")
        if (!dryRun && Clicker.channel() == Clicker.Channel.A11Y) {
            LogBus.warn("当前走无障碍通道，无法读取前台包名，跳转后停留时间将按默认 ${KugouConfig.dwellSeconds}s 计时")
        }

        while (currentCoroutineContext().isActive) {
            if (dryRun && (RunnerState.rounds >= maxRounds ||
                        System.currentTimeMillis() - startAt > KugouConfig.simulateTimeoutMs)
            ) {
                LogBus.ok("模拟结束：共 ${RunnerState.rounds} 轮，识别到候选 ${RunnerState.clicks} 次")
                break
            }
            while (RunnerState.paused && currentCoroutineContext().isActive) delay(300)
            if (!currentCoroutineContext().isActive) break

            val frame = capture.grab()
            if (frame == null) {
                LogBus.warn("截图失败，检查屏幕录制授权后重试")
                delay(1500)
                continue
            }

            var wait = KugouConfig.pollIntervalMs
            try {
                wait = step(frame)
            } catch (t: Throwable) {
                LogBus.error("本轮异常：${t.message}")
            } finally {
                capture.releaseFrame(frame)
            }

            RunnerState.rounds++
            if (quotaStopped) {
                LogBus.warn("额度耗尽终止态：主循环已安全退出，本轮不再做任何操作")
                break
            }
            if (wait > 0) delay(wait)
        }
        val tail = if (quotaStopped) KugouConfig.QUOTA_STOP_TEXT else "正常结束"
        LogBus.info("酷狗音乐任务已结束：成功 $successCount 条，失败 $failTotal 次（$tail）")
    }

    // ------------------------------------------------------------------
    // 单轮状态机
    // ------------------------------------------------------------------
    private suspend fun step(frame: Bitmap): Long {
        val items = ocr.recognize(frame)
        val w = frame.width
        val h = frame.height
        val now = System.currentTimeMillis()

        val fg = ForegroundApp.current()
        val inSys = fg != null && KugouConfig.SYS_DIALOG_PKGS.contains(fg)
        val inQq = fg == null || fg == KugouConfig.PKG

        logPreview(items)

        // v3.2.0 额度耗尽弹窗：全流程最高优先级拦截。
        // 该弹窗盖在任务列表页上，旧版本会继续按任务列表页找「加时长/去浏览」，
        // 点不动又反复兜底，最终卡死；更危险的是绿色付费按钮落在「免费畅听」规则 ROI 内。
        // 因此：识别到弹窗后除「取消」外禁止任何点击，点完「取消」即安全终止本轮。
        if (!quotaStopped) {
            val quota = KugouText.quotaHit(items, w, h)
            if (quota != null) return quotaStop(items, w, h, quota)
        }

        // 系统跳转/权限弹窗：最高优先级，点「允许」放行进第三方
        if (inSys && KugouConfig.autoAllowJump && state != State.IN_THIRD) {
            val allow = findAllow(items, w, h)
            if (allow != null) {
                LogBus.ok("系统弹窗：点击「${allow.text}」放行跳转")
                tapAt(allow.cx, allow.cy, "系统允许")
                return 1500
            }
        }

        return when (state) {
            State.IN_THIRD -> inThird(now, inQq)
            State.RETURNING -> returning(now, inQq)
            State.JUMP_WAIT -> jumpWait(now, inQq, inSys, fg, items)
            State.SUCCESS_WAIT -> successWait(now, inQq, inSys, items, w, h)
            State.DONE_PAGE -> donePage(now, inQq, inSys, items, w, h)
            State.IDLE -> idle(frame, items, now, w, h, inQq, inSys)
        }
    }

    /** 第三方停留计时：到点按包名直接拉起 酷狗音乐 主 Activity（v3.9.3 起不再按返回键） */
    private suspend fun inThird(now: Long, inQq: Boolean): Long {
        val elapsed = (now - thirdEnterTs) / 1000f
        if (inQq && elapsed > 2f) {
            LogBus.info("已回到 酷狗音乐，等待到账提示")
            state = State.SUCCESS_WAIT
            succWaitTs = now
            return 600
        }
        if (elapsed >= dwellPlanSec) {
            LogBus.ok("第三方已停留 ${"%.0f".format(elapsed)}s（计划 ${dwellPlanSec}s），按包名拉回酷狗音乐")
            launchApp()
            state = State.RETURNING
            returnTs = now
            returnTries = 0
            return KugouConfig.bringBackWaitMs
        }
        return 800
    }

    /** 等酷狗音乐回到前台；未回来时再次按包名拉起，连续失败才记一次失败 */
    private suspend fun returning(now: Long, inQq: Boolean): Long {
        if (inQq) {
            LogBus.ok("已回到 酷狗音乐，等待「恭喜获得」")
            state = State.SUCCESS_WAIT
            succWaitTs = now
            return 600
        }
        if (now - returnTs >= KugouConfig.bringBackWaitMs) {
            if (returnTries < KugouConfig.bringBackRetry) {
                returnTries++
                LogBus.warn("酷狗音乐未回到前台，再次按包名拉起（第 $returnTries/${KugouConfig.bringBackRetry} 次）")
                launchApp()
                returnTs = now
                return 1200
            }
            LogBus.warn("多次拉起酷狗音乐仍无效，记一次失败并回到任务页")
            state = State.IDLE
            returnTries = 0
            fail()
            return 3000
        }
        return 800
    }

    /** 已点跳转，等前台切成第三方 */
    private suspend fun jumpWait(now: Long, inQq: Boolean, inSys: Boolean, fg: String?, items: List<OcrItem>): Long {
        if (!inQq && !inSys) {
            LogBus.ok("已跳到第三方 ${fg}，开始 ${dwellPlanSec}s 停留计时")
            state = State.IN_THIRD
            thirdEnterTs = now
            return 600
        }
        if (now - jumpTs >= KugouConfig.jumpWaitMs) {
            LogBus.warn("点跳转后 ${KugouConfig.jumpWaitMs / 1000}s 前台仍为 ${fg ?: "未知"}，未发生跳转")
            failStreak++
            failTotal++
            state = State.IDLE
            // 同一条广告连续点不出跳转时先退出该广告页，下一轮换一条任务
            if (failStreak >= 2) {
                if (!tryCloseAd(items)) back("离开广告页")
            }
            applyBackoff()
            return KugouConfig.backWaitMs
        }
        return 800
    }

    /**
     * 已回主 App，等奖励到账提示。
     * v3.6.0：若奖励浮层已出现（含「再看1个广告，送惊喜奖励」面板），说明本条广告已看完，
     * 不再死等「恭喜获得」超时记失败，直接转 IDLE 处理浮层底部「点击浏览」，继续下一条。
     */
    private suspend fun successWait(
        now: Long,
        inQq: Boolean,
        inSys: Boolean,
        items: List<OcrItem>,
        w: Int,
        h: Int
    ): Long {
        if (!inQq && !inSys) {
            LogBus.info("用户切换到其他应用，本轮等待取消")
            state = State.IDLE
            return 500
        }
        if (KugouText.rewardPanelHit(items, w, h) != null) {
            LogBus.ok("检测到奖励浮层，本条广告已完成，转入浮层底部「点击浏览」处理")
            state = State.IDLE
            missCount = 0
            taskSwipes = 0
            return 600
        }
        val waited = now - succWaitTs
        if (waited >= KugouConfig.successWaitMs) {
            LogBus.warn("回主 App 已 ${waited / 1000}s 仍未出现「恭喜获得」，记为失败")
            state = State.IDLE
            failStreak++
            failTotal++
            applyBackoff()
            return KugouConfig.pollIntervalMs
        }
        return KugouConfig.pollIntervalMs
    }

    /** 已获得页 / 奖励浮层页：优先点浮层底部「点击浏览」继续，其次关闭广告页回任务列表 */
    private suspend fun donePage(
        now: Long,
        inQq: Boolean,
        inSys: Boolean,
        items: List<OcrItem>,
        w: Int,
        h: Int
    ): Long {
        if (!inQq && !inSys) {
            state = State.IDLE
            doneCloseTries = 0
            return 500
        }
        // v3.6.0：该页命中 taskListKeys 时 tryCloseAd 会拒绝关闭，必须走底部按钮，否则会永久停住
        val browse = KugouText.rewardBrowseHit(items, w, h)
        if (browse != null && clickRewardBrowse(browse, w, h, now)) {
            return KugouConfig.clickCooldownMs
        }
        if (doneCloseTries >= KugouConfig.doneCloseTries) {
            LogBus.info("已获得页关闭 $doneCloseTries 次无效，改按返回键")
            back("返回任务列表")
            doneCloseTries = 0
            state = State.IDLE
            return KugouConfig.backWaitMs
        }
        if (tryCloseAd(items)) {
            doneCloseTries++
            LogBus.info("关闭已获得页（第 $doneCloseTries 次）")
            return 1500
        }
        if (KugouText.successHit(items) == null) {
            LogBus.info("「已获得」横幅已消失，视为回到任务列表，继续下一条")
            state = State.IDLE
            doneCloseTries = 0
            missCount = 0
        }
        return KugouConfig.pollIntervalMs
    }

    /** 主工作态：找广告入口 / 点跳转 / 关广告页 / 任务列表滑动 */
    private suspend fun idle(
        frame: Bitmap,
        items: List<OcrItem>,
        now: Long,
        w: Int,
        h: Int,
        inQq: Boolean,
        inSys: Boolean
    ): Long {
        // A0) 刚点过任务列表的「加时长」：若前台已离开 酷狗音乐，说明入口是直跳型（中间没有
        //     「点击跳转后浏览N秒」过渡页），直接进入第三方停留计时，否则会卡在 IDLE 乱点
        if (taskJumpPending && !inQq && !inSys && now - taskJumpTs <= KugouConfig.jumpWaitMs) {
            val need = KugouText.parseDwellSeconds(items)
            if (need > 0) {
                dwellPlanSec = (need + KugouConfig.dwellExtra).coerceIn(KugouConfig.dwellMin, KugouConfig.dwellMax)
            }
            LogBus.ok("点击「加时长」已直跳第三方，开始 ${"%.0f".format(dwellPlanSec)}s 停留计时")
            taskJumpPending = false
            state = State.IN_THIRD
            thirdEnterTs = now
            return 600
        }
        if (taskJumpPending && now - taskJumpTs > KugouConfig.jumpWaitMs) taskJumpPending = false

        // A1) v3.6.0 「免费听歌模式」页弹出的奖励浮层：
        //     看完第一条广告回来后，酷狗会弹出奖励面板（「广告奖励待领取 / 再看1个广告，送惊喜奖励」），
        //     要求点面板最底部的「点击浏览5秒，送惊喜奖励」才继续。旧版本这里只找关闭按钮，
        //     而该面板文字命中 taskListKeys（含「点击浏览」），tryCloseAd 为保护免费听歌页拒绝关闭，
        //     结果就永久停在奖励界面不动。现在识别到面板即点最底部按钮，点完按直跳型入口继续状态机。
        if (inQq) {
            val panel = KugouText.rewardPanelHit(items, w, h)
            if (panel != null) {
                val got = KugouText.successHit(items)
                if (got != null && now - lastSuccessTs > KugouConfig.successDedupMs) {
                    lastSuccessTs = now
                    successCount++
                    failStreak = 0
                    LogBus.ok("🎉 成功第 $successCount 条：检测到「${got.text}」")
                }
                val browse = KugouText.rewardBrowseHit(items, w, h)
                if (browse != null) {
                    rewardPanelMiss = 0
                    if (clickRewardBrowse(browse, w, h, now)) return KugouConfig.clickCooldownMs
                    // 按钮点击冷却中：面板还在，原地等待，绝不往别处乱点
                    return KugouConfig.pollIntervalMs
                }
                // 浮层在但按钮这帧没认出来（OCR 漏行）：原地多等几轮，避免退回上一页或误点别的按钮
                rewardPanelMiss++
                if (rewardPanelMiss <= KugouConfig.rewardPanelMissLimit) {
                    LogBus.info(
                        "奖励浮层已出现（特征「${panel.text}」），底部「点击浏览」暂未识别，第 $rewardPanelMiss 次重试"
                    )
                    return KugouConfig.pollIntervalMs
                }
                LogBus.warn("奖励浮层连续 $rewardPanelMiss 轮未识别到底部「点击浏览」，放行常规流程处理")
            }
        }

        // A0.5) v3.6.0 前置开关校验：任务页顶部「自动看广告续时长」必须为开启态。
        //       放在所有任务点击之前：开关没开时任务行点了也不给时长，先确认开关。
        //       有奖励浮层 / 到账提示 / 付费墙弹窗时跳过，避免把浮层文字误当任务页。
        if (inQq && !inSys && !switchGaveUp &&
            (switchPendingConfirm || (!switchOnConfirmed && now - switchCheckedAt >= KugouConfig.switchRecheckMs))
        ) {
            val blocked = KugouText.rewardPanelHit(items, w, h) != null ||
                KugouText.successHit(items) != null ||
                KugouText.quotaHit(items, w, h) != null
            if (!blocked && KugouText.switchLabelHit(items, w, h) != null) {
                val handled = ensureSwitchOn(frame, w, h, now)
                if (handled > 0) return handled
            }
        }

        // A) 奖励到账提示：先于任何点击判定，避免又点一次跳转
        val succ = KugouText.successHit(items)
        if (succ != null && now - lastSuccessTs > KugouConfig.successDedupMs) {
            lastSuccessTs = now
            successCount++
            failStreak = 0
            LogBus.ok("🎉 成功第 $successCount 条：检测到「${succ.text}」")
            state = State.DONE_PAGE
            doneCloseTries = 0
            missCount = 0
            taskSwipes = 0
            adPageRounds = 0
            return 1000
        }

        // A2) 「关闭免费听歌页」确认弹窗兜底：出现「继续免费听」立即点回，绝不点「确认关闭」，
        //     避免误关页面后剩余免费畅听时长被清零
        if (inQq) {
            val keep = KugouConfig.keepListeningTargets.asSequence()
                .mapNotNull { t -> items.firstOrNull { KugouText.match(it.text, t) && KugouConfig.DIALOG.contains(it.cx, it.cy, w, h) } }
                .firstOrNull()
            if (keep != null) {
                LogBus.warn("检测到「关闭后时长将清零」确认弹窗，点击「${keep.text}」保住剩余免费畅听时长")
                tapAt(keep.cx, keep.cy, "继续免费听")
                missCount = 0
                return 1500
            }
        }

        // B) 直播间误入：秒退
        val live = items.firstOrNull { KugouText.isLiveTop(it.text, it.cy, h) }
        if (live != null) {
            LogBus.warn("⚡ 检测到顶部直播特征「${live.text}」，立即返回退出")
            back("退直播间")
            missCount = 0
            return 1500
        }

        // C) 广告页 CTA：点「点击跳转后浏览N秒」进第三方（只在 IDLE 点，避免重复触发跳转）
        val cta = findJumpCta(items, w, h)
        if (cta != null && inQq) {
            val parsed = KugouText.parseDwellSeconds(items)
            val need = maxOf(parsed, KugouConfig.dwellSeconds)
            dwellPlanSec = (need + KugouConfig.dwellExtra).coerceIn(KugouConfig.dwellMin, KugouConfig.dwellMax)
            LogBus.ok(
                "⚡ 广告页跳转「${cta.text}」→ 计划第三方停留 ${dwellPlanSec}s" +
                    "（页面要求 ${if (parsed > 0) "${parsed}s" else "未识别"}，默认 ${KugouConfig.dwellSeconds}s 取大 + 缓冲 ${KugouConfig.dwellExtra}s）"
            )
            tapAt(cta.cx, cta.cy, "跳转广告")
            jumpTs = now
            state = State.JUMP_WAIT
            missCount = 0
            adPageRounds = 0
            return 2000
        }

        // D) 规则表匹配点击（广告入口 / 领奖 / 跳过 / 关闭）
        val clicked = findAndClick(items, w, h, now)
        if (clicked != null) {
            missCount = 0
            taskSwipes = 0
            adPageRounds = 0
            glyphTries = 0
            LogBus.info("已点击「$clicked」，等待页面响应")
            // 任务列表直跳型入口：点完若直接切到第三方广告页，下一轮由 A0 接管进入停留计时
            if (clicked in KugouConfig.directJumpTargets) {
                taskJumpPending = true
                taskJumpTs = now
            }
            return if (clicked == "领取" || clicked == "立即领") KugouConfig.clickCooldownMs / 2 else KugouConfig.clickCooldownMs
        }

        // E) 广告页兜底：文字目标全未命中时，按比例点右上角「×」
        //    任务列表页严禁兜底：该页右上角是页面自身关闭钮，点中会触发「时长清零」确认弹窗
        val strength = KugouText.adStrength(items)
        val onTaskList = KugouText.isTaskListPage(items)
        if (strength > 0 && !onTaskList) {
            if (strength >= 2) adPageRounds++
            if (strength >= 2 && adPageRounds >= KugouConfig.glyphFallbackRounds) {
                if (glyphTries >= 2) {
                    LogBus.warn("图形×已连点 $glyphTries 次仍无效，改用返回键退出")
                    back("离开广告页")
                    glyphTries = 0
                    adPageRounds = 0
                    missCount = 0
                    return 2000
                }
                if (tapCloseGlyph(frame, items)) {
                    glyphTries++
                    adPageRounds = 0
                    missCount = 0
                    LogBus.info("广告页无文字目标，已点右上角「×」兜底（第 $glyphTries 次）")
                    return 2000
                }
            }
        } else {
            adPageRounds = 0
            glyphTries = 0
        }

        // F) 任务列表页暂无可点任务：上滑找下一条（禁止按返回，会退出任务页）
        val taskHit = if (strength == 0) KugouText.taskPageHit(items) else null
        if (taskHit != null && inQq && !inSys) {
            taskSwipes++
            if (taskSwipes > KugouConfig.taskSwipeMax) {
                LogBus.warn(
                    "任务列表已连续上滑 ${KugouConfig.taskSwipeMax} 次仍无可做任务（特征「$taskHit」），" +
                        "等 ${KugouConfig.taskIdleWaitMs / 1000}s 后重试（可能任务冷却中或今日已做完）"
                )
                taskSwipes = 0
                failStreak++
                applyBackoff()
                return KugouConfig.taskIdleWaitMs
            }
            LogBus.info("任务列表暂无可点任务（特征「$taskHit」），上滑找下一条（第 $taskSwipes/${KugouConfig.taskSwipeMax} 次）")
            swipeUp(frame)
            missCount = 0
            return KugouConfig.pollIntervalMs
        }

        // G) 未命中
        missCount++
        if (missCount >= KugouConfig.maxMissBeforeBack) {
            LogBus.warn("连续 $missCount 轮未匹配到目标，执行返回离开当前页")
            back("返回")
            missCount = 0
            return KugouConfig.backWaitMs
        }
        return if (sameScreenRounds >= 3) KugouConfig.idlePollIntervalMs else KugouConfig.pollIntervalMs
    }

    /**
     * v3.6.0：点击奖励浮层最底部的「点击浏览N秒，送惊喜奖励」按钮。
     *
     * 这是「看完第一条广告结束返回后停在奖励界面不动」的根因修复：旧版本只在该页找关闭按钮，
     * 而浮层文字命中 taskListKeys（含「点击浏览」），tryCloseAd 为保护免费听歌页拒绝关闭，
     * 于是永久停住。现在改为识别并点击底部按钮，点完按「直跳型入口」处理，
     * 由 IDLE 的 A0 / C 分支接管进入第三方停留计时，继续走五阶段状态机。
     *
     * 落点双向夹紧：水平锁在屏幕中段（避开左右两侧会员横幅「￥1立即开通」），
     * 垂直锁在按钮文字带内，确保不与页面底部会员横幅 / 右上角关闭位重叠。
     */
    private suspend fun clickRewardBrowse(browse: OcrItem, w: Int, h: Int, now: Long): Boolean {
        if (w <= 0 || h <= 0) return false
        val cd = lastClickAt[rewardBrowseKey] ?: 0L
        if (now - cd < KugouConfig.rewardBrowseCooldownMs) return false
        val bx = (browse.cx / w).coerceIn(KugouConfig.REWARD_BROWSE_CLAMP_X0, KugouConfig.REWARD_BROWSE_CLAMP_X1) * w
        val by = (browse.cy / h).coerceIn(KugouConfig.REWARD_BROWSE_CLAMP_Y0, KugouConfig.REWARD_BROWSE_CLAMP_Y1) * h
        LogBus.ok(
            "⚡ 奖励浮层底部「${browse.text}」→ 点击继续看下一条广告" +
                "（落点 ${(bx / w * 100).toInt()}%,${(by / h * 100).toInt()}%）"
        )
        val ok = tapAt(bx, by, rewardBrowseKey)
        lastClickAt[rewardBrowseKey] = now
        RunnerState.lastHit = rewardBrowseKey
        if (!ok) return false
        taskJumpPending = true
        taskJumpTs = now
        missCount = 0
        taskSwipes = 0
        doneCloseTries = 0
        adPageRounds = 0
        return true
    }

    /**
     * v3.6.0 前置开关校验：确保任务页顶部「自动看广告续时长」处于开启状态。
     *
     * 判定与保险策略：
     * 1) 开关状态靠像素彩色占比判定（OCR 只能读到标签文字，读不到开关形态）；
     * 2) 判定「已开启」→ 立即结束，不做任何多余点击；
     * 3) 判定「不确定」→ 也不点击，宁可跳过也不冒险把用户已开启的开关点成关闭；
     * 4) 判定「未开启」→ 点击开关轨迹中心，点完不复用旧帧，等下一轮新截图做二次确认；
     * 5) 二次确认仍未开启则重试，累计 [KugouConfig.switchMaxTries] 次后彻底停止点击，
     *    并在 [KugouConfig.switchRecheckMs] 冷却期内不再校验，避免来回切换打扰用户。
     *
     * 返回 >0 表示本轮已处理、需要等待的毫秒数；0 表示无需等待、继续后续判定。
     */
    private suspend fun ensureSwitchOn(frame: Bitmap, w: Int, h: Int, now: Long): Long {
        val ratio = KugouSwitch.colorRatio(frame, KugouConfig.SWITCH)
        val st = KugouSwitch.state(frame, KugouConfig.SWITCH)
        val pct = "%.2f".format(ratio)

        if (switchPendingConfirm) {
            // 二次确认：用点击之后的**新截图**复核，避免同一帧自证
            switchPendingConfirm = false
            switchCheckedAt = now
            if (st == KugouSwitch.ON) {
                switchOnConfirmed = true
                LogBus.ok("前置校验二次确认通过：「自动看广告续时长」已开启（彩色占比 $pct），继续执行任务")
                return 0
            }
            if (st == KugouSwitch.UNKNOWN) {
                switchGaveUp = true
                LogBus.warn("前置校验二次确认无法判定（彩色占比 $pct），为避免反复切换已停止点击开关，继续执行任务")
                return 0
            }
            if (switchTries >= KugouConfig.switchMaxTries) {
                switchGaveUp = true
                LogBus.warn(
                    "前置校验：已点击开关 $switchTries 次仍未确认开启（彩色占比 $pct），" +
                        "停止点击以免反复切换，继续执行任务"
                )
                return 0
            }
            LogBus.warn("前置校验二次确认未通过（彩色占比 $pct），再点一次开关")
        } else {
            switchCheckedAt = now
            when (st) {
                KugouSwitch.ON -> {
                    switchOnConfirmed = true
                    LogBus.ok("前置校验：「自动看广告续时长」开关已开启（彩色占比 $pct），不做多余点击")
                    return 0
                }
                KugouSwitch.UNKNOWN -> {
                    LogBus.info("前置校验：「自动看广告续时长」开关状态不确定（彩色占比 $pct），本轮不点击（避免误关）")
                    return 0
                }
                else -> {
                    if (switchTries >= KugouConfig.switchMaxTries) {
                        switchGaveUp = true
                        LogBus.warn("前置校验：已尝试 $switchTries 次，放弃点击开关，继续执行任务")
                        return 0
                    }
                }
            }
        }

        switchTries++
        val px = KugouConfig.SWITCH.cx * w
        val py = KugouConfig.SWITCH.cy * h
        LogBus.warn(
            "前置校验：「自动看广告续时长」开关${KugouSwitch.stateName(st)}（彩色占比 $pct），" +
                "点击开关开启（第 $switchTries/${KugouConfig.switchMaxTries} 次）" +
                "@(${(px / w * 100).toInt()}%,${(py / h * 100).toInt()}%)"
        )
        tapAt(px, py, "自动看广告开关")
        switchPendingConfirm = true
        switchCheckedAt = now
        return KugouConfig.clickCooldownMs
    }

    /**
     * v3.2.0 额度耗尽终止态处理：
     * 1) 只在弹窗取消热区里找「取消」，付费/开通会员按钮（含「元、会员、开通、畅听、VIP、￥」）
     *    在任何环节都不会被点击；
     * 2) 点「取消」关闭弹窗后立即置位终止标记，主循环不再执行任何点击，避免误关任务页；
     * 3) 日志 + 悬浮条状态位同步提示「今日免费额度已用完，已停止」，停留数秒后安全收尾退出。
     */
    private suspend fun quotaStop(
        items: List<OcrItem>,
        w: Int,
        h: Int,
        quota: OcrItem
    ): Long {
        val cancel = KugouText.quotaCancelHit(items, w, h)
        if (cancel == null) {
            quotaCancelMiss++
            if (quotaCancelMiss < KugouConfig.quotaCancelMissLimit) {
                LogBus.warn("检测到「${quota.text}」弹窗，但未定位到「取消」按钮：本轮不做任何点击，等待弹窗可点（第 $quotaCancelMiss 次）")
                return 1500
            }
            // 多次仍定位不到「取消」：不点任何按钮，直接进入终止态收尾，避免空转卡死
            quotaStopped = true
            taskJumpPending = false
            LogBus.error(KugouConfig.QUOTA_STOP_TEXT + "：弹窗存在但未识别到「取消」，已放弃点击并安全终止本轮")
            RunnerState.haltNotice = KugouConfig.QUOTA_STOP_TEXT
            RunnerState.message = KugouConfig.QUOTA_STOP_TEXT
            RunnerState.mode = RunnerState.Mode.IDLE
            delay(KugouConfig.quotaNoticeMs)
            return 0
        }
        quotaCancelMiss = 0

        LogBus.warn("检测到「${quota.text}」弹窗：严禁点击任何付费/开通会员按钮，仅点「${cancel.text}」关闭")
        tapAt(cancel.cx, cancel.cy, "关闭额度耗尽弹窗")
        lastClickAt["取消"] = System.currentTimeMillis()
        RunnerState.lastHit = "取消"

        quotaStopped = true
        missCount = 0
        taskJumpPending = false

        LogBus.error(KugouConfig.QUOTA_STOP_TEXT + "：已点「取消」关闭弹窗，本轮任务安全终止，不再执行任何点击")
        RunnerState.haltNotice = KugouConfig.QUOTA_STOP_TEXT
        RunnerState.message = KugouConfig.QUOTA_STOP_TEXT
        RunnerState.mode = RunnerState.Mode.IDLE

        // 保持提示在悬浮条上可见，随后由主循环安全退出（AutoService 收尾并释放采集）
        delay(KugouConfig.quotaNoticeMs)
        return 0
    }

    // ------------------------------------------------------------------
    // 匹配与点击
    // ------------------------------------------------------------------

    /** 按规则表顺序找可点目标，命中即点击并返回目标名 */
    private suspend fun findAndClick(items: List<OcrItem>, w: Int, h: Int, now: Long): String? {
        // 额度耗尽弹窗期间：除「取消」外一律不点（由 quotaStop 统一处理，这里只做兜底拦截）
        if (quotaStopped || KugouText.quotaHit(items, w, h) != null) return null
        // 任务列表页禁止关闭类目标：该页右上角是页面自身关闭钮，点中会弹
        // 「关闭后将失去免费畅听权限，剩余时长将清零」，把已攒时长清零
        val onTaskList = KugouText.isTaskListPage(items)
        for (rule in KugouConfig.rules) {
            if (!rule.enabled) continue
            if (onTaskList && rule.name in KugouConfig.closeRuleNames) {
                if (rule.name == "关闭" || rule.name == "关闭广告") {
                    LogBus.info("当前为任务列表页，跳过关闭类目标「${rule.name}」")
                }
                continue
            }
            val cd = lastClickAt[rule.name] ?: 0L
            if (now - cd < rule.cooldownMs) continue

            val cands = items.filter { it ->
                KugouText.match(it.text, rule.name) && rule.roi.contains(it.cx, it.cy, w, h) &&
                    // 付费/开通会员类按钮永不点击（含「元、会员、开通、畅听、VIP、￥」），
                    // 仅当识别文本与目标词完全一致时放行（如正常入口「免费畅听」）
                    !(KugouText.isPayDanger(it.text) && KugouText.norm(it.text) != KugouText.norm(rule.name)) &&
                    // 关闭类目标绝不接受「确认关闭」这类危险确认按钮
                    !(rule.name in KugouConfig.closeRuleNames &&
                        ("确认" in it.text || "清零" in it.text || "失去" in it.text))
            }
            if (cands.isEmpty()) continue

            val roiCx = rule.roi.cx * w
            val roiCy = rule.roi.cy * h
            val best = if (rule.corner) {
                cands.sortedWith(
                    compareByDescending<OcrItem> { it.score }
                        .thenByDescending { it.cx + (h - it.cy) }   // 偏右上
                ).first()
            } else {
                cands.sortedWith(
                    compareByDescending<OcrItem> { it.score }
                        .thenBy { (it.cx - roiCx).pow(2) + (it.cy - roiCy).pow(2) }
                ).first()
            }

            // 落点换算 + 点击前二次校验：普通目标绝不允许落入右上角关闭热区
            val x = safePointX(best, rule)
            if (!verifyPoint(x, best.cy, rule, w, h)) {
                LogBus.warn("命中「${rule.name}」但落点 (${(x / w * 100).toInt()}%,${(best.cy / h * 100).toInt()}%) 触及右上角关闭热区，已放弃本次点击")
                continue
            }
            tapAt(x, best.cy, rule.name)
            lastClickAt[rule.name] = now
            RunnerState.lastHit = rule.name
            return rule.name
        }
        return null
    }

    /**
     * 落点 X 坐标（采集帧像素）：
     * - 关闭类目标且文本带「×」（如「广告丨×」）时取框内右侧 × 字形位置，否则点框中心会落在「广告」二字上；
     * - 其余目标取文字框中心，并把落点水平限制在规则 ROI 内，避免贴近右侧关闭热区。
     */
    private fun safePointX(item: OcrItem, rule: KugouConfig.Rule, w: Int = 0): Float {
        if (rule.name in KugouConfig.closeRuleNames && KugouText.hasGlyphChar(item.text)) {
            return item.rightAnchor(0.80f)
        }
        if (w <= 0) return item.cx
        val left = rule.roi.x0 * w
        val right = rule.roi.x1 * w
        return item.cx.coerceIn(minOf(left, right), maxOf(left, right))
    }

    /** 点击前二次校验：普通目标落点不得进入右上角关闭热区，且必须仍在规则 ROI 内 */
    private fun verifyPoint(x: Float, y: Float, rule: KugouConfig.Rule, w: Int, h: Int): Boolean {
        if (w <= 0 || h <= 0) return true
        val rX = x / w
        val rY = y / h
        if (rule.name !in KugouConfig.closeRuleNames && KugouConfig.CLOSE_HOTSPOT.containsRatio(rX, rY)) return false
        return rule.roi.contains(x, y, w, h)
    }

    /** 广告页「点击跳转后浏览N秒」按钮 */
    private fun findJumpCta(items: List<OcrItem>, w: Int, h: Int): OcrItem? =
        items.filter { KugouText.jumpCtaHit(it.text) && KugouConfig.CTA_JUMP.contains(it.cx, it.cy, w, h) }
            .sortedByDescending { it.score }
            .firstOrNull()
            ?: items.filter { KugouText.jumpCtaHit(it.text) && KugouConfig.AD_CTA.contains(it.cx, it.cy, w, h) }
                .sortedByDescending { it.score }
                .firstOrNull()

    /** 系统弹窗「允许」按钮 */
    private fun findAllow(items: List<OcrItem>, w: Int, h: Int): OcrItem? {
        for (target in KugouConfig.allowTargets) {
            val hit = items.filter { KugouText.match(it.text, target) }
                .filter { KugouConfig.SYS_ALLOW.contains(it.cx, it.cy, w, h) }
                .sortedByDescending { it.score }
                .firstOrNull()
            if (hit != null) return hit
        }
        return null
    }

    /** 关闭广告页：先按规则表文字目标，再按比例点右上角；返回是否发生了点击 */
    private suspend fun tryCloseAd(items: List<OcrItem>, w: Int = 0, h: Int = 0): Boolean {
        // 额度耗尽弹窗：只能点「取消」，关闭类兜底一律禁用（右上角 × 可能是任务页关闭钮）
        if (quotaStopped || KugouText.quotaHit(items, w, h) != null) {
            LogBus.info("额度耗尽弹窗期间禁用关闭类兜底，仅允许点「取消」")
            return false
        }
        // 任务列表页不是广告页，绝不在这里点右上角：那是页面自身的关闭钮，
        // 点中会弹「关闭后将失去免费畅听权限，剩余时长将清零」
        if (KugouText.isTaskListPage(items)) {
            LogBus.info("任务列表页不做关闭类点击，跳过关闭动作")
            return false
        }
        // OCR 坐标是采集帧坐标，缺省时必须用 frameWidth/frameHeight，用真实屏幕尺寸会把 ROI 算偏
        val vw = if (w > 0) w else capture.frameWidth
        val vh = if (h > 0) h else capture.frameHeight
        val names = listOf("×", "关闭广告", "关闭", "知道了", "完成", "收下")
        for (name in names) {
            val rule = KugouConfig.rules.firstOrNull { it.name == name } ?: continue
            val hit = items.filter { KugouText.match(it.text, name) && rule.roi.contains(it.cx, it.cy, vw, vh) }
                .sortedByDescending { it.score }
                .firstOrNull()
            if (hit != null) {
                tapAt(safePointX(hit, rule, vw), hit.cy, "关闭")
                return true
            }
        }
        return if (vw > 0 && vh > 0) {
            val frame = capture.grab() ?: return false
            try {
                tapCloseGlyph(frame, items)
            } finally {
                capture.releaseFrame(frame)
            }
        } else false
    }

    /**
     * 关闭钮兜底：
     * 1) 优先用 OCR 识别到的「×」条目（原始文本带 × 且位于顶部），点击点取框内右侧 × 字形，
     *    避免「广告丨×」这种宽框点中心落在「广告」二字上导致无效点击；
     * 2) 无识别结果时才退回固定比例（0.92, 0.093）；
     * 3) 任务列表页直接拒绝，防止误关闭「免费听歌」页。
     */
    private suspend fun tapCloseGlyph(frame: Bitmap, items: List<OcrItem> = emptyList()): Boolean {
        if (KugouText.isTaskListPage(items)) {
            LogBus.warn("任务列表页禁止图形「×」兜底（右上角为页面关闭钮，会清零免费畅听时长）")
            return false
        }
        val glyph = KugouText.closeGlyphHit(items, frame.height)
        val x = glyph?.rightAnchor(0.85f) ?: (frame.width * 0.92f)
        val y = glyph?.cy ?: (frame.height * 0.093f)
        if (glyph != null) {
            LogBus.info("按 OCR 定位关闭×：文字框右端 (${(x / frame.width * 100).toInt()}%,${(y / frame.height * 100).toInt()}%)")
        }
        return tapAt(x, y, "关闭×")
    }

    // ------------------------------------------------------------------
    // 基础动作
    // ------------------------------------------------------------------

    private suspend fun tapAt(cx: Float, cy: Float, label: String): Boolean {
        val x = (cx * capture.scaleX).toInt()
        val y = (cy * capture.scaleY).toInt()
        if (dryRun) {
            RunnerState.clicks++
            RunnerState.lastHit = label
            LogBus.ok("模拟命中「$label」@($x,$y)（未点击）")
            delay(400)
            return true
        }
        // v3.8.0：无障碍节点优先（命中控件本体），失败自动回退坐标点击
        if (A11yNode.available() && tapAtNode(cx, cy, label)) return true
        val ok = Clicker.tap(x, y)
        if (!ok) {
            LogBus.error("点击未生效：${Clicker.lastError()}")
            return false
        }
        RunnerState.clicks++
        RunnerState.lastHit = label
        return true
    }

    /**
     * v3.8.0 无障碍优先点击：节点命中的是控件本体，比坐标更稳（宽框、近邻文字不会误点）；
     * 节点不可用 / 无对应文案 / 距离过远时自动回退原坐标点击，业务判定逻辑一行未改。
     */
    private suspend fun tapAtNode(cx: Float, cy: Float, label: String): Boolean {
        val x = (cx * capture.scaleX).toInt()
        val y = (cy * capture.scaleY).toInt()
        val tolerance = capture.scaleY * KUGOU_A11Y_TOLERANCE_RATIO * capture.frameHeight
        val viaNode = runCatching {
            A11yNode.clickByKeys(a11yKeysFor(label), x.toFloat(), y.toFloat(), tolerance)
        }.getOrDefault(false)
        if (viaNode) {
            RunnerState.clicks++
            RunnerState.lastHit = label
            LogBus.ok("无障碍节点命中「$label」@($x,$y)")
            return true
        }
        return false
    }

    /** 标签 → 无障碍节点文案（命中不到即回退坐标点击） */
    private fun a11yKeysFor(label: String): List<String> = when {
        label.contains("关闭") -> listOf("关闭", "知道了", "完成", "收下", "跳过")
        label.contains("允许") -> listOf("允许", "始终允许", "仅在使用中允许", "确定")
        label.contains("继续免费听") -> listOf("继续免费听", "继续听", "继续")
        label.contains("跳转") -> listOf("跳转", "去看看", "去浏览", "立即查看", "点击查看")
        label.contains("点击浏览") -> listOf("点击浏览", "点击观看")
        label.contains("开关") -> listOf("自动看广告", "自动领取")
        label.contains("领取") -> listOf("领取", "立即领取", "领取奖励", "收下")
        else -> listOf(label)
    }

    private fun back(label: String) {
        if (!dryRun) Clicker.back()
        RunnerState.lastHit = label
    }

    private fun swipeUp(frame: Bitmap) {
        if (dryRun) return
        val x = (frame.width / 2f * capture.scaleX).toInt()
        val y1 = (frame.height * 0.75f * capture.scaleY).toInt()
        val y2 = (frame.height * 0.35f * capture.scaleY).toInt()
        Clicker.swipe(x, y1, x, y2, 400)
    }

    private suspend fun applyBackoff() {
        if (failStreak <= 0) return
        val idx = (failStreak - 1).coerceIn(0, KugouConfig.failBackoffMs.size - 1)
        val ms = KugouConfig.failBackoffMs[idx]
        LogBus.info("连续失败 $failStreak 次，退避 ${ms / 1000}s 后继续")
        delay(ms)
    }

    private fun fail() {
        failStreak++
        failTotal++
    }

    /** 拉起 酷狗音乐（优先 shell am，其次 startActivity） */
    private fun launchApp(): Boolean {
        if (Clicker.channel() == Clicker.Channel.SHIZUKU) {
            if (ShizukuUtil.exec("monkey -p ${KugouConfig.PKG} -c android.intent.category.LAUNCHER 1", 5000)) {
                return true
            }
        }
        val intent = context.packageManager.getLaunchIntentForPackage(KugouConfig.PKG)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** 识别预览按内容变化节流，避免刷屏 */
    private fun logPreview(items: List<OcrItem>) {
        if (items.isEmpty()) return
        val preview = items.take(10).joinToString(" | ") { "${it.text}@(${it.cx.toInt()},${it.cy.toInt()})" }
        if (preview != lastPreview) {
            lastPreview = preview
            sameScreenRounds = 0
            LogBus.info("· 识别: $preview")
        } else {
            sameScreenRounds++
        }
    }
}
