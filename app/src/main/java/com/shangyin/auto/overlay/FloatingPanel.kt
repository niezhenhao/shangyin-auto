package com.shangyin.auto.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.shangyin.auto.R
import com.shangyin.auto.core.Platform
import com.shangyin.auto.core.RunnerState
import com.shangyin.auto.databinding.ViewFloatingBinding
import com.shangyin.auto.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 运行时悬浮控制条：状态、暂停/继续、模拟、停止、最新一条日志。
 *
 * v2.0.0 性能改造：
 * - 文本只在内容真正变化时 setText，避免每帧 invalidate/layout；
 * - 拖动时 updateViewLayout 按 16ms 节流，拖动更跟手、不卡顿；
 * - 视图销毁时彻底解绑，避免泄漏导致的持续重绘。
 */
class FloatingPanel(
    private val context: Context,
    private val platform: Platform,
    private val onPauseToggle: () -> Boolean,
    private val onSimulate: () -> Unit,
    private val onStop: () -> Unit
) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var binding: ViewFloatingBinding? = null
    private var params: WindowManager.LayoutParams? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var logJob: Job? = null

    private var lastLogText = ""
    private var lastStatusText = ""

    fun isShowing(): Boolean = binding != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (binding != null) return
        val b = ViewFloatingBinding.inflate(LayoutInflater.from(context))
        // 悬浮条只标识本次启动来源，两项任务互斥不会共用一条
        b.tvTitle.text = context.getString(
            when (platform) {
                Platform.QQ -> R.string.float_title_qq
                Platform.KUGOU -> R.string.float_title_kugou
                else -> R.string.float_title_qishui
            }
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 24
        lp.y = 200

        // 拖动（16ms 节流，避免输入事件洪泛触发反复 layout）
        b.root.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            private var lastMoveAt = 0L

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = lp.x
                        startY = lp.y
                        moved = false
                        lastMoveAt = 0L
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved = true
                        if (moved) {
                            val now = System.currentTimeMillis()
                            if (now - lastMoveAt >= 16) {
                                lastMoveAt = now
                                lp.x = startX + dx.toInt()
                                lp.y = startY + dy.toInt()
                                runCatching { wm.updateViewLayout(b.root, lp) }
                            }
                        }
                    }
                }
                return moved
            }
        })

        b.btnPause.setOnClickListener {
            val paused = onPauseToggle()
            b.btnPause.text = if (paused) "继续" else "暂停"
        }
        b.btnSim.setOnClickListener { onSimulate() }
        b.btnStop.setOnClickListener { onStop() }

        runCatching {
            wm.addView(b.root, lp)
            binding = b
            params = lp
        }

        logJob = scope.launch {
            LogBus.lines.collect { list ->
                val last = list.lastOrNull()?.text ?: return@collect
                if (last != lastLogText) {
                    lastLogText = last
                    b.tvLog.text = last
                }
                val st = RunnerState.mode
                val status = when (st) {
                    RunnerState.Mode.RUNNING ->
                        if (RunnerState.paused) "已暂停 · 点${RunnerState.clicks}"
                        else "运行中 · 点${RunnerState.clicks}"
                    RunnerState.Mode.SIMULATING -> "模拟中 · ${RunnerState.rounds}轮"
                    RunnerState.Mode.IDLE -> RunnerState.haltNotice.ifBlank { "待命" }
                }
                if (status != lastStatusText) {
                    lastStatusText = status
                    b.tvStatus.text = status
                }
            }
        }
    }

    fun hide() {
        logJob?.cancel()
        logJob = null
        val b = binding ?: return
        b.root.setOnTouchListener(null)
        b.btnPause.setOnClickListener(null)
        b.btnSim.setOnClickListener(null)
        b.btnStop.setOnClickListener(null)
        runCatching { wm.removeView(b.root) }
        binding = null
        params = null
        lastLogText = ""
        lastStatusText = ""
    }

    fun dispose() {
        hide()
        scope.cancel()
    }
}
