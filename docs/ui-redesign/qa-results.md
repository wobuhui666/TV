# 云端 UI 验收结果：最后两项阻断已通过（2026-10-02 UTC）

最终三文件修复已经精确同步到云端、完成增量构建，并在 API 24 x86_64 模拟器上专项回归通过。此前全部遥控/边界验收继续有效；本轮仅重测两项修复及其风险路径。此结论不等于目标真机性能或生产 ARM 播放已经实测。

## 最终阻断专项结果

| 场景 | 结果与证据 |
|---|---|
| 无配置冷启、选择源、设置 Back | 三次标题均 `[96,216][1824,290]`，导航 `[200,32][1736,128]`；原标题 y=56 的重叠消失。选择源可聚焦并返回原焦点。`blocker-no-config-cold/focused/settings/back.*`、`blocker-no-config.mp4`。 |
| 有历史但空推荐、空推荐与 Hero 相互刷新 | 保留真实历史，空推荐选择按钮可达。选中空内容时导航按既有规则隐藏，标题 y=216；Hero→空推荐→Hero 不抢到失效焦点，Back 稳定回导航。`blocker-empty-history-*`、`blocker-home-transitions.mp4`、`blocker-refreshed-hero.*`、`blocker-hero-nav-restored.*`。 |
| 原稳定崩溃完整旅程 | Home→Search(fast)→结果详情→Back→Live→Setting→直播主页弹窗→壁纸配置弹窗→应用/主题色彩；偏好值核实粉(-39271)→绿(-16734096)→粉→蓝(-1)。未进入 CrashActivity，返回直播仍为001风景频道。`verified-theme-complete-journey.mp4`（84.316秒）、`verified-*-prefs.json`、`verified-live-return-channel.*`。 |
| 直播无重建 | 切换前、后台设置、粉色、蓝色、返回五次 `Local Activity=53943ee`、`PlayerView=4100871` 完全一致。`verified-object-identities.json`、相应 `*-instances.txt` 与事件日志；不是只比较 ActivityRecord token。 |
| 点播进度与 PlayerView | 第1集暂停于00:19/02:00，粉与绿切换后仍00:19；`Local Activity=1635991`、`PlayerView=1581994` 在全屏/小窗、后台、五次换色、返回始终相同。`verified-vod-theme-preservation.mp4`、`verified-vod-paused-before.*`、`verified-vod-return-pink/green.*`。 |
| 点播详情焦点 | 详情“简介”焦点在绿→粉→蓝返回时保持，XML详情宿主与截图中具体操作一致；同一个 PlayerView 仅调整全屏/小窗尺寸。`verified-vod-detail-theme-focus.mp4`、`verified-vod-detail-green-before/pink-after/blue-after.*`。 |
| 实际主题色更新 | 设计将 primary/secondary、焦点和播放控件固定为中性色，不能要求播放器整体染色。真正主题强调色保留在 tertiary：Push复制反馈录像1.5秒帧，蓝RGB(174,190,244)、粉RGB(255,177,200)分别匹配37/40个图标像素（视频压缩容差28）；`verified-accent-blue/pink.mp4`、`*-frame.png`、`verified-accent-colors.json`。更改主题期间底层 Video/PlayerView 仍相同。 |

两个生产修复消除了无Hero首页对齐问题和**主题变化触发的**不必要播放页重建。未修改通用服务连接状态机；系统自身 Activity 重建时的服务连接竞态未声称全面修复，也没有做延迟服务绑定专项。

本轮控制栏在进入设置期间按原计时逻辑收起，返回显示原暂停进度；未把临时控制条隐藏写成永久焦点丢失。详情具体操作焦点另行独立验证。

## 已完成且有证据的项目（前轮继承）

| 项目 | 证据与准确范围 |
|---|---|
| 真实遥控选集 | 右→下→下进入episode控件，选第2集，全屏标题确认为第2集；`recovery-episode*`。旧episode-two图实为收藏，已作废其选集结论。 |
| 继续观看进度 | 保存第2集51241ms/120095ms，从首页实际继续观看卡进入后控制栏57秒，差值符合进入期间播放；`recovery-before-card-history.json`、`recovery-real-continue-focus.*`、`recovery-resume-card.mp4`。 |
| 换源 | 仅给fixture增加备用片库；按“换源”后详情站源从演示片库变备用片库；`recovery-source-changed.xml`、`recovery-source-player.mp4`。 |
| 播放层级 | 设置抽屉→控制栏→中心信息层→全屏→详情小窗；`recovery-player-*`、`final-player-controls.*`。 |
| 直播 | 003城市→纪录分类→005自然纪实，下键快捷到006人文故事；覆盖层确认真实选中项，Back关闭；`recovery-live-navigation.mp4`。 |
| 搜索 | fixture旧slow延迟25秒，编辑并用屏幕键盘搜索键提交fast；fast先到，旧响应后未覆盖且焦点保持，详情Back回结果卡；`recovery-search-replace.mp4`。后续静态重拍加入第二fixture源，所以截图显示2条，原延迟用例录像为1条。 |
| 图片与标题 | 横图、竖图、404图片字母占位、长中文名均可显示并聚焦；`final-missing-art-card.*`、`final-long-title-card.*`、`detail-long-title.*`。 |
| 空历史/收藏 | 首页不占空历史分区；完整历史有“还没有观看记录”，收藏通用空状态；`recovery-empty-history*`、`recovery-empty-favorites.*`。临时测试DB已恢复。 |
| 我的/推送/文件/发现 | 页面可见；文件权限授予后可浏览目录。CastActivity可启动/返回，仅空闲界面，无真实发送端投屏。 |
| 设置焦点修复 | 修复后七种色彩都能纯D-pad到达（含横向滚动隐藏两色），每次读取偏好值核对；粉色冷启持久化、上下/左右边界通过。原组合旅程崩溃已在本轮最终三文件修复后专项通过，见上文。 |
| 设置主子动作 | VOD/LIVE/WALL主点击和长按配置、主页子按钮、壁纸默认/刷新均可达。配置历史无其他条目时弹窗自动关闭；不是有历史数据回归。MPV/JS开关可操作，导出子按钮明确提示暂无日志；有内容文件导出未测。 |
| 快连按/后台 | 144次横向连按后可回导航；后台5秒后仍原hero内容与焦点；`recovery-fast-keys*`、`recovery-background-*`。 |


