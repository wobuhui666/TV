# CLAUDE.md

> 本文件是 leanback（TV）版 **JetStream 美化体系** 的项目须知，供在 `app/src/leanback/` 下工作时参考。
> 仓库通用规范（模块结构 / 构建 / 代码风格 / 测试 / 提交 / 安全）见下方导入的 AGENTS.md。

@AGENTS.md

## JetStream 主题色系统（leanback）

单一事实源：`app/src/leanback/.../ui/theme/JetStreamPalette.kt` —— 定义预设（星际蓝默认 / 跟随壁纸 / B站粉 / 翡翠绿 / 琥珀金 / 曜石紫 / 赤焰红），`colorScheme()` 供 Compose，`resolveColor()` / `resolveColorStateList()` 拦截 View 层所有 `R.color.jetstream_*` 取色。

- **View 层取色统一走** `View.jetStreamColor(colorRes)` / `jetStreamColorStateList(colorRes)`（定义在 `ui/custom/JetStreamViewBackgrounds.kt`）。**不要**再用 `ContextCompat.getColor` / `ResUtil.getColor` / Compose `colorResource` 直接取 jetstream 色。
- **切主题刷新**：`RefreshEvent.theme()` → leanback `BaseActivity.onThemeEvent` → `JetStreamThemeController.refresh()`（Compose 重组）+ `recreate()`（View 层）；`SettingActivity` 覆写 `onThemeChanged()` 免重建。
- **样式化 View 集中处**：`ui/custom/JetStreamPageSurfaces.kt`（~75 个类）、`JetStreamDialogSurfaces.kt`、`JetStreamPlaybackOverlayLayouts.kt`；Compose 岛用 `AbstractComposeView` + `JetStreamTheme {}` 包裹。
- **内容氛围**：`ui/theme/JetStreamAmbient.kt` 全局总线（海报 → backdrop + 主色），`JetStreamPageBackgroundView` 观察并平滑过渡；推送点在 `FeaturedVodPresenter`（首页 hero）与 `VideoActivity.setArtwork()`（详情页）。
- **品牌字体**：MiSans 子集（`res/font/misans_*.ttf`，仅 leanback 打包）；Compose 走 `JetStreamFontFamily`，View 层走 `TextView.applyJetStreamTypeface()`（各 `applyJetStream*Text` 漏斗末尾调用）。

> 新增任何会变色的 UI，一律经调色板取色，不要绕过 `jetStreamColor()`。

## Compose 自定义控件的焦点（重要坑）

leanback 的 JetStream Compose 控件（`JetStreamChipRow` / `JetStreamHomeNavView` / `JetStreamVodControlView` / `JetStreamVodDetailView` 等）继承 `AbstractComposeView`，保留宿主 View 的焦点入口，再通过 `FocusRequester` 交给对应的 TV Material 子控件。按钮自身处理遥控器确认、长按、语义与焦点动画。

- **状态判断**：子控件取得焦点后，宿主 `isFocused()` 会变 false，但 `hasFocus()` 仍为 true。判断整块 Compose 控件是否持有焦点应使用 `hasFocus()`，不能用宿主 `onFocusChanged(false)` 隐藏子控件的高亮。
- **记忆位置**：使用子控件 `Modifier.onFocusChanged` 更新当前 index/key；再次从 View 层进入时请求该子控件焦点。延迟请求和滚动后的请求须检查宿主仍持有焦点，避免抢回已经移开的焦点。
- **触屏**：TV Material 1.0 只处理遥控器按键；使用 `ui/components/TvTouchInput.kt` 的 `tvTouchInput` 给同一焦点目标补轻点与长按，在触发动作前请求对应子控件的实际焦点。不要再叠加 `clickable` / `focusable` 创建第二个焦点目标。

## 快速编译验证（leanback）

flavor 维度是 `["mode","abi"]`（mode: leanback/mobile，abi: arm64_v8a/armeabi_v7a），variant 名**必须带 abi 段**（`leanback` + `Arm64_v8a`），否则报 "task not found"。

```bash
# Kotlin 类型/语法快检（改 Compose/Kotlin 后）
./gradlew :app:compileLeanbackArm64_v8aDebugKotlin --console=plain --offline

# 改了 Java（连带触发 Kotlin，一次覆盖两者）
./gradlew :app:compileLeanbackArm64_v8aDebugJavaWithJavac --console=plain --offline
```

成功标志 `BUILD SUCCESSFUL`。若报 "Gradle build daemon disappeared"，是内存不足被 OOM 杀（VS Code server 会占数 GB），应降 `-Xmx` 或扩容机器，而非加大堆。
