# TV 源与播放验证

这些 instrumentation 工具用于明确选择的测试安装。工作流只编译测试，不运行真实源请求、模型下载或设备操作。

## 没有本地构建资源时

手动运行 [Build isolated TV validation APKs](../../.github/workflows/build-validation.yml)。新工作流需要先在默认分支可发现，再在 **Run workflow** 中选择包含待测代码的分支；命令行等价入口为：

```bash
gh workflow run build-validation.yml --ref "$VALIDATION_REF"
```

工作流使用现有公开 Media3 fork、JDK 21、Python 3.10 和 Android SDK 37.0，分别构建：

| Artifact 名称前缀 | 应用包 | Instrumentation 包 |
| --- | --- | --- |
| `tv-validation-sourceprobe-arm64-` | `com.fongmi.android.tv.sourceprobe` | `com.fongmi.android.tv.sourceprobe.test` |
| `tv-validation-preview-arm64-` | `com.fongmi.android.tv.preview` | `com.fongmi.android.tv.preview.test` |

配置弹窗专项使用 `-f configuration_dialog=true`，只构建独立包 `com.fongmi.android.tv.sourceprobe.wexdialog` 及其 `.test` 包，沿用 `tv-validation-sourceprobe-arm64-` artifact 前缀。常规单元测试和 lint 使用默认验证模式；专项构建不重复这些检查。实际包名以 `build-metadata.json` 为准。

在专用 ARM64 设备安装该 APK 对后，使用下列命令检查用户指定的配置中心。该入口要求 API 29 起，通过返回键关闭弹窗，不提交表单值；摘要保存在应用 externalFiles 的 `aiwex-config-dialog/report.json`。

```bash
adb -s "$DEVICE_SERIAL" shell am instrument -w -r \
  -e class com.fongmi.android.tv.ui.activity.AiwexConfigDialogAndroidTest \
  -e source_config_url https://9280.kstore.vip/aiwex.json \
  com.fongmi.android.tv.sourceprobe.wexdialog.test/androidx.test.runner.AndroidJUnitRunner
```

每个 artifact 包含 `*-app-debug.apk`、`*-test-debug.apk` 和 `build-metadata.json`，保留 7 天。元数据包含应用提交、实际 Media3 提交、包名和下载文件 SHA-256。ABI 为 `arm64-v8a`。执行的 Gradle 任务为 `assembleLeanbackArm64_v8aDebug` 和 `assembleLeanbackArm64_v8aDebugAndroidTest`；因此也会编译通用的核心播放/焦点测试。

CI 只使用临时 runner 的默认 Android debug 签名，不读取发布密钥、源配置或设备信息。已有安装若使用另一 debug key，需在本地用原 key 对应用和测试 APK **一起重签**，再覆盖安装；否则 Android 会拒绝升级。不要为解决签名不匹配而卸载需要保留数据的安装。

```bash
apksigner sign --ks "$DEBUG_KEYSTORE" --ks-key-alias "$DEBUG_KEY_ALIAS" \
  --out sourceprobe-app-resigned.apk sourceprobe-app-debug.apk
apksigner sign --ks "$DEBUG_KEYSTORE" --ks-key-alias "$DEBUG_KEY_ALIAS" \
  --out sourceprobe-test-resigned.apk sourceprobe-test-debug.apk
apksigner verify --verbose --print-certs sourceprobe-app-resigned.apk
apksigner verify --verbose --print-certs sourceprobe-test-resigned.apk
adb -s "$DEVICE_SERIAL" install -r sourceprobe-app-resigned.apk
adb -s "$DEVICE_SERIAL" install -r sourceprobe-test-resigned.apk
```

密码由签名工具交互读取。两份 APK 的证书须一致；重签后的 SHA-256 与 CI 元数据不同，应重新记录。`preview` 同理。CI 编译成功不等于设备播放验证通过。

## 工具适用范围

| 类 | 范围与前提 |
| --- | --- |
| `PythonSourceFunctionalTest` | 特定红果分类契约探针。除源公开分类外，还要求 `rank_home`、四类榜单、真人/漫剧/AI 分类及主题；默认搜索词也是中文。不能把它的失败直接解释为任意其他 Spider 不兼容。 |
| `PythonSourcePlaybackTest` | 读取私有播放清单，通过生产 Exo/MPV 管线验证画面、seek、音效、速度/音量、预载和 PCM。需要视频片段以及足够时长；预载项目另需同剧第一、第二集。 |
| `SourceCaseRefresh` | 真实 Chaquopy Loader 的辅助类，按清单的 `seriesId`、线路和集数重新解析。脚本只加载一次，60 秒调用限时；不修改持久源配置。 |
| `SourceStreamProbe` | 仅由可选下载诊断调用的原始 HTTP/Range 比对辅助类。完整文件上限 64 MiB。 |
| `SourcePreloadProbe` / `SourceAudioEffectProbe` | 检查当前项目生产预载和 DSP 内部状态的辅助类，使用反射，依赖本项目和所构建的 Media3 版本。 |
| `AsrModelDownloadTest` | 只允许 `sourceprobe` 包，调用生产下载器安装/校验普通话模型。会访问模型下载服务并保留模型文件，有存储与网络开销。 |
| `PythonSourceAsrTest` | 已安装模型后验证真实 Exo PCM → 本地普通话识别 → 可见字幕。限定直接 HTTP(S) 第一集，排除 loopback，关闭远程翻译；90 秒总预算，准备/刷新会占用观测时间。 |

