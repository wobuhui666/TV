# 播放规格胶囊实施计划

日期：2026-10-26。类型：执行实施；视觉以用户提供的截图与展开、停留、收起时序为准。

## 目标

在 TV 点播和直播真正开始播放时，于视频左上角短暂展示原生规格胶囊。复用应用 Logo，显示当前视频分辨率、动态范围、编码与位深，以及音频编码和声道。若片源明确提供内容分级，可追加小标签；未知信息省略，评分不作分级。

胶囊高度约 48dp，深色半透明背景、细描边与局部高斯模糊。完整展示 3 秒，先隐藏文字约 150ms，再向左收成圆形，最后 Logo 缩放淡出。动画只改变绘制属性，固定视图布局，全部内容受圆角裁剪。

## 分工和步骤

1. **规格数据**：共享纯数据模型与格式化规则；按需读取 Exo/MPV 当前选中轨道，保留未知值，不增加播放循环或网络查询。验证分辨率、HDR、编码、位深、声道和未知回退。
2. **原生绘制**：TV 自定义 View 绘制背景、Logo、两列双行信息、细分隔线与可选分级。圆形入场、宽度展开、文字渐显并轻移、驻留、文字淡出、收回、Logo 消失。尊重系统动画关闭；不接收焦点或触摸。
3. **背景采样**：从 PlayerView 当前视频表面获取有界低分辨率像素，只模糊胶囊背景，后台计算，限制采样次数和一个在途请求。API 26+ SurfaceView 可用局部 PixelCopy；API 24/25 或 TextureView 先小尺寸采样再裁剪。无有效图像时用深色半透明背景；不切换渲染器。
4. **播放器接入**：新播放请求清理上一轮状态；`onPlayingChanged(true)` 启动一次；轨道/尺寸事件用于补齐待展示数据。缓冲恢复、暂停恢复、快进后 READY 不重复播放动效。退出页面、失败、停止、全屏切换与新源请求取消旧动画、计时及采样。
5. **验证与证据**：单元测试校验规格和回退；Android 测试校验生命周期、裁剪、Logo/文字阶段、隐藏后不再采样、异步旧结果丢弃以及系统动画关闭。保留完整展开截图与动效录制，检查窗口和全屏、长文字、无音频、未知规格及背景失败。

## 接入位置与兼容性

- 点播：`VideoActivity.startPlayback()` 为新媒体启动点；`onPlayingChanged()` 为实际播放触发；`onTracksChanged()`、`onSizeChanged()` 更新可用数据。`enterFullscreen()` / `exitFullscreen()` 会切换窗口布局及可能的表面，必须取消旧坐标采样。
- 直播：`LiveActivity.start(Result,long)` / `startPlayback()` 重置；`onPlayingChanged()` 触发。频道初次信息层当前会先显示，接入时应协调其隐藏与胶囊启动。
- 实际顶栏是 `JetStreamVodControlView.TopInfo()`，位置约 48dp / 28dp；旧 `widget.top` 通常为 GONE，不能只根据它判断重叠。遥控器打开信息或控制层时收起胶囊。
- 点播窗口为 432×243dp，应使用较小内边距和可用宽度上限；全屏沿用 TV 安全边距。胶囊始终归属视频容器。
- 现有 `MediaRatingHelper` 返回 TMDB/豆瓣评分，没有年龄分级。若追加 `Vod` 分级字段，需同步 JSON/XML/Parcel 与来源变化清理，不能以标题或评分推断。

## 验收标准

