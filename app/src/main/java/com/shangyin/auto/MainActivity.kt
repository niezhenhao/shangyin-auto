package com.shangyin.auto

import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shangyin.auto.a11y.TapService
import com.shangyin.auto.core.Clicker
import com.shangyin.auto.core.RunnerState
import com.shangyin.auto.databinding.ActivityMainBinding
import com.shangyin.auto.service.AutoService
import com.shangyin.auto.ui.GuideDialog
import com.shangyin.auto.util.LogBus
import com.shangyin.auto.util.ShizukuUtil
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private var pendingKind = KIND_RUN
    private var awaitingAuth = false
    private var lastRunStateText = ""
    private var lastBtnRunText = ""

    companion object {
        private const val KIND_RUN = 0
        private const val KIND_SIM = 1
        private const val KIND_IDLE = 2
        private const val REQ_SHIZUKU = 3001
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    LogBus.ok("Shizuku 已授权，点击将走 shell 通道")
                } else {
                    LogBus.warn("Shizuku 授权被拒绝，将使用无障碍通道")
                }
                refreshState()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingAuth = false
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                LogBus.warn("已取消屏幕录制授权，本次不会开始")
                Toast.makeText(this, "未获得屏幕录制授权，无法开始", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            val drive = when (pendingKind) {
                KIND_SIM -> AutoService.buildIntent(this, result.resultCode, data, dryRun = true, simulate = true)
                KIND_IDLE -> AutoService.buildIntent(this, result.resultCode, data, dryRun = true, simulate = true)
                else -> AutoService.buildIntent(this, result.resultCode, data, dryRun = false)
            }
            if (pendingKind == KIND_IDLE) {
                // 仅悬浮窗：先按待命模式起服务，再切模拟以外的一切由悬浮窗控制
                drive.action = AutoService.ACTION_IDLE
            }
            launchService(drive)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.tvVersion.text = "v" + runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("1.0")

        b.tvLog.movementMethod = ScrollingMovementMethod()

        requestNotificationIfNeeded()

        b.btnRun.setOnClickListener { begin(KIND_RUN) }
        b.btnSimulate.setOnClickListener { begin(KIND_SIM) }
        b.btnFloat.setOnClickListener { begin(KIND_IDLE) }
        b.btnStop.setOnClickListener { stopAll() }
        b.btnWireless.setOnClickListener {
            startActivity(Intent(this, WirelessDebugActivity::class.java))
        }
        b.btnClearLog.setOnClickListener { LogBus.clear() }

        // 点击通道自检：点一下通道标签即可验证 Shizuku shell 是否可用
        b.pillChannel.setOnClickListener { selfCheckChannel() }

        b.btnPermScreen.setOnClickListener { begin(KIND_RUN) }
        b.btnPermOverlay.setOnClickListener { openOverlaySettings() }
        b.btnPermA11y.setOnClickListener { openA11ySettings() }
        b.btnPermBattery.setOnClickListener { openBatterySettings() }
        b.btnPermAll.setOnClickListener { guidePermissions() }
        b.btnGuide.setOnClickListener { GuideDialog.show(this) }

        // 首次启动自动弹出使用说明；之后可从右上角「使用说明」再次打开
        GuideDialog.showIfFirstLaunch(this)

        // 日志增量渲染：只追加新增行，避免每来一条日志就整屏重建文本
        lifecycleScope.launch {
            var shown = 0
            LogBus.lines.collect { list ->
                when {
                    list.size < shown -> {
                        b.tvLog.text = list.joinToString("\n") { "${it.time}  ${it.text}" }
                        shown = list.size
                    }
                    list.size > shown -> {
                        val chunk = StringBuilder()
                        for (i in shown until list.size) {
                            chunk.append(list[i].time).append("  ").append(list[i].text).append('\n')
                        }
                        shown = list.size
                        if (b.tvLog.text.length > 24000) {
                            b.tvLog.text = list.joinToString("\n") { "${it.time}  ${it.text}" }
                        } else {
                            b.tvLog.append(chunk)
                        }
                        b.tvLog.scrollTo(0, b.tvLog.layout?.height ?: 0)
                    }
                    else -> return@collect
                }
                refreshRunState()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (ShizukuUtil.isBinderAlive()) {
            runCatching {
                Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onStop() {
        runCatching {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        }
        super.onStop()
    }

    // ---------------- 状态 ----------------

    private fun refreshState() {
        val shizukuPill = b.pillShizuku
        when {
            !isShizukuInstalled() -> {
                shizukuPill.text = "Shizuku · 未安装"
                b.tvShizukuState.text = "未检测到 Shizuku。上音可直接用无障碍通道完成点击，Shizuku 为可选增强。"
            }
            !ShizukuUtil.isBinderAlive() -> {
                shizukuPill.text = "Shizuku · 未运行"
                b.tvShizukuState.text = "Shizuku 服务未运行。进入无线调试启动按步骤开启后，点击会走 shell 通道，速度更稳。"
            }
            !ShizukuUtil.hasPermission() -> {
                shizukuPill.text = "Shizuku · 待授权"
                b.tvShizukuState.text = "Shizuku 已运行，等待授权。"
                runCatching { ShizukuUtil.requestPermission(REQ_SHIZUKU) }
            }
            else -> {
                shizukuPill.text = "Shizuku · 已就绪"
                b.tvShizukuState.text = "Shizuku 已授权（v${ShizukuUtil.version()}），点击通过 shell input 执行。"
            }
        }

        val channel = Clicker.channelName()
        b.pillChannel.text = "点击通道 · $channel"

        val overlay = Settings.canDrawOverlays(this)
        b.pillOverlay.text = if (overlay) "悬浮窗 · 已开" else "悬浮窗 · 未开"

        b.tvPermOverlay.text = if (overlay) "已开启" else "未开启"
        b.tvPermA11y.text = if (TapService.instance != null) "已开启" else "未开启"
        b.tvPermBattery.text = if (isIgnoringBattery()) "已加入" else "未加入"
        b.tvPermScreen.text = if (AutoService.alive) "已授权" else "每次授权"

        refreshRunState()
    }

    private fun refreshRunState() {
        val mode = RunnerState.mode
        val text = when (mode) {
            RunnerState.Mode.RUNNING ->
                if (RunnerState.paused) "状态：已暂停｜已点击 ${RunnerState.clicks} 次｜最近命中 ${RunnerState.lastHit}"
                else "状态：运行中｜已点击 ${RunnerState.clicks} 次｜最近命中 ${RunnerState.lastHit}"
            RunnerState.Mode.SIMULATING -> "状态：模拟中｜已跑 ${RunnerState.rounds} 轮｜命中 ${RunnerState.clicks} 次"
            RunnerState.Mode.IDLE -> "状态：待命"
        }
        b.tvRunState.text = text
        lastRunStateText = text
        val runLabel = if (mode == RunnerState.Mode.RUNNING) "运行中…" else getString(R.string.btn_run)
        if (runLabel != lastBtnRunText) {
            lastBtnRunText = runLabel
            b.btnRun.text = runLabel
        }
    }


    private fun requestNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
    }


    private fun selfCheckChannel() {
        val ch = Clicker.channel()
        if (ch == Clicker.Channel.NONE) {
            Toast.makeText(this, "没有可用通道：请开 Shizuku 或无障碍", Toast.LENGTH_LONG).show()
            return
        }
        if (ch == Clicker.Channel.SHIZUKU) {
            val shellOk = com.shangyin.auto.util.ShizukuUtil.exec("echo ok", 3000)
            val tapOk = shellOk && com.shangyin.auto.util.ShizukuUtil.exec("input tap 5 5", 3000)
            val ok = shellOk && tapOk
            val msg = when {
                ok -> "Shizuku shell 与 input tap 均可用"
                !shellOk -> "Shizuku shell 失败：${com.shangyin.auto.util.ShizukuUtil.lastError}"
                else -> "input tap 失败：${com.shangyin.auto.util.ShizukuUtil.lastError}"
            }
            LogBus.add(msg, if (ok) LogBus.LEVEL_OK else LogBus.LEVEL_ERROR)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        } else {
            val ok = com.shangyin.auto.a11y.TapService.instance != null
            val msg = if (ok) "无障碍通道可用" else "无障碍未连接"
            LogBus.add(msg, if (ok) LogBus.LEVEL_OK else LogBus.LEVEL_WARN)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        packageManager.getPackageInfo(ShizukuUtil.SHIZUKU_PKG, 0)
        true
    }.getOrDefault(false)

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    // ---------------- 动作 ----------------

    private fun begin(kind: Int) {
        if (Clicker.channel() == Clicker.Channel.NONE) {
            AlertDialog.Builder(this)
                .setTitle("还不能执行点击")
                .setMessage(
                    "上音需要一条点击通道：\n\n" +
                        "1. Shizuku：功能最稳，通过无线调试启动后即可用；\n" +
                        "2. 无障碍点击：无需电脑、无需 Shizuku，开启后可直接使用。\n\n" +
                        "建议先开无障碍作为兜底。"
                )
                .setPositiveButton("去开无障碍") { _, _ -> openA11ySettings() }
                .setNeutralButton("看 Shizuku 教程") { _, _ ->
                    startActivity(Intent(this, WirelessDebugActivity::class.java))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            LogBus.warn("未开启悬浮窗权限，本次不显示悬浮控制条")
        }

        if (awaitingAuth) {
            Toast.makeText(this, "正在等待屏幕录制授权…", Toast.LENGTH_SHORT).show()
            return
        }
        pendingKind = kind
        awaitingAuth = true
        val mpm = getSystemService(MediaProjectionManager::class.java)
        runCatching { projectionLauncher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure {
                awaitingAuth = false
                LogBus.error("无法请求屏幕录制授权：${it.message}")
            }
    }

    private fun stopAll() {
        startService(AutoService.stopIntent(this).apply { setPackage(packageName) })
        RunnerState.stop()
        refreshState()
        Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show()
    }

    private fun launchService(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (t: Throwable) {
            LogBus.error("启动失败：${t.message}")
        }
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure { LogBus.error("无法打开悬浮窗设置：${it.message}") }
    }

    private fun openA11ySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure { LogBus.error("无法打开无障碍设置：${it.message}") }
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun guidePermissions() {
        val missing = mutableListOf<String>()
        if (!Settings.canDrawOverlays(this)) missing.add("悬浮窗")
        if (TapService.instance == null && !ShizukuUtil.hasPermission()) missing.add("无障碍点击")
        if (!isIgnoringBattery()) missing.add("电池优化白名单")

        if (missing.isEmpty()) {
            Toast.makeText(this, "权限已齐，可以开始", Toast.LENGTH_SHORT).show()
            return
        }
        when (missing.first()) {
            "悬浮窗" -> openOverlaySettings()
            "无障碍点击" -> openA11ySettings()
            else -> openBatterySettings()
        }
        LogBus.warn("还需开启：${missing.joinToString("、")}")
    }
}
