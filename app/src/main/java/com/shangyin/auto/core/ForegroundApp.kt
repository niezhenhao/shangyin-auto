package com.shangyin.auto.core

import com.shangyin.auto.a11y.TapService
import com.shangyin.auto.util.ShizukuUtil

/**
 * 当前前台应用包名。
 *
 * QQ音乐流程要靠「前台包名是否还是 QQ音乐」来驱动状态机（跳转第三方 / 返回 / 系统弹窗），
 * 因此优先用 Shizuku shell 读取 dumpsys，其次退化为无障碍服务的窗口变化监听。
 * 结果缓存 350ms，避免每轮都跑一次 dumpsys 拖慢主循环。
 */
object ForegroundApp {

    private val pkgRegex = Regex("([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/")
    private val focusRegex = Regex("(?:mCurrentFocus|mFocusedApp|mResumedActivity).*?([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/")

    private const val CACHE_MS = 350L

    @Volatile
    private var cached: String? = null

    @Volatile
    private var cachedAt: Long = 0L

    /** 是否具备可靠的前台包名读取能力 */
    fun available(): Boolean =
        Clicker.channel() == Clicker.Channel.SHIZUKU || TapService.lastPackage != null

    fun current(): String? {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cached

        val fromShell = if (Clicker.channel() == Clicker.Channel.SHIZUKU) queryShell() else null
        val value = fromShell ?: TapService.lastPackage
        cached = value
        cachedAt = now
        return value
    }

    private fun queryShell(): String? {
        val out = ShizukuUtil.execWithOutput(
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'"
        )
        parse(out)?.let { return it }

        val out2 = ShizukuUtil.execWithOutput(
            "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
        )
        return parse(out2)
    }

    private fun parse(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val line = raw.lineSequence().firstOrNull { it.contains("mCurrentFocus") || it.contains("mResumedActivity") || it.contains("mFocusedApp") }
            ?: raw.lineSequence().firstOrNull { pkgRegex.containsMatchIn(it) }
            ?: return null
        val m = focusRegex.find(line) ?: pkgRegex.find(line)
        val pkg = m?.groupValues?.getOrNull(1) ?: return null
        // 过滤掉窗口管理器自身的非应用条目
        if (pkg.startsWith("android.") || pkg == "android") return "android"
        return pkg
    }
}
