package com.shangyin.auto.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无障碍点击执行器：Shizuku 不可用时的备用通道。
 * 除「点击 / 返回」外，顺带记录窗口切换事件里的前台包名，
 * 供 QQ音乐流程判断「是否已跳到第三方 / 是否已回到 QQ音乐」。
 */
class TapService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TapService? = null
            private set

        /** 最近一次窗口变化事件对应的包名（无障碍通道下用于判定前台应用） */
        @Volatile
        var lastPackage: String? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ev = event ?: return
        if (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            ev.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val pkg = ev.packageName?.toString()
            if (!pkg.isNullOrBlank() && (ev.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || lastPackage == null)) {
                lastPackage = pkg
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * 同步点击（v3.8.0）：等手势真正执行完成再返回，避免「派发成功但事件被丢弃」
     * 被误判成点击成功；超时或系统回调取消均视为失败，供上层重试。
     */
    fun tapSync(x: Int, y: Int, timeoutMs: Long = 800L): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val latch = CountDownLatch(1)
        val completed = AtomicBoolean(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(true)
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed.set(false)
                latch.countDown()
            }
        }
        if (!dispatchGesture(gesture, callback, null)) return false
        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return finished && completed.get()
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
