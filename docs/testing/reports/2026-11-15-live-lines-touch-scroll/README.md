# 直播线路选择与首页触摸滚动

对应 [issue #119](https://github.com/wobuhui666/TV/issues/119) 的第 4、5 项。

## 测试版本与环境

- GHA 构建快照：`6786aa6b6537c4e4415ff0b8e3d5926fcc7aa033`；基于 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，包含当时工作区已有改动与本次修复。本报告不代表 Release 191 原始 APK 的结果。
- Media3：`342f6590e02ac390e0bd6b4488b8a1bff55747c1`，使用仓库固定补丁。
- Ubuntu 24.04、JDK 21.0.13、Python 3.10.22、Android SDK 37.0 / build-tools 37.0.0。
- 本机约 2 GB 内存，无 adb 设备或 Android 模拟器；本地 Kotlin 编译因换页缓慢而中止，未形成测试结果。
- 按用户要求改用 GitHub Actions 的隔离构建与 Android API 34 x86_64 模拟器。

[TV／手机 APK、单测和 lint 构建](https://github.com/wobuhui666/TV/actions/runs/43680375719)；[专项单测与两尺寸 UI 回归](https://github.com/wobuhui666/TV/actions/runs/43680375763)。

## 行为与覆盖

直播线路按钮显示完整列表，TV 使用播放侧栏，手机版使用底部面板。显示来源中的线路名或本地化编号，高亮当前项；指定索引直接切换。取消、当前项、无效或空线路不刷新；频道、配置或线路列表变化后，旧选择不能应用到新的频道。遥控器左右切线及错误自动下一线路保留。

首页内容触摸开始时取消旧焦点请求，滑动及惯性停止后继续保留视口，阻止历史、主视觉、来源结果和窗口恢复焦点强制回顶。遥控器、显式首页入口及返回键仍恢复原有导航；音量键不结束触摸浏览保护。

新增 JUnit 类 `LiveLineSelectionTest`、`LivePlaybackLineSelectionTest`，共 13 项，覆盖名称与编号、空值、选中索引、精确切线、当前项不刷新、过期频道/列表和左右循环。

新增真实 preview UI 类 `HomeTouchScrollTest`、`LiveLineDialogTest`，各 3 项。首页覆盖实际滑动、迟到历史/来源结果、弹窗关闭、排队焦点及触摸转遥控；线路面板覆盖高亮定位、精确点击、取消、重复展示、60 项列表及触摸后遥控选择。

## 验证结果

| 检查 | 结果 |
| --- | --- |
| 13 项直播专项 JUnit | GHA 通过，6 + 7 项，零失败／错误／跳过 |
| TV / mobile ARM64 debug APK | 等待 GHA 验证 |
| TV preview 应用与 AndroidTest 编译 | 等待 GHA 验证 |
| TV ARM64 debug lint | 首轮发现 3 个线路编号格式参数错误，已修正，等待重跑 |
| 新增 6 项 Android UI 用例 | 首轮失败，修正后重跑；两种尺寸共 12 项 |

首轮 UI 暴露了两处问题：Leanback 不实现 RecyclerView 的滚动统计，测试及返回键判定需要使用可见行几何；线路面板的标题触摸会穿透到外部取消层。已修正，并保留同一行位置、精确选线和取消不回调的断言，补充逐步焦点／触摸状态与截图。lint 发现已有 `%s` 编号资源被传入整数，调用处改为字符串，保留现有翻译格式。

本轮不包含 MPV 黑屏或 Exo 性能调查。旧版来源首页主动刷新会删除旧推荐行，本次保留该加载策略；内容增删造成的布局位移不等于焦点强制回顶。尚未验证实际手机触摸、长列表电视遥控和真实直播线路播放，UI 用例编译成功不能代替设备验收。

## 运行方式

按根目录 [本地构建说明](../../../../LOCAL_BUILD_ENV.md) 设置 SDK、Python 与 `MEDIA3_SOURCE_DIR`，并加载 SDK 37.0 minor API init 脚本。

```bash
./gradlew -I "$TV_SDK_INIT" :app:testLeanbackArm64_v8aDebugUnitTest \
  --tests 'com.fongmi.android.tv.playback.live.LiveLineSelectionTest' \
  --tests 'com.fongmi.android.tv.playback.live.LivePlaybackLineSelectionTest' \
  --no-daemon --build-cache --max-workers=1
```

设备回归使用隔离 preview 包：

```bash
./gradlew -I "$TV_SDK_INIT" -PtvPreview \
  :app:assembleLeanbackArm64_v8aPreview \
  :app:assembleLeanbackArm64_v8aPreviewAndroidTest --max-workers=1
adb shell am instrument -w -r \
  -e class com.fongmi.android.tv.test.HomeTouchScrollTest,com.fongmi.android.tv.test.LiveLineDialogTest \
  com.fongmi.android.tv.preview.test/androidx.test.runner.AndroidJUnitRunner
```

安装 preview 应用与测试 APK 后再执行 instrumentation。现有 `HomeNavigationIntegrationTest` 可用于补充遥控焦点回归；UI 覆盖需要实际设备或模拟器。
