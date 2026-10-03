# 最终阻断专项 QA：通过

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


最终完整报告：`QA-RECOVERY-RESULTS.md`。构建/source SHA：`final-reports/final-verification.json`。28份录像均有通过解码记录，本轮只新增验证10份。性能和真实设备限制保持。
