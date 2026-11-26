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
    QQ(1, "QQ音乐", "com.tencent.qqmusic", "看广告 / 点击跳转后浏览N秒 / 跳过广告 / 关闭"),
    KUGOU(2, "酷狗音乐", "com.kugou.android", "自动看广告续时长 / 去浏览 / 了解详情 / 关闭"),
    CLOUD(3, "网易云音乐", "com.netease.cloudmusic", "观看领奖励 / 去观看 / 点击跳转后停留N秒 / 关闭");

    companion object {
        fun of(id: Int): Platform = entries.firstOrNull { it.id == id } ?: QISHUI
    }
}