- 视觉匹配参考的圆标、左右双行、弱分隔线、深色毛玻璃和圆角裁剪；文字清晰，背景模糊不影响文字。
- 每次新媒体只播放一次完整动效；展开完成后保持约 3000ms；文字先于背景收缩消失；Logo 有一次有限动效，无永久动画。
- 所显示格式来自当前选中流；未知分级、位深、动态范围、声道均不伪造。
- 无每帧布局、每帧媒体查询或全分辨率截图；同一时刻最多一个采样/模糊任务；结束后停止所有任务并释放图像。
- 快速换集/频道、错误、退后台、退出页面、全屏切换不残留旧规格、旧图像或回调，不夺取遥控器焦点。
- 保留现有 Exo/MPV 架构和 mobile 编译兼容；测试与实测的环境边界明确记录。

## 验证环境

本地已有 JDK 21 和 `/home/ubuntu/android-sdk` 的 SDK/build-tools 37.0，但没有已连接设备、模拟器镜像、Python 3.10 或当前项目的 Media3 checkout；当前剩余磁盘约 4.5GB。优先使用仓库已有构建/UI 工作流，避免把环境准备误计为验证完成。

配置完整的构建环境可运行：

```bash
./gradlew -I "$TV_SDK_INIT" :app:testLeanbackArm64_v8aDebugUnitTest :app:compileLeanbackArm64_v8aDebugJavaWithJavac :app:compileMobileArm64_v8aDebugJavaWithJavac --no-daemon --build-cache --max-workers=2
./gradlew -I "$TV_SDK_INIT" :app:assembleLeanbackArm64_v8aPreview :app:assembleLeanbackArm64_v8aPreviewAndroidTest -PtvPreview --no-daemon --build-cache --max-workers=2
```

现有 `.github/workflows/tv-ui-verify.yml` 使用 API 34 x86_64 预览环境。该工作流默认关闭系统动画，验证胶囊动效时需临时设 `animator_duration_scale=1`，结束后恢复；普通截图测试仍可保留原设置。x86 预览不能证明 ARM 原生 MPV 播放性能。

## 当前状态

- [x] 阅读播放器、浮层、采样相关代码和本地环境；确定接入点与兼容性风险。
- [x] 完成目标、分工、验收标准和参考视觉计划。
- [x] `Vod` 显式分级元数据已接通 JSON 别名 / XML / Parcel；新增别名、空值、错误类型、评分不推断与 Parcel 往返测试，待 CI 执行。
- [x] 规格模型、原生 View 与背景采样实现完成。
- [x] 点播/直播接入及生命周期清理完成。
- [x] 编译、重点测试、截图/动效证据和最终审查完成。

## 验证结果（2026-10-27）

- 最终实现提交：`39dc707cb32a71563ea08debe194c3df14ae3f10`。
- 构建验证：https://github.com/wobuhui666/TV/actions/runs/40425008488 ，成功；688 app + 210 catvod + 16 quickjs = 914 项 JUnit，零失败、错误或跳过；mobile 编译及 TV APK 构建通过。
- UI 验证：https://github.com/wobuhui666/TV/actions/runs/40425005727 ，成功；新增 17 项 Android 测试，录屏另执行自然动效测试 1 次；原有 33 项功能测试及外观综合测试通过。
- 已检查 10 张胶囊阶段/布局/模糊截图和自然动效录屏。真实 SurfaceView、TextureView 采样及 Exo 本地 PCM 播放经过验证。
- 录屏期间软件 GPU 模拟器的 Window.FrameMetrics p50=50.13ms、p95=866.78ms，48 帧中47帧超过16.7ms；首轮未录屏为 p50=31.01ms、p95=50.57ms。数据只反映模拟器与录屏负载，不能宣称60fps或ARM实机流畅度已通过。
- 原生 MPV 实机性能、API24/25实机采样路径未执行；保留静态兼容和降级保护。

## 2026-10-27 视觉调整

用户要求 Logo 内部元素增加动效、改善分级框，并在验证后直接合并。Logo 使用缓存矢量路径，在原有动画时钟内分阶段组合主体、镂空和两个橙色折角；分级标签改为无描边的半透明圆角底色。补充内部元素像素位移与静止品牌一致性检查，保留系统关闭动画和生命周期清理。
