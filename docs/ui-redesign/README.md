# TV 界面改版

最新补丁为 2026-10-03 **R2K**：[三星添加源崩溃修复及实际设备证据](source-crash/README.md)。视觉修复基线为 **R2H**，见[视觉修复、实际截图与验收限制](visual-rework/README.md)。以下 10 月 2 日构建、录像和测试数字是历史记录，不代表 R2H 全面视觉验收通过。

这次改版覆盖 leanback 电视端。默认采用中性暗底、白色焦点和内容优先的排版，保留当前源浏览及详情小窗自动播放。Android 最低版本仍为 API 24。

## 页面与遥控操作

- 首页导航分为首页、片库、发现、直播、搜索和我的；直播入口取决于是否已有直播配置。长按导航仍可打开首页菜单。
- 首页主视觉按实际图片比例构图，横图铺满、竖图完整显示。标题、图片和点击目标同步更新；遥控操作暂停轮播，页面失焦或离开屏幕时停止定时切换。
- 继续观看使用独立横卡，显示集数、有效进度和剩余分钟。当前源推荐使用横向片单；源自定义的列表和圆形展示仍保留。
- 搜索页在同一界面保留输入、联想、来源筛选和结果，明确提交后才搜索。修改输入不会把已有结果冒充为新关键词结果；结果标题始终显示真正的查询词。Back 从结果返回输入，再次 Back 离开页面。
- 详情保留小窗自动播放，新增全屏观看主操作；选集优先展示，线路和画质位于次级区域。全屏与小窗切换沿用原来的播放器、渲染与焦点恢复路径。
- 播放控制分为片名、时间轴及播放操作、次级工具三层。高级功能保留在设置抽屉；关闭菜单后返回原操作位置。
- 设置复合行的配置主操作与右侧按钮分别获得焦点，方向键可进入所有主题色块；点播、直播和壁纸配置的点击及长按编辑保留。
- 我的集中收藏、当前配置的完整观看记录、手机推送、投屏说明、本地文件和设置。观看记录长按进入管理模式，Back 先退出管理；删除操作按顺序完成，离开页面不会丢弃已接受的删除。

## 视觉与资源规则

- 参考视口为 960 × 540 dp，主要内容采用 48 dp 水平安全边距；标题、辅助信息和按钮层级统一。
- 卡片焦点放大约 1.04 倍，焦点颜色与缩放使用 120 ms 反馈，主视觉使用约 300 ms 过渡。聚焦信息预留位置，避免列表高度跳动。
- 默认隐藏全局壁纸，内容背景归属于所在页面。设置中的“显示壁纸背景”可以重新启用原有图片、GIF 和视频壁纸；“跟随壁纸”取色在后台独立执行。
- 主题预设保留为辅助强调色；操作焦点使用深灰底、白字与细白轮廓。原生开关使用不同的滑块与轨道颜色。
- 首页只保留两张主视觉目标，图片由 Glide 缓存，横幅 URL 元数据缓存有明确上限。失败冷却与重试策略沿用之前的修复。

## 实现边界

保留现有 View、Leanback 与 Compose 混合架构。导航和详情使用宿主 View 焦点，播放控制继续使用其现有 Compose 子焦点。播放引擎、服务所有权、源协议和数据库版本不变；完整观看记录增加按配置查询的 DAO 方法，复用已有索引。

开发与验证使用独立工作树及 Codespace。改动发布到 `ui/apple-tv-redesign`，不修改默认分支；截图测试使用单独的合成内容配置。

## 云端验证

2026-10-02 最终源码（包括设置焦点、首页对齐和播放页主题修复）构建完成，105 个源码 SHA-256 已逐一匹配。最后两项阻断专项回归通过，详见[完整 QA 报告](qa-results.md)与[专项报告](final-blocker-qa.md)。以下检查在 Codespace 的独立工作树执行，JDK 21、Android SDK 37，复用项目 Media3 复合构建：

```sh
./gradlew :app:assembleLeanbackArm64_v8aDebug \
  :app:testLeanbackArm64_v8aDebugUnitTest \
  :app:lintLeanbackArm64_v8aDebug \
  --offline --no-daemon --build-cache --max-workers=2
./gradlew :app:compileMobileArm64_v8aDebugJavaWithJavac \
  --no-daemon --build-cache --max-workers=2
```

- ARM64 TV debug APK 构建成功；单元测试 216 项通过，0 失败、0 跳过。
- Android lint 通过：0 错误、269 警告、6 提示。本次没有关闭 lint 或添加 baseline 来隐藏问题；这不是“全仓无警告”的声明。
- 2026-10-01 共享 DAO、图片和壁纸工具的 mobile Java 编译通过；此后仅电视端尾部修复未重跑 mobile 编译。首次离线检查缺少两个 mobile 依赖，联网解析后通过。
- [API 24 ART 日志](api24-art.txt)：31 项检查通过，其中 30 项针对 production `MediaMatcher` / `MediaIdentity`，1 项确认设备 API。旧 `\p{IsHan}` 对照在同一 ART 上报错，当前字符扫描及静态 Pattern 初始化正常。
- [验证清单](verification.json)记录被测源码的 SHA-256、源码提交、测试结果及生产 ARM64 APK SHA-256；临时认证信息和 APK 均未提交仓库。

