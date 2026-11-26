package com.shangyin.auto.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 软件更新检测（使用说明弹窗关闭后触发）。
 *
 * 策略：
 * 1. 请求 GitHub 最新 Release（/releases/latest），取 tag_name 作为远端版本号；
 * 2. 与当前安装包的 versionName 比较（版本比较为通用数字段逐段比较，随版本号自动生效，不写死任何版本号）；
 * 3. 远端更新 → 弹窗展示新版本号与更新要点，按钮跳浏览器下载该版本 APK 直链；
 * 4. 已是最新 → 轻提示「已是最新版本」；
 * 5. 网络失败 / 解析失败 → 静默跳过，绝不阻塞主流程；
 * 6. 结果写入 SharedPreferences 做短时缓存，缓存有效期内容内不再发起网络请求。
 */
object UpdateChecker {

    const val REPO = "niezhenhao/shangyin-auto"

    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PREF = "shangyin_prefs"
    private const val KEY_TS = "update_check_ts"
    private const val KEY_VERSION = "update_check_version"
    private const val KEY_NOTES = "update_check_notes"
    private const val KEY_URL = "update_check_url"

    /** 缓存有效期：6 小时内不重复请求，避免每次启动都打接口 */
    private const val CACHE_MS = 6 * 60 * 60 * 1000L

    private const val TIMEOUT_MS = 6000

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-check").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** 启动自动检测的一次性闸门：同一进程生命周期内只放行一次 */
    private val launchChecked = java.util.concurrent.atomic.AtomicBoolean(false)

    data class Info(
        val version: String,
        val notes: String,
        val apkUrl: String
    )

