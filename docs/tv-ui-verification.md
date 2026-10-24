# TV 主题与触屏验证

TV 的设置 → 应用 → 外观模式可切换深色/浅色，默认保留深色。生产 `ui.rex.RexTheme.isLight()` 是统一入口；`RexTheme.setLight(boolean)` 保存偏好并发出主题刷新，`ui.theme.RexTheme` 委托到此入口。Compose 与 View 都经 JetStreamPalette 取色，现有强调色独立保留。Rex 精选卡的图片叠字保留白色，专用深色 scrim 保证浅色模式也可读。

导航、筛选条、详情、设置和播放操作复用当前 TV Material 控件的真实 Compose 子焦点；触摸点击/长按先定位同一项，随后方向键从触摸位置继续。列表滑动期间不抢焦点。点播单击显示控制、双击保留原操作，水平滑动快进/快退；直播竖向滑动切台。触屏仍为可选硬件。

`.github/workflows/tv-ui-verify.yml` 在推送 `feat/tv-touch-light-theme-issue77` 或手动运行时验证最新默认分支合并后的源码，参考 [zhhshss/tv-emulator-verify](https://github.com/zhhshss/tv-emulator-verify) 的 KVM、API 34 google_apis x86_64 模拟器和 ARM 转译流程。构建使用 JDK 21、Python 3.10、SDK 37.0 和默认分支固定的 Media3 提交 `342f6590e02ac390e0bd6b4488b8a1bff55747c1`，包括仓库校验的本地补丁。工作流构建同一生产源码的 `leanbackArm64_v8aPreview` 包、运行 JUnit 与设备测试、上传预览 APK、截图和诊断。无需发布签名或在线视频源。

```bash
./gradlew -I "$TV_SDK_INIT" :app:assembleLeanbackArm64_v8aPreview :app:assembleLeanbackArm64_v8aPreviewAndroidTest :app:testLeanbackArm64_v8aPreviewUnitTest -PtvPreview --max-workers=2
bash .github/scripts/verify-tv-ui.sh
```

本机构建环境按 [LOCAL_BUILD_ENV.md](../LOCAL_BUILD_ENV.md) 导出 `MEDIA3_SOURCE_DIR`、`TV_SDK_INIT`、SDK 与 JDK 路径。安装脚本从两个 APK 的 Gradle 元数据读取应用与测试包名，再核对设备注册的 instrumentation，兼容 `tvApplicationId` / `TV_APPLICATION_ID` 的并存包配置。

`leanbackPreview` 仅加入离线预览 Activity 和固定数据；正式包不包含此入口。真实首页复用 `activity_home.xml`、`PosterHomeController.register()` 与 `attachSnapshot()`、`RexHomeCatalog`、`RexSpotlightPresenter`、`RexPosterPresenter`、`CustomRowPresenter`，保持生产栏目和书架顺序、卡片尺寸、间距、字体和动画；导航沿用当前首页的首页/片库/推荐/搜索/我的五项，滚动后的工具栏显隐沿用生产逻辑。五个真实栏目提供 60 条固定影片，海报与横图是由 Canvas 生成的内存 PNG，由生产 ImgUtil/Glide 读取 data URI；不依赖远程图片。片库与搜索热词同样使用当前生产 KeepAdapter / WordAdapter 及其网格/Flexbox 配置。独立导航、筛选和 native-grid-probe 是交互探针，不代表首页排版。点击调用生产跳转逻辑，预览 Activity 记录真实 Intent 来断言栏目 key 和 TMDB 影片 key，保持场景离线。

设备测试通过 UIAutomator 实际点击、长按与滑动，覆盖 Rex 精选卡和混合推荐海报的首次触摸、随后 D-pad 右移/确认、原生横向 rail 和整个首页的竖向滑动，同时断言两套配色的文字对比度、主题切换及重建持久化。每套主题要求 24 个完整场景，包括当前 Rex 首页、精选行、推荐行、书架，以及独立控件探针、搜索、详情、对话框、播放和 TV Material 设置；额外保存重建后的浅色首页。保留原生播放控件的探针会切换主题两次，断言标题对象保持不变且文字颜色同步更新，并截图之后打开的对话框。播放器另外验证单击、双击、滑动及 ACTION_CANCEL。截图检查要求指定场景完整、非空白、浅深背景显著不同。上传的 `rex-theme-comparison.png` 用于真实首页的浅/深色并排审阅，`theme-comparison.png` 汇总其他场景；最终仍需人工查看截图判断美观、遮挡与焦点。
