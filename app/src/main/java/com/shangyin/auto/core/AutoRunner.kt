package com.shangyin.auto.core

import android.graphics.Bitmap
import com.shangyin.auto.a11y.A11yNode
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

    /**
     * 停止原因提示（v3.2.0）：本轮因业务原因主动终止时写入，例如
     * 「今日免费听歌额度已用完，已停止」。停止后悬浮条状态位直接显示该文案，
     * 不再显示无信息量的「待命」。
     */
    @Volatile var haltNotice: String = ""

    fun reset(m: Mode, p: Platform = Platform.QISHUI) {
        mode = m
        platform = p
        rounds = 0
        clicks = 0
        lastHit = "-"
        paused = false
        haltNotice = ""
        message = if (m == Mode.SIMULATING) "模拟中" else "运行中"
    }

    fun stop() {
        mode = Mode.IDLE
        paused = false
        message = haltNotice.ifBlank { "已停止" }
    }
}

/**
 * 自动看广告主循环：截图 → OCR → 直播间判定 → 广告期收尾状态机 → 入口匹配 → 点击 → 冷却。
 *
 * v2.0.0 行为约定：
 * - 连续 N 次未匹配到目标【只写一条日志提示】，绝不自动返回、不暂停、不关闭任何页面，循环继续跑；
 * - 日志预览按内容变化节流，空闲时自动放慢轮询，降低 CPU 与耗电；
 * - 帧缓冲由 ScreenCapture 复用，本类只负责归还，不再 recycle。
 *
 * v3.8.0 关键修复（按用户录屏逐帧校准）：
 * 1. 广告页顶部「领取成功 ×」是一个整框，OCR 只给出框中心（x≈0.849），
 *    旧逻辑点在「领取」二字上，X 永远不会被点到。现在改为
 *    文字（含 × 时取框内右端）→ 图形 ×（像素级）→ 固定坐标 三级兜底；
 * 2. 广告播完判定从「见到字样就点」改为「画面静止 + 角标 / 最短观看时长」，
 *    避免广告还在播就关掉丢奖励；
 * 3. 广告结束先看有没有奖励面板可领（领取奖励 / 收下 / 继续观看），
 *    领完再决定是否点 X 关闭；广告条消失后若既无面板也不再点 X，
 *    避免误关免费听歌页；
 * 4. 每次点击先走无障碍节点（精确命中控件本体），失败再回退坐标点击。
 */
