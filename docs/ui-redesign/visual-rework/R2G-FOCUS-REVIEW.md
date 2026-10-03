# R2G 全屏返回焦点静态复核

日期：2026-10-03。仅审查本次新增的 `restoreDetailActionFocus` 捕获/消费逻辑及 `restoreActionFocus()`，并沿实际调用链读取周边实现；未修改应用源码，未执行云端、ADB、构建或运行测试。工作区 diff 中更早的详情按钮重构不作为本次新增改动重复验收。

**结论：未发现当前观看按钮 → 全屏 → 返回路径的阻断性静态问题。新补丁能针对已观察到的 Compose 宿主焦点落到导演文字的问题，显式恢复已记住的动作按钮。运行效果仍须以 R2G 实机 XML/画面验证，不能由静态检查替代。**

## 恢复时序

1. `VideoActivity.enterFullscreen()` 先保存 `getCurrentFocus()` 及 `mBinding.detail.hasFocus()`，之后才将焦点交给视频、隐藏详情。这避免在详情已失焦后错误记录来源。
2. `exitFullscreen()` 先恢复窗口布局、清除全屏状态，再经 `updateFullscreenViews()` 同步将 detail 和 scroll 设为 `VISIBLE`。`updateFocus()` 在此只配置方向导航目标，没有发起另一个竞争焦点请求。
3. 随后 `restoreActionFocus()` 检查详情可见、启用且 `requestFocus()` 成功；归一化选中动作并增加 `entryFocusToken`。因此，即使 Android 将实际焦点先交给内部 Compose 宿主/导演 TextView，外层自定义 View 的 `onFocusChanged()` 没有再次触发，显式 token 仍会触发恢复。
4. `LaunchedEffect(entryFocusToken)` 在 Compose 应用阶段后使用 `remember` 保存的 `FocusRequester` 请求 `selectedAction`。四个 requester 与固定四动作一一对应；`normalizeSelectedAction()` 保证索引合法并避开禁用动作，WATCH 始终启用。效果执行前再次检查 `hasFocus()`，详情已失焦时不继续抢回焦点。
5. Activity 的恢复标记随即清零，不会沿用到下一次全屏会话。`applyWindowVideoStyle()` 的延后任务只执行视频 outline/invalidate，不会覆盖恢复焦点；`hideInfo()` 未新增焦点恢复操作。

仅 `GONE/VISIBLE` 切换不会触发当前 `DisposeOnDetachedFromWindow` 的销毁策略，所以此路径保留同一详情 Composition、`selectedAction` 与 requester。补丁没有通过重建详情获得焦点。

## 来源区分与元信息

- **观看按钮：** 其 `onWatch()` → `onVideo()` → `enterFullscreen()` 调用同步发生，动作仍持有焦点时记录 detail=true，退出使用新路径。按钮 `onFocusChanged` 已记录 WATCH 索引，因此能避开 Android 宿主默认找第一个可聚焦元信息的行为。
- **视频窗口：** 焦点位于 `mBinding.video`，detail=false；退出继续原有 `getFocus1()` 与视频 fallback 路径。
- **选集：** `shouldEnterFullscreen()` 在点击已选剧集时进入全屏，剧集控件位于独立 scroll 区域，不是 detail 子树，detail=false；不会被本补丁强制移到观看按钮。原选集恢复机制本身不在此次改动内。
- **导演/演员等链接：** `ClickableInfoText` 保留原 `TextView`/`LinkMovementMethod`，此次未增加链接事件监听、未改链接动作，也未在普通返回链接搜索页时调用 `restoreActionFocus()`。现有普通链接路径不会被新全屏返回分支直接截获。
- **边界说明：** `detail.hasFocus()` 判定的是整个详情区域，不是“仅动作按钮持焦”。若未来加入从元信息持焦状态自动进入全屏的路径，该状态也会恢复到旧 `selectedAction`，而非元信息链接。本次现有入口主要是观看按钮、视频窗口、选集及初始化投屏检查，未发现普通元信息点击触发这一情况。

## 服务与播放所有权

新增代码只有一个 Activity 布尔值、一次详情方法调用，以及 View 内 focus/token 操作。没有新增 Intent、Activity 创建/结束、服务 bind/unbind、控制器交接、播放器创建/释放或播放源请求。原 `enterFullscreen/exitFullscreen` 的同 Activity、同播放器布局切换方式保持不变；原渲染模式切换调用也没有在此次补丁中改变。

## 仍需运行确认的具体断言

- R2G 从“全屏观看”进入后返回：foreground 仍是同一 `VideoActivity`；XML 的具体 focused 节点应为观看动作而非仅 Compose 宿主或导演文字；画面上的按钮焦点框完整。
- 返回后立即按一次方向键，验证没有第二个延后恢复把焦点拉回；再重复进出一次，确认标记与 token 不残留。
- 从当前选中剧集进入并返回，检查原剧集/列表焦点；从视频窗口进入并返回，检查窗口焦点。这两条应继续走原恢复分支。
- 在非全屏详情点击导演/演员链接并返回，确认既有链接行为不受影响。

`restoreActionFocus()` 在 `requestFocus()` 失败时直接返回，没有新增延后重试。按当前先恢复 VISIBLE 的调用顺序未发现必然失败条件，且当次视频仍是可用焦点；但不能仅凭静态检查把布局首帧成功率认定为已验证。

审查源码：[VideoActivity.java](../../../app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java#L1006)、[JetStreamVodDetailView.kt](../../../app/src/leanback/java/com/fongmi/android/tv/ui/custom/JetStreamVodDetailView.kt#L133)。
