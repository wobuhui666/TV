# PASS — 设置复合行焦点静态复核

审查时间：2026-10-02 02:08:46 UTC
审查结论：PASS（仅源码静态审查；编译和真实 D-pad 回归仍待 QA）。未发现阻断问题。

## 审查对象与 SHA-256

- `app/src/leanback/java/com/fongmi/android/tv/ui/custom/JetStreamSettingView.kt`
  - `dfdc063d7532e95d8f57e943161397806ff1e67587a0f089cc78ad66bfb4a514`
- `app/src/leanback/java/com/fongmi/android/tv/ui/activity/SettingActivity.java`
  - `c465606103a24ddc4d05196dbef9e4899d1e1baaa84d3fddaaf0eaebb89ee4f5`
- `gradle/libs.versions.toml`
  - `f17c144c7bcd54932bb0484de80989f2dc9b4fda7a5c0cdbf4ca9f2a8aad7543`

## 审查范围与依据

- `JetStreamSettingView.kt:413-423`：有 actions 的外层 Row 不再安装 combinedClickable；没有产生可聚焦父目标，首行 FocusRequester 保留。
- `JetStreamSettingView.kt:403-407, 426-440`：主操作移入左侧 Column，click 和 long-click 仍分别传原 row.key。VOD/LIVE/WALL 的配置和编辑入口保留；SettingActivity.java:285-287、357-359 的处理分支未改动。
- `JetStreamSettingView.kt:431`：theme_color 左侧标签排除主操作 Modifier，因此不可点击、不可聚焦；主题按钮仍走 theme_color:<value>，由 SettingActivity.java:279-282 解析。
- `JetStreamSettingView.kt:474-480, 535-547`：ActionChip 保留自己的 combinedClickable 与 action.key；其与左侧主操作成为同层可达焦点。horizontalScroll 保留，不拦截方向键，不引入新的可聚焦父容器。
- `JetStreamSettingView.kt:417-419, 432-437, 506-527`：复合行外层保持静态底色，主操作区域独立缩放并使用对应焦点背景/文字色；子按钮独立显示焦点。删除 rowFocused 颜色分支，避免左侧聚焦时未聚焦按钮继承深色文字但缺少相应浅色底。普通行/开关继续使用整行焦点样式。
- 父级 combinedClickable 的点击及合并语义从复合容器移除；主操作和子按钮各自保留点击语义，主题标签不再暴露无效点击。首行 FocusRequester 仍可遍历到左侧主操作这个首个可聚焦后代。
- 已按 BOM 2024.10.00 核对 Compose UI/Foundation 1.7.4 官方 sources：TwoDimensionalFocusSearch 仅搜索已聚焦项的兄弟节点；FocusRequester 遍历后代焦点；Scroll/ScrollingContainer 不创建额外的可聚焦父节点。本修复正面消除了原缺陷。
- `git diff --check -- app/src/leanback/java/com/fongmi/android/tv/ui/custom/JetStreamSettingView.kt` 通过。

## 仍需 QA 验证

1. 主题行从上下进入后焦点为实际色彩按钮，左右遍历全部五项（含横向滚动），OK 更新配色/勾选/当前值，重入与冷启保持选择。
2. 左右边界、上下退出和 Back 正常，无焦点陷阱；聚焦的具体操作具有清晰焦点样式。
3. VOD/LIVE/WALL 左侧主操作 click/long-click，以及右侧按钮可通过 D-pad 到达并执行；普通值行与开关无回归。
4. 进入设置/切换分区的首行焦点恢复仍正常；检查实际语义树已出现独立操作节点。
5. QA 所测文件必须匹配上述生产文件 SHA；本 PASS 不替代编译或运行通过结论。

本审查未修改生产源码、未构建、未操作模拟器；仅新建本交接文档。
