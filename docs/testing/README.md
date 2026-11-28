# 验证报告

[如何构建和运行源／播放器测试](source-validation.md)描述操作方式；下表保存实际执行结果。报告内的成功、失败、未覆盖项均保留，不能把历史测试结果推广到所有设备或后续提交。

| 日期 | 报告 | 主要范围与限制 |
| --- | --- | --- |
| 2026-11-28 | [Trakt / Bangumi 手机扫码连接](reports/2026-11-28-bangumi-phone/README.md) | GHA：28 项网页、40 项专项与 1,389 项完整应用单测、24 项 API 34 检查及 Preview 构建通过；含 Trakt 官网授权／手机 Client ID，真实账号未覆盖 |
| 2026-11-28 | [WEX 自建源迁移与 X20 实测](reports/2026-11-28-wex-x20/README.md) | 16 个点播源、3 个电视直播源；Kazumi Android 10／API 9 与上游同步、MP4 并发及双向拖动、斗鱼直连对照、哔哩 4K 抽测及直播帧证据；18 处引用迁至 zhhshss、9 处解析地址替换；Wex 官方入口同步、旧 JAR 清理和 MyPan 分类验证；23 处 jsdmirror 引用、自建 JAR 改后缀与 X20 加载验证；不覆盖全部频道与 MPV |
| 2026-11-28 | [网页日志与图标对齐](reports/2026-11-28-web-logs/README.md) | GHA：18 项网页、10 项专项与 1,373 项完整应用单测、17 项 API34 回归及 Preview 构建通过；含真实 logcat → HTTP |
| 2026-11-24 | [日历账号、栏目筛选、媒体库提速与按钮居中合并发布](reports/2026-11-24-integrated-release/README.md) | 合并后 1656 项单测、API 24／34 日历与追剧各 23 项、API 34 首页／媒体库 88 项（首次 87，重跑通过）及推送页通过；按钮居中有修复前后截图；未在实体电视安装，海报墙界面回归未重跑 |
| 2026-11-22 | [媒体库选集与选源性能](reports/2026-11-22-media-library-performance/README.md) | 本机 274 项专项 JVM 与微基准（选源 17.1→3.9 ms、标题匹配 38.2→0.68 ms）；GitHub Actions 单测 1564 项、API 34 模拟器 88/88、lint 无新增错误，对照运行确认修改前每个片源被搜索两次；实体电视与真实片源未测 |
| 2026-11-22 | [首页栏目完整筛选](reports/2026-11-22-home-wall-filters/README.md) | 25 次真实 TMDB 请求通过；GHA 上 TV 变体 1,352 项单测、Debug／androidTest APK、lint 无新增错误、API 34 模拟器 11 项海报墙界面通过；未在实体设备安装 |
| 2026-11-22 | [追更日历账号与浅色模式](reports/2026-11-22-calendar-accounts-light/README.md) | 主题刷新信号宿主、账号对话框浅色／深色像素与焦点；API 24、34 各 23 项界面通过，API 24 焦点偶发丢失未定位；Bangumi 真实账号脚本对照（导入、一次追剧与搁置写入），修正写入请求被 415 拒绝等三处；应用内真实连接与 Trakt 未实测 |
| 2026-11-21 | [本地功能统一发布](reports/2026-11-21-all-local-release/README.md) | 1557 项全量 JVM、API24／34 日历追剧、API34 首页／媒体库与真实 Parcel 交接通过；lint 新增 Error 0、Warning 3，真实片源和实体电视范围保留 |
| 2026-11-21 | [桌面横幅与源弹窗联合发布](reports/2026-11-21-combined-release/README.md) | 同提交 1481 项单测、API24／34 横幅 4 项、API34 两尺寸 52 项回归通过；lint 零新增问题，实体电视与真实源限制保留 |
| 2026-11-19 | [aiwex 配置弹窗回归](reports/2026-11-19-aiwex-config-dialog/README.md) | 最终同代码提交：真实 Guard 双入口弹出与返回键关闭、52 项 Android 与 13 项集中 JVM 回归通过；仅安装独立 debug 包 |
| 2026-11-18 | [TV 桌面横幅](reports/2026-11-18-launcher-banner/README.md) | API24/API34 标准桌面接口各2项、1481项单测及lint零新增问题；灵动桌面版本与实体电视待实测 |
| 2026-11-17 | [统一发布验证](reports/2026-11-17-unified-release/README.md) | 最终同提交 app 1190 与共享模块、API31 89 项、API34 44 次执行通过；lint 零新增 Error，实体电视与真实片源限制保留 |
| 2026-11-16 | [首页精选与模式选择](reports/2026-11-16-home-modes/README.md) | 39 项专项 JVM、23 项首页／模式／触摸与 1 项模式兼容回归通过；真实首页、下载与源扩展 ABI、lint 限制见报告 |
| 2026-11-16 | [媒体库详情对齐与原生稳定性](reports/2026-11-16-library-alignment-stability/README.md) | 详情/TLS 阶段 12 项单测、2 项离线、4 项交互、10 次详情及两组各 34 次连接／1536 个副本压力通过；分别运行于三个进程，旧失败和实体电视限制保留 |
| 2026-11-16 | [#119 MPV 重播、刷新与字幕返回](reports/2026-11-16-issue119-mpv/README.md) | 重播、连续刷新、EOF、Exo→MPV 与字幕 Surface 重建；记录最终提交及设备结果，保留历史失败和 MT5873／真实 Vulkan 的覆盖限制 |
| 2026-11-15 | [代理配置解析](reports/2026-11-15-proxy-parsing/README.md) | 基线 3 项失败、修复后 4 项 JVM 回归通过；Android 初始化与在线实测未完成 |
| 2026-11-15 | [Exo 声音与弹幕停顿](reports/2026-11-14-playback-stutter/README.md) | 最终1058项JUnit、36项Android及5组功能实播；三轮性能实验与撤回原因保留，MT5873实机未验证 |
| 2026-11-15 | [Issue #119 界面修复](reports/2026-11-15-issue119-ui/README.md) | 直播完整线路列表、TV 包触摸浏览位置与源配置弹窗；两种尺寸的 Android 窗口、焦点和表单回归 |
| 2026-11-15 | [Compose TV 媒体库详情重做](reports/2026-11-15-library-detail-redesign/README.md) | 46 项 JVM、48 项 Android 与 3 项既有方法复测通过；最终主包模拟器掉帧及原生退出保留，性能与稳定性验收未通过 |
| 2026-11-15 | [媒体库与全屏播放器打磨](reports/2026-11-15-library-polish/README.md) | aiwex 实播、海报尺寸、全屏选集、换源与遥控器回归；结果和限制见报告 |
| 2026-11-14 | [媒体库搜索与界面](reports/2026-11-14-library-search/README.md) | 搜索后台解析／关联、卡片回收、主题与滚动；本轮 Android 和 lint 结果见报告 |
| 2026-11-20 | [追更日历悬浮日期栏](reports/2026-11-20-calendar-floating-dates/README.md) | 43 项专项 JVM、API 24／34 各 16 项交互与 18 张截图通过；同行布局及焦点反色验证，完整 lint 仍有 121 项既有错误 |
| 2026-11-19 | [媒体库片源兼容性](reports/2026-11-19-library-source-compatibility/README.md) | 100源搜索／媒体对照、瓜子剧集与电影正片、126项回归通过；最终画质APK现场复测受下载限制 |
| 2026-11-19 | [媒体库首页大卡与新剧数据](reports/2026-11-19-library-home/README.md) | 首版 60 项变体检查；后续移除筛选／调整海报顺序的 20 项检查、Java 编译及新包校验通过；Android 界面／Preview／lint 未执行 |
| 2026-11-19 | [追更日历界面重排](reports/2026-11-19-calendar-redesign/README.md) | 43 项专项 JVM、API 24／34 各 16 项交互与 18 张截图通过；完整 lint 121 项既有错误，日历范围无新增 |
| 2026-11-18 | [追更日历与媒体库追剧](reports/2026-11-18-airing-calendar/README.md) | 32 项专项 JUnit、API 24／34 各 12 项交互与 9 张截图通过；完整 lint 仍有 121 项工作区错误，改动范围无新增 |
| 2026-11-16 | [首页精选与模式选择](reports/2026-11-16-home-modes/README.md) | 39 项专项 JVM、24 项最终 Android 回归通过；保留初期焦点失败及修订证据，主包交付核验与 lint 限制见报告 |
| 2026-11-16 | [媒体库详情对齐与原生稳定性](reports/2026-11-16-library-alignment-stability/README.md) | 当前 12 项单测、2 项离线、4 项交互、十轮详情与两组证书压力有限回归通过；最终三张画面及完整下载校验通过，旧失败与硬件限制保留 |
| 2026-11-15 | [Instabox Android 安装与界面](reports/2026-11-15-instabox-android/README.md) | 现有 ARM64 APK 经实例 ARM 转译安装，首页、我的、设置与方向键返回通过；未验证最新工作树或片源播放 |
| 2026-11-15 | [直播线路与首页触摸滚动](reports/2026-11-15-live-lines-touch-scroll/README.md) | 1170 项应用单测、12 项模拟器 UI 与 TV／手机 APK 通过；实体设备及真实直播流未覆盖 |
| 2026-11-14 | [aiwex 实际配置](reports/2026-11-14-aiwex-live/README.md) | 用户指定在线配置的导入、Guard、搜索与返回；实测范围和架构限制见报告 |
| 2026-11-21 | [连续集数与社区跳过](reports/2026-11-14-community-skip/README.md) | Vercel 东京与两端共享缓存部署、91 项中间层测试及真实搜索／图片缓存命中；此前客户端验证见报告，本次未构建 APK |
| 2026-11-01 | [社区片头片尾、播放与界面](reports/2026-11-01-community-skip/README.md) | 779 项单测、实际 Exo 区间跳转、99 源配置加载与设置截图；GHA 抽测源未返回内容，未验证本轮真实片源播放 |
| 2026-11-14 | [连续集数与社区跳过](reports/2026-11-14-community-skip/README.md) | Groq / Workers AI 两端部署、29 项历史中间层测试、82 项独立 PR JVM 测试及真实季集匹配；未构建新 APK 或电视实测 |
| 2026-11-01 | [海报墙、推荐筛选、字幕与表单](reports/2026-11-01-tv-polish/README.md) | 实际 Android 筛选、字幕标签、长表单滚动提示；示例内容，不代表 ARM 实机性能 |
| 2026-10-31 | [流畅度与稳定性](reports/2026-10-31-responsiveness/README.md) | 离屏释放、晚到分页、解析取消与帧时间对比；纯 ARM 隔离包单独验证受保护来源 |
| 2026-10-06 | [同步、Kazumi 与最终安装](reports/2026-10-06-sync-kazumi/README.md) | Syncplay 实际 Exo、WebDAV Room 与坚果云并发、希番双引擎双集、WEX 和最终发布；保留双真机及外站限制 |
| 2026-10-05 | [ECH 初版](reports/2026-10-05-ech/README.md) | 发布自身 ECH 配置的目标、共享客户端及认证代理；不包含后来的 CF 配置补全 |
| 2026-10-05 | [红果与 TV 播放](reports/2026-10-05-hongguo/README.md) | 源功能、Exo/MPV、字幕、预加载、暂停弹幕；长查询和模型下载失败仍明确保留 |
| 2026-10-06 | [Cloudflare ECH 补全](reports/2026-10-06-cloudflare-ech/README.md) | 目标没有自身 ECH 配置时的生产路径；区分 DNS 配置获取和真实加密握手 |
| 2026-10-06 | [DoH 崩溃与 WEX](reports/2026-10-06-doh/README.md) | 主线程切换崩溃、连接释放、旧源迁移；部分源故障没有恢复 |
| 2026-10-06 | [新旧源接口兼容](reports/2026-10-06-spider-compatibility/README.md) | Java/QuickJS/Python，含后续 CI5 旧源回归；Sorani 设备 TCP 超时、腾讯分类为空等限制保留 |
| 2026-10-06 | [源发布与 CDN](reports/2026-10-06-ciallo-publishing/README.md) | 新源加入 WEX、条件写入及 JAR 下载验证；不代表站点播放网络恢复 |
| 2026-10-06 | [Girigiri 加载与搜索](reports/2026-10-06-girigiri-loading/README.md) | 同设备初始化、缓存与分页测量；仅一轮样本，本轮未重新验证媒体解码 |
| 2026-10-06 | [Logo 轮廓](../ui-redesign/tmdb-logo/README.md) | 生产算法离线渲染、完整构建与安装；效果图不是设备详情页截图 |

UI 历史验收见 [UI 改版](../ui-redesign/README.md)，复杂字幕等内核证据见 [播放器同步说明](../player-core-sync.md)。Cloudflare 优选和触屏研究位于 [研究目录](../research/README.md)，不计为完整验收通过。

每份导出报告的 JSON、文本证据与所需图片位于同目录。原 `SHA256SUMS` 是历史原件清单，可能包含未上传的 APK；整理后文件的哈希以 [导入清单](../archive/local-notes-20261006/IMPORTS.json)为准。私有配置、访问票据及原始私有日志没有复制进来。
