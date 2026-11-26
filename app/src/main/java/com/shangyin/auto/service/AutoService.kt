package com.shangyin.auto.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.shangyin.auto.MainActivity
import com.shangyin.auto.R
import com.shangyin.auto.core.AutoRunner
import com.shangyin.auto.core.OcrEngine
import com.shangyin.auto.core.RunnerState
import com.shangyin.auto.core.ScreenCapture
import com.shangyin.auto.overlay.FloatingPanel
import com.shangyin.auto.util.IntegrityGuard
import com.shangyin.auto.util.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 自动化前台服务：持有 MediaProjection、驱动主循环、承载悬浮窗。
 */
class AutoService : Service() {

    companion object {
        const val ACTION_START = "com.shangyin.auto.action.START"
        const val ACTION_START_SIM = "com.shangyin.auto.action.START_SIM"
        const val ACTION_IDLE = "com.shangyin.auto.action.IDLE"
        const val ACTION_STOP = "com.shangyin.auto.action.STOP"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_DRY = "dry_run"

        private const val CHANNEL_ID = "shangyin_run"
        private const val NOTIF_ID = 1001

        @Volatile
        var instance: AutoService? = null
            private set

        @Volatile
        var alive: Boolean = false
            private set

        fun buildIntent(
            context: Context,
            resultCode: Int,
            data: Intent,
            dryRun: Boolean,
            simulate: Boolean = false
        ): Intent = Intent(context, AutoService::class.java).apply {
            action = if (simulate) ACTION_START_SIM else ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_DATA, data)
            putExtra(EXTRA_DRY, dryRun)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, AutoService::class.java).apply { action = ACTION_STOP }
    }

    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var capture: ScreenCapture? = null
    private var ocr: OcrEngine? = null
    private var panel: FloatingPanel? = null
    private var dryRun = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        alive = true
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) return START_NOT_STICKY

        // 防盗版：非官方安装包不允许拉起任何自动化能力
        val verdict = IntegrityGuard.check(this)
        if (!verdict.ok) {
            LogBus.error("完整性校验失败，已拒绝启动：${verdict.reason}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP) {
            stopAll()
            return START_NOT_STICKY
        }

        if (action == ACTION_START || action == ACTION_START_SIM || action == ACTION_IDLE) {
            // Android 14 要求先进入前台，再获取 MediaProjection
            if (!startForegroundCompat()) {
                stopSelf()
                return START_NOT_STICKY
            }
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            }
            dryRun = intent.getBooleanExtra(EXTRA_DRY, false)

            if (data == null) {
                LogBus.error("缺少屏幕录制授权，无法开始")
                stopAll()
                return START_NOT_STICKY
            }
            startWork(
                resultCode,
                data,
                simulate = action == ACTION_START_SIM,
                dry = dryRun || action == ACTION_IDLE,
                idle = action == ACTION_IDLE
            )
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun startWork(
        resultCode: Int,
        data: Intent,
        simulate: Boolean,
        dry: Boolean,
        idle: Boolean = false
    ) {
        job?.cancel()
        capture?.stop()

        val cap = ScreenCapture(this)
        if (!cap.start(resultCode, data)) {
            LogBus.error("屏幕采集启动失败，请重新授权屏幕录制")
            stopAll()
            return
        }
        capture = cap
        if (ocr == null) ocr = OcrEngine()

        val mode: RunnerState.Mode = when {
            idle -> RunnerState.Mode.IDLE
            simulate -> RunnerState.Mode.SIMULATING
            else -> RunnerState.Mode.RUNNING
        }
        RunnerState.reset(mode)
        LogBus.ok("屏幕采集已就绪 ${cap.width}x${cap.height}")

        showPanelIfAllowed()
        updateNotification()

        if (idle) {
            LogBus.info("待命模式：用悬浮条上的「模拟」开始试跑")
            return
        }

        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        job = s.launch {
            try {
                AutoRunner(cap, ocr!!, dry || simulate).run()
            } catch (t: Throwable) {
                LogBus.error("运行异常：${t.message}")
            } finally {
                LogBus.info("主循环已结束")
                RunnerState.stop()
                stopSelf()
            }
        }
    }

    fun togglePause(): Boolean {
        RunnerState.paused = !RunnerState.paused
        LogBus.info(if (RunnerState.paused) "已暂停" else "已继续")
        return RunnerState.paused
    }

    fun switchToSimulate() {
        job?.cancel()
        LogBus.info("切换为模拟模式")
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        val cap = capture ?: return
        RunnerState.reset(RunnerState.Mode.SIMULATING)
        job = s.launch {
            try {
                AutoRunner(cap, ocr ?: OcrEngine(), true).run()
            } catch (t: Throwable) {
                LogBus.error("模拟异常：${t.message}")
            } finally {
                RunnerState.stop()
                stopSelf()
            }
        }
    }

    private fun showPanelIfAllowed() {
        if (panel?.isShowing() == true) return
        val p = FloatingPanel(
            context = this,
            onPauseToggle = { togglePause() },
            onSimulate = { switchToSimulate() },
            onStop = { stopAll() }
        )
        p.show()
        panel = p
    }

    private fun stopAll() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        capture?.stop()
        capture = null
        ocr?.close()
        ocr = null
        panel?.dispose()
        panel = null
        RunnerState.stop()
        LogBus.info("已停止")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        instance = null
        job?.cancel()
        scope?.cancel()
        capture?.stop()
        ocr?.close()
        panel?.dispose()
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        return runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            true
        }.getOrElse { t ->
            LogBus.error("前台服务启动失败：${t.javaClass.simpleName} ${t.message}")
            false
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("上音运行中")
            .setContentText("正在识别并点击「领取奖励 / 继续观看」")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "自动化运行", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
