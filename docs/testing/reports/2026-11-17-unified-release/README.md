# 统一发布验证

本轮合入媒体库、首页模式、MPV、Exo 与覆盖层、直播选线、源配置、社区跳过及代理缓存修复。媒体库关联 [#113 指定评论](https://github.com/wobuhui666/TV/issues/113#issuecomment-6785037943)，MPV 关联 [#119](https://github.com/wobuhui666/TV/pull/125)。

测试提交：`b69ff09f049743af4279a57572fcae4c957514ce`。下列三组检查均在此提交执行并成功；本报告和索引为检查完成后补充的文档，不改变测试代码。详细计数见 [verification.json](verification.json)。

| 检查 | 结果 |
| --- | --- |
| [完整构建与回归](https://github.com/wobuhui666/TV/actions/runs/43996953582) | app 1190、catvod 244、QuickJS 28、WASM 19 项 JUnit，零失败、错误、跳过；TV/mobile 与测试 APK 构建通过 |
| [API 31 媒体库与原生门禁](https://github.com/wobuhui666/TV/actions/runs/43996955968) | 核心 29、媒体库 48、文件夹 6、表单 5、TLS 1，共 89 项通过 |
| [API 34 两尺寸源兼容门禁](https://github.com/wobuhui666/TV/actions/runs/43996958609) | 720p／240dpi 与 1080p／320dpi 各 22 项，共 44 次执行通过 |
| lint 差分 | 相对 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec` 新增 Error 0；保留 108 个既有 Error，Warning 348 → 388 |
| 其他宿主检查 | Python 51、Media3 补丁 10、播放器宿主 JUnit 17 项，以及 QuickJS、表单、WASM 门面通过 |

JDK 21、Python 3.10、SDK／build-tools 37.0.0；使用固定 Media3 提交的 GitHub Actions 构建，不在低内存工作机执行 Gradle。主要命令为 `:app:testLeanbackArm64_v8aDebugUnitTest`、`:app:testLeanbackArm64_v8aPreviewUnitTest`、`:app:assembleLeanbackArm64_v8aDebug`、`:app:assembleMobileArm64_v8aDebug`、`:app:assembleLeanbackArm64_v8aDebugAndroidTest` 与 `:app:lintLeanbackArm64_v8aDebug`；工作流提供 SDK minor 与隔离包名配置。

API 31 使用 1920×1080／320dpi x86_64 模拟器。8 张夹具全部能由目标 UID 读取，目标 UID 与进程 UID 均为 10145，SHA 与测试提交清单一致。验证覆盖三模式、分类／目录／账号路由、触摸返回遥控、播放返回焦点、迁移、季集、选源和续播。表单保留线程、次数、输入超时、禁用状态、返回值和按钮几何断言；长内容通过真实滚动检查状态与底部输入。API 34 另覆盖源配置／详情的单次获取和销毁后回调保护。

TLS 对照在独立进程加载 x86_64 Conscrypt，34 次加密请求及平台／随包 provider 各 1536 个真实证书副本的有限压力通过。显式 GC、弱引用及 finalizer 记录不能证明每次 native free 或自然 NativeAllocationRegistry 回收，也不证明历史崩溃根因。UI 与 TLS 并非同一进程。

[MPV 原任务](../2026-11-16-issue119-mpv/README.md)在外部 ARM64 X20／API 29 完成 30／30 主矩阵，覆盖本地、设备 HTTP、HLS、gpu-next 和请求 Vulkan 后实际回退 OpenGL；检查原生时钟、暂停及彩色视频像素。同夹具旧版复现重播／EOF／刷新和加载定位问题。额外跨 ADB HTTP 为 5／6，剩余项在 Exo 准备阶段超时，未进入 MPV。海信实机和真实 Vulkan 未验证，字幕黑屏未在 X20 自然复现。

[首页与模式](../2026-11-16-home-modes/README.md)和[详情／TLS 阶段](../2026-11-16-library-alignment-stability/README.md)分别保留原作者截图、结果与历史失败。真实首页显示国产精选且浏览／返回进程正常；测试源的 Go 扩展在 ARM 转译环境存在 ABI 限制，真实目录内容未加载，路由和空态由专项回归覆盖。不能把离线门禁推广为全部片源、账号、实体电视性能或稳定性验收。

「媒体库」仍可能不稳定、也可能不好用，欢迎反馈。切回入口：**设置 → 首页与发现 → 使用模式 → 简洁**，同一入口也可选「经典」。正式 APK、签名、版本、OTA 与 Release 目标在统一发布后另行下载核验，本报告不预先宣称发布资产已验证。