上述八个文件中四个是测试入口，四个是辅助类。它们适合作为显式调用的诊断工具提交，不是离线通用回归套件。使用 `-e class` 选择所需类/方法，不要无筛选运行全部 instrumentation。完整 app/debug APK 需要本项目的 Chaquopy、MPV、libass 和 sherpa native 依赖；不要剥离这些库后把失败解释为播放器缺陷。播放器界面使用 debug 专用 `CorePlaybackActivity`，不是正式首页。

## 私有播放清单与选择参数

`files/source-validation/playback-cases.json` 位于被测应用私有目录，由功能探针生成。清单包含源 SHA-256 及 `cases`；每项包含 `seriesId`、`line`、1-based `episodeIndex`、`episodeCount`、`url`、`headers`、`parse: 0`。它含真实地址和请求头，不能提交或上传到公共 CI artifact。

功能探针需 `source_url`：可访问的 HTTP(S) Python 脚本，使用独特 ASCII 文件名，例如 `validation_source_001.py`，不带查询、片段或用户信息。源应是用户明确授权执行的代码。源下载缓存属于该测试，结束时清理；源内部建立的进程内服务需要与消费它的播放测试处于同一 instrumentation 进程。

播放测试默认消费现有清单，不重新加载脚本。可选参数：

| 参数 | 默认与作用 |
| --- | --- |
| `source_case_index` | `-1`；显式值为清单的 **0-based** 索引，例如 `5` 表示第六项。 |
| `source_case_limit` | `0` 为全部；按剧集排序后取前 N 项。显式单项选择仍保留此参数。 |
| `source_decode_mode` | `hard`；也可为 `soft`，表示选择模式，不证明物理硬件解码。 |
| `source_playback_timeout_seconds` | `45`，限制在 15–120 秒。 |
| `source_refresh_url` | 空；非空时用真实 Loader 只刷新本方法所播放的清单项，不跑分类/搜索。重新写入该项地址、请求头与 SHA，未刷新项保留原 SHA。 |
| `source_download_diagnostic` | `false`；仅启用独立完整文件/Range/本地 seek 方法。 |

单项 Exo 调试入口为 `PythonSourcePlaybackTest#exoPlaysResolvedEpisodesAndKeepsStateAcrossAudioEffects`。例如在已准备私有清单的安装上：

```bash
adb -s "$DEVICE_SERIAL" shell am instrument -w \
  -e class com.fongmi.android.tv.test.PythonSourcePlaybackTest#exoPlaysResolvedEpisodesAndKeepsStateAcrossAudioEffects \
  -e source_case_index 5 -e source_decode_mode soft \
  -e source_refresh_url "$AUTHORIZED_SOURCE_URL" \
  com.fongmi.android.tv.sourceprobe.test/androidx.test.runner.AndroidJUnitRunner
```

完整文件诊断方法为 `exoSeeksTheSelectedLoopbackStreamAsACompletePrivateFile`，另传 `source_download_diagnostic=true`。它只接受所选 loopback 视频，用完整 GET 保存文件，再校验起始、4096、1 MiB、近尾四个 4 KiB 闭区间，以及 1 MiB 起的开放尾 Range（只比较前 4 KiB）。报告分别记录 Range 是否正确、本地文件两向 seek 是否通过；Range 不正确仍会尝试本地播放。

完整诊断文件保留在 `files/source-validation/download-case-N.mp4`，同索引重复运行会覆盖；不完整文件会删除。它是私有媒体数据，不上传到公共报告。脱敏 JSON 报告写入应用的 external files `source-validation/`：包含状态码、长度、散列、偏移、异常类型和计数，不包含 URL、请求头、媒体内容或原始异常消息。logcat 和截图不享有同样保证，分享前单独检查。

## 从真实界面导入到播放

以下是基于当前 TV UI 代码的操作路径；实际响应仍需设备验证。HTTP 配置服务必须能从 Android 应用访问，电脑侧 `localhost` 不会自动映射到 Android。

1. 首页顶部选 **我的**，按确定进入；默认焦点是左上 **收藏**。方向键下、下、右可到 **设置**，也可直接点击设置卡片。全新空首页的“选择源”按钮也会进入设置。
2. 设置默认是 **源** 分区，选择第一行 **点播**。对话框 `text` 输入框会请求焦点；清空原文本后输入授权配置 URL，点 **确定**（`positive`）或使用输入法完成。名称可留空。
3. 等配置加载进度消失且“点播”行显示新配置。若配置含多个站点，使用该行的 **首页** 动作选择待测站点；然后返回到首页，等源标题和内容刷新。
4. 首页顶部选 **片库**，进入 `VodActivity`。左右移动到源提供的 **排行榜** 分类；分类获得焦点约 100 毫秒后自动切页。确定键/菜单键会切换筛选，不是必须的选页动作。等加载结束、卡片出现后向下进入列表。
5. 排行榜若返回目录卡片，选择目标榜单后等子列表出现，再选择剧集进入 `VideoActivity`。等详情标题、线路和分集出现，并确认画面/时间推进；切换线路或分集后重新等待首帧。
6. 选择视频画面或详情 **观看** 动作进入全屏；选不同分集会重新解析播放，选当前分集也可能直接进入全屏。验证首帧、时钟推进、暂停/恢复及前后 seek，不能仅把播放器窗口或 READY 状态当成播放成功。

触屏自动化可使用可见文本或上述视图 ID。Compose 导航/设置项的导出节点不一定具有传统 View 的稳定 resource-id，因此方向键与可见文字比固定坐标可靠。默认首页顶部顺序为首页、片库、发现、搜索、我的；有直播配置时会插入直播，避免按固定次数盲移到“我的”。
