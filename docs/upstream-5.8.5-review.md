# FongMi 5.8.5 功能合入评估

> 这是 2026-10-31 的历史评估，正文描述当时的缺口，**不是当前待办清单**。2026-11-01 已补充下表；实际能力和入口以 [项目 README](../README.md)、当前源码及对应 PR 为准。

## 当前合入状态（2026-11-01）

| 历史建议 | 当前状态 | 记录 |
| --- | --- | --- |
| 第一批六项：取消旧 seek、空代理 host、HTTP STRM、Python 生命周期、长表单遥控滚动、缺失本地文件 404 | 已按本项目架构适配 | [PR #90](https://github.com/wobuhui666/TV/pull/90) |
| VTT 上传和本地同名字幕发现 | 已加入对应入口、扫描和字幕候选处理 | PR #90；[本地字幕与播放器说明](player-core-sync.md) |
| 同一集 seek 后保留下集缓存 | 已适配本项目的有限缓存与取消规则 | PR #90 |
| 长表单淡色滚动提示、海报墙筛选与字幕标签 | 已完成后续微调 | [PR #91](https://github.com/wobuhui666/TV/pull/91)、[界面证据](testing/reports/2026-11-01-tv-polish/README.md) |
| AISkip 替换 | 旧实现已移出 App，改用 TheIntroDB、IntroDB、TheOtherDB 的绝对时间段 | [PR #92](https://github.com/wobuhui666/TV/pull/92)、[使用说明](community-skip.md) |
| DRM 预加载、动态运行时整体重构、未列入已完成 PR 的行为候选 | 不属于上述合入结果，不可据旧建议宣称已实现 | 保留原评估，后续变动须以实际代码和验证确认 |

第一批的验证与架构范围见 [2026-10-31 流畅度报告](testing/reports/2026-10-31-responsiveness/README.md)；较新的构建与应用测试见 [2026-11-01 回归](testing/reports/2026-11-01-community-skip/README.md)。

## 原始评估

评估时间：2026-10-31。上游基准 `9febe402bb951c92856e26b95deae36c34248071`；本地基准 `44f8daa61`（版本号同步已完成）。上游从已记录的 `aa30eb047` 到最新共有 24 个提交，包含版本调整、功能修复和运行时重构。本次只读评估，没有修改应用源码或实施功能合并。

## 第一批建议

| 改动 | 上游提交 | 本地缺口和收益 | 合入边界 |
| --- | --- | --- | --- |
| 取消旧快进/回退会话 | [7bf1157](https://github.com/FongMi/TV/commit/7bf115759881e27df819bb01a357f4d7094d295c) | 本地已有延迟合并，但缺设备、downTime、canceled 按键归属判断；Live/Cast 部分换媒体或停止路径未清延迟 seek。可防止旧按键松开后跳到新节目的错误位置 | 适配现有 PendingSeek/Live/Cast；保留长按 10→20→30 秒加速、触屏手势和动画，不整换上游 SeekInput |
| 代理 URI host 为空时保护 | [0a4e0f9](https://github.com/FongMi/TV/commit/0a4e0f93641d82b205ab5a5dd40a18e249a666a9) | Policy 仍直接 uri.getHost().toLowerCase；有些合法 HttpUrl 转 URI 后 host 为空，可崩溃 | 改用规范化 HttpUrl；保留本地代理继承、鉴权、Cloudflare/ECH 路由策略 |
| HTTP STRM 解析修复 | [7d70ebd](https://github.com/FongMi/TV/commit/7d70ebdfe56997ae9bccca7539a413a6999a09e3) | 仍依赖 Content-Disposition 且整段读正文；NAS/WebDAV/HTTP 返回无文件名响应头时打不开 STRM。新增 BOM/空白处理与 64KiB 上限可减少误读大文件 | 保留重定向、HTTP 错误和非指针正文的原 URL 回退，以及本地 Extractor.stop/exit 契约 |
| Python 初始化失败与销毁隔离 | [123cc08](https://github.com/FongMi/TV/commit/123cc086159b0f13bf54bdf64bacbc259ca47c88) | 初始化失败的 Python 对象未清理；clear() 中一个 destroy 异常会中断其余源释放 | 按当前 Chaquopy/Python 3.10 架构移植；保留懒初始化、取消/等待交互时不缓存失败的语义 |
| 长源表单的遥控滚动 | [bda5e1d](https://github.com/FongMi/TV/commit/bda5e1d67613574ec3276a28d4f6956b3e6e7d48) | onFocusKey 直接 focusSearch，没有先 ScrollView.arrowScroll；长表单可能跳到按钮而未滚到下一字段 | 只补滚动/焦点逻辑；保留 JetStream 窗口尺寸、主题色、字体和动画。下拉主题和矢量箭头可另作小美化 |
| 缺失本地源返回 404 | [0234d3d](https://github.com/FongMi/TV/commit/0234d3dcee079fb4d5406e7f42f06c73cd8d4fb1) | 文件不存在当前变成 500，脚本不能区分缺文件和服务故障 | 小改动，可和网络修复一起合入 |

## 下一批功能

| 功能 | 当前情况 | 建议 |
| --- | --- | --- |
| VTT 上传、本地视频同名字幕发现（2645429） | VTT 解码已有；Action.onFile 仅接 srt/ssa/ass，缺 LocalSubtitleScanner/SubtitleArchive | 加上传入口与 USB/本地同名字幕扫描；适配 FileChooser/PUSH，不复制上游全套 LOCAL 架构 |
| 同一集拖动进度后保留下集缓存（c2e3ca0） | NextMediaPreload 在 SEEK 后 pause、取消 HTTP、移除源再重建 | 借鉴保留已准备缓存；维持本地剩余45秒触发、64MiB和单网络槽预算，避免增加弱设备负担 |
| 手动硬/软解模式记忆（1488525的一部分） | 新 PlayerManager 默认为 HARD | 仅保存用户手动选择，区分自动失败回退；不取消现有软解、静音、隧道和 Dolby 保护 |
| 弹幕时间偏移仅作用于当前播放（6f64521的一部分） | 当前 offset 仍保存在全局 Prefers | 可避免调过一部片后其他片也偏移，但这是行为选择；普通倍速继续按每部 History 记忆 |
| 新源 API 兼容入口（8c296c0的一部分） | 旧 getProxyUrl/init(Context)/public dialog 及脚本全局接口有实际兼容测试 | 只新增 proxyUrl/danmakuUrl 等别名、新旧并存；不删除旧接口 |

## 暂缓或不直接合入

- **关闭自动解码重试（1488525）**：会撤掉本地针对软解、静音音轨、隧道、Dolby 和超时的恢复能力，不符合弱设备稳定性目标。
- **普通倍速临时化（6f64521）**：会删除本地每部影片的倍速记忆；长按临时倍速已实现，不需重复。
- **DRM/ClearKey 下集预加载（3808c58、23fbfdf）**：本地两层明确拒绝 DRM；固定 Media3 不包含 LocalClearKeyLicense，不能只删判断。需先补许可处理及对应预加载夹具，Widevine/PlayReady 另验许可证生命周期和资源预算。
- **Exo 脚本音轨命令（a09d231）**：普通音轨选择已有；缺 ExoCommand 和配套 TrackSelectionUtil。可选低优先级功能，需要播放器依赖适配。
- **cryptography（99be430）**：当前只有 pycryptodome。应先找兼容 Chaquopy/Python 3.10、双 ARM ABI 的 wheel，不能把上游 python-android 原生运行时直接覆盖本地。
- **Python/QuickJS/SDK 整体 API 重构（8c296c0、ade54d6、f1aa53e）**：会删除 public dialog、旧 Java init/proxy、Python fetch/post/html/localProxy 和旧 JS 导出，并更换 wrapper/AAR。真实旧 JAR 和脚本兼容性需要保留，新流式能力另做。
- **动态运行时 profiles、模块 ABI 自动选择、未安装 MPV 隐藏（5cbfc56、c3345b8、23639b7、ef96a48）**：本地内置 mpv/chaquo/quickjs、固定 Media3 composite；没有 RuntimeModule/RuntimeInstaller。安装包按 FLAVOR_abi 选择，这些上游模块管理逻辑没有直接对应的需求。

## 可单独补充的 Android 7 兼容检查

上游 `01d2d56` 还把 APK ABI 检测由 `ZipFile.stream()` 改为 `entries()`/`Enumeration`。本地 `UpdateApkValidator.java:50` 仍使用 stream；已查 AOSP Android 7 ZipFile 源码，其本身没有该方法。可单独改为枚举读取，不需要引入动态模块管理。当前工程开启 core-library desugar，是否已经兜底尚未做 API24 实机验证，因此此项是兼容性候选，不能称为已复现的崩溃。

## 已具备，不重复合入

- Android 7 failedFuture 兼容：CompletableFutures helper 及 Net/NetCache/Dialog/QuickJS 异步路径已有实现。
- Java/Python/QuickJS 共享登录对话框主体，任务取消、等待输入时暂停预算、后台禁止交互等已经移植；只需补上面的滚动缺口。
- 普通音轨选择、VTT 解码、长按临时倍速已有实现。
- 旧三/四/五字段 proxy 响应、base64 和有符号/无符号 byte 数组等兼容仍应保留。

## 代码核对位置

- `catvod/src/main/java/com/github/catvod/net/OkProxySelector.java:86`、`ProxyRedirectInterceptor.java`：空 host 风险。
- `app/src/main/java/com/fongmi/android/tv/player/extractor/Strm.java:28`：无文件名响应头及大正文处理缺口。
- `app/src/main/java/com/fongmi/android/tv/server/process/Action.java:63`：VTT 上传入口。
- `app/src/main/java/com/fongmi/android/tv/api/loader/PyLoader.java:31`：逐源销毁和初始化失败清理。
- `app/src/main/java/com/fongmi/android/tv/ui/dialog/SpiderDialog.java:163`：滚动/焦点路径。
- `app/src/leanback/java/com/fongmi/android/tv/ui/custom/CustomKeyDownVod.java:84`、LiveActivity/CastActivity：按键归属及迟到 seek。
- `app/src/main/java/com/fongmi/android/tv/player/exo/NextMediaPreload.java:182`：SEEK 后重建预加载。
- `app/src/main/java/com/fongmi/android/tv/playback/vod/NextEpisodePreloader.java:98`、PlayerManager.preload：DRM 限制。

选定实际移植后，验证应沿用此前GHA环境：closest JUnit、手机版/TV编译、真实WebView与遥控/切源回归，最后通过纯ARM隔离包复验 aiwex 配置。所有候选应拆成小提交，保持现有 UI/动画和已验证的源兼容入口。
