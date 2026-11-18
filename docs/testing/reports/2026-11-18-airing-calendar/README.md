# 追更日历与媒体库追剧验证

首轮 GitHub Actions 的 32 项专项单测和 APK 构建通过；交互验证因截图环境、存储清理和标签等待时序失败，修订后准备重跑。

## 输入与环境

- 基准提交：`6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，包含工作区既有未提交修订和本轮改动。相关源码 SHA-256 见 [source-inputs.json](source-inputs.json)。
- 本地：Ubuntu、约 2 GB RAM / 2 GB swap，JDK 21、Python 3.10.22、SDK 37.0、Gradle 9.1.0。
- Media3：`342f6590e02ac390e0bd6b4488b8a1bff55747c1`，沿用当前两份固定补丁。
- 可用 ARM64 设备：X20 / Android 10（API 29），物理屏幕 1200×1920，密度覆盖值 275；本轮尚未运行设备测试。

## GitHub Actions

- [验证运行 44414493763](https://github.com/wobuhui666/TV/actions/runs/44414493763)，源码快照 `eb6627ffd7b1ed8038afa9de5cc7aa958175b1cb`。
- 使用独立验证分支 `verify/issue-118-airing-calendar-20261118`，包含当前媒体库基础代码与本轮改动。
- 预期每个 API 任务执行 32 项专项 JUnit、Debug／Preview 构建、完整 lint 报告与任务范围比较、12 项 Android 交互和实际截图检查；API 24／34 分别运行。
- 使用离线排期和生成图片夹具，不注入线上 TMDB 或发布签名凭据。

## 首轮失败与修订

- API 34 安装成功，但 `adb run-as` 清理外部截图目录被 scoped storage 阻止，交互测试未启动。改为测试进程通过应用上下文清理限定证据目录。
- API 24 执行了 12 项交互，7 项成功、5 项失败。其中 4 项在 `UiAutomation.takeScreenshot()` 返回空时失败；另 1 项在追剧标签更新前读取到空值。未把这些失败记为通过。
- API 24 的模拟器物理屏幕为 1080×1920，而逻辑覆盖值为 1920×1080。修订使用原生 1080p TV 显示配置，并增加读取真实 `screencap` 的截图回退；仍要求真实 PNG、正确尺寸和非空白画面。
- 标签断言改为等待准确的可访问标签，数据库、状态和截图断言保留；测试数量仍为 8＋4。
- 完整 lint 有 121 个错误、387 个警告和 6 个提示。历史基线有 108 个错误；另外 13 项是工作区既有 `LibraryDetailArtwork` 的 HCT RestrictedApi 调用，任务范围未新增错误。完整 lint 未通过。

## 当前结果

| 检查 | 结果 |
| --- | --- |
| `git diff --check` | 通过 |
| 日历布局与图标 XML | 解析通过 |
| 日历语言资源 | 英文、简中、繁中各 22 个同名资源，无重复或缺项 |
| 日历与 TMDB 专项 Gradle 单测 | 首轮两个 API 任务均通过 32 项，失败／错误／跳过为 0 |
| TV ARM64 Debug／Preview APK | 首轮编译通过，Preview 与测试 APK 成功安装 |
| Android lint | 完整报告未通过；日历／追剧范围新增错误为 0 |
| 日历 8 项与追剧 4 项 Preview 交互 | API 24 首轮 7 成功／5 失败；API 34 首轮被前置清理阻止，待重跑 |

未将部分成功推广为完整 UI 验收；最新修订尚未重跑。线上 TMDB、ARM 真机交互与播放性能未验证。
