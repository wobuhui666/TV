# 流畅度和稳定性排查（2026-10-31）

本轮保留现有布局、主题、动画和功能，以真实 aiwex 配置验证小范围生命周期修复。

## 已修复的问题

| 触发场景 | 原问题 | 修复后的行为 |
| --- | --- | --- |
| 搜索时访问多个来源页 | `destroyItem()` 不释放页面，海报和视图随访问来源数增加 | 离屏页释放 View，保留 Fragment 与已加载数据 |
| 翻页请求发出后切到相邻来源 | 晚到结果被可见性判断丢弃，返回也无法重新应用 | 后台页保存结果，回看时保留分页数据 |
| 改关键词或筛选后再看同一来源 | 新的 detach 行为可能保留旧 Fragment 标签 | 清理当前和离屏 Fragment 后建立新一轮页 |
| 旧版首页批量清历史 | 主线程执行数据库删除，可能阻塞遥控操作 | 立即更新卡片，既有队列异步删除，固定本次配置 ID |
| 停止解析或快速切源 | 已排队任务仍创建 WebView，旧解析超时任务继续保留引用 | 停止后丢弃创建任务，同时取消超时任务 |
| 嵌套网页嗅探后停止 | 子 WebView 不归父实例清理，可能继续运行 JavaScript 和请求 | 子实例随父实例停止并销毁 |
| Rex 栏目响应头已到达、正文仍下载时离开页面 | 请求过早移出取消登记；旧错误还可能启动日期回退 | 保持登记到正文与界面交付结束，丢弃取消后的回调 |
| 多个 Rex 请求同时注册、完成 | 注册表移除和新增之间存在竞态 | 三个重复实现共用同步的 `TaggedCalls` |

## 真实配置和验证范围

本机约 909 MiB 内存、6 GiB 可用磁盘，Android 构建和模拟器验证交给 GitHub Actions。
配置于本轮下载时为 34,563 字节，含 99 个站点（均为 type=3）、2 个直播、4 个解析。
完整配置、源响应、播放地址及原始源日志不进入提交或公开报告。

真实配置导航测量使用 API 34 / x86_64 模拟器、1920×1080、系统动画倍率 1，基线和优化版运行同一测试。
既有 fixture 仪器回归按原有方式使用动画倍率 0，完成后清空测试配置并恢复倍率 1，再开始真实配置和帧时间测量。
包括真实配置加载、默认来源首页、三次搜索输入页往返、多源搜索结果页及遥控导航。
源探测覆盖默认首页和最多三个可搜索来源；多源搜索使用应用现有调度器。
源返回内容的状态与界面检查结果分别记录，`SpiderNull` 标记为 `source-not-loaded`，不算作业务空结果或源功能通过。

当天插件 SHA-256 为 `97ed47577933dfd9b4d6c7d638888d349ad24ec8ee1dfc4afc9f1b33d97eabee`，ZIP 内仅含 ARMv7/ARM64 的 wexguard 原生库，ELF machine 分别为 40/183。其初始化按 `CPU_ABI.contains("64")` 选库，混入 x86 QuickJS 库的预览进程因而尝试加载 ARM64 库；运行时确认 `EM_AARCH64` 与 `EM_X86_64` 不匹配，失败被封装成 `SpiderNull`。

另建纯 ARM64 `com.fongmi.android.tv.sourceprobe` 包后，API 34 模拟器通过 `libndk_translation.so` 启用 ARM 兼容运行：`primaryCpuAbi=arm64-v8a`、`processArchitecture=aarch64`，受保护插件实际初始化成功并返回内容。因此，x86 预览中的空结果属于测试包架构限制，实际源行为以独立纯 ARM 测试为准。详见 [脱敏 ABI 检查](source-abi-inspection.json)。

单次软件 GPU 模拟器测量只用于定位和粗略比较，不能作为实机帧率提升承诺。
ARM 播放器和低性能电视上的实际解码、长时间播放仍需真实设备验证。

## 回归检查

