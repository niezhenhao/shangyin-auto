package com.shangyin.auto.core

import android.content.Context
import com.shangyin.auto.a11y.TapService
import com.shangyin.auto.util.LogBus
import com.shangyin.auto.util.ShizukuUtil

/**
 * 点击执行器：优先 Shizuku（shell input），退化到无障碍手势。
 */
object Clicker {

    enum class Channel { SHIZUKU, A11Y, NONE }

    fun channel(): Channel = when {
        ShizukuUtil.hasPermission() -> Channel.SHIZUKU
        TapService.instance != null -> Channel.A11Y
        else -> Channel.NONE
    }

    fun channelName(): String = when (channel()) {
        Channel.SHIZUKU -> "Shizuku"
        Channel.A11Y -> "无障碍"
        Channel.NONE -> "不可用"
    }

    fun tap(x: Int, y: Int): Boolean {
        if (channel() == Channel.SHIZUKU) {
            if (ShizukuUtil.exec("input tap $x $y", 2500)) return true
        }
        // Shizuku 失败或未授权时，自动退化到无障碍通道
        return TapService.instance?.tap(x, y) ?: false
    }

    fun back(): Boolean {
        if (channel() == Channel.SHIZUKU) {
            if (ShizukuUtil.exec("input keyevent 4", 2500)) return true
        }
        return TapService.instance?.back() ?: false
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 350): Boolean =
        when (channel()) {
            Channel.SHIZUKU -> ShizukuUtil.exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
            else -> false
        }

    fun lastError(): String = when (channel()) {
        Channel.SHIZUKU -> ShizukuUtil.lastError.ifBlank { "Shizuku 执行失败" }
        Channel.A11Y -> "无障碍手势未派发"
        Channel.NONE -> "没有可用的点击通道"
    }

    fun checkAndLog(context: Context): Boolean {
        val ch = channel()
        if (ch == Channel.NONE) {
            LogBus.error("点击通道不可用：请先启动 Shizuku，或在权限中心开启无障碍点击")
            return false
        }
        LogBus.ok("点击通道：${channelName()}")
        return true
    }
}
