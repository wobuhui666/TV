# 文档导航

这里汇总使用说明、实现约束、验证报告和历史记录。实测结论以各报告标注的日期、提交、设备与限制为准；历史交付记录不等同于当前版本全部通过。

## 使用与配置

| 主题 | 文档 |
| --- | --- |
| 配置字段与示例 | [CONFIG](CONFIG.md) |
| 原生海报墙、搜索过滤与海报选源 | [使用说明](native-discovery.md) |
| 诊断、缓存与播放工具 | [使用说明](practical-improvements.md) |
| OTA 更新与云控 | [使用说明](ota-updates.md) |
| 直播与节目单 | [LIVE](LIVE.md) |
| 直播时移与回看 | [源配置、播放与控制](live-timeshift.md) |
| 本地服务与接口 | [LOCAL](LOCAL.md) |
| 爬虫接口 | [SPIDER](SPIDER.md) · [新旧接口兼容](spider-api-compatibility.md) |
| WASM 来源 | [模块调用、内存、导入函数及资源生命周期](spider-wasm.md) |
| 源登录与交互表单 | [弹窗接口与任务生命周期](spider-dialogs.md) |
| 社区片头片尾跳过 | [三服务接口、匹配与手动优先](community-skip.md) |
| 播放规格胶囊 | [展示内容、动效与元数据规则](playback-specs-capsule.md) |
| TV 界面 | [UI 改版与验收](ui-redesign/README.md) |
| 一起看 | [Syncplay 服务端、房间、TLS 与同步行为](syncplay.md) |
| 多设备同步 | [WebDAV 收藏、观看进度与冲突处理](webdav-sync.md) |
| 本地构建环境 | [工具链与构建说明](../LOCAL_BUILD_ENV.md) |
| 宣传素材 | [视频、封面与来源](media/README.md) |

## 实现与验证

| 主题 | 从这里开始 |
| --- | --- |
| 播放器、字幕与预加载 | [内核同步说明](player-core-sync.md) |
| Exo 声音与弹幕停顿 | [弹幕查询优化与诊断验证](testing/reports/2026-11-14-playback-stutter/README.md) |
| ECH 与 DoH | [当前行为和验证边界](ech-validation.md) |
| 源、播放器及设备回归 | [验证报告目录](testing/README.md) · [测试运行方法](testing/source-validation.md) |
| 近期功能与界面 | [社区跳过与播放回归](testing/reports/2026-11-01-community-skip/README.md) · [海报墙、字幕和表单](testing/reports/2026-11-01-tv-polish/README.md) |
| 流畅度和稳定性 | [真实配置、生命周期与对比结果](testing/reports/2026-10-31-responsiveness/README.md) |
| TMDB 透明 Logo 轮廓 | [实现、预览和安装验证](ui-redesign/tmdb-logo/README.md) |
| Girigiri 加载优化 | [优化前后同设备测量](testing/reports/2026-10-06-girigiri-loading/README.md) |
| 两个动漫源的发布与 CDN | [发布和 WEX 更新记录](testing/reports/2026-10-06-ciallo-publishing/README.md) |

## 研究与历史

- [研究目录](research/README.md)：Cloudflare 优选路由设计、触屏静态审计；与已完成的设备验证分开记录。
- [构建耗时核对](process/build-time-audit-20261006.md)：对应指定旧对话快照，不是持续更新的性能指标。
- [历史记录与来源清单](archive/local-notes-20261006/README.md)：旧 UI 方案、接续记录、调查与重复文档的去向。
- [归档索引](archive/README.md)：旧 AISkip 实现、已完成开发计划及历史源码参考。
- [FongMi 5.8.5 历史评估](upstream-5.8.5-review.md)：保留当时建议，并补充实际合入状态；不是待办清单。
- [FongMi 5.8.7 稳定性适配](upstream-5.8.7-review.md)：2026-11-06 的播放、投屏、选集和目录筛选同步范围及验证入口。
- [完整备份分支](https://github.com/wobuhui666/TV/tree/docs/local-notes-backup-20261006)：保留早先未整理快照，供追溯原路径。

本次归档覆盖已有备份、五个本机导出实测目录的更新，以及 `/data` 下五个 TV 相关目录。仅收录文档、公开验证摘要和经过查看的说明图片；APK、私有配置、凭据及原始私有日志不放入文档目录。来源、读取时间、脱敏记录、校验值和未收录链接见 [IMPORTS.json](archive/local-notes-20261006/IMPORTS.json)。

2026-11-01 的新增整理补充最近的功能说明、公开验证与截图，已完成的会话笔记移入历史目录，本地重复产物按 [开发目录维护](workspace-maintenance.md) 清理。

## 维护说明

`docs/` 不再整体忽略，新增文档可正常出现在 `git status` 中，并通过 `git add -- docs/明确的文件路径` 纳入版本控制。新增或移动文档时同步更新本索引及相关链接；提交前逐项检查内容，只保留公开说明、验证摘要和必要图片，设备配置、凭据及原始私有日志留在仓库外。