API 24 字符匹配验证使用真实 production 类，覆盖静态正则、集名扫描和身份匹配；不覆盖依赖完整应用的 `Trans` 实现及 `Vod` 重载。`normalize()` 走其原有容错路径。

## 界面运行环境与限制

截图环境为 Android 7.0 / API 24、1920 × 1080、density 320、简体中文的 x86_64 模拟器。界面使用独立的 type 1 HTTP 演示片库、演示直播和测试视频，图片来自 Unsplash，仅作为验证内容，不内置到应用或更改默认源。

项目生产 APK 打包 ARM 原生库。为运行 x86_64 截图，独立测试 APK 替换为依赖自带的 x86_64 AVIF/GIF/QuickJS/graphics 库，并仅在测试 DEX 中跳过 eager `PyLoader` 初始化；生产源码与生产 APK 保持正常原生初始化。此环境可验证命名页面和遥控流程，不代表生产 ARM APK、Python、MPV、真实投屏接收或硬件解码已在目标设备验证。

当贝 H3S、坚果 J10S 的实际画面、性能、遥控器差异及长时间播放仍需设备验收；API 24 模拟器不能替代这两台设备。

软件渲染模拟器短测 PSS 为 84253→84855 KiB，增长 602 KiB；500 帧中 496 帧 janky（99.2%），p50=53ms、p95=85ms。该环境未达到流畅性验收标准，不能推断目标设备性能或证明无内存泄漏；[原始采样摘要](evidence/performance-summary.json)。

## 页面证据

同一演示片库下的首页和详情对照。首页均聚焦顶部导航，详情均聚焦小窗；播放帧及历史状态不完全相同，因此不是逐像素或性能对照。

| 页面 | 改版前 | 改版后 |
| --- | --- | --- |
| 首页 | [截图](evidence/home-before.png) | [截图](evidence/home-after.png) |
| 详情 | [截图](evidence/detail-before.png) | [截图](evidence/detail-after.png) |
| 搜索 | — | [结果与输入同屏](evidence/search-after.png) |


## 最终专项证据

- [无配置首页截图](evidence/home-empty.png)与[冷启、选源、返回录像](evidence/blocker-no-config.mp4)：标题起点 y=216，位于顶栏底部 y=128 下方，不再重叠。
- [完整主题组合旅程](evidence/verified-theme-complete-journey.mp4)：搜索、详情、直播、设置弹窗及多次换色后正常返回同一频道。
- [点播进度保持](evidence/verified-vod-theme-preservation.mp4)与[详情焦点保持](evidence/verified-vod-detail-theme-focus.mp4)：主题切换前后保留00:19暂停位置及简介操作焦点。
- [对象标识记录](evidence/verified-object-identities.json)确认 Live/Video Activity 与 PlayerView 未重建；[颜色反馈记录](evidence/verified-accent-colors.json)确认实际强调色更新。
- [28份录像索引](evidence/README.md)区分仓库精选与完整归档；原始云端解码记录与 SHA 一并保存。

[设置焦点静态复核](settings-focus-review.md)和[最终两项修复静态复核](final-blockers-review.md)均通过，运行结果另以上述专项为准。播放页仅消除了主题触发的不必要重建，未修改通用服务连接状态机；系统自身重建或独立服务断连的竞态未作全面修复或验证。

## 遥控运行证据

以下录像来自前轮设置焦点修复后的功能验收，后续首页/播放页修复由上面的最终专项覆盖。视频可从仓库下载查看；[大小、时长、SHA 与云端解码记录](evidence/video-validation.json)。

| 流程 | 录像与范围 |
| --- | --- |
| 设置主题 | [色块方向键选择](evidence/fixed-settings-themes.mp4)；独立启动路径的选择与偏好持久化，不代表组合路径 |
| 设置配置 | [主操作与子按钮](evidence/fixed-settings-source-actions.mp4)；点播/直播/壁纸点击及长按，历史列表无其他条目 |
| 继续观看 | [第2集恢复](evidence/recovery-resume-card.mp4)；[数据库与控制栏文本](evidence/resume-evidence.json)，51.241秒记录，进入后57秒，包含取证播放时间 |
| 直播 | [分类、频道与快捷换台](evidence/recovery-live-navigation.mp4)，003→005→006 |
| 搜索 | [慢旧请求被新查询取代](evidence/recovery-search-replace.mp4)，旧请求延迟25秒；录像与静态截图的fixture来源数量可能不同 |

设置独立回归覆盖七种色彩（含需横向滚动的选项）；空日志导出仅验证“暂无日志”提示，有内容导出未测。文件页授权后可浏览目录，投屏仅查看空闲界面。无配置源加载存在已捕获日志异常，不声明全程日志无异常。