- 应用完整 TV preview JUnit 测试与手机版 Java/Kotlin 编译。
- TV ARM64 debug APK 构建。
- `ParseJobLifecycleTest`：真实 WebView 主、子页面的停止行为，以及停止后的挑战弹窗和 JavaScript 续执行。
- `NativeBrowseIntegrationTest#searchReleasesOffscreenViewsAndKeepsLoadedPagesAndLateResults`：离屏释放、邻页和分离页晚到分页、换关键词隔离。
- `HistoryActionsIntegrationTest#legacyHomeClearRunsInTheQueueAndKeepsTheCapturedConfiguration`：队列阻塞时界面及时返回、跨配置删除隔离。
- `TaggedCallsTest` 与 `RexClientCancellationTest`：取消已排队结果、同 tag 重用、并发注册、真实慢 HTTP 正文取消。

## 美化候选（仅建议，尚未修改）

1. 来源面板 meta 与底部提示当前为 11sp、状态为 12sp，可提升至 13sp 并提高次级文字对比，便于远距离阅读。
2. 搜索状态行保持位置和文案，只强调结果数/来源数，使用现有主题强调色。
3. 海报首页页脚保持三个按钮顺序和功能，失败时突出“重试”，完成时突出“更多”。

所有建议保持当前布局、动画和交互，尚未修改或合并到应用。

[打开可切换前后效果的美化预览](visual-suggestions.html)（演示数据，非 App 截图）。

## 实测结果

两套验证均通过，验证源码提交为 `c69ac9e0c2dec85031e83aa02d64f75bfa9dfdbd`。

- [导航对比、单测、构建与回归](https://github.com/wobuhui666/TV/actions/runs/41216971763)：708 项单测，0 失败/错误/跳过；7 项真实 WebView/分页/数据库仪器回归；1 项完整真实配置导航检查；手机版编译和 TV ARM64 debug APK 构建成功。
- [纯 ARM 真实受保护源验证](https://github.com/wobuhui666/TV/actions/runs/41216969577)：1 项仪器测试通过，插件初始化成功，99 站点配置解析成功。
- [TV ARM64 debug APK](https://github.com/wobuhui666/TV/actions/runs/41216971763/artifacts/13048965603)；独立 sourceprobe app/test APK 在第二个工作流的 artifacts 中。
- 两种验证均未记录应用 Java 崩溃、native fatal signal 或 ANR。

纯 ARM 测试使用关键词“庆余年”，默认首页返回 20 条内容、7 个分类；花卷 3 条搜索结果，观影 2 条，玩偶 0 条。玩偶本轮未证明有匹配内容，不把空结果视为功能通过或已确认的应用缺陷。详见 [真实源报告](guarded-source-report.json)。

下面为单次软件 GPU 模拟器的参考数据，属于 x86 预览导航检查，不代表真实源加载/播放速度。纯 ARM 测试单独验证功能，不参与这一性能表。

| 指标 | 基线 | 优化版 |
| --- | ---: | ---: |
| 配置加载 ms | 6831 | 2967 |
| 帧样本数 | 624 | 659 |
| 帧时间 P50 ms | 50.12 | 50.12 |
| 帧时间 P95 ms | 150.13 | 133.51 |
| 返回首页后 PSS MiB | 235.07 | 222.38 |

两次 P50 基本相同；P95 和内存快照虽降低，配置耗时受网络影响、内存快照受 GC 与缓存影响，不能据此承诺实机提升百分比。确定的资源优化由回归验证：访问 8 个来源后仅保留当前及相邻最多 3 页 View，保留晚到分页，停止子 WebView 的 JS/网络，以及取消慢 HTTP 正文和旧回调。

[脱敏验证汇总](verification-summary.json) · [基线报告](baseline-report.json) · [优化版报告](optimized-report.json) · [首页截图](home.png) · [搜索截图](search.png) · [结果页截图](search-results.png)

实际电视上的解码、长时间播放和低性能设备帧率尚未验证。本轮没有修改播放引擎、布局或动画参数。
