package com.shangyin.auto.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

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

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
}
