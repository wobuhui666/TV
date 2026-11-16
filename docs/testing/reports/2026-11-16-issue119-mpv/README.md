# #119 MPV 重播、刷新与本地字幕返回验证

对应 [#119 重播／刷新补充](https://github.com/wobuhui666/TV/issues/119#issuecomment-6898808163)及第 1 项字幕文件选择返回后仅有声音的问题；行为说明见 [MPV 进度与 Surface 生命周期](../../../mpv-hls-seeking.md)。

**主验收矩阵 30／30 通过；附加远端 HTTP 组 5／6，合计 36 次方法执行、35 次通过。** 附加组失败在 Exo 准备阶段，尚未进入 Exo→MPV 切换，保留为失败。应用为 `e178b85c3237648d8ff8dbadb6d2846cf4cc9557`，fixture 为 `9240f8501abf272859f53ccc1a2df710bf2b631b`；当前生产代码与 e178 相同。修复前同夹具对照已复现重播、EOF、加载定位和 HTTP 刷新归零；明细见 [native-summary.json](native-summary.json)。测试后的临时设置和授权均已恢复。

## 最终原生结果

各组六个方法检查播放中／暂停重播、EOF 重播、连续刷新、Exo→MPV 后连续刷新、加载期间替换 seek 目标及字幕文件夹取消／选中后恢复视频。计数核对 instrumentation 方法状态、当前运行的 JSON、像素、宿主释放及设置恢复，不把残留 JSON 当作通过。

| 媒体／请求后端 | 实际 `vo/context` | 结果 | instrumentation 用时 |
| --- | --- | --- | --- |
| 私有本地 MP4／OpenGL | `gpu/android` | 6／6 | 26.569 秒 |
| 私有本地 MP4／gpu-next OpenGL | `gpu-next/android` | 6／6 | 26.995 秒 |
| 私有本地 MP4／请求 Vulkan | 回退 `gpu-next/android` | 6／6 | 27.788 秒 |
| 静态 HLS，经远端 fixture／ADB reverse | `gpu/android` | 6／6 | 180.822 秒 |
| 设备本地 HTTP MP4 | `gpu/android` | 6／6 | 25.826 秒 |
| 附加远端 HTTP MP4，经 ADB reverse | `gpu/android` | 5／6 | 232.292 秒 |
| baseline `7fd11e24ad`／相同 9240 fixture，本地 MP4 | `gpu/android` | 3／6 | 81.855 秒 |
| baseline／相同 fixture，远端 HTTP refresh 单项 | `gpu/android` | 0／1 | 37.016 秒 |

主验收包含前五组，完整覆盖六个方法。附加 HTTP 组失败在 `real-exo-ready`：既有 Exo 15 秒准备超时触发 fallback，未完成预期交接；该失败保留，不能将总体写为 36／36。

修复前本地对照在重播时继续到约 30.4 秒、EOF 后未重新加载、加载中的 26 秒目标被旧 12 秒目标覆盖；HTTP 首次刷新后 native／reported 进度均为 0，预期保留约 12 秒。本地 refresh、字幕 Surface 和 Exo→MPV 对照通过，不能称 X20 已自然复现所有反馈。

设备为 X20／incar、ARM64、Android API 29，hardware `ums512_1h10`。素材是 FFmpeg `testsrc2` 生成的 90 秒 SDR H.264／AAC 视频。设备本地 HTTP 只读使用正常应用已有 `/file` 路由提供本任务自有合成文件，未改该应用或配置；完整响应 7,867,807 字节的 SHA256 与素材一致，Range 响应亦已校验。HLS／附加 HTTP 通过远端 fixture 服务和 `adb reverse` 传输，均不使用真实点播源。

字幕回归调用真实 `TrackDialog.folder`／`FileActivity`。X20 自然返回时保留 Texture，未自然复现原反馈的 Surface 丢失；测试在文件页前台通过 `ViewGroup` 移除、再恢复同一个真实 `TextureView`，触发实际 Surface 销毁／重建，并检查进度、暂停状态和彩色视频像素，没有伪造 Surface 回调。以下为最终 `gpu-next/android` 的实际帧：

| 取消字幕选择后保持暂停 | 选中本地字幕后继续播放 |
| --- | --- |
| ![取消后的视频帧](subtitle-cancel.png) | ![选中字幕后的视频帧](subtitle-selected.png) |

## 构建与核验

[GHA 43951359098](https://github.com/wobuhui666/TV/actions/runs/43951359098)完整通过，构建同一 fixture／临时签名的修复 app 和 baseline。workflow `headSha` 与 lint comparison 的 `currentSha` 为 9240；实际 app 提交取自 checkout／APK metadata，分别为 e178／7fd。APK 包名、应用／测试证书、instrumentation 目标和 MPV 原生库均已校验。

| 检查 | 结果 |
| --- | --- |
| focused JUnit | 10 suites／58 tests，零失败、错误或跳过 |
| full app JUnit | 143 suites／1044 tests，零失败、错误或跳过；已包含 focused，不累加 |
| mobile ARM64 | Java 编译通过 |
| lint 增量门禁 | 基线 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，新增 error 为 0；现有 108 errors 保留 |
| lint 完整计数 | 基线 108 errors／348 warnings／6 hints；当前 108／349／6；新增 debug 宿主 `MpvRegressionActivity.kt:19` 的 `InflateParams` 警告 |

计数取自 focused／full 的 `TEST-*.xml` 及 `artifacts/lint/comparison.json`。下载 [两套 APK](https://github.com/wobuhui666/TV/actions/runs/43951359098/artifacts/14269175396)／[完整报告](https://github.com/wobuhui666/TV/actions/runs/43951359098/artifacts/14271950016)。报告归档 SHA256 `cbf7874a58c0f53a07b20ff2fcdf552cec49cd5d70ea24c0807a8d9c2a31a0fb` 已核对。

fixed／baseline app SHA256 为 `b4a9ef915b65d3212eba5d939cf8275b80928ed5eb027615b46a36c664930255`／`e5c2efd0cf0b15f64a9655391a8837cc19d5f0d4d946903864a88a2c2c85c851`，Test APK 同为 `a8550aa03f8236114cc4d28601dc33be34fcb43280c5793e808442e8ad80959e`；设备本轮测试未改变 APK 字节。两侧 native 库哈希相同，本轮未改 native 二进制。

GHA 使用 Ubuntu、Temurin JDK 21、Python 3.10、Android SDK 37.0／build-tools 37.0.0、NDK 29.0.14206865、CMake 3.22.1、固定 Media3 `342f6590e02ac390e0bd6b4488b8a1bff55747c1` 及仓库 composite settings／stubs。init 脚本设置 SDK minor API、临时 debug 签名和隔离包 `com.fongmi.android.tv.mpv119`／`.test`，不覆盖正常安装。主要 Gradle 命令：

```bash
./gradlew -I "$RUNNER_TEMP/tv-mpv119.init.gradle" \
  :app:assembleLeanbackArm64_v8aDebug :app:assembleLeanbackArm64_v8aDebugAndroidTest \
  --no-daemon --build-cache --max-workers=2 --console=plain
./gradlew -I "$RUNNER_TEMP/tv-mpv119.init.gradle" :app:testLeanbackArm64_v8aDebugUnitTest \
  --tests 'com.fongmi.android.tv.player.mpv.*' --tests 'com.fongmi.android.tv.playback.vod.*' \
  --no-daemon --build-cache --max-workers=2 --console=plain
./gradlew -I "$RUNNER_TEMP/tv-mpv119.init.gradle" \
  :app:testLeanbackArm64_v8aDebugUnitTest :app:compileMobileArm64_v8aDebugJavaWithJavac \
  --no-daemon --build-cache --max-workers=2 --console=plain
./gradlew -I "$RUNNER_TEMP/tv-mpv119.init.gradle" -I "$RUNNER_TEMP/tv-mpv119-lint-report.init.gradle" \
  :app:lintLeanbackArm64_v8aDebug --no-daemon --build-cache --max-workers=2 --console=plain
```

lint 保留完整基线／当前 XML、HTML，由 `compare-lint-reports.py` 比较新增错误，不抑制 issue ID。重型构建／JVM 验证在 GHA，原生播放在外部 ARM64 设备。

## 设备执行条件与历史尝试

执行前保存屏幕超时、保持唤醒、旋转和临时存储授权原值，验证 Awake／display ON、横屏及独立测试应用文件读取授权（API 29 为 READ／WRITE_EXTERNAL_STORAGE）。延长超时不会唤醒已休眠设备。入口：

```bash
adb -s "$TV_MPV_DEVICE" shell input keyevent KEYCODE_WAKEUP
adb -s "$TV_MPV_DEVICE" shell settings put system accelerometer_rotation 0
adb -s "$TV_MPV_DEVICE" shell settings put system user_rotation 1
adb -s "$TV_MPV_DEVICE" shell am instrument -w -r \
  -e class com.fongmi.android.tv.player.mpv.MpvPlaybackSemanticsAndroidTest \
  com.fongmi.android.tv.mpv119.test/androidx.test.runner.AndroidJUnitRunner
```

HTTP／HLS 另传 `mpv_semantics_url`；后端由 `mpv_gpu_next`／`mpv_vulkan` 指定，并记录实际后端。9240 fixture 使用 `NEW_TASK | CLEAR_TASK`，manager 创建前检查同一宿主 RESUMED、获焦、Texture 附着／可用，捕获时检查生命周期，释放后等待 DESTROYED；原生时钟、像素和超时断言保持原值。HLS／设备本地 HTTP／最终 baseline 使用设备上的 `setsid` 启动器并保存 stdout／完成标记，本机只轮询收集，避免长时间本机启动器被中断。

| 历史尝试 | 结果／原因；不计入最终 36 次执行 |
| --- | --- |
| 483 matched fixture 对照 | baseline 2／6；fixed 本地／HLS 各 6／6，HTTP 3／6；失败为像素、replay 时钟、Exo 准备，HTTP Surface 单项成功不覆盖整组失败 |
| 934 五组 | 28／30；HTTP Exo 准备失败、请求 Vulkan 回退时 Exo→MPV 丢失暂停；e178 保留用户暂停／速度并修正通知同步 |
| 旧 e178／61f fixture 组 | e178 两组各 1／6、fresh EOF／Exo 各 1／1；61f Vulkan 1／6、HTTP 0／6；后续电源日志证实 Asleep／display OFF，不据此判定生产暂停帧或同名实例故障；61f 额外 VO seek 已撤回 |
| 9240 休眠／竖屏诊断 | 休眠时五项前置检查拒绝，唤醒后最后一项 Exo 交接通过；Awake 竖屏组三项完成通过后，字幕页方向变化重建 debug 宿主，`TrackDialog.onStart` 空 manager 导致 Java fixture 崩溃，余项未完成 |
| 9240 未授权／被中断尝试 | 未授权组 5／6，字幕读取失败；本地中断组五项完成通过，HTTP 中断组零项完成；均保留诊断，不以残留结果补齐 |
| 签名／设备启动失败 | 最初两次 GHA 编译后签名校验失败；OEM 锁屏 SIGKILL／prefork 未完成启动与 Java fixture 崩溃分开记录，未执行的方法不计通过 |

483 对照 [GHA 43924204911](https://github.com/wobuhui666/TV/actions/runs/43924204911)的 baseline／fixed app 为 7fd／483，fixture、签名及 Test APK 相同。当前主验收是在明确唤醒／横屏／授权后整组重跑，历史失败和中断没有隐藏或累加进成功计数。

## 覆盖限制

- X20 不能代替海信 Android 11／MT5873 的驱动、硬解、稳定性验收；请求 Vulkan 实际回退 OpenGL，未验证真实 Vulkan。
- debug `MpvRegressionActivity` 未覆盖完整 `VideoActivity`／媒体库集成。TV 正式页面已有横屏／配置变化处理，debug 竖屏崩溃单列；自然字幕返回与显式 Surface 重建分开记录。
- 合成素材和静音测试不验证真实源、扬声器输出、HDR、高码率或实机性能。原始日志、APK、私有配置和签名保留仓库外。
- 最终已恢复 fixed 安装并核对 APK SHA，恢复临时权限／屏幕／旋转设置，移除本任务的转发、HTTP 临时文件和 fixture 服务；正常应用服务和数据未改动。未合并 PR，未发布 Release。