class AutoRunner(
    private val capture: ScreenCapture,
    private val ocr: OcrEngine,
    private val dryRun: Boolean
) {

    /** 广告期收尾阶段 */
    private enum class Phase { SEEK, IN_AD, AD_END, CLAIM, CLOSE }

    private var missCount = 0
    private var missNoticed = false
    private var lastPreview = ""
    private var sameScreenRounds = 0
    private val maxRounds = if (dryRun) AutoConfig.simulateRounds else Int.MAX_VALUE
    private val startAt = System.currentTimeMillis()

    // ---- v3.8.0 收尾状态 ----
    private var phase = Phase.SEEK
    private var adEnterAt = 0L
    private var endSince = 0L
    private var closeTries = 0
    private var claimTries = 0
    private var backTried = false
    private var panelWaitRounds = 0
    private var lastSig = 0L
    private var staticRounds = 0
    private var lastSkipAt = 0L
    private var lastAdWarnAt = 0L
    private var grabFailStreak = 0

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
                grabFailStreak++
                if (grabFailStreak == 1 || grabFailStreak % 5 == 0) {
                    LogBus.warn("截图失败，检查屏幕录制授权后重试（连续 ${grabFailStreak} 次）")
                }
                if (grabFailStreak >= 20) {
                    RunnerState.haltNotice = "连续 $grabFailStreak 次截图失败：请检查屏幕录制授权"
                    LogBus.error(RunnerState.haltNotice)
                    grabFailStreak = 0
                }
                delay(1500)
                continue
            }
            grabFailStreak = 0

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

        val vw = frame.width
        val vh = frame.height

        // 1. 直播间误入判定
        val live = items.any { TextMatch.isLiveTop(it.text, it.cy, vh) }
        if (live) {
            LogBus.warn("⚡ 检测到顶部[关注/更多直播]，退直播间")
            if (!dryRun) Clicker.back()
            RunnerState.lastHit = "退直播间"
            delay(1500)
            return true
        }

        // 2. 画面动/静判定（用于广告播完识别）
        val sig = QishuiText.frameSignature(frame)
        if (QishuiText.signatureDiff(sig, lastSig) > AutoConfig.adStaticDiff) {
            staticRounds = 0
        } else {
            staticRounds++
        }
        lastSig = sig

        // 3. 广告期识别
        val adPage = QishuiText.isAdPage(items, vh)
        val adDone = QishuiText.adDoneHit(items, vh)
        // 奖励面板只在「已进入广告流程」时才认，避免把免费听歌页的入口当成面板
        val panel = (adPage || adDone || phase != Phase.SEEK) &&
            QishuiText.rewardPanelHit(items, vw, vh)

        if (adPage || adDone || phase != Phase.SEEK) {
            return handleAdFlow(items, frame, adPage, adDone, panel)
        }

        // 4. 普通页：找入口
        return seek(items, frame)
    }

    // ------------------------------------------------------------------
    // 广告期：先领奖励面板，再关闭广告
    // ------------------------------------------------------------------
    private suspend fun handleAdFlow(
        items: List<OcrItem>,
        frame: Bitmap,
        adPage: Boolean,
        adDone: Boolean,
        panel: Boolean
    ): Boolean {
        val now = System.currentTimeMillis()
        val vw = frame.width
        val vh = frame.height
        val adPresent = adPage || adDone

        // ---------- A. 广告条已消失：可能已自动关闭并回落到免费听歌页 ----------
        if (!adPresent) {
            val claim = if (panel) QishuiText.panelClaimHit(items, vw, vh) else null
            if (claim != null && claimTries < AutoConfig.maxClaimTries) {
                return tryClaim(claim, "奖励面板")
            }
            if (phase == Phase.IN_AD || phase == Phase.CLAIM || phase == Phase.AD_END) {
                panelWaitRounds++
                if (panelWaitRounds <= 4 && now - adEnterAt < 30_000L) {
                    if (panelWaitRounds == 1) {
                        LogBus.info("广告已结束，正在等待「领取奖励」面板出现")
                    }
                    return false
                }
                LogBus.warn("广告结束后未发现可领取的奖励面板，按普通页继续（不会再点右上角 X）")
            } else {
                LogBus.ok("广告已关闭，继续寻找下一个入口")
            }
            resetFlow()
            return false
        }

        // ---------- B. 广告页在场：进入收尾流程 ----------
        if (phase == Phase.SEEK) {
            phase = Phase.IN_AD
            adEnterAt = now
            LogBus.info(
                "进入广告页：顶部${if (adDone) "已出现「领取成功」角标" else "为广告条"}，开始等待广告播完"
            )
        }
        if (adEnterAt == 0L) adEnterAt = now

        // B1. 面板优先：有可点的领取按钮就先领，领到再关广告
        if (panel) {
            val claim = QishuiText.panelClaimHit(items, vw, vh)
            if (claim != null && claimTries < AutoConfig.maxClaimTries) {
                return tryClaim(claim, "奖励面板")
            }
            if (claim == null && claimTries < AutoConfig.maxClaimTries) {
                // 面板在但这一帧没认出按钮：原地有限等待，别急着关，更别退回上一页
                panelWaitRounds++
                if (panelWaitRounds <= 6) {
                    if (panelWaitRounds == 1) LogBus.info("奖励面板已出现，正在等待「领取」按钮识别")
                    return false
                }
                LogBus.warn("奖励面板停留超过 6 轮仍无可点按钮，转为关闭广告")
            } else if (claimTries >= AutoConfig.maxClaimTries) {
                LogBus.warn("领取奖励已尝试 $claimTries 次仍未消失，转为关闭广告")
            }
        }

        // B2. 广告是否播完
        val timeoutEnd = now - adEnterAt >= AutoConfig.adMaxWaitMs
        val staticEnd = staticRounds >= AutoConfig.adStaticRounds &&
            (adDone || now - adEnterAt >= AutoConfig.adMinWatchMs)
        val ended = adDone || (adPage && (staticEnd || timeoutEnd))

        if (!ended) {
            phase = Phase.IN_AD
            trySkip(items, frame)
            if (now - lastAdWarnAt > 10_000L) {
                lastAdWarnAt = now
                LogBus.info(
                    "广告播放中：已等 ${(now - adEnterAt) / 1000}s，画面" +
                        if (staticRounds > 0) "静止 $staticRounds 轮" else "在变化"
                )
            }
            return false
        }

        // B3. 结束确认：稳定观察一小段，避免角标刚弹出时误点
        if (phase != Phase.AD_END) {
            phase = Phase.AD_END
            endSince = now
            LogBus.info(
                "判定广告已播完（" +
                    when {
                        adDone -> "已出现「领取成功」角标"
                        timeoutEnd -> "超时收尾"
                        else -> "画面静止"
                    } + "），准备收尾"
            )
        }
        if (now - endSince < AutoConfig.adEndWaitMs) return false

        // B4. 收尾前最后确认一次面板（可能刚弹出）
        if (panel && claimTries < AutoConfig.maxClaimTries) {
            val late = QishuiText.panelClaimHit(items, vw, vh)
            if (late != null) return tryClaim(late, "奖励面板")
        }

        // B5. 关闭广告
        return closeAd(items, frame)
    }

    /** 领取奖励面板按钮；成功返回 true（自带等待） */
    private suspend fun tryClaim(claim: OcrItem, from: String): Boolean {
        phase = Phase.CLAIM
        val x = (claim.cx * capture.scaleX).toInt()
        val y = (claim.cy * capture.scaleY).toInt()
        LogBus.add(
            "—— $from 命中「${claim.text}」 置信度 ${"%.2f".format(claim.score)}",
            LogBus.LEVEL_OK
        )
        val ok = tapSmart(x, y, listOf(claim.text, "领取", "收下", "继续观看"), "领取奖励")
        claimTries++
        if (ok) {
            missCount = 0
            missNoticed = false
            delay(AutoConfig.claimWaitMs)
        } else {
            delay(600)
        }
        return true
    }

    /** 广告播放中：命中「跳过」文案时点击，加速进入收尾 */
    private suspend fun trySkip(items: List<OcrItem>, frame: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastSkipAt < 4_000L) return
        val skip = items.firstOrNull { it.text.contains("跳过") && it.score >= AutoConfig.ocrScore }
            ?: return
        if (skip.cx < 0.55f * frame.width || skip.cy > 0.35f * frame.height) return
        lastSkipAt = now
        if (dryRun) {
            LogBus.ok("模拟命中「跳过广告」@(${skip.cx.toInt()},${skip.cy.toInt()})")
            return
        }
        val x = (skip.cx * capture.scaleX).toInt()
        val y = (skip.cy * capture.scaleY).toInt()
        if (tapSmart(x, y, listOf("跳过广告", "跳过"), "跳过广告")) {
            LogBus.ok("已点击「跳过广告」，加速收尾")
            delay(800)
        }
    }

    // ------------------------------------------------------------------
    // 关闭广告 X：文字 → 图形 × → 固定坐标
    // ------------------------------------------------------------------
    private suspend fun closeAd(items: List<OcrItem>, frame: Bitmap): Boolean {
        val vw = frame.width
        val vh = frame.height

        if (closeTries >= AutoConfig.maxCloseTries) {
            if (!backTried) {
                backTried = true
                LogBus.warn("关闭 X 已尝试 $closeTries 次仍未生效，退回键兜底")
                if (!dryRun) Clicker.back()
                RunnerState.lastHit = "返回键收尾"
                delay(AutoConfig.closeWaitMs)
                return true
            }
            LogBus.error("广告页无法关闭，请手动点一次右上角 X（已停止重试，避免误操作）")
            // 重置阶段并重新计时，避免死循环空转
            phase = Phase.IN_AD
            adEnterAt = System.currentTimeMillis()
            closeTries = 0
            backTried = false
            delay(AutoConfig.clickCooldownMs)
            return false
        }

        phase = Phase.CLOSE

        // ---- 一级：文字（含 × 时取框内右端，即 × 所在处）----
        val textHit = QishuiText.closeTextHit(items, vh)
        if (textHit != null) {
            val nx = QishuiText.closePointX(textHit, vw)
            val x = (nx * capture.scaleX).toInt()
            val y = (textHit.cy * capture.scaleY).toInt()
            val label = "关闭X(文字「${textHit.text}」)"
            LogBus.info("· 关闭一级兜底：$label → ($x,$y)")
            if (tapSmart(x, y, listOf(textHit.text, "关闭", "×", "知道了", "完成"), label)) {
                closeTries++
                delay(AutoConfig.closeWaitMs)
                return true
            }
        }

        // ---- 二级：图形 ×（像素级定位，OCR 认不出时兜底）----
        val spot = CloseGlyphFinder.find(
            frame,
            AutoConfig.closeGlyphRoiX0,
            AutoConfig.closeGlyphRoiX1,
            AutoConfig.closeGlyphRoiY0,
            AutoConfig.closeGlyphRoiY1
        )
        if (spot != null) {
            val x = (spot.cx * capture.scaleX).toInt()
            val y = (spot.cy * capture.scaleY).toInt()
            val label = "关闭X(图形 置信度 ${"%.2f".format(spot.score)})"
            LogBus.info("· 关闭二级兜底：$label → ($x,$y) 极性 ${spot.polarity}")
            if (tapSmart(x, y, listOf("关闭", "×"), label)) {
                closeTries++
                delay(AutoConfig.closeWaitMs)
                return true
            }
        }

        // ---- 三级：固定坐标（录屏校准：× 中心约 0.918, 0.058）----
        val fx = (AutoConfig.closeFixedX * vw * capture.scaleX).toInt()
        val fy = (AutoConfig.closeFixedY * vh * capture.scaleY).toInt()
        LogBus.info("· 关闭三级兜底：固定坐标 → ($fx,$fy)")
        if (tapSmart(fx, fy, listOf("关闭", "×"), "关闭X(固定坐标)")) {
            closeTries++
            delay(AutoConfig.closeWaitMs)
            return true
        }

        closeTries++
        LogBus.error("三级关闭兜底均未点击成功：${Clicker.lastError()}")
        delay(600)
        return true
    }

    // ------------------------------------------------------------------
    // 普通页：按优先级找业务入口
    // ------------------------------------------------------------------
    private suspend fun seek(items: List<OcrItem>, frame: Bitmap): Boolean {
        val vw = frame.width
        val vh = frame.height

        for (target in AutoConfig.targets) {
            val roi = AutoConfig.roiFor(target)
            val candidates = items.filter { TextMatch.match(it.text, target) }
                .filter {
                    it.cx >= roi.x0 * vw && it.cx <= roi.x1 * vw &&
                        it.cy >= roi.y0 * vh && it.cy <= roi.y1 * vh
                }

            if (candidates.isEmpty()) continue

            val sorted = if (target in AutoConfig.centerTargets) {
                val midX = vw * 0.5
                val midY = vh * 0.5
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
            // v3.8.0：「领取成功 ×」若出现在顶部广告条，说明是广告角标而非入口，
            // 必须点它右端的 ×，绝不能点框中心（旧逻辑正卡在这里）。
            val isTopBadge = QishuiText.isTop(best.cy, vh) && QishuiText.hasGlyphChar(best.text)
            val nx = if (isTopBadge) QishuiText.closePointX(best, vw) else best.cx
            val x = (nx * capture.scaleX).toInt()
            val y = (best.cy * capture.scaleY).toInt()

            if (isTopBadge) {
                LogBus.info("识别到「${best.text}」属广告角标，按关闭 X 处理")
                phase = Phase.CLOSE
                adEnterAt = System.currentTimeMillis()
            }

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
            val done = tapSmart(x, y, listOf(target, best.text), target)
            if (!done) {
                LogBus.error("点击未生效：${Clicker.lastError()}")
                delay(600)
                return true
            }

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
     * 统一点击：先走无障碍节点（精确命中控件本体，宽框更可靠），
     * 节点不可用或未命中时回退到坐标点击（Shizuku → 无障碍同步手势）。
     */
    private suspend fun tapSmart(x: Int, y: Int, keys: List<String>, label: String): Boolean {
        if (dryRun) {
            RunnerState.clicks++
            RunnerState.lastHit = label
            LogBus.ok("模拟命中「$label」@($x,$y)（未点击）")
            delay(300)
            return false
        }

        if (A11yNode.available()) {
            val tolerance = capture.scaleY * AutoConfig.nodeClickToleranceRatio * capture.frameHeight
            val ok = runCatching {
                A11yNode.clickByKeys(keys, x.toFloat(), y.toFloat(), tolerance)
            }.getOrDefault(false)
            if (ok) {
                RunnerState.clicks++
                RunnerState.lastHit = label
                LogBus.ok("无障碍节点命中「$label」→ 已点击控件 @($x,$y)")
                return true
            }
        }

        val done = Clicker.tap(x, y)
        if (!done) {
            LogBus.error("点击未生效：${Clicker.lastError()}")
            return false
        }
        RunnerState.clicks++
        RunnerState.lastHit = label
        LogBus.ok("坐标点击「$label」@($x,$y)")
        return true
    }

    private fun resetFlow() {
        phase = Phase.SEEK
        adEnterAt = 0L
        endSince = 0L
        closeTries = 0
        claimTries = 0
        backTried = false
        panelWaitRounds = 0
        staticRounds = 0
        lastSig = 0L
        lastSkipAt = 0L
        lastAdWarnAt = 0L
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
