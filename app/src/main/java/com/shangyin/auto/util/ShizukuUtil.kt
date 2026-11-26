package com.shangyin.auto.util

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.lang.reflect.Method

/**
 * Shizuku 状态检测与 shell 执行封装。
 *
 * Shizuku api 13.1.5 未公开 newProcess，这里通过反射调用其私有静态方法，
 * 拿到 ShizukuRemoteProcess（继承 Process）后执行 input tap / keyevent 等命令。
 */
object ShizukuUtil {

    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    private val newProcessMethod: Method? by lazy {
        runCatching {
            Shizuku::class.java
                .getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                .apply { isAccessible = true }
        }.getOrNull()
    }

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isRunning(): Boolean = isBinderAlive()

    fun hasPermission(): Boolean = runCatching {
        isBinderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun version(): Int = runCatching { if (isBinderAlive()) Shizuku.getVersion() else 0 }.getOrDefault(0)

    fun requestPermission(code: Int): Boolean = runCatching {
        if (isBinderAlive() && !Shizuku.isPreV11() && !hasPermission()) {
            Shizuku.requestPermission(code)
            true
        } else {
            false
        }
    }.getOrDefault(false)

    private fun createProcess(command: String): Process? {
        val m = newProcessMethod ?: return null
        return runCatching {
            m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        }.getOrNull()
    }

    /** 最近一次执行失败的原因，供界面/日志展示 */
    @Volatile
    var lastError: String = ""
        private set

    /** 以 shell 身份执行命令（需 Shizuku 已运行且已授权）。 */
    fun exec(command: String, timeoutMs: Long = 6000): Boolean {
        if (!hasPermission()) {
            lastError = "Shizuku 未授权"
            return false
        }
        val process = createProcess(command)
        if (process == null) {
            lastError = if (newProcessMethod == null) {
                "Shizuku newProcess 反射不可用"
            } else {
                "创建 shell 进程失败（binder 未就绪）"
            }
            return false
        }

        var code = -1
        val worker = Thread {
            code = runCatching { process.waitFor() }.getOrDefault(-1)
        }
        worker.isDaemon = true
        worker.start()
        runCatching { worker.join(timeoutMs) }

        runCatching {
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
        }
        if (worker.isAlive) {
            runCatching { process.destroy() }
            lastError = "命令执行超时：$command"
            return false
        }
        if (code != 0) {
            lastError = "命令退出码 $code：$command"
            return false
        }
        lastError = ""
        return true
    }

    fun execWithOutput(command: String): String? {
        if (!hasPermission()) return null
        val process = createProcess(command) ?: return null
        return runCatching {
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor()
            text
        }.getOrNull()
    }
}