## 最终源码、构建与产物

- 本代理未编写生产代码；校验云端旧 SHA 后仅同步 HomeActivity、LiveActivity、VideoActivity。先前设置焦点修复保持。105项源码SHA与最终本地树/云端构建树一致，详见 `final-reports/final-verification.json`。
- `assembleLeanbackArm64_v8aDebug`、`testLeanbackArm64_v8aDebugUnitTest`、`lintLeanbackArm64_v8aDebug` 全部成功（3m33s；16执行、854 up-to-date）。216测试，0失败/错误/跳过；lint269 Warning、6 Hint、0 Error。
- ARM64 Debug APK SHA：`39b368a3d5087989af95e73cb304c7b62c6f1a55667fdf34e60b35b9bfe706ad`。
- 独立x86 UI APK SHA：`6c626a28b934490c69bb0815ac76c0c817e48db133e478067a95c7a866dd5986`。由最终 ARM64 APK 复用既有脚本生成，仅测试APK替换x86库并跳过PyLoader eager初始化。
- HomeActivity SHA：`1b231a4f7fe422e75fdbb20e584cff16ece7ccdbe94b85eca8878e5cd5d7d8cf`。
- LiveActivity SHA：`da2d805521fb38f5894d607bdbfa8f1a76138522f422bcd7b086caceb3100acf`。
- VideoActivity SHA：`c047471be61bae3a62322e48d05878b961e39b60e3874da0169b536e9ae30f0c`。

## 性能及未实测限制

- 保留前轮软件渲染模拟器结果：PSS84253→84855KiB，短程增加602KiB；仅未见明显无界增长，不能证明无泄漏。500帧中496 janky（99.2%），p50=53ms、p95=85ms，不能声称60fps或性能验收通过，需真机评估。
- 前轮快连按后一次导航宿主density2→1.125比例变化，干净冷启普通列表返回未复现；本轮不重跑性能，保留观察项。
- 本轮完整异常检查未见 CrashActivity、应用FATAL、ANR、Unable to start activity。无配置时VodConfig/LiveConfig的缺URL NPE仍被捕获并打印；不能宣称日志零异常。`final-blocker-runtime-review.json` 保留精确范围。
- 当贝H3S、坚果J10S、生产ARM实际播放、Python/MPV、硬解、真实投屏发送端/网络兼容、真实EPG、其他API、延迟系统重建服务连接均未实测。播放器进度采用本地120秒fixture；布局/遥控结果不替代真实源兼容验证。
- 配置历史无其他条目、日志导出无内容文件等前轮限制不变；不声称已验证有内容配置/日志导出。

## 环境与证据交接

- 本地完整证据：`/home/ubuntu/TV-ui-work/recovery-evidence/final-qa/`；云端：`/workspaces/TV-ui-results/final-qa/`。本轮新增10份录像，均在停止录屏并pull后逐份ffprobe与全帧解码通过；总28份录像，前18份沿用已完成解码结论。`video-validation.json` 含新增录像SHA。
- 本轮增量归档 `final-blocker-evidence.tar.gz`；最新总归档 `recovery-final-evidence.tar.gz`。推荐联系表 `contact-final-blockers-1/2.jpg`，精选清单 `final-blocker-curated-screenshots.json`。
- 本轮取证截图先dump再截图。2秒复制反馈采用停止后验证的录像帧，并在JSON注明来源与1.5秒时间点；不冒充同期XML截图。
- `blocker-theme-*`为首轮探索，固定按键导致色名文件名与实际偏好错位；`fixed-theme-2.*`仍是修复前崩溃。二者均不作最终主题通过图，最终以 `verified-theme-*` 及对应偏好为准。旧阻断完整报告保存在 `QA-RECOVERY-RESULTS.before-final.md`。
- 开始本轮时发现Codespace临时区和模拟器/fixture进程均已丢失，adb设备为空，旧AVD也在/tmp。复用原脚本、现有SDK/系统镜像最小恢复API24模拟器，从持久DB证据恢复历史，恢复相同120秒视频/八集/六频道/slow-fast/备用源；没有重建仓库或SDK、没有修改生产源码。此次环境恢复与源码问题分开记录。
- 环境保持运行，停止全部QA和录屏进程。未提交、推送、建PR、合并、发送外部通知或关闭Codespace；后续交付由根代理协调。

更新：2026-10-02T05:03:07.998252+00:00
