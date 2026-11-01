# 开发目录与资料维护

本文说明 2026-11-01 的文档整理与本地开发产物去向。整理没有修改 App 运行代码、播放器功能、UI 或动画。

## 保留和上传

| 内容 | 现在的入口 |
| --- | --- |
| 功能目录、使用前提、安装与验证范围 | [中文 README](../README.md)、[English README](../README.en.md) |
| 流畅度排查、基线/优化对比与纯 ARM 来源检查 | [2026-10-31 报告](testing/reports/2026-10-31-responsiveness/README.md) |
| 海报墙、推荐筛选、本地字幕标签与长表单提示 | [2026-11-01 界面证据](testing/reports/2026-11-01-tv-polish/README.md) |
| 社区片头片尾、实际 Exo 跳转、99 源配置加载与设置截图 | [2026-11-01 回归](testing/reports/2026-11-01-community-skip/README.md) |
| 旧上游评估与实际合入状态 | [FongMi 5.8.5 历史评估](upstream-5.8.5-review.md) |
| 完成的计划、过期会话与最小旧源码参考 | [开发笔记归档](archive/development-notes/2026-11-01/README.md) |
| 旧 AISkip App/Worker/设置与测试 | [AISkip 历史实现](archive/aiskip-20261101/README.md) |

原始 GHA 大包中只选取经检查的摘要与必要截图，保存文件大小和 SHA-256。完整 APK 由 [Releases](https://github.com/wobuhui666/TV/releases) 或对应 GHA 提供，避免重复存入 Git。私有配置、Cookie、令牌、播放响应、依赖下载目录和原始私有日志未上传。

## 本地清理范围

本次只处理本项目已确认的开发目录，具体目录和结果记录在 [清理清单](archive/development-notes/2026-11-01/CLEANUP.json)。临时目录按用途分类，先保存必要资料，再删除重复产物。

| 类型 | 处理方式 |
| --- | --- |
| 项目 `.gradle/`、`build/`、`chaquo/build/`、`mpv/build/` 与 `.kotlin/` | 清理可重建缓存和生成文件；同时移除曾误跟踪的三份 Kotlin 编译错误日志 |
| 133 个 TV 相关临时开发目录 | 删除重复 GHA 报告、下载 APK/JAR、测试桩与工具副本；其中四个目录先保存必要旧源码参考 |
| 原 `.claude/plan/` 和根目录旧会话笔记 | 已完成内容移入文档归档，空目录清理；根目录 `AGENTS.md`、`CLAUDE.md` 保留 |
| 已结束任务的 `.codex-handoff/` | 内容已汇总到公开验证资料后清理，不上传内部会话接续原文 |
| Android SDK、`.git/`、`.media3/`、源码与第三方输入 | 保留，支持后续开发和可重现构建 |

审计时 TV 临时目录约 **4.76 GiB**，项目生成缓存另约 **104 MiB**。清单记录按路径统计的字节数，实际文件系统释放量可能受压缩、硬链接和其他任务影响；不以此承诺固定磁盘增量。

本次实际完成 133 个临时目录、五处项目缓存以及已结束任务接续目录的清理，另移除八份被公开摘要替代的临时文档／日志。清理前后文件系统可用空间增加约 **4.93 GiB**；这是本次观察值，不代表所有文件的逻辑大小之和。

`mpv/build/generated/ffmpeg9` 由 Gradle 的 `prepareFfmpeg9` 从受跟踪的 `app/libs/mpv-android-lib-v0.0.3.aar`、`third_party/mpv/mpv-android-ffmpeg9-native.zip` 和 bridge ZIP 重建，不是唯一原生源码。`.media3` 则是固定提交的依赖源码，含 composite settings 和配套补丁，其父目录会用于定位 `.github/media3-stubs`，不能当作临时输出删除或随意移动。

## 后续放置约定

- 应用、接口、原生补丁和测试代码继续放在现有模块与 `third_party/`，避免把开发临时副本作为新模块上传。
- 使用说明放 `docs/`；日期化测试证据放 `docs/testing/reports/日期-主题/`，先写清提交、环境、通过项和未覆盖项。
- 完成的方案与旧参考放 `docs/archive/`，注明历史状态；未实施的候选不能出现在当前功能清单里。
- 生成 APK、构建输出和原始抓取数据放本地临时目录或 GHA artifacts，提交只保留经过检查的摘要与必要图片。
- `.gitignore` 继续保留 `/docs` 的已有策略：新增公开文件逐项检查并明确纳入 Git；不要用整目录强制加入把私有测试数据一起上传。

本地恢复构建时按 [LOCAL_BUILD_ENV.md](../LOCAL_BUILD_ENV.md) 准备工具链并运行所需 Gradle 任务，生成缓存会自动重新建立。本次整理只做文档、图片、归档和构建日志清理，验证采用链接、JSON、文件校验及 Git 差异检查，不重复构建 APK。
