package com.shangyin.auto.core

/**
 * 支持的目标平台。同一时刻只允许一个平台在跑：
 * 用哪一个入口启动，就只有那一个平台的悬浮条与任务，互斥不冲突。
 */
enum class Platform(
    val id: Int,
    val label: String,
    val pkg: String,
    val targetHint: String
) {
    QISHUI(0, "汽水音乐", "com.luna.music", "领取奖励 / 继续观看 / 领取成功"),
    QQ(1, "QQ音乐", "com.tencent.qqmusic", "看广告 / 点击跳转后浏览N秒 / 跳过广告 / 关闭");

    companion object {
        fun of(id: Int): Platform = if (id == QQ.id) QQ else QISHUI
    }
}
