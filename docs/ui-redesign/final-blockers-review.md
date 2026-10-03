# 最终两项阻断修复：只读源码复核

结论：**PASS（限定本轮源码静态复核）**。未发现这两项改动引入的确定回归或遗漏的必要 XML 主题刷新。此结论不替代构建、原崩溃组合路径及首页边界的运行回归，不能据此声明最终 UI 验收通过。

复核时间：2026-10-02 04:21 UTC。工作区 `/home/ubuntu/TV-ui-redesign`，HEAD `337bab43fee5c132c2ee2d81b55bb1f4718f4f37`。已读取根 `AGENTS.md`、`FINAL-BLOCKERS-FIX.md`、`QA-NEW-BLOCKERS.md`，并参考 `QA-RECOVERY-RESULTS.md` 的具体崩溃栈及原布局坐标。未修改源码、构建或操作模拟器；只新增本记录。

## 精确范围与 SHA-256

以下哈希来自本次读取的工作区文件，与 `FINAL-BLOCKERS-FIX.md` 一致；不是仅对 HEAD 的审查。

| 文件（相对仓库根） | SHA-256 |
| --- | --- |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/HomeActivity.java` | `1b231a4f7fe422e75fdbb20e584cff16ece7ccdbe94b85eca8878e5cd5d7d8cf` |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java` | `da2d805521fb38f5894d607bdbfa8f1a76138522f422bcd7b086caceb3100acf` |
| `app/src/leanback/java/com/fongmi/android/tv/ui/activity/VideoActivity.java` | `c047471be61bae3a62322e48d05878b961e39b60e3874da0169b536e9ae30f0c` |

本轮审查的改动为 Home 的统一 inset/keyline helper 及初始化、添加/移除 Hero 调用；Live/Video 的 `onThemeChanged()` 覆盖。此前首页焦点 generation 等改动只作为上下文核对，不据此次复核重新宣称它们的全部运行场景通过。其他未提交文件的变更不属于本轮批准范围。

## 首页：PASS

- `HomeActivity.java:548` 将无 Hero 时的 `paddingTop`、`windowAlignmentOffset` 同设为 80dp，有 Hero 时同设为 0；底部仍为 48dp。配合已禁用的 window/item 百分比对齐和 item offset=0，布局顶部与选中项对齐使用同一边界，直接修复原来的 padding=80dp、keyline=0 不一致。
- 初始化 `:219`、`setFeatured()` 的 `:545`、`removeFeatured()` 的 `:573` 三条路径均调用 helper；`removeFeatured()` 在寻找 Hero 前恢复无 Hero inset，因此“本来没有 Hero”的清理路径也覆盖。扫描未发现其他 recycler padding/window offset 写入绕过 helper。
- 空结果仍添加原 EmptyHome，内部标题 padding 未改；历史与推荐行仍共享同一外层 inset，选择源入口及原焦点 generation/cid 守卫未因 helper 改变。Hero 仍插在首行，0 keyline 的既有锚定逻辑保留。
- 运行边界仍须 QA 验证：无配置冷启及选中选择源后标题不与顶栏相交、进入设置返回、有历史但推荐空、空推荐与 Hero 双向刷新、普通 Hero/历史/推荐列表返回导航。仅源码不能证实最终像素坐标。

## 播放页主题事件：PASS

- `BaseActivity.java:128` 的 MAIN 线程事件处理先调用 `JetStreamThemeController.refresh()`，再虚调用 `onThemeChanged()`；Live `:171`、Video `:332` 的覆盖不调用父类 `recreate()`。停止在后台的 Activity 仍注册 EventBus，因此原组合路径里的后台 Live 同样受保护。
- `JetStreamTheme.kt:63` 的 snapshot token 和 `:213` 的 `remember(version)` 使仍存在的 Compose 主题读取新 palette。Live/Video 自己的 `onRefreshEvent()` 没有 THEME 分支，不会额外选频道、请求详情或发起播放。
- 这两个覆盖不重绑服务、不重新创建 binding/PlayerView、不设置播放器、也不移除或重装 LiveData 观察者。`PlaybackActivity` 的服务绑定位于初始化，解绑、释放 controller 和 forever observer 位于销毁；主题事件不再走这些生命周期路径，因而消除了此次已复现的“主题重建后 ON_START 回放 LiveData 早于服务连接”触发链。
- `JetStreamVodControlView` 的进度状态由 `remember(player)` 持有，轮询 effect 取决于 player/控制层可见性；播放按钮及抽屉焦点 effect 取决于控制层、焦点、分组等状态。`JetStreamChipRow` 的滚动 effect 取决于 focusedIndex，详情动作选择保存在现存 View 的 state 中。它们均未将 theme version/colorScheme 用作销毁、重新创建或 focus effect 的 key。此主题更新无直接进度或焦点重置副作用。
- 原 `onStop()` 按后台播放设置暂停、返回时按播放所有权 reclaim 的行为仍存在，不能把这种原有行为当成主题切换导致归零。跨页面已切换实际播放内容时，也不能仅以旧 Activity 存活推断其仍拥有服务。

## XML 配色遗漏核查：PASS

已追踪 `activity_live.xml`、`activity_video.xml`、control/widget/progress include、Live 的 group/channel/EPG item，以及相应 JetStream sidebar/overlay/page surfaces、标签、进度环和 Player 样式。

这些 XML View 中确有只在构造时解析颜色的控件，但当前 `JetStreamPalette.kt:197` 的 `presentationPalette()` 将 primary/onPrimary、primaryContainer、secondary 及其容器固定为中性色，surface/outline/background 也固定；随用户主题变化的是 tertiary 强调色。所查播放页现存 XML 控件及 Player 样式没有这一动态 tertiary 依赖，故没有证据要求为此重绑 adapter 或重建 View。详情内部 AndroidView 文本的 `update` 还会更新 text/link color。现有 wallpaper 可见性/画布刷新由独立事件处理，不依赖这里的 Activity 重建。

## 检查与运行证据边界

三个文件的 `git diff --check` 通过。未运行编译、单元测试或设备操作。最终验收仍应由 QA 对相同 SHA 的 APK 完成原 Home→Search→详情→Live→设置弹窗→多次换色路径，返回检查频道、选集、进度、焦点及无 CrashActivity。

已向 `final_blocker_regression` 提供无需改代码的对象保留证据方案：切色前后保存 `dumpsys activity` 客户端 `ACTIVITY` 对象 identity 及 View Hierarchy 中 `PlayerView{...}` identity，并结合 events log 的 create/destroy 记录。ActivityRecord token、截图或 UIAutomator 节点 ID 单独相同不能证明对象未重建；如设备不输出 PlayerView identity，必须将该项标记为间接证据。不要为取证 force-stop/relaunch。正常后台暂停需按设置解释。

系统自身重建、进程死亡或独立的服务断连 race 不在这次两项修复已解决或已验证的范围；当前审查没有据此提出额外阻断。
