package com.shangyin.auto.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.shangyin.auto.core.TextMatch
import kotlin.math.hypot

/**
 * 无障碍节点通道（v3.8.0 新增）。
 *
 * 目的：在 OCR 已判定「该点哪里」之后，优先用无障碍节点树做一次精确点击，
 * 再退回到坐标点击，解决两类长期问题：
 * 1. OCR 坐标只能取文字框中心，宽框（如「广告丨×」「领取成功 ×」）中心会点空；
 * 2. 无障碍手势 / shell input 偶发丢弃事件，点击无声失效。
 *
 * 安全约束（不改变既有业务命中逻辑）：
 * - 只做「按文字找节点」，节点必须落在调用方给的 OCR 坐标附近（tolerance 像素内），
 *   避免同名字样在别的区域（例如任务列表里的「关闭」）被误点；
 * - 匹配到节点后优先 performAction(ACTION_CLICK)，节点不可点击时沿父链最多上溯 4 层；
 * - 节点点击无效时，用节点自己的中心点做一次手势点击（比 OCR 坐标更准）。
 */
object A11yNode {

    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 18
    private const val MAX_ANCESTOR = 4

    data class Hit(
        val text: String,
        val cx: Float,
        val cy: Float,
        val clickable: Boolean
    )

    fun available(): Boolean = TapService.instance != null

    /** 遍历当前活动窗口，收集文字命中 keys 且可见的节点 */
    fun snapshot(keys: List<String>): List<Hit> {
        val service = TapService.instance ?: return emptyList()
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return emptyList()
        val out = ArrayList<Hit>(12)
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        val rect = Rect()
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val raw = node.text?.toString() ?: node.contentDescription?.toString()
            if (!raw.isNullOrBlank() && keys.any { hit(raw, it) }) {
                if (node.isVisibleToUser) {
                    runCatching { node.getBoundsInScreen(rect) }
                    if (rect.width() > 0 && rect.height() > 0) {
                        out.add(
                            Hit(
                                text = raw,
                                cx = rect.exactCenterX(),
                                cy = rect.exactCenterY(),
                                clickable = node.isClickable
                            )
                        )
                    }
                }
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return out
    }

    /**
     * 按文字点击节点。[nearX]/[nearY] 是 OCR 给出的期望点（真实屏幕像素），
     * 只有距离在 [tolerance] 内的节点才会被采用，超出视为「不是同一个控件」直接放弃。
     */
    fun clickByKeys(
        keys: List<String>,
        nearX: Float,
        nearY: Float,
        tolerance: Float
    ): Boolean {
        val service = TapService.instance ?: return false
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return false
        val best = findNearest(root, keys, nearX, nearY, tolerance) ?: return false

        // 1) 节点自身或父链上的可点击节点直接派发 ACTION_CLICK
        var cursor: AccessibilityNodeInfo? = best
        var level = 0
        while (cursor != null && level <= MAX_ANCESTOR) {
            if (cursor.isClickable && runCatching { cursor!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)) {
                return true
            }
            cursor = runCatching { cursor!!.parent }.getOrNull()
            level++
        }
        // 2) 退回手势点击，落点取节点中心（与 OCR 坐标无关，更贴合控件）
        val bounds = Rect()
        runCatching { best.getBoundsInScreen(bounds) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return TapService.instance?.tapSync(
            bounds.exactCenterX().toInt(),
            bounds.exactCenterY().toInt()
        ) ?: false
    }

    /** 等待 keys 从窗口消失，用于校验「点击是否真的生效」 */
    fun waitKeyGone(keys: List<String>, timeoutMs: Long, intervalMs: Long = 120L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (snapshot(keys).isEmpty()) return true
            runCatching { Thread.sleep(intervalMs) }
        }
        return snapshot(keys).isEmpty()
    }

    private fun findNearest(
        root: AccessibilityNodeInfo,
        keys: List<String>,
        nearX: Float,
        nearY: Float,
        tolerance: Float
    ): AccessibilityNodeInfo? {
        var bestNode: AccessibilityNodeInfo? = null
        var bestDist = Float.MAX_VALUE
        var bestClickable = false
        val rect = Rect()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val raw = node.text?.toString() ?: node.contentDescription?.toString()
            if (!raw.isNullOrBlank() && keys.any { hit(raw, it) } && node.isVisibleToUser) {
                runCatching { node.getBoundsInScreen(rect) }
                if (rect.width() > 0 && rect.height() > 0) {
                    val d = hypot(rect.exactCenterX() - nearX, rect.exactCenterY() - nearY)
                    if (d <= tolerance && (d < bestDist - 1f || (d <= bestDist + 1f && node.isClickable && !bestClickable))) {
                        bestNode = node
                        bestDist = d
                        bestClickable = node.isClickable
                    }
                }
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.addLast(child to depth + 1)
                }
            }
        }
        return bestNode
    }

    private fun hit(raw: String, key: String): Boolean {
        val a = TextMatch.normalize(raw)
        val b = TextMatch.normalize(key)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.contains(b) || b.contains(a)
    }
}
