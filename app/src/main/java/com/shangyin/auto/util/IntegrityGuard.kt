package com.shangyin.auto.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.security.MessageDigest

/**
 * 上音 · 官方版本完整性校验（防盗版）。
 *
 * 核心逻辑：任何对 APK 的改动（改代码、换包名、反编译后二次打包）都必须重新签名，
 * 因此校验「安装包签名证书指纹」即可把非官方重打包版本挡在启动流程之外。
 * 另外附加调试器 / 注入框架（Frida、Xposed、LSPatch 等）痕迹检测，
 * 防止有人在不重签名的前提下动态 Hook 绕过校验。
 */
object IntegrityGuard {

    /** 官方仓库地址，用于引导用户获取正版 */
    const val OFFICIAL_HOME = "https://github.com/niezhenhao/shangyin-auto"

    /** 官方包名，改包名共存版同样视为非官方 */
    private const val OFFICIAL_PACKAGE = "com.shangyin.auto"

    /** 官方签名证书 SHA-256 白名单（release 与 debug 各一枚） */
    private val OFFICIAL_CERT_SHA256 = setOf(
        "217C51D9867CF761F05A1486BE42A38387E889712AA0FFCAA566275F7A51180E",
        "180FA92D21E1B72D1163CA82F609FC83D50070CD238D4CA7FD419D9D90C29E09"
    )

    /** 常见注入框架落地文件 */
    private val HOOK_FILES = listOf(
        "/data/local/tmp/frida-server",
        "/data/local/tmp/re.frida.server",
        "/data/local/tmp/frida-server-16.0.0",
        "/data/local/tmp/xposed",
        "/data/local/tmp/lspatch",
        "/sdcard/frida-server"
    )

    /** /proc/self/maps 中出现的注入库特征 */
    private val HOOK_SO = listOf(
        "frida", "xposed", "lspatch", "libsubstrate", "edxposed", "libriru", "riru"
    )

    data class Verdict(val ok: Boolean, val reason: String)

    /** 执行全部校验，返回是否可信及不可信原因 */
    fun check(ctx: Context): Verdict {
        if (ctx.packageName != OFFICIAL_PACKAGE) {
            return Verdict(false, "安装包已被改名（当前包名 " + ctx.packageName + "），非官方发布版本")
        }

        val cert = signerSha256(ctx)
            ?: return Verdict(false, "无法读取安装包签名信息")
        if (cert !in OFFICIAL_CERT_SHA256) {
            return Verdict(false, "安装包签名与官方版本不一致（已被二次打包或重签名）")
        }

        if (isHooked(ctx)) {
            return Verdict(false, "检测到调试或注入框架痕迹（Frida / Xposed / LSPatch）")
        }

        return Verdict(true, "")
    }

    /** 读取当前安装包签名证书的 SHA-256（大写十六进制） */
    private fun signerSha256(ctx: Context): String? = runCatching {
        val pm = ctx.packageManager
        val info: PackageInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                ctx.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }

        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
        bytes?.let { sha256Hex(it) }
    }.getOrNull()

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(Character.forDigit(v shr 4, 16))
            sb.append(Character.forDigit(v and 0x0F, 16))
        }
        return sb.toString().uppercase()
    }

    /** 调试器 / 注入框架痕迹检测 */
    private fun isHooked(ctx: Context): Boolean {
        // 可调试包本身不拦调试器，方便本人开发调试
        val debuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable && (Debug.isDebuggerConnected() || Debug.waitingForDebugger())) return true

        for (path in HOOK_FILES) {
            val exists = runCatching { File(path).exists() }.getOrDefault(false)
            if (exists) return true
        }

        val maps = runCatching { File("/proc/self/maps").readLines() }.getOrDefault(emptyList())
        if (maps.isEmpty()) return false
        for (line in maps) {
            val lower = line.lowercase()
            for (key in HOOK_SO) {
                if (lower.contains(key)) return true
            }
        }
        return false
    }
}
