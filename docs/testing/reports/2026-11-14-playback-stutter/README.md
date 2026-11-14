# Exo 声音与弹幕停顿排查

日期：2026-11-14。GHA 验证提交：`b0aa1ce6e956267bb6d9b307fe3e3a06f392b2dc`，分支 `fix/exo-danmaku-stutter-20261114`；基准提交：`6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`。宿主机初查在有其他未提交功能改动的主工作树进行，GHA 使用只包含本轮修复、诊断及验证配置的隔离分支。

[构建与回归](https://github.com/wobuhui666/TV/actions/runs/43590570128) 和 [Android 播放回归](https://github.com/wobuhui666/TV/actions/runs/43590570318) 均成功，测试及产物摘要见 [gha-summary.json](gha-summary.json)。

Media3 固定提交：`342f6590e02ac390e0bd6b4488b8a1bff55747c1`。新增补丁 `danmaku-render-index.patch` 的 SHA-256：`22343b0223dc63ebffe4e2f9ba76b408356269f172784597e799a6e38b212eda`；完整登记与应用约束见 [Media3 补丁说明](../../../../third_party/media3/README.md)。

## 问题与改动

用户报告 Exo 点播时声音、弹幕偶尔同时短暂停顿，画面继续；音频直通、音效及 AI 字幕未开启。本机未连接 Android 真机，GHA 使用 API 34 模拟器；没有取得用户原片源的音频欠载或帧时间日志。

发现弹幕渲染池在正常播放中不断追加历史分块，每次查询为所有分块创建 `TreeMap.subMap`、迭代器和装箱键，复制时间桶时还会创建临时数组；即使只有一个分块命中，也会重新排序结果。补丁改为后台稳定排序的数组，先用首尾时间跳过不相交的分块，再二分查询并逐项复制；只有多个分块实际命中时才合并排序。发布分块仍只短暂持锁，历史数据保留供回退和重测量使用。

Exo 新增事件诊断，记录最近的音频解码器、格式与输出初始化，音频欠载时的缓冲容量、距上次写入时间、播放位置和媒体缓冲时长，以及音频异常和视频丢帧。音频输出、缓冲、解码及直通参数保持原样。诊断使用既有的可选脱敏记录器，没有周期轮询或同步磁盘快照；释放事件记录自身配置，最近初始化配置单独标为 `lastOutput`，避免混淆晚到的旧输出释放。

## 环境与结果

GHA：`ubuntu-latest`、Temurin JDK 21、Python 3.10、Gradle 9.1.0、AGP 9.0.1、Android SDK / build tools 37.0；设备回归使用 API 34 Google APIs x86_64 模拟器、SwiftShader、`-noaudio`。

本机初查：Linux x86_64、OpenJDK 21.0.13、JUnit 4.13.2、Python 3，物理内存约 909 MiB。本机 Gradle 使用缓存中的 JetBrains JDK 21，没有连接的 adb 设备。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| GHA 构建 | 通过 | TV ARM64 debug app / instrumentation、手机 ARM64 debug；生成并校验隔离包 |
| GHA Gradle JUnit | 1332 项通过，零失败/错误/跳过 | app 1049、catvod 237、quickjs 27、wasm 19；新增弹幕索引 7 项、Exo 诊断 6 项均在真实 Android 变体中执行 |
| GHA Preview JUnit | 1049 项通过，零失败/错误/跳过 | 同一应用测试的 Preview 变体，不与上述计数累加 |
| GHA Android 回归 | 94 项通过，零忽略/条件跳过 | 13 套回归，包括暂停弹幕 2 项、弹幕控制权 3 项、播放控制权 11 项；真实本地 XML 加载及 Canvas 暂停帧恢复 |
| GHA lint | 无新增错误 | 基准和候选均有 108 Error、348 Warning、6 Hint；比较器列出 `LeanbackUsesWifi`、`DataExtractionRules` 两项 manifest 警告，不能称完整 lint 清洁 |
| GHA Python 回归 | 51 项通过 | 原有 Python 源 API 测试 |
| 宿主机 JUnit | 13 项通过 | 起点排除/终点包含、相同时间稳定顺序、调用方前缀、空区间、极端时间、250 个随机前进/回退范围、历史重测量、clear/release/detach/config generation 失效；诊断关闭、播放中开启、未知容量时长、状态清理、晚到释放与脱敏 |
| 补丁脚本测试 | 10 项通过 | 首次/重复应用、校验值和登记目标、额外本地与暂存改动保护、错误基准、部分应用和独立补丁 |
| 真实补丁隔离应用 | 通过 | 从固定提交建立临时 checkout，仅检出三个目标文件；两份真实补丁首次应用、重复应用及 index 保持通过 |
| 差异检查 | 通过 | TV 与 Media3 的 `git diff --check` |
| 本机 Android 变体 JUnit | 未完成 | 默认配置 180 秒超时；384 MiB heap、单 worker 的复试 300 秒超时，均未到 app 测试执行阶段；由上述 GHA 完成构建与测试 |

查询对照使用修改前、修改后生产类与相同输入：80 个分块、96,000 条弹幕，每块覆盖 120 秒、间隔 90 秒，模拟约两小时的历史；预热 5,000 次，再查询 20,000 次。以下为 GHA 的一次采样平均值，时间受 JVM 和构建负载影响。

| 查询范围 | 原分配/次 | 修改后分配/次 | 原耗时/次 | 修改后耗时/次 |
| --- | --- | --- | --- | --- |
| 当前 3 秒范围，命中一个分块 | 16,888 B | 0 B | 18.37 µs | 0.36 µs |
| 历史之后的空范围 | 16,000 B | 0 B | 12.89 µs | 0.31 µs |

## 产物

| 产物 | 下载 |
| --- | --- |
| TV ARM64 Preview debug app / test，原始 ARM 包 | [validation-preview](https://github.com/wobuhui666/TV/actions/runs/43590570128/artifacts/14111189773) |
| TV ARM64 Sourceprobe debug app / test | [validation-sourceprobe](https://github.com/wobuhui666/TV/actions/runs/43590570128/artifacts/14112297719) |
| 手机 ARM64 debug | [mobile-validation](https://github.com/wobuhui666/TV/actions/runs/43590570128/artifacts/14112243097) |
| JUnit、lint 与查询对照 | [unit-tests](https://github.com/wobuhui666/TV/actions/runs/43590570128/artifacts/14112691629) |
| Android 回归摘要与截图 | [ui-verification](https://github.com/wobuhui666/TV/actions/runs/43590570318/artifacts/14112656344) |

APK 来自隔离 debug 包名，不是发布版更新。产物有 GitHub 保留期限，提交中仅保存公开摘要，没有保存 APK 或原始日志。

## 复现命令与限制

宿主机脚本使用真实的查询、数据、测量配置和诊断记录源码，并从固定 Media3 源码提取诊断回调、输出配置和事件构造接口。Android Painter、Handler、部分 Media3 数据接口为小型替身；不运行 Android 绘制或真实线程调度，不测试扬声器、音频设备或网络。

```bash
python3 tools/testing/playback-stutter/verify.py
python3 -m unittest discover -s third_party/media3 -p 'test_*.py' -v
```

Android 环境准备方法见 [本地构建说明](../../../../LOCAL_BUILD_ENV.md)。本轮尝试的变体命令：

```bash
./gradlew :app:testLeanbackArm64_v8aDebugUnitTest \
  --tests androidx.media3.ui.danmaku.DanmakuRenderPoolTest \
  --tests com.fongmi.android.tv.player.exo.ExoPlaybackDiagnosticsTest \
  --offline --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=192m -XX:ReservedCodeCacheSize=64m -Dfile.encoding=UTF-8 --add-exports jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED' \
  -Pkotlin.compiler.execution.strategy=in-process
```

本机构建配置日志未出现具体依赖或 OOM 错误，无法认定超时的确切调用；缓存没有完整的 Kotlin 2.2.21 / Compose 编译器和已编译的 Media3 产物。完整应用编译、APK 安装和上述模拟器回归已由 GHA 完成；ARMv7、MPV 原生运行和用户设备音频未验证。

分配改进限定于被测查询：多个分块重叠命中时仍会排序，查询仍检查各分块的首尾索引，历史内存未裁剪。该优化已证实减少 Java 查询负担，但不能据此断言用户的声音瞬断已经解决。实机应使用包含本轮修改的构建，在“诊断日志”开始记录后复现并标记问题，对比音频欠载、播放状态与视频丢帧；操作见 [诊断日志用法](../../../practical-improvements.md#诊断日志)。
