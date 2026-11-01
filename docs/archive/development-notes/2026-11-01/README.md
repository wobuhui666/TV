# 已完成开发笔记与旧接口参考

整理日期：2026-11-01。内容来自本仓库既有笔记和本地开发临时目录，只保留必要参考，不属于当前应用源码、构建输入或待办清单。

## 计划与会话

| 资料 | 原路径与状态 |
| --- | --- |
| [播放规格胶囊计划](playback-specs-capsule-plan.md) | 原 `.claude/plan/playback-specs-capsule.md`；已在 PR #88 完成，当前使用规则见 [播放规格胶囊说明](../../../playback-specs-capsule.md) |
| [旧 Claude 会话笔记](claude-session-note.md) | 原根目录 `.claude-session-note.md`；记录旧 fork、分支与当时待办，仅供追溯，不作为当前指令 |

当前仓库的 [AGENTS.md](../../../../AGENTS.md) 与 [CLAUDE.md](../../../../CLAUDE.md) 继续保留在根目录，适用于当前开发。

## 最小源码参考

[SOURCEFILES.json](SOURCEFILES.json)记录 16 个参考文件的原目录、大小与 SHA-256，以及归档副本的哈希。文件统一增加 `.reference` 后缀，供查看差异，不能作为当前可直接编译的代码或可直接应用的补丁。

| 原临时目录 | 保存内容 | 限制 |
| --- | --- | --- |
| `catvod-new-sources` | 旧 GL Python 转换、Sorani 及 Host 检查参考 | 独立来源代码，不代表本应用捆绑内容或站点服务可用 |
| `tv-new-spider-api` | 旧 QuickJS JNI、Python Gradle 插件和选择接口参考 | 用于追溯兼容性调查；没有复制私有配置、抓取响应或安装包 |
| `tv-pr747-review` | 旧上游 HTTP、播放器、解析和轨道实现 | 与当前内核结构不同，不能直接覆盖现有实现 |
| `tv-fongmi-review-20261025-notes` | 旧 artwork、集序和目录筛选补丁以及检查结果 | 保留当时评估材料，当前功能状态以 [README](../../../../README.md) 和源码为准 |

旧接口兼容的公开验证见 [接口兼容性说明](../../../spider-api-compatibility.md) 和 [2026-10-06 报告](../../../testing/reports/2026-10-06-spider-compatibility/README.md)。来自上游和第三方的参考保留原作者标记，许可证和接口归属不因归档改变；本项目许可证见 [LICENSE.md](../../../../LICENSE.md)。

未导入下载 JAR/APK、依赖安装目录、HTML/JS 抓取 chunks、私有配置、Cookie、播放响应或原始私有日志。可重建临时副本在资料保留后清理，具体去向见 [开发目录维护](../../../workspace-maintenance.md)。
