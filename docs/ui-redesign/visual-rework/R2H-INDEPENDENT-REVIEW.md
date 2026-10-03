# R2H 最终独立复核

日期：2026-10-03。本轮仅本地只读检查源码、diff、已下载截图和验收日志；未修改应用源码，未执行云端、ADB、构建或重新跑测试。

**结论：已检查范围未发现明确交付阻断问题。发现页无 TMDB key 时的空态、长按确认后保持可操作及 Back 返回有运行证据；发现页成功加载/失败后恢复到内容未实测，不能标为通过。其余下列画面只对实际看见的状态作视觉结论，不扩展为全测试矩阵通过。**

## Discover 修改复核

- `loadQuery.onError()` 与成功分支共同检查请求 generation 及当前 query。首屏失败调用 `replaceResultRows(..., false)`，移除 `discover_filter_progress`；翻页失败不清空已有结果。
- `showContentIfReady()` 不再用包含固定 hero/header/filter 骨架的 adapter size 判定成功。实际 hero、查询结果或推荐列表有内容即保留页面；推荐有海报但不满足 hero 背景筛选时也不会被错误隐藏。两路均完成且无实际内容才显示空态，仍在等待且无内容时显示进度。
- 空态使用现有 `JetStreamEmptyStateView.setText(int)`，简中、繁中和英文资源均存在。没有修改共享 `ProgressLayout`。
- DPAD_CENTER、ENTER、NUMPAD_ENTER 的首次 DOWN 记录按键，重复 DOWN 消费而不发起请求；匹配且未取消的 UP 才执行一次重试。无配对 UP 在空态仅消费；`onPause()` 清除按键记录。Back 不在拦截范围。
- 重试在同一 Activity 内取消本页旧请求，然后执行 `showProgress / loadFeatured / loadGenres / refreshResults(true)`。featured 和 genres 各有 generation，query 延用 `DiscoverRequestState` generation；旧回调不能减少当前 featured 计数、覆盖当前 genres 或替换新查询结果。Activity 销毁状态检查保留。
- 生产环境 `DiscoverApi.post()` 通过 `App.post()` 投递回调，因此本次重试启动过程不会被无 key 的失败回调同步打断并污染新一轮计数。

## 无 key 的实际含义与证据边界

主执行提供 R2H 构建检查结果：`tmdb_key_configured=false`。源码进一步确认 `DiscoverApi.buildTmdbUrl()` 在 key 为空时返回 null，`DiscoverQuery.buildUrl()` 也有同样前置条件。失败通过异步回调到达 Activity，**在发出 HTTP 请求之前结束**；更换 `tmdb_proxy_url` 不会绕过该条件。因此本轮本地 TMDB 代理 fixture 不能证明成功列表恢复。豆瓣推荐走独立路径；若其有真实内容，代码仍保留内容页面。

直接读取 [r2h-final-empty.log](r2h-final-empty.log)：

- 初始空态及 held OK 后均为 `DiscoverActivity`，同一个 `ActivityRecord{226219a ... t14}`。
- 初始 XML 没有具体 focused 节点；held OK 后焦点位于 `progressLayout`，未虚构为空态内部按钮焦点。Activity 级确认键处理不依赖该文字 View 可聚焦。
- Back 随后回到 `LiveActivity`，具体焦点在 video。
- 日志明确写明 `Retry-success not tested`。两张终态截图也不能证明一次重试网络批次的数量，或一定能捕捉到短暂 loading 帧。每次释放只发起一轮重试属于本次静态控制流结论。

## 直接看过的画面

| 证据 | 实际观察 | 限制 |
| --- | --- | --- |
| [发现最终空态](evidence/discover-empty-r2h.png)；本地另逐张查看 `discover-final-held-ok.png` | 中央图标、不可用及确认重试文案、返回提示清楚；未残留巨大空白列表骨架或 loading 行 | 成功态未实测；截图是稳定终态 |
| [直播频道](evidence/live-r2h.png) | 左侧类别、右侧 001–003 频道层次明确；001 风景频道细焦点框四边完整；视频仍可见 | 不据静态截图证明实际切台解码正确 |
| [直播换组后频道](evidence/live-group-r2h.png) | 显示 004–006 频道；004 旅行频道焦点完整，文字与类别不重叠 | 仅确认该换组后状态，没有推断全频道遍历通过 |
| [真实长标题详情](evidence/detail-long-r2f.png) | 标题两行后明确省略，没有覆盖元信息或动作按钮；360 集说明可见，选集与分段行排列完整；视频焦点框完整 | 首屏图不能证明第 360 集可达或全部长列表导航通过 |
| [播放器抽屉](evidence/player-drawer-r2f.png)；本地另查看第二份 `player-drawer-open.png` | 播放速度首行焦点框完整，画面比例、播放器、解码、片头片尾等行清楚；抽屉与背景播放内容层次可辨 | 抽屉遮住部分后层控制条属于覆盖状态；没有据此推断抽屉末项及所有设置功能通过 |

此前独立看过的 R2F 文件页与七色主题结果见 [R2F-VISUAL-REVIEW.md](R2F-VISUAL-REVIEW.md)；全屏返回观看按钮的静态时序结论见 [R2G-FOCUS-REVIEW.md](R2G-FOCUS-REVIEW.md)。这些既有检查不升级为未观察场景的通过结论。

## 剩余 diff 的范围检查

核对当前变更清单及重点代码：TV Material 依赖只加入 leanback；Home 边缘对齐、列表间距与宽度计算、搜索建议高度及结果边距、收藏空态、历史卡片高度、取消默认双重 Leanback 缩放、文件图标取消独立焦点圈、统一细描边与中性色焦点、Compose 真实按钮焦点与详情恢复均属于 UI 范围。未在本次所查改动中发现新增播放器实例、播放服务交接、Activity 全屏重建、删除用户数据或 fixture 注入生产逻辑。`VideoActivity` 的全屏返回新增部分仍仅为焦点来源记录与详情恢复调用。

这些变化中较大的 Compose 控件重构沿用前期视觉和运行证据，本轮没有把所有历史控件分支重新逐项运行。未列入上表的场景不能由本报告标为通过。

本地 `git diff --check` 通过。主执行另报告最终源码 122 文件 SHA 匹配、216 单测及 0 lint error；这些构建/测试由主执行完成，本轮没有重新执行，交付应保留对应原始记录。`TvFocusComponents.kt`、三语言 `visual_empty.xml` 与 `tv_visual.xml` 在检查时仍为 untracked，必须纳入最终源码归档/提交，避免仅提交 tracked diff 导致资源或类缺失。

## 最终保留项

1. **明确未测：发现页成功加载及从失败恢复到真实内容。** 当前 key 缺失是无法到达 TMDB 请求的直接前置条件，不应以 fixture 启动或空态截图代替成功证据，也不应为验收把虚构 key 或绕过逻辑混入生产源码。
2. 发现页无 key 重试会很快再次失败，现有文案提供可操作出口，但不能通过重试补齐缺失的构建配置。交付说明需保留此限制。
3. 所见范围无明确 blocker；最终报告应分开表达“无 key 空态与返回已验证”和“成功恢复未验证”。

归档补记（主执行）：上述检查时未跟踪的全部源码/资源已纳入提交 `298182efc`；全部改变的应用源码均在 R2H 构建清单内。