    /** 当前安装包版本名（如 3.5.0），取不到时返回空串 */
    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * 版本比较：逐段数字比较，返回 -1 / 0 / 1。
     * 支持 "v3.5.0"、"3.5.1-beta"、"3.10" 这类写法，非数字后缀忽略。
     */
    fun compareVersion(a: String, b: String): Int {
        val pa = segments(a)
        val pb = segments(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun segments(raw: String): List<Int> {
        val s = raw.trim().removePrefix("v").removePrefix("V")
        return s.split('.', '-', '_', '+', ' ')
            .map { part -> part.takeWhile { it.isDigit() } }
            .filter { it.isNotEmpty() }
            .mapNotNull { it.toIntOrNull() }
    }

    /**
     * App 启动后调用：异步执行一次更新检测，不阻塞界面。
     * - 同一进程生命周期内只执行一次（界面重建、Activity 重新创建都不重复触发）；
     * - 内部复用 checkAfterGuide 的 6 小时缓存与失败静默策略，绝不打扰正常使用。
     */
    fun checkOnLaunch(activity: Activity) {
        if (!launchChecked.compareAndSet(false, true)) return
        checkAfterGuide(activity)
    }

    /**
     * 使用说明弹窗关闭后调用：缓存命中时直接用缓存结果，否则请求 GitHub。
     * 全程不抛异常、不弹错误，失败就静默返回。
     */
    fun checkAfterGuide(activity: Activity) {
        if (activity.isFinishing) return
        val sp = activity.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val cur = currentVersion(activity)
        if (cur.isBlank()) return

        val ts = sp.getLong(KEY_TS, 0L)
        val cachedVersion = sp.getString(KEY_VERSION, "") ?: ""
        val cachedUrl = sp.getString(KEY_URL, "") ?: ""
        val cachedNotes = sp.getString(KEY_NOTES, "") ?: ""

        val age = System.currentTimeMillis() - ts
        if (ts > 0L && age >= 0L && age < CACHE_MS && cachedVersion.isNotBlank()) {
            // 缓存新鲜：不再请求网络；仅在缓存显示有更新时提示，避免每次启动重复打扰
            if (compareVersion(cachedVersion, cur) > 0) {
                showUpdateDialog(activity, Info(cachedVersion, cachedNotes, cachedUrl))
            }
            return
        }

        io.execute {
            val info = try {
                fetch()
            } catch (e: Exception) {
                null
            }
            main.post {
                if (activity.isFinishing) return@post
                if (info == null) return@post   // 网络失败：静默跳过，不阻塞使用
                sp.edit()
                    .putLong(KEY_TS, System.currentTimeMillis())
                    .putString(KEY_VERSION, info.version)
                    .putString(KEY_NOTES, info.notes)
                    .putString(KEY_URL, info.apkUrl)
                    .apply()
                val latest = currentVersion(activity)
                if (compareVersion(info.version, latest) > 0) {
                    showUpdateDialog(activity, info)
                } else {
                    Toast.makeText(activity, "已是最新版本 v$latest", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 手动检测（点击右上角「更新」按钮）：
     * 1. 忽略 6 小时缓存与本地缓存版本，强制请求 GitHub 最新 Release；
     * 2. 检测结束回调 onFinish(是否成功)，由调用方恢复按钮加载态；
     * 3. 有新版本 → 弹窗；已是最新 → 轻提示；网络/服务异常 → 明确提示失败原因，不静默。
     */
    fun checkManual(activity: Activity, onFinish: (Boolean) -> Unit) {
        val cur = currentVersion(activity)
        if (cur.isBlank()) {
            Toast.makeText(activity, "检测失败：无法读取当前版本号", Toast.LENGTH_SHORT).show()
            onFinish(false)
            return
        }
        io.execute {
            var reason = ""
            val info = fetchWithReason { reason = it }
            main.post {
                onFinish(info != null)
                if (activity.isFinishing) return@post
                if (info == null) {
                    Toast.makeText(
                        activity,
                        "检测更新失败：" + reason.ifBlank { "网络不可用，请检查网络后重试" },
                        Toast.LENGTH_LONG
                    ).show()
                    return@post
                }
                // 手动检测不写缓存：避免「使用说明关闭后自动检测」在 6 小时内重复弹出同一更新
                if (compareVersion(info.version, cur) > 0) {
                    showUpdateDialog(activity, info)
                } else {
                    Toast.makeText(activity, "当前已是最新版本 v$cur", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 同步请求最新 Release；校验失败返回 null（自动检测不关心原因，静默处理） */
    private fun fetch(): Info? = fetchWithReason(null)

    /**
     * 同步请求最新 Release。失败返回 null，并通过 onError 回传可读的失败原因；
     * onError 为 null 时保持静默（自动检测路径）。
     */
    private fun fetchWithReason(onError: ((String) -> Unit)?): Info? {
        val conn = try {
            (URL(API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "shangyin-auto-update-check")
            }
        } catch (e: Exception) {
            onError?.invoke("更新地址无效（" + e.javaClass.simpleName + "）")
            return null
        }
        try {
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                onError?.invoke("无法连接更新服务器（" + e.javaClass.simpleName + "）")
                return null
            }
            if (code != 200) {
                onError?.invoke(
                    if (code == 403 || code == 429) {
                        "请求过于频繁（HTTP $code），请稍后重试"
                    } else {
                        "更新服务器返回异常（HTTP $code）"
                    }
                )
                return null
            }
            val text = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                onError?.invoke("读取更新信息失败（" + e.javaClass.simpleName + "）")
                return null
            }
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                onError?.invoke("更新信息解析失败")
                return null
            }
            val tag = json.optString("tag_name", "")
            if (tag.isBlank()) {
                onError?.invoke("未获取到有效版本号")
                return null
            }
            val version = tag.trim().removePrefix("v").removePrefix("V")
            if (segments(version).isEmpty()) {
                onError?.invoke("未获取到有效版本号")
                return null
            }

            val body = json.optString("body", "").trim()
            val notes = body.split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(8)
                .joinToString("\n")
                .ifBlank { "修复问题并优化自动点击稳定性。" }

            // 优先取 Release 附件里的 APK 直链，没有则退回到 Release 页面
            var apkUrl = json.optString("html_url", "https://github.com/$REPO/releases")
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        val u = a.optString("browser_download_url", "")
                        if (u.isNotBlank()) {
                            apkUrl = u
                            break
                        }
                    }
                }
            }
            return Info(version, notes, apkUrl)
        } finally {
            conn.disconnect()
        }
    }

    private fun showUpdateDialog(activity: Activity, info: Info) {
        if (activity.isFinishing) return
        val cur = currentVersion(activity)
        val msg = StringBuilder()
            .append("当前版本：v").append(cur).append('\n')
            .append("最新版本：v").append(info.version).append('\n')
            .append('\n')
            .append("更新要点：\n").append(info.notes)
            .toString()
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 v${info.version}")
            .setMessage(msg)
            .setPositiveButton("立即下载") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkUrl)))
                } catch (e: Exception) {
                    Toast.makeText(activity, "无法打开浏览器，请手动访问 $REPO", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("稍后再说", null)
            .show()
    }
}
