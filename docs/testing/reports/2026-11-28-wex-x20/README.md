# WEX 自建源迁移与 X20 实测

当前 Wex 官方部分已按 [最新 aiwex.json](https://9280.kstore.vip/aiwex.json) 同步：**94 个新版 Wex 条目、30 个独立源，共 124 个点播条目**。旧 JAR 引用为 0，个人网盘统一进入「我的｜网盘」。本页早期逐源播放数字保留历史范围；最新配置迁移验证见下文。

2026-11-28，检查指定 WEX 配置中引用 `wobuhui666`、`zhhshss` 的 **16 个点播源和 3 个电视直播源**。游戏直播按斗鱼、虎牙、YY 分别抽样，共 21 条结果。迁移、9 处解析器地址替换及修复版配置更新已完成。累计抽样确认 **16 个源出现实际视频（mgnacg 缓冲明显），另 3 个仍未出画面**；后续 MP4 并发能力及回读部署见下文；源文件能下载不等于站点能播放。

## 迁移结果

- 源仓库及历史已复制到 [zhhshss/CatVodSpider](https://github.com/zhhshss/CatVodSpider/tree/wex-20261128) 和 [zhhshss/js](https://github.com/zhhshss/js/tree/wex-20261128)。原仓库保留。
- WEX 的 14 个独立 JAR 引用、哔哩的 API／规则引用、背景图和 Logo，共 **18 处地址**已改为 zhhshss，并固定提交。游戏与电视直播原本已在 zhhshss。
- 新旧 JAR、两份 JS、两张图片的 SHA-256 一致。X20 下载的 JAR 也已核对 SHA-256。坚果云条件 PUT 成功，最终 GET 与候选字节一致，仍有 104 个点播源、6 个直播配置，旧账号引用数为 0。
- 迁移前后完整配置、凭据和原始日志保存在仓库外的私有目录；公开记录仅保留哈希和字段变更。
- 后续按用户指定，将旧解析服务器替换为 `http://uk.frp.one:42822`：全局解析器 1 处、8 个源的 `playUrl`，共 9 处。逐字保留原路径、`pwd`、`url` 等查询参数、`json:` 前缀和请求头。条件 PUT 返回 204，回读一致，旧解析地址引用数为 0。其余 8 个源只验证引用替换，未逐个播放。

| 迁移时文件 | 迁移时固定提交（修复版见后文） |
| --- | --- |
| CatVodSpider JAR 与源码 | `74a91c878b9c9e902ef975cb546072b9dd2bff9e` |
| 哔哩规则与 drpy2 | `92ff75f478eb55381b02aba8f34b1c6497a65da1` |
| 背景图与 Logo | `888c1e0b85729c6c596d5c7166f56cd8c0702da3` |
| Python 游戏／电视直播 | `25ff7031e94b14c94ce7e8894394f04f3c1a0907` |

## 实测结果

以下结论只针对当次样本。逐源阶段、耗时、连续帧采样、文件哈希及迁移回读见 [verification.json](verification.json)。

| 来源 | X20 实测结果 |
| --- | --- |
| Girigiri | 已适配新版公开 API；首页 24 项、2 个分类，分类首页／下一页／尾页、搜索下一页及空结果均通过；X20 已播放 1080p 视频。 |
| 青空 Sorani | 已修复官方站误用镜像路径造成的 404；X20 返回 5 个分类、8 条搜索结果及 14 集列表。播放接口要求登录；后续密集分类复测还遇到 HTTP 403。支持在私有 `ext.accessToken` 配置有效访问令牌，未验证登录后的播放。 |
| Kazumi：7sefun、akianime、DM84、MXdm、xfdmnext | Android 10 编码调用已修复，搜索、详情和视频解码通过。 |
| Kazumi：baimao | 更新官方重定向域名与剧集 XPath，活跃《航海王》详情返回 6 条线路并实际播放；原站部分搜索条目没有资源。 |
| Kazumi：moonci | 识别 MacCMS 内嵌稀饭 Next 播放页，并按原片目／剧集／线路调用公开取址 API；初轮《暴力宇宙海贼》1080p 实播通过；同步上游 1.1 搜索接口后，《海贼王女》发布版复测 1080p、105 帧、推进 4.28 秒。 |
| Kazumi：AGE | 新增公开播放器取址；《葬送的芙莉莲 第二季》第 2 条线路在 R4 发布版实播 1080p、98 帧；同步 1.6 规则后的 R5 同字节 JAR 复测通过；最新并发版以默认单路加载 HLS，发布地址再验 99 帧、推进 4.08 秒。部分剧集仍限制原站 App 播放。 |
| Kazumi：ezdmw | 已修复 iframe 取址和 `.webp` 入口的 HLS 识别，清单返回成功；媒体分片请求超时／HTTP 502，另一线路返回空媒体地址，X20 仍未确认播放。 |
| Kazumi：dalvdm | 同步上游 1.2 规则并补齐 API 9：搜索 20 条、详情 7 条线路；最终发布版 X20 实播 1080p、103 帧、自然推进 4.22 秒。 |
| Kazumi：mgnacg | 最新 JAR 直接适配公开播放器并启用 3 路 MP4 Range 下载。最终发布地址实测 1080p、185 帧，约 26.8 秒推进 7.69 秒，仍明显缓冲。旧的 `bad base-64` 已定位为站点自定义播放器；备用线仍有网页解析失败，不保证全线路可用。 |
| Kazumi：mutefun | 同步上游 1.6 新域名及页面规则，X20 搜索仍返回 HTTP 403，主机页面包含站点验证表单。 |
| 哔哩｜4K(JX) | 10 个分类、3 条搜索结果，详情与剧集可读。换用指定新解析服务器后，解析约 2.4 秒成功；《航海王：红发歌姬》显示 3840×2160 视频，21 个渲染帧，约 4.6 秒观察内自然推进 4.15 秒。短时播放通过。 |
| 游戏直播：斗鱼 | 改为原生播放器直连 FLV，保留头部与回看。相同房间对照：原 Python 转发约 27.1 秒推进 22.3 秒、16 个缓冲采样；直连约 28.9 秒推进 28.5 秒、855 个渲染帧、零缓冲采样，1080p 连续播放。 |
| 游戏直播：虎牙 | 120 个房间；详情、取址成功。1080p、62 个渲染帧，约 8.9 秒观察内推进 4.2 秒；短时播放成立，持续流畅性未验证。 |
| 游戏直播：YY | 60 个房间；详情、取址成功。1080×1920、127 个渲染帧，约 13.1 秒观察内推进 4.2 秒；短时播放成立，持续流畅性未验证。 |
| 央视频｜Python | 64 个频道；CCTV1 为 1080p，18.4 秒内增加 229 个渲染帧，最长观测帧间隙 608ms。抽样可播放，观测帧率约 12.4fps。 |
| 央视官网｜Python | 20 个频道；CCTV-1 综合从 576p 切至 720p，23 秒增加 561 帧，最长观测帧间隙 830ms。抽样连续播放。 |
| 广东广电｜Python | 52 个频道；“广东卫视4K”实际媒体为 1080p，21.7 秒增加 528 帧，最长观测帧间隙 572ms。抽样连续播放。 |

12 个 Kazumi 原先均受 `URLEncoder.encode(String, Charset)` 在 Android 10 不存在的影响。已改用字符串编码名重载；修复后继续逐站验证，站点登录、验证码、资源下架和播放器失效分别记录，不把公共编码修复等同于每个网站都能播放。

## 后续源修复

首轮 R4 修复版已于 `2026-11-28T07:56:24Z` 写入坚果云：更新 14 个 JAR 引用、baimao／moonci 规则及游戏 Python API，共 15 个源。使用 ETag 条件 PUT 返回 204，最终 GET 与候选字节完全一致，配置 SHA-256 为 `73583006b97b3f2de04432b7b2ff4d7e3daa343f01fac22ea6e501b4739e8182`。104 个点播源、6 个直播配置保留；9 处新解析地址、原路径与参数保留，旧解析地址及 wobuhui666 引用均为 0。

修复源码与 JAR 位于 [CatVodSpider 固定提交](https://github.com/zhhshss/CatVodSpider/commit/1b871364547dba1d0f2776a2901accd19ac1d013)，游戏源位于 [Python 固定提交](https://github.com/zhhshss/tv-python-sources/commit/6a4cac899912733ce536dcc05196f9ee94235654)。Linux JDK21 / SDK37.0 执行 `spiderJar` 完成 Release 编译、R8、lintVital、JAR 类引用及 MD5 检查；同时修正 PowerShell 检查脚本对 Linux 临时目录尾斜杠的处理。新 JAR SHA-256：`47ee3fbe04f1a33844e6863607b578a2b120600c35085a6fe31f4e07d7a93ce8`。

修复包含 Girigiri 新 API、Sorani 官方／镜像地址区分及可选访问令牌、Kazumi Android 10 编码与 moonci 嵌套播放、baimao 域名与章节规则、AGE／E站公开 iframe 播放器适配、斗鱼直连。首轮 R4 时，Sorani 仍需有效账号令牌，dalvdm、mgnacg、mutefun 受站点验证阻断，E站后续媒体不可用。dalvdm、mgnacg 后续经上游规则同步改善，见下文。

首轮 R4 发布 JAR 在 X20 上复测 AGE 和 E站；此前其余 8 个修复成功的 CatVod 源使用 R3 发布地址实播通过。R4 新增分支只匹配 AGE／E站域名，其余源未在 R4 重复播放。JSON 为每项记录实际测试的 JAR 哈希／提交；本地候选验证与发布地址验证分别标注。斗鱼最终发布版另外观察约 28.9 秒，渲染 853 帧、自然推进 28.4 秒。

哔哩初测的旧解析服务器约 15 秒连接失败，已由上述新地址复测替代。旧结果保留在 JSON 的 `supersededObservations`；最新地址的条件更新、路径／参数保留检查及回读记录见 `parserOriginUpdate`。

电视直播的 HLS 窗口会移动，`currentPosition` 多次回退甚至暂时为负。因此不能用观察末尾位置减起始位置判断直播是否停播；最终结论使用连续帧计数、`isPlaying` 和帧间隙。JSON 保留探针原始 `status`，最终结论以每项 `assessment` 及 `continuousFrameEvidence` 为准。

## Kazumi 上游复查与同步

2026-11-28 复查 [Predidit/KazumiRules](https://github.com/Predidit/KazumiRules/commit/64ea35976c383a3a760eb23d744fcada076317ee)：最新规则提交为 **2026-11-13**，当天没有新提交。原来导入的是 **2026-10-03** 快照，落后 28 次提交；首轮排查没有先核对上游，漏掉了其中的搜索修复。Kazumi 应用另于 **2026-11-26** 发布 [2.4.7](https://github.com/Predidit/Kazumi/releases/tag/2.4.7)，应用版本与规则版本分别核对。

| 规则 | 原快照 → 最新 | 实际变化 |
| --- | --- | --- |
| AGE | 1.5 → 1.6 | API 标记与广告选项；原 App 限制仍存在 |
| dalvdm | 1.1 → 1.2 | 改用公开 suggest 搜索、详情地址模板及相对章节 XPath |
| mgnacg | 1.0 → 1.2 | 改用公开 suggest 搜索、修正详情根路径及章节 XPath |
| mutefun | 1.2 → 1.6 | 新域名 `www.mutedm.com`、新搜索／章节及验证页面规则 |
| moonci | 1.0 → 1.1 | 改用公开 suggest 搜索和详情地址模板 |
| giriGiriLove | 2.4 → 2.7 | 同步参考规则；现用独立 Girigiri 源已经适配该公开 API |

适配器新增 API 9 的 `sourceTemplate` 支持，保留裸 ID 与旧 API 行为。真实 Java 解析器的 13 项主机检查、Release／R8／lintVital／JAR 检查通过；X20 复测五个实际变更规则，另从发布地址复验 dalvdm、moonci、mgnacg。mgnacg 的缓冲和失败线路保留在报告，不能以其搜索恢复推断播放完全恢复。LMM、TvTFun 虽已在上游恢复启用，本次没有扩大原有源数量。

上一轮 R5 源提交为 [25094a57004001e7a4206d8c3fdbf691436ca34b](https://github.com/zhhshss/CatVodSpider/commit/25094a57004001e7a4206d8c3fdbf691436ca34b)，JAR SHA-256 为 `0b55e3edc02f0d7e5e28d030847e0cccd84c21f7f1ec9495b06fb81d30a04262`。于 `2026-11-28T08:16:07Z` 条件更新坚果云并完成精确回读：14 个 JAR 引用和 5 个内嵌规则已更新；白猫本地规则、moonci 嵌套播放器、斗鱼脚本、104 个点播源／6 个直播配置及 9 处解析器替换均保留。最终配置 SHA-256 为 `b7e188a4cfe694f9eaba3a7eaea7056640cdeec4e32c1c35ece52fcdfce62ab0`。

未改动的源不重复播放；JSON 逐项记录实际测试版本，并保留前轮结果于 `preUpstreamRuleObservations`，完整上游差异、主机检查、X20 数值与部署证据见 `upstreamRuleUpdate`。

## JAR 播放并发补充

当前源提交为 [53ca701458d5](https://github.com/zhhshss/CatVodSpider/commit/53ca701458d54f8092e4b3b6595cde57e96e5434)，JAR SHA-256：`cbd0c123b9df13f56fb8b3d5d2a0ffea8860ecc04d4c839f30196903516feb96`。坚果云于 `2026-11-28T12:37:12Z` 条件 PUT 返回 204，GET 回读字节一致；14 个 JAR 引用已更新，只有 mgnacg 设置 `mediaThreads: 3`，其余源默认 1。原 104 个点播源、6 个直播配置及 9 处新解析地址与参数全部保留。最新配置 SHA-256：`a8b1f4185cd713c5b3af53b033f3011b44d17f992b4efe6645682c4129b040d8`。

按用户要求，新增针对播放加载与缓冲的 `ext.mediaThreads`，范围 1–4，默认 1。只对明确取得的 MP4 直链启用 HTTP Range 并发；mgnacg 同时适配原站公开播放器取址，避免依赖 WebView 嗅探后无法接入 JAR 代理。HLS 仍走原来的读取路径。

- 首块 16 KiB、后续块 128 KiB；每个请求最多保留指定数量的块，最多两个活跃流，不缓存整部影片。
- 只有正确的 206、Content-Range、总长度、强 ETag 及未压缩响应才进入并发；不支持的服务器／Range 形式回到普通读取。后续块仍逐块验证，拒绝文件版本变化或错位数据。
- 首次响应后复用实际下载地址，使用独立 HTTP/1.1 连接，按顺序输出。宿主原生固定长度响应保持 Content-Length 与 Content-Range 一致。
- 关闭、取消和源销毁会取消队列及在途请求。所有已完成的 X20 检查在销毁后均记录到 0 个下载线程。

35 项主机检查通过，包含真实 HTTP、字节顺序、并发上限、完整／有限／开放范围、弱／缺失／变化 ETag、错误 Content-Range、截断、服务器忽略 Range、取消，以及宿主 NanoHTTPD 的完整和范围响应。Release 编译、R8、lintVital 和 JAR／MD5 检查通过。

X20 实测分为真实网站与受控样片。受控样片在 X20 本机提供 HTTP Range，再经过同一发布字节的 JAR 代理与生产 ExoPlayerEngine：向前拖至 20 秒、再回到 3 秒后恢复播放，116 个渲染帧，最后一次拖动后推进 4.07 秒。该样片只验证代理与拖动，不算作网站可用性证据；本机下载很快，250ms 采样未捕捉到短暂工作线程，但签名代理和 13 次 206 请求均已核对。早期远程转发样片受 ADB 隧道延迟影响，未出画面的结果也保留。

真实 mgnacg 首线仍明显缓冲。初始单路样本仅出现首帧；同一 HTTP/2 连接内并发也未改善。独立连接及固定长度响应版能记录到更多帧与自然推进，但不能把多次观察的差异视为单独线程数的性能基准。X20 对真实稀饭源的并发拖动因网络慢没有完成全部恢复，不能报告双向拖动成功；该源未在正式配置开启并发。

最终发布字节与源码提交、实际发布地址复测、候选前后配置哈希、精确回读及各次失败／缓冲数字统一保存在 `verification.json` 的 `mediaConcurrencyUpdate`。未逐个复测所有源、MPV、声音或长期播放。

| mgnacg 样本 | 首个视频采样 | 首帧后观察 | 自然推进 | 渲染帧 | 峰值下载线程 |
| --- | --- | --- | --- | --- | --- |
| 单路基线 | 31.8 秒 | 9.3 秒 | 0 秒 | 1 | 0 |
| 最终发布版，3 路 | 9.0 秒 | 26.8 秒 | 7.69 秒 | 185 | 3 |

两次观察的网络条件和总观察窗口不同；不报告固定加速倍数。最终发布样本首帧后仍约 18.3 秒处于缓冲，最长观测无新增帧间隔约 6.45 秒。AGE 的未启用并发 HLS 发布回归为 1080p、99 帧、推进 4.08 秒。

## Wex 官方入口更新与旧版本清理

按用户「都用新的，旧的删掉」更新 Wex 部分。官方配置最新日期为 2026-11-27，主 JAR MD5 为 `231915407140da7d4319728bf4266174`，SHA-256 为 `1688386d2e21a75f3f086b3680df7cbd7e838714804f3182b8bfd64e9fbb4531`。已有全局 `spider` 正是此版本；问题出在旧入口仍依赖 10 月 5 日 JAR，部分过时入口甚至已不在主 JAR 中。

- 删除 28 个旧 key（包括师兄的旧 key），加入 48 个官方当前 key；其中师兄迁到官方 key，原 `ext` 完全一致。最终 94 个官方 Wex 条目的非显示字段与上游一致。
- 移动、夸克、UC、天翼、百度、迅雷、123、115 等个人盘统一使用 `csp_MyPanGuard`。115 分享采用 `csp_FakeAi115ShareGuard`，配置中心使用 `csp_PanConfigGuard`；这些入口均继承主 JAR。
- 葵花、大鼻涕、新6V、旧全能 DIY、给力搜索、无名、双星、太狗等已不在官方列表中的入口被移除；旧 `ShortHG` 也不在主 JAR 中，随旧入口移除。最新版提供独立 `DiyVod` 等入口，不伪称所有旧站都有一对一替代。
- 14 处旧 JAR 覆盖全部移除，没有旧 `WexWoydpanGuard` 等 API。保留全部 30 个独立源及 6 个直播配置；9 处新解析地址、原路径与参数、背景／Logo 和其余顶层配置保持一致。

X20／Android 10 用生产 BaseLoader 从官方地址加载主 JAR，94 个条目对应的类检查全部通过。`MyPan` 返回移动、夸克、天翼、115、123、百度、迅雷、UC、光鸭等分类，配置中心首页也返回成功。这只验证迁移与入口可加载，未读取个人盘目录或账号凭据，未逐一播放 94 个源。

坚果云于 `2026-11-28T13:22:40Z` 使用 ETag 条件 PUT 返回 204，最终 GET 与候选字节一致。配置 SHA-256：`f640044cff4b597fdec8ffd40ceda1e801c67eaf5ab6a5dca0089a0c420f70f2`。更新前后配置和 ETag 仅保存在仓库外私有目录；删除／新增清单、类覆盖、设备数值与回读证据见 `verification.json` 的 `wexOfficialConfigurationUpdate`。旧 key 对应的历史记录／收藏可能需要重新选择新版入口。

## jsdmirror 分发地址更新

按用户最终要求，**Wex 主 JAR 与 niuma 原地址、MD5 及对应配置保持不变**。仅替换可验证的资源分发地址：共 23 处配置引用、10 个不同 URL；保留 124 个点播源、6 个直播配置、全部 API／规则／参数及 9 处新解析地址。

| 改动 | 范围 |
| --- | --- |
| 自建 JAR | Girigiri、Sorani、12 个 Kazumi 源共 14 处 `jar`，迁到固定提交的 `jar/cdn/custom_spider.txt` |
| 游戏直播 Python | `game_live.py` 改用 jsdmirror；3 个相对路径辅助模块一并验证 |
| 电视直播 Python | `yangshipin.py`、`cctv.py`、`gdtv.py`，以及前两者的 JSON 快照，共 5 处 |
| 仓库直播列表 | Kimentanm 的 `iptv.m3u`、develop202 的 `interface.txt`，共 2 处，继续跟随原分支 |
| 节目单 | 央视频配置的 `litiande03/epg@master/pl.xml.gz`，继续跟随原分支 |

现有哔哩 JS、壁纸及 Logo 已使用 jsdmirror，本轮核对字节后保留。所有可替换的 GitHub／旧代理配置 URL 均已迁移；第三方服务接口仍使用各自原站。

自建 `.jar` 原后缀在 jsdmirror 返回 403；发布同字节 `.txt` 副本后返回 200。副本位于 [CatVodSpider 固定提交](https://github.com/zhhshss/CatVodSpider/commit/61913bc26cbac8ccd632f0bb4d2112278758e563)，完整地址与来源见 `verification.json` 和该提交的 `jar/cdn/manifest.json`。原 JAR 与 CDN 副本均为 174,478 字节，MD5 `5a60ca9c5bd95d98ac26acea0982f368`，SHA-256 `cbd0c123b9df13f56fb8b3d5d2a0ffea8860ecc04d4c839f30196903516feb96`。仅改变交付地址与后缀，没有重编译或修改 JAR 内容。

主机对 16 项现有资源及依赖进行字节核对，其中 Python 对照固定提交 Git blob，动态列表／EPG 对照当时 GitHub 原文件；另核对 JAR 副本。JSON、Python 语法、M3U 及 gzip XML 格式检查通过。`cctv.json` 首次收到 Fastly TLS 路由 421，后续一次请求返回 200 且字节一致，未隐去该失败。

X20／Android 10 通过生产 BaseLoader 从新地址进行 5 项检查，均通过：

- 自建 JAR 首次下载前无该 URL 缓存，加载约 3.52 秒；设备下载文件 SHA-256 一致，Kazumi／Girigiri／Sorani 三个入口类存在，Kazumi 首页可解析。
- 游戏 Python 加载约 3.80 秒，3 个辅助模块初始化成功，返回斗鱼／虎牙／YY 三类。
- 央视频、央视、广东 Python 均能调用实际直播目录，分别返回 64、20、104 个频道。
- 设备网络记录有 8 次 jsdmirror HTTP 200，没有该域名的请求失败。测试只验证下载、加载、依赖与目录；本轮没有重新逐源播放，不据此宣称缓冲速度提升。

develop202 的 `interface.txt` 当时在上游和 CDN 都只有 M3U 文件头、0 个频道，分发地址更换不能修复上游空列表；没有把这一路标为可播放。分支地址仍会受 CDN 缓存影响，未进行成对吞吐量测速。

坚果云于 `2026-11-28T13:50:08Z` 条件 PUT 返回 204，最终 GET 与候选字节完全一致。配置 SHA-256：`d999ec01d1c873548ab64e2abba5e7059b4d3b5ce68255835b0c453760d6c7f1`。Wex 主 JAR、4 个 niuma 源对象、源顺序及其余字段的不变性已逐字段检查。原配置、认证信息及原始日志只保存在仓库外私有目录；公开数值见 `verification.json` 的 `jsdmirrorDeliveryUpdate`。本轮沿用已安装独立测试包，未重新确认 APK SHA-256，未构建或修改正式 TV 应用。

## 环境与覆盖限制

- 实体 X20，Android 10 / API29，ARM64；已安装的独立 `com.fongmi.android.tv.sourceprobe` 5.8.7。首次实测记录的 APK SHA-256 为 `ac27e95fa99398d93e4a34f0a79563f587ea02574726842ffffc36ad46635699`；后续 Wex 入口／CDN 轮未重新确认该哈希。该旧 APK 的精确构建提交未能确认，不能将结果标成当前工作区 HEAD 的验证。
- 使用应用真实 JarLoader、QuickJS、Chaquopy、ParseJob 和 ExoPlayerEngine，通过现有已签名 instrumentation 调用私有诊断 JAR。按配置 URL 加载迁移后的源；配置只注入测试进程内存。网络请求由 X20 发出，探针使用阿里 DoH 和 ECH。
- 现有测试入口的 `OK (1 test)` 只说明采集器完成；源成功与否按独立阶段结果判断。测试页面启动、多清晰度地址归一化、`jx=1` 和本地代理等早期探针问题已纠正后复测；排除校验失败和测试器异常，避免算成源故障。
- 逐源抽取首页／搜索和有效详情、剧集或频道；资源失败时有针对性地更换作品和线路，AGE／E站以第 2 条线路复测。Girigiri 另验分类、搜索分页与空结果，斗鱼包含 30 秒观察。未逐个播放所有房间、频道、线路和清晰度，也未验证登录、账号 Cookie、长时间稳定性、MPV 或声音输出。播放时静音。
- 没有修改正式应用或安装新版应用；本轮更新独立源源码、JAR、Python 和远端配置。当前 TV 工作区应用源码未构建，不声称通过 TV 应用 Gradle 单测、lint 或完整应用回归；源仓库的构建检查另列于上文。

## 画面证据

截图来自 X20 的真实视频 Surface，仅裁切黑边与系统导航区域。斗鱼截图是直连修复前的历史样本；最终发布版连续性以 JSON 的 `published-douyu` 及同房间对照帧计数为准。

| 斗鱼：修复前 Python 转发样本 | 虎牙：短时播放 |
| --- | --- |
| ![斗鱼实测](game-douyu.jpg) | ![虎牙实测](game-huya.jpg) |
