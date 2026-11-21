# PR #130／#131 联合发布验证

本轮一起合入 [TV 桌面横向卡片 #130](https://github.com/wobuhui666/TV/pull/130) 和 [源配置弹窗修复 #131](https://github.com/wobuhui666/TV/pull/131)。验证提交为 `042788112f5d481abef01ddc02e49a156cae307b`，基准为 `854727a4a4255413e9a598bda37471f3177004f5`。验证后只补充本报告和索引，应用、资源及测试代码保持一致。计数摘要见 [verification.json](verification.json)。

| 检查 | 结果 |
| --- | --- |
| [完整构建与回归](https://github.com/wobuhui666/TV/actions/runs/44855990751) | app 1190、catvod 244、QuickJS 28、WASM 19，共 1481 项单测通过，失败／错误／跳过均为 0；TV ARM64、mobile ARM64 与测试 APK 构建通过 |
| [API24／API34 桌面横幅](https://github.com/wobuhui666/TV/actions/runs/44855992980) | 每种系统 2 项，共 4 项设备测试通过；六张三语言运行时横幅为不透明 320×180，与联合源码逐像素一致；每种系统另有 1190 项 preview 单测通过 |
| [API34 源弹窗与操作回归](https://github.com/wobuhui666/TV/actions/runs/44855994986) | 1080p／320dpi 与 720p／240dpi 各 26 项，共 52 项 Android 回归通过；1190 项 preview 单测通过，含 13 项直播选线集中回归，13 项不另行累加 |
| lint 差分 | 新增 Error 0、Warning 0；既有 Error 108 → 108，Warning 388 → 385，Hint 6 → 6 |
| 宿主检查 | Python 来源兼容 51 项、Media3 补丁 10 项、播放器宿主 JUnit 17 项及四组 JavaScript 检查通过；OTA 发布工具 14 项通过 |

环境为 GitHub Actions Ubuntu、JDK 21、Python 3.10、SDK／build-tools 37.0.0，Media3 固定提交 `342f6590e02ac390e0bd6b4488b8a1bff55747c1`。设备检查使用隔离的 x86_64 preview 包。主要 Gradle 命令为 `:app:testLeanbackArm64_v8aDebugUnitTest`、`:app:testLeanbackArm64_v8aPreviewUnitTest`、`:app:assembleLeanbackArm64_v8aDebug`、`:app:assembleMobileArm64_v8aDebug`、`:app:assembleLeanbackArm64_v8aDebugAndroidTest` 和 `:app:lintLeanbackArm64_v8aDebug`，工作流提供 SDK minor 配置。

横幅覆盖标准 Android TV 接口与资源渲染，未验证具体第三方桌面或实体电视。弹窗检查覆盖离线源、页面保留、重复窗口捕获、用户返回和表单窗口；两种尺寸的关键截图已查看。本轮未重跑真实 aiwex Guard 真机、账号提交、真实直播或播放性能。原任务的真实源结果及范围保留在 [弹窗报告](../2026-11-19-aiwex-config-dialog/README.md)。

[Release 193](https://github.com/wobuhui666/TV/releases/tag/build-44858036452-1) 已正式发布，[发布构建 44858036452](https://github.com/wobuhui666/TV/actions/runs/44858036452) 成功，耗时 14 分 33 秒。发布源码与 tag 指向 `d23b1b5b1d04500aa431fda968216434b0b9e147`；相对验证提交只补充文档。后续本报告的发布验收更新也只修改文档，不再触发 APK 发布。

四个下载后的 ARM64／ARMv7 标准版与共存版均通过 ZIP、包名、单一 ABI、Android 7.0 最低版本和签名 v2 校验，证书 SHA-256 均与 Release 192 标准包一致。统一版本为 `5.8.7+20261121.025040`／`218392640`，高于上一版 `218090613`。两份 OTA 清单及 GitHub 资产 SHA-256、大小均与下载文件一致；每包三语言横幅与发布提交逐像素一致。Release 为非草稿、非预发布，已设为 Latest；六个资产完整，详见 [release-verification.json](release-verification.json)。本轮未将正式 APK 安装到实体电视。

正式构建命令为 `assembleLeanbackArm64_v8aRelease assembleLeanbackArmeabi_v7aRelease --no-daemon --build-cache --max-workers=2`；共存版额外使用 `-PtvApplicationId=com.wobuhui666.android.tv`。
