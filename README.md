# TV · JetStream

**让大屏回归内容，让遥控器操作更顺手。**

面向 Android TV 和电视盒子的影音应用，也提供 Android 手机版。基于 [FongMi/TV](https://github.com/FongMi/TV) 与 [CatVod](https://github.com/CatVodTVOfficial/CatVodTVJarLoader)，围绕首页、片库、搜索、播放和设置，持续完善 JetStream 大屏界面与播放内核。

[![TV · 点击观看 90 秒界面宣传片](docs/media/TV-promo-cover.jpg)](docs/media/TV-promo.mp4)

[下载安装](#下载安装) · [功能亮点](#功能亮点) · [构建指南](LOCAL_BUILD_ENV.md) · [内核验证](docs/player-core-sync.md) · [反馈问题](https://github.com/wobuhui666/TV/issues) · [English](README.en.md)

[观看宣传片](docs/media/TV-promo.mp4) / [下载 MP4](https://github.com/wobuhui666/TV/raw/refs/heads/ui/apple-tv-redesign/docs/media/TV-promo.mp4) · 90 秒 · 1080p · 中文字幕与配乐

宣传片由模拟器实录制作，使用演示片库展示界面，不提供影片资源或订阅服务。演示片段为 Sintel © Blender Foundation（CC BY 3.0）；完整[素材来源与署名](docs/media/CREDITS.md)。

## 功能亮点

| 体验 | 当前源码提供的能力 |
| --- | --- |
| 遥控器优先 | Material 控件、海报轻缩放与平滑边框、移动 Tab 指示器和设置面板过渡；焦点操作立即响应。 |
| 首页与片库 | 主视觉、继续观看、收藏与历史；按来源分类筛选，跨站搜索。 |
| 多种主题 | 主题色覆盖首页、详情、播放与设置，支持跟随内容营造背景氛围。 |
| 双播放内核 | Exo / Media3 与 MPV；倍速、缩放、字幕轨道、片头片尾设置。 |
| 原生 ASS 与双字幕 | libass 样式和动画、内嵌／导入字体、独立主副字幕；MPV 保留原生渲染。 |
| 复杂字幕 | 已验证 SUP / PGS、高分辨率内嵌 VobSub、DVB，以及 ASS 重叠、卡拉 OK、裁剪和矢量绘图。 |
| 下一集预加载 | Exo 提前准备符合条件的下一集，预载约 10 秒并在切集时复用媒体源。 |
| 音频效果 | 对白、夜间、音乐和自定义预设；五段 EQ、中置增强、响度归一化及限幅。 |
| 一起看 | [兼容官方 Syncplay 服务端](docs/syncplay.md)，两端各自播放同一影片，同步播放、暂停和进度；支持 TLS。 |
| 多设备同步 | [WebDAV 收藏与观看进度同步](docs/webdav-sync.md)，逐条合并、删除同步与并发保护；订阅和少量弹幕偏好可选。 |
| 直播与扩展 | 直播分组、EPG、追看；Java JAR、QuickJS、Python 配置扩展。 |
| 网络与投放 | 按规则选择代理并处理跳转认证；DoH、请求头、DLNA 与局域网控制；[可选 ECH，含 Cloudflare 共享配置补全（默认关闭）](docs/ech-validation.md)。 |

当前默认分支为 [`ui/apple-tv-redesign`](https://github.com/wobuhui666/TV/tree/ui/apple-tv-redesign)，已合入新播放内核。发布 APK 可能滞后于源码；例如 [v421](https://github.com/wobuhui666/TV/releases/tag/v421) 早于这轮内核修复，下载时请核对对应发布说明。

<details>
<summary>展开界面预览：从首页到播放与设置</summary>

![TV 宣传片分镜总览： 首页、片库、推荐、搜索、详情、播放、直播与主题设置](docs/media/TV-promo-contact.jpg)

来自成片的 13 个章节。宣传画面展示录制时的界面版本，后续细节以当前应用为准。

</details>

## 下载安装

### 1. 选择适合设备的 APK

进入本仓库的 **[Releases](https://github.com/wobuhui666/TV/releases)**，阅读发布说明并下载对应安装包。

| 选择 | 适用设备 |
| --- | --- |
| `leanback` / TV 版 | Android TV、电视盒子，以及主要使用遥控器的设备。 |
| `mobile` / 手机版 | Android 手机和触屏设备；以当次发布实际提供的 APK 为准。 |
| `arm64-v8a` | 运行 64 位 ARM Android 系统的设备。 |
| `armeabi-v7a` | 需要 32 位 ARM 安装包的设备。 |

最低支持 **Android 7.0 / API 24**。请按 Android 系统 ABI 选包，处理器支持 64 位不等于系统支持 64 位。升级时使用相同签名的安装包，保留已有配置与记录。

### 2. 添加自己的配置

安装后，在 **设置 → 来源** 中添加配置 URL 或本地文件。点播、直播、解析和扩展能力取决于所添加的配置及其服务。

- 点播、解析、网络与弹幕字段：[配置说明](docs/CONFIG.md)。
- M3U、TXT、JSON 和频道分组：[直播来源格式](docs/LIVE.md)。

### 3. 开始播放，按需调节

选择来源，浏览片库或搜索片名，然后进入详情选集播放。常用新增入口：

| 入口 | 可以设置 |
| --- | --- |
| 设置 → 预加载 | 下一集预加载。 |
| 播放器 → 字幕轨道 → 高级字幕 | 第二字幕、ASS 样式和字体导入。 |
| 播放器 → 音轨 → 音效 | 对白增强、EQ、响度和限幅。 |
| 播放器 → 选项；我的 → 设置 → 应用 | “一起看”开关加入／退出 Syncplay，独立连接设置选择服务器与房间。 |
| 我的 → 设置 → 多设备同步 | WebDAV 账号、同步范围、立即同步及自动同步。 |
| 我的 → 设置 | 主题、播放及其他偏好。 |

下一集预加载面向 Exo 的已解析 HTTP(S) 点播，排除 DRM、仍需解析和非 HTTP 来源；MPV 使用自己的当前流缓存。音频效果处理 PCM，开启后可能需要退出编码直通。字幕格式、容器和设备差异见[播放内核说明](docs/player-core-sync.md)。

## 配置与集成

| 文档 | 内容 |
| --- | --- |
| [CONFIG.md](docs/CONFIG.md) | 点播、直播、解析、代理、DoH、弹幕与配置示例。 |
| [原生海报墙与选源](docs/native-discovery.md) | 可选首页、我的片单、继续观看菜单、轮播控制、搜索过滤、详情选源与 TMDB 网络兼容。 |
| [诊断与工具](docs/practical-improvements.md) | 缓存管理、诊断包、来源健康、网盘检查和播放体验改进。 |
| [OTA 更新](docs/ota-updates.md) | 手动更新、重要版本云控、镜像下载和发布流程。 |
| [SPIDER.md](docs/SPIDER.md) | Java、JavaScript、Python 爬虫接口与返回结构。 |
| [LOCAL.md](docs/LOCAL.md) | 局域网推送、播放控制、字幕和弹幕注入。 |
| [LIVE.md](docs/LIVE.md) | 直播来源、频道分组与格式。 |

支持配置文档所列范围内的 Forward Widget 适配，包括部分视频列表、搜索和播放模块；依赖 WebView、加密格式或未实现宿主 API 的模块不能直接通用。

手机版可控制兼容的 DLNA 设备，TV 版可接收投放。画中画等手机专属能力、DRM、HDR 和音频直通均取决于相应平台与设备，不应视为所有电视上的统一能力。

## 开发与验证

完整步骤见 **[本地构建指南](LOCAL_BUILD_ENV.md)**。需要 JDK 21、Python 3.10、Android SDK 37，以及配套 Media3 fork；首次构建先完成指南中的 SDK 和 composite build 配置。

也可以从公开的 **[TV Codespace 模板](https://github.com/zhhshss/tv-codespace-template)** 开始，复用工具链和测试环境。

```bash
# 按构建指南准备环境；手动配置 SDK 37.0 时带上对应的 -I 参数。
./gradlew :app:assembleLeanbackArm64_v8aDebug
./gradlew :app:assembleMobileArm64_v8aDebug
./gradlew :app:testLeanbackArm64_v8aDebugUnitTest :catvod:testDebugUnitTest
```

共享业务位于 `app/src/main/`，TV 与手机界面分别位于 `app/src/leanback/`、`app/src/mobile/`；`catvod`、`quickjs`、`chaquo` 提供扩展基础，`mpv` 和 `subtitle-ass` 集成原生播放与字幕库。

以下为 2026-10-04 已记录的内核验证结果，完整条件、日志和限制见[验证报告](docs/player-core-sync.md)：

| 验证项目 | 结果 |
| --- | --- |
| 应用单元测试 | 267 项通过。 |
| 代理测试 | 14 项通过。 |
| x86_64 Android 模拟器 | 12 项受控播放回归通过。 |
| ARM64 Android 环境 | 15 项通过，包含 Python 原生扩展、MPV、双字幕、音效和缩略图。 |
| 构建 | TV ARM64 / ARMv7 APK 构建通过，手机 ARM64 Java / Kotlin 编译通过。 |
| TV Lint | 0 错误，现有 311 条警告。 |

ARM64 验证节点的系统标识为 `redroid`，不代表三星实体设备验证；HDMI、硬件 HDR、真实片源速度及端到端 AI 识别仍需相应环境实测。

## 反馈与贡献

欢迎通过 [Issues](https://github.com/wobuhui666/TV/issues) 提交问题。请附上应用版本或提交号、Android 版本、设备 ABI、播放内核、复现步骤和相关日志；分享前去掉令牌、私人地址与配置凭据。

改动前可阅读 [仓库开发约定](AGENTS.md)。播放器、解析和数据行为的变更请附上对应回归验证。

## 致谢与许可证

感谢 [FongMi/TV](https://github.com/FongMi/TV)、[CatVod](https://github.com/CatVodTVOfficial/CatVodTVJarLoader) 以及相关开源项目的作者与贡献者。

项目采用 [GNU GPL v3.0](LICENSE.md)。第三方组件保留各自许可证，详见 [libass 说明](third_party/libass/NOTICE.md)、[MPV 来源与重编说明](third_party/mpv/README.md) 和[宣传素材署名](docs/media/CREDITS.md)。

应用是播放与配置工具，不提供影视资源、订阅或第三方内容的使用权。请自行添加并合法使用有权访问的来源。
