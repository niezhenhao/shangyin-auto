package com.shangyin.auto.util

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

data class LogLine(val time: String, val text: String, val level: Int)

/**
 * 全局轻量日志总线：后台线程写、界面与悬浮窗订阅。
 *
 * v2.0.0 性能改造：写入先进无锁队列，主线程按 120ms 合并派发一次，
 * 避免高频日志（每秒数条）触发界面反复重建文本、悬浮窗反复 invalidate。
 */
object LogBus {

    const val LEVEL_INFO = 0
    const val LEVEL_OK = 1
    const val LEVEL_WARN = 2
    const val LEVEL_ERROR = 3

    private const val MAX = 200
    private const val FLUSH_DELAY = 120L

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentLinkedQueue<LogLine>()
    private val buffer = ArrayList<LogLine>(MAX + 8)

    @Volatile private var scheduled = false

    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines

    fun info(msg: String) = add(msg, LEVEL_INFO)
    fun ok(msg: String) = add(msg, LEVEL_OK)
    fun warn(msg: String) = add(msg, LEVEL_WARN)
    fun error(msg: String) = add(msg, LEVEL_ERROR)

    fun add(msg: String, level: Int) {
        val line = LogLine(fmt.format(Date()), msg, level)
        pending.add(line)
        if (!scheduled) {
            scheduled = true
            main.postDelayed({ flush() }, FLUSH_DELAY)
        }
        android.util.Log.println(
            when (level) {
                LEVEL_ERROR -> android.util.Log.ERROR
                LEVEL_WARN -> android.util.Log.WARN
                else -> android.util.Log.INFO
            },
            "ShangYin",
            msg
        )
    }

    private fun flush() {
        scheduled = false
        while (true) {
            val l = pending.poll() ?: break
            buffer.add(l)
        }
        if (buffer.size > MAX) buffer.subList(0, buffer.size - MAX).clear()
        _lines.value = ArrayList(buffer)
    }

    fun clear() {
        pending.clear()
        buffer.clear()
        _lines.value = emptyList()
    }
}
