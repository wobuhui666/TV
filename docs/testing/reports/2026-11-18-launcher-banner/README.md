# TV 桌面横幅验证

关联 [Discussion #105 的灵动桌面卡片反馈](https://github.com/wobuhui666/TV/discussions/105#discussioncomment-19704383)。测试提交 `e8ae2007fd57f3abaea5cdea52c32377366480f2`，后续仅补充本报告和索引。计数见 [verification.json](verification.json)。

TV 版原来引用仅 API26 以上存在的 mipmap 横幅，已有 drawable 图片属于另一资源类型，不能给 Android 7.x 回退。新横幅使用标准 320×180 不透明位图，保留品牌标志并包含 TV／影视／影視；应用与 HomeActivity 显式引用同一横幅。普通方形图标和启动入口保持原配置。

| 检查 | 结果 |
| --- | --- |
| [API24/API34 安装后接口验证](https://github.com/wobuhui666/TV/actions/runs/44323417351) | 各 2 项测试全部通过，无失败或跳过 |
| [TV/mobile 构建与回归](https://github.com/wobuhui666/TV/actions/runs/44323423560) | app 1190、catvod 244、QuickJS 28、WASM 19，共 1481 项单测通过，零失败／错误／跳过 |
| lint 差分 | 相对 `854727a4a4255413e9a598bda37471f3177004f5` 零新增问题；Error 108→108，Warning 388→385 |

使用 GitHub Actions、JDK21、Python3.10、SDK/build-tools37.0.0 和固定 Media3 提交。命令为 `:app:assembleLeanbackArm64_v8aPreview`、`:app:assembleLeanbackArm64_v8aPreviewAndroidTest`、`:app:testLeanbackArm64_v8aPreviewUnitTest -PtvPreview`；构建验证另执行 TV/mobile Debug 与 AndroidTest 构建、应用／共享模块单测及 lint 差分。未在低内存工作机运行 Gradle。

安装后的 PackageManager 查询普通 LAUNCHER 与 LEANBACK_LAUNCHER，均找到唯一 HomeActivity；应用和活动提供同一非零 banner。`getApplicationBanner`、`ActivityInfo.loadBanner` 和 `getActivityBanner` 返回完整 16:9 位图。渲染检查包含不透明背景、可见标志和名称、三个语言的文字不同且标志一致。API24/API34 六张输出图逐像素一致，分别与源 PNG 匹配。

| 简体 | 繁体 | 英文 |
| --- | --- | --- |
| ![影视](images/launcher-banner-zh-CN.png) | ![影視](images/launcher-banner-zh-TW.png) | ![TV](images/launcher-banner-en.png) |

以上图片是 Android 资源的实际渲染，不是灵动桌面截图。当前未取得灵动桌面版本或 APK，尚未验证该桌面、实体电视或正式 Release；第三方桌面控制横幅使用与图标缓存，更新后可能需要刷新卡片。API24/API34 使用隔离 x86_64 模拟器包，验证标准桌面接口与资源回退。

隔离测试 APK 与短期诊断见上述 Actions，仓库产物保留期为一天；本目录保存必要图片和公开摘要，不包含 APK、凭据或原始私有日志。
