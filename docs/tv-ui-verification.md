# TV 主题与触屏验证

TV 的设置 → 应用 → 外观模式可切换深色/浅色，默认保留深色。`RexTheme.isLight` 是统一入口；`RexTheme.setLight(boolean)` 保存偏好并发出主题刷新。Compose 与 View 都经 JetStreamPalette 取色，现有强调色独立保留。

导航、筛选条、详情及设置使用宿主 View 的唯一焦点与内部索引；触摸点击/长按更新同一索引，随后方向键从触摸位置继续。列表滑动期间不抢焦点。点播单击显示控制、双击保留原操作，水平滑动快进/快退；直播竖向滑动切台。触屏仍为可选硬件。

`.github/workflows/tv-ui-verify.yml` 参考 [zhhshss/tv-emulator-verify](https://github.com/zhhshss/tv-emulator-verify) 的 KVM、API 34 google_apis x86_64 模拟器和 ARM 转译流程。工作流构建同一生产源码的 `leanbackArm64_v8aPreview` 包、运行 JUnit 与设备测试、上传预览 APK、截图和诊断。无需发布签名或在线视频源。

```bash
./gradlew :app:assembleLeanbackArm64_v8aPreview :app:assembleLeanbackArm64_v8aPreviewAndroidTest :app:testLeanbackArm64_v8aPreviewUnitTest -PtvPreview --max-workers=2
bash .github/scripts/verify-tv-ui.sh
```

`leanbackPreview` 仅加入离线预览 Activity 和固定卡片数据；正式包不包含此入口。测试通过 UIAutomator 实际点击、长按与滑动，断言触摸后 D-pad 的结果、宿主焦点、两套配色的文字对比度、主题切换及重建持久化。截图覆盖首页、导航、筛选、卡片、滚动、搜索、详情、对话框、播放控件、设置。播放器另外验证单击、双击、滑动及 ACTION_CANCEL。截图检查要求完整、非空白且浅深背景显著不同；最终仍需人工查看截图判断美观和遮挡。
