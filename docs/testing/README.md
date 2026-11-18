# 验证报告

[如何构建和运行源／播放器测试](source-validation.md)描述操作方式；下表保存实际执行结果。报告内的成功、失败、未覆盖项均保留，不能把历史测试结果推广到所有设备或后续提交。

| 日期 | 报告 | 主要范围与限制 |
| --- | --- | --- |
| 2026-11-16 | [首页精选与模式选择](reports/2026-11-16-home-modes/README.md) | 39 项专项 JVM、24 项最终 Android 回归通过；保留初期焦点失败及修订证据，主包交付核验与 lint 限制见报告 |
| 2026-11-16 | [媒体库详情对齐与原生稳定性](reports/2026-11-16-library-alignment-stability/README.md) | 当前 12 项单测、2 项离线、4 项交互、十轮详情与两组证书压力有限回归通过；最终三张画面及完整下载校验通过，旧失败与硬件限制保留 |
| 2026-11-15 | [Compose TV 媒体库详情重做](reports/2026-11-15-library-detail-redesign/README.md) | 46 项 JVM、48 项 Android 与 3 项既有方法复测通过；最终主包模拟器掉帧及原生退出保留，性能与稳定性验收未通过 |
| 2026-11-15 | [Instabox Android 安装与界面](reports/2026-11-15-instabox-android/README.md) | 现有 ARM64 APK 经实例 ARM 转译安装，首页、我的、设置与方向键返回通过；未验证最新工作树或片源播放 |
| 2026-11-15 | [直播线路与首页触摸滚动](reports/2026-11-15-live-lines-touch-scroll/README.md) | 1170 项应用单测、12 项模拟器 UI 与 TV／手机 APK 通过；实体设备及真实直播流未覆盖 |
| 2026-11-15 | [媒体库与全屏播放器打磨](reports/2026-11-15-library-polish/README.md) | aiwex 实播、海报尺寸、全屏选集、换源与遥控器回归；结果和限制见报告 |
| 2026-11-14 | [aiwex 实际配置](reports/2026-11-14-aiwex-live/README.md) | 用户指定在线配置的导入、Guard、搜索与返回；实测范围和架构限制见报告 |
| 2026-11-14 | [媒体库搜索与界面](reports/2026-11-14-library-search/README.md) | 搜索后台解析／关联、卡片回收、主题与滚动；本轮 Android 和 lint 结果见报告 |
| 2026-11-15 | [Exo 声音与弹幕停顿](reports/2026-11-14-playback-stutter/README.md) | 最终1058项JUnit、36项Android及5组功能实播；三轮性能实验与撤回原因保留，MT5873实机未验证 |
| 2026-11-15 | [连续集数与社区跳过](reports/2026-11-14-community-skip/README.md) | fast / Groq / Workers AI 两端部署、69–70 token 系统提示、41 项中间层测试、69 项客户端 JVM 测试及真实回退；本次未构建新 APK 或电视实测 |
| 2026-11-01 | [社区片头片尾、播放与界面](reports/2026-11-01-community-skip/README.md) | 779 项单测、实际 Exo 区间跳转、99 源配置加载与设置截图；GHA 抽测源未返回内容，未验证本轮真实片源播放 |
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

- [2026-11-18 追更日历与媒体库追剧](reports/2026-11-18-airing-calendar/README.md)：当前静态检查通过，完整编译与设备回归待验证。
