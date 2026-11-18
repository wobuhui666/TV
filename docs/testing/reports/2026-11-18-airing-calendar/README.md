# 追更日历与媒体库追剧验证

本轮代码和测试用例已接入；当前仅完成静态检查，完整编译与设备交互尚未验证。

## 输入与环境

- 基准提交：`6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，包含工作区既有未提交修订和本轮改动。相关源码 SHA-256 见 [source-inputs.json](source-inputs.json)。
- 本地：Ubuntu、约 2 GB RAM / 2 GB swap，JDK 21、Python 3.10.22、SDK 37.0、Gradle 9.1.0。
- Media3：`342f6590e02ac390e0bd6b4488b8a1bff55747c1`，沿用当前两份固定补丁。
- 可用 ARM64 设备：X20 / Android 10（API 29），物理屏幕 1200×1920，密度覆盖值 275；本轮尚未运行设备测试。

## 当前结果

| 检查 | 结果 |
| --- | --- |
| `git diff --check` | 通过 |
| 日历布局与图标 XML | 解析通过 |
| 日历语言资源 | 英文、简中、繁中各 22 个同名资源，无重复或缺项 |
| 日历与 TMDB 专项 Gradle 单测 | 未完成；修正 Python 环境后，完整 TV Kotlin 编译仍受内存／交换空间限制 |
| TV ARM64 Debug／Preview APK | 尚未完成 |
| Android lint | 尚未运行 |
| 日历 8 项与追剧 4 项 Preview 交互 | 测试用例已加入，尚未执行 |

未将静态通过计为单测或 UI 通过；未生成可交付 APK。完整构建、真实 Logo 绘制、追剧到日历再取消的交互，以及线上 TMDB 排期均未完成验收。
