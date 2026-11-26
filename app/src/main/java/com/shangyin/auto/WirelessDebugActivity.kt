package com.shangyin.auto

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shangyin.auto.a11y.TapService
import com.shangyin.auto.core.Clicker
import com.shangyin.auto.databinding.ActivityWirelessBinding
import com.shangyin.auto.util.ShizukuUtil
import com.shangyin.auto.util.WirelessDebugScanner
import rikka.shizuku.Shizuku

class WirelessDebugActivity : AppCompatActivity() {

    private lateinit var b: ActivityWirelessBinding
    private var scanner: WirelessDebugScanner? = null
    private var pairingAddr = ""
    private var connectAddr = ""

    private companion object {
        const val REQ_SHIZUKU = 3101
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQ_SHIZUKU) {
                Toast.makeText(
                    this,
                    if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku 已授权" else "Shizuku 授权被拒绝",
                    Toast.LENGTH_SHORT
                ).show()
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWirelessBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnRefresh.setOnClickListener { refresh() }
        b.btnGrant.setOnClickListener { requestShizuku() }
        b.btnDevSettings.setOnClickListener { openDevSettings() }
        b.btnOpenShizuku.setOnClickListener { openShizuku() }
        b.btnUseA11y.setOnClickListener { openA11ySettings() }
        b.btnCopy.setOnClickListener { copyAddr() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        scanner = WirelessDebugScanner(
            this,
            onResult = { type, host, port ->
                val addr = "$host:$port"
                if (type == WirelessDebugScanner.TYPE_PAIRING) {
                    pairingAddr = addr
                    b.tvPairing.text = "配对服务：$addr"
                } else {
                    connectAddr = addr
                    b.tvConnect.text = "连接服务：$addr"
                }
            },
            onError = { msg -> if (msg.isNotBlank()) b.tvState.append("\n$msg") }
        ).also { it.start() }

        runCatching { Shizuku.addRequestPermissionResultListener(permissionListener) }
    }

    override fun onPause() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        scanner?.stop()
        scanner = null
        super.onPause()
    }

    private fun refresh() {
        val installed = runCatching {
            packageManager.getPackageInfo(ShizukuUtil.SHIZUKU_PKG, 0); true
        }.getOrDefault(false)

        val state = when {
            !installed -> "Shizuku：未安装\n请先安装 Shizuku 后再回来。当前可先用无障碍通道。"
            !ShizukuUtil.isBinderAlive() -> "Shizuku：已安装，服务未运行\n请按下面的步骤用无线调试启动。"
            !ShizukuUtil.hasPermission() -> "Shizuku：已运行，等待授权\n点「请求授权」允许上音使用 shell。"
            else -> "Shizuku：已运行且已授权（v${ShizukuUtil.version()})\n点击通道：${Clicker.channelName()}"
        }
        val a11y = if (TapService.instance != null) "无障碍：已开启" else "无障碍：未开启"
        b.tvState.text = "$state\n$a11y"

        b.btnGrant.isEnabled = ShizukuUtil.isBinderAlive() && !ShizukuUtil.hasPermission()
    }

    private fun requestShizuku() {
        if (!ShizukuUtil.isBinderAlive()) {
            Toast.makeText(this, "Shizuku 服务未运行，先按步骤启动", Toast.LENGTH_SHORT).show()
            return
        }
        ShizukuUtil.requestPermission(REQ_SHIZUKU)
    }

    private fun openDevSettings() {
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (i in candidates) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        Toast.makeText(this, "无法打开开发者选项，请手动进入设置", Toast.LENGTH_SHORT).show()
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(ShizukuUtil.SHIZUKU_PKG)
        if (intent == null) {
            Toast.makeText(this, "未安装 Shizuku", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(intent)
        }
    }

    private fun openA11ySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }
    }

    private fun copyAddr() {
        val text = buildString {
            if (pairingAddr.isNotBlank()) append("配对：$pairingAddr\n")
            if (connectAddr.isNotBlank()) append("连接：$connectAddr")
        }.trim()
        if (text.isBlank()) {
            Toast.makeText(this, "还没发现端口，先打开无线调试", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("无线调试地址", text))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
}
