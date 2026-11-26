---

# 上音（ShangYin）

把 PC 端 `qishui-auto` 的 ADB + OCR 自动看广告脚本，改造成手机本地运行的 Android App。
不需要电脑、不需要数据线，全部在手机上完成：截图 → 离线中文 OCR → 匹配目标词 → 点击。

- 包名：`com.shangyin.auto`
- 版本：**2.0.0**（release 正式签名）
- 最低系统：Android 8.0（API 26），推荐 Android 11+（无线调试启动 Shizuku 需要 11+）


## 下载安装

| 方式 | 地址 |
| --- | --- |
| 直接下载 APK（手机浏览器可下） | https://github.com/niezhenhao/shangyin-auto/raw/main/dist/ShangYin-v2.0.0-release.apk |
| 仓库 Releases | https://github.com/niezhenhao/shangyin-auto/releases/tag/v2.0.0 |

安装前注意：若手机已装 1.0.0（debug 签名），请先卸载再装 2.0.0，否则会提示签名冲突。

## 2.0.0 更新

| 项 | 2.0.0 的变化 |
| --- | --- |
| 误判直播间 | 连续未命中不再触发返回键退出页面（原逻辑会误退），改为只在日志提示，循环继续 |
| 使用说明 | 新增内置说明弹窗：做什么、用到哪些权限及用途、分步操作、注意事项；首次启动自动弹一次，之后可从主界面右上角「使用说明」再次打开 |
| 运行流畅度 | 屏幕采集按需等比缩小后直接建虚拟屏，去掉每帧全屏 Bitmap + 缩放；解码缓冲与点击通道复用；日志增量渲染，不再整屏重建；OCR 与点击在独立线程，主线程不阻塞 |
| 签名与分发 | 配置 release 签名（keystore + v1/v2/v3），输出可长期覆盖安装的正式包 |

## 界面与功能

主界面只保留必要动作，去掉一切多余元素：

| 入口 | 作用 |
| --- | --- |
| 看广告启动 | 授权屏幕录制后开始自动识别并点击 |
| 模拟跑一遍 | 只识别、不点击，最多 6 轮 / 45 秒，用于确认识别是否正确 |
| 悬浮窗 | 启动待命模式并显示可拖动的悬浮控制条（暂停 / 模拟 / 停止 / 最新日志） |
| 停止 | 立即结束运行并释放屏幕采集 |
| 使用说明 | 打开内置说明弹窗（做什么 / 权限用途 / 操作步骤 / 注意事项） |
| 进入无线调试启动 | 进入 Shizuku 无线调试启动向导 |

权限中心一屏列全，逐项可跳转：屏幕截图、悬浮窗、无障碍点击、电池优化白名单。

## 自动化规则（与 PC 版一一对应）

| 项 | 取值 |
| --- | --- |
| 目标词优先级 | 领取奖励 → 继续观看 → 领取成功 |
| OCR 置信度阈值 | 0.5 |
| OCR 缩放宽度 | 720px |
| 轮询间隔 | 1200ms |
| 点击冷却 | 2000ms（「领取成功」不冷却） |
| ROI 校验 | 领取奖励/继续观看：中央 (0.25–0.75)x(0.35–0.70)；领取成功：近全屏 |
| 排除词 | 秒 / 解锁 / 后可领取 / 第 |
| 连续未命中 | 仅写日志（`maxMisses=8`），不返回、不退出页面 |
| 直播间判定 | 顶部（y < 屏高×0.25）出现「关注 / 更多直播」时判定为误入，但 2.0.0 已不再自动退出 |

## 点击通道

- Shizuku（推荐）：通过 shell 执行 `input tap`，速度快、不受无障碍限制。
- 无障碍服务：完全离线可用，无需 Shizuku，作为兜底方案。

## Shizuku 无线调试启动

App 内「进入无线调试启动」页做了三件事：

1. 分步骤图文指引（开发者选项 → 无线调试 → 配对码配对 → Shizuku 无线调试启动）
2. 用 NSD 实时发现系统广播的 `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`，显示配对端口与连接端口，可一键复制
3. 提供直达按钮：打开开发者选项 / 打开 Shizuku / 请求授权 / 刷新状态 / 改用无障碍

## 源码结构

```
app/src/main/java/com/shangyin/auto/
├── MainActivity.kt
├── WirelessDebugActivity.kt
├── core/
│   ├── AutoConfig.kt      # 规则常量（目标词、ROI、冷却、阈值）
│   ├── TextMatch.kt       # 目标词匹配与排除词
│   ├── OcrEngine.kt       # ML Kit 离线中文 OCR
│   ├── ScreenCapture.kt   # MediaProjection + VirtualDisplay + ImageReader
│   ├── Clicker.kt         # Shizuku / 无障碍双通道点击
│   └── AutoRunner.kt      # 主循环：截图 → OCR → 匹配 → 点击
├── ui/GuideDialog.kt      # 2.0.0 使用说明弹窗
├── util/
│   ├── LogBus.kt
│   ├── ShizukuUtil.kt
│   └── WirelessDebugScanner.kt
├── a11y/TapService.kt
├── overlay/FloatingPanel.kt
└── service/AutoService.kt
app/src/main/res/layout/dialog_guide.xml   # 2.0.0 说明弹窗布局
```

## 构建

需要 JDK 17 + Android SDK（compileSdk 34）+ Gradle 8.7。

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

### release 签名

签名配置在 `app/build.gradle.kts`，从**项目根目录**的 `keystore.properties` 读取：

```properties
storeFile=shangyin-release.jks
storePassword=******
keyAlias=shangyin
keyPassword=******
```

`keystore.properties` 与 `*.jks` 已在 `.gitignore` 中排除，**不入库**。自行构建时请用自己的 keystore，否则无法覆盖安装已发布的 APK。
证书指纹（SHA-256）：`217c51d9867cf761f05a1486be42a38387e889712aa0ffcaa566275f7a51180e`

## 已知限制

- 编译通过、静态逻辑对齐 PC 版，**尚未在真机上跑通完整广告链路**，首次使用建议先点「模拟跑一遍」确认识别与 ROI 命中；
- 从 1.0.0（debug 签名）升级到 2.0.0（release 签名）因签名不同，需先卸载旧版再安装；
- Shizuku 无线调试启动依赖 Shizuku 应用本身，未安装时只能用无障碍通道；
- APK 约 44MB，主要是 ML Kit 中文 OCR 离线模型。
*（内容由AI生成，仅供参考）*

