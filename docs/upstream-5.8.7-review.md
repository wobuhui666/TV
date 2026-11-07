# FongMi 5.8.7 稳定性适配

本轮上游参考为 FongMi/TV `4512b4c9f82534ff66acfde06ccdd68f35b074f0`
（2026-11-06）。从上次评估的 `9febe402bb951c92856e26b95deae36c34248071`
起共有 24 个提交；本地起点为 `b2434caaff4cd7b0a614c152acc07b83453d6c8c`。
开发版本调整为 5.8.7，具体功能以本项目实现和下表为准。

| 上游更新 | 本轮适配 |
| --- | --- |
| `4512b4c9` 爬虫列表显式选集 | 支持 `vod_play_id`，贯通列表、启动 Intent、Parcel 和点播控制器。先查所有线路的精确 URL，再使用现有季集解析器匹配名称；同分不同 URL 拒绝自动选择。指定集不存在时显示错误并保留手选入口；同一作品重新指定集数也会生效。 |
| `a5186122` 投屏续播 | 保留首次接收端的原有历史恢复，补上随 Intent 传递的独立快照和同片重投。发送端在点击设备时读取当前进度；发送后换媒体或关闭弹窗，迟到响应不会停止新的播放。文件地址只改媒体 ID 和对应集 URL，保留名称与配置标记。 |
| `6e9dd1bb` 播放状态与归属 | 服务绑定使用稳定身份；旧的同 key 界面不能解绑新界面、暂停新播放或清除新导航回调。当前媒体准备后恢复视图、状态和已知尺寸。结束跳转去重，保留回看返回直播与浏览器解析期间的结束抑制。直接开始播放前取消旧 ParseJob。 |
| `fa5a3926` 画面尺寸与弹幕 | 手机播放器按当前 `VideoSize` 及像素宽高比计算容器高度，退出全屏／PiP 时重算。弹幕回调核验当前绑定、请求和 Result；旧界面销毁不会取消新界面的弹幕。 |
| `dea4faae` 浏览器客户端重绑 | `onRebind` 恢复浏览器客户端计数，浏览器解绑允许重新绑定；本地 Activity 绑定与浏览器计数仍分开。 |
| `b4b8a9e2` 目录筛选 | 当前子目录应用筛选时保留返回栈，父子目录筛选值独立。TV 的未知子目录继承根筛选，明确声明为空的子目录保持为空；手机筛选框显示当前子目录选择的快照。 |
| `51813cb6` 按键 seek 接管 | 本地输入层已具备非 seek 按键取消和零偏移取消；保留触屏连续合并，补控件接管的取消路径和实际 KeyEvent 回归。 |

## 本轮保留的架构与能力

现有 `EpisodeLabelParser`、混版 `EpisodeNavigator`、生成标签来源标记、Logvar
严格匹配、ASSRT 主副字幕和播放状态保护继续使用。保留 JetStream TV 界面、来源候选、
海报转场、有限预加载、软解／静音音轨／隧道／Dolby 恢复、时移与 MediaBrowser 解析保护。
Media3 继续固定为 `wobuhui666/media` 的 `342f6590e02ac390e0bd6b4488b8a1bff55747c1`。

## 本轮没有整体引入的更新

- `04ab460f` 的 Exo/MPV 60/120 fps 补帧需要新的播放器 API、FFmpeg 渲染器和
  MPV 原生配置支持。本轮没有加入一个后端无法兑现的设置入口。
- WASM 已通过保留现有 Java／QuickJS 接口的独立解释器接入，见 [WASM 说明](spider-wasm.md)。
  QuickJS 配套 JAR/AAR、python-android、Node 和动态运行时 profile 需要
  独立处理旧 JAR／脚本兼容与原生 ABI。本地继续内置 Chaquopy Python 3.10、QuickJS、MPV。
- `47a41c8b` 的完整 BT 两阶段解析与任务生命周期依赖上游运行时重构；本轮没有用
  上游实现覆盖现有 XLTaskHelper。YouTube 的取消／错误传播也需与本地 Source／ViewModel
  契约一起处理。

## 验证范围

GitHub Actions 使用 `build-validation.yml` 构建 TV／手机 arm64 APK、运行应用与库的
完整 JUnit，并比较当前 TV lint 与本地起点的完整报告。
`tv-ui-verify.yml` 的 `upstream_stability` 入口运行 12 套、78 项 Android 回归，
包括显式选集、目录筛选、真实按键接管、绑定归属、迟到弹幕、MediaSession、时移、
混版导航、Parcel、History、Logvar 与 ASSRT。结果、每套日志及截图作为 GHA artifact 保存。
验证报告中的通过状态以对应工作流实际结果为准。

模拟器覆盖 Exo 与应用行为；MPV 非方形像素画面和真实 DLNA 接收设备需另验。
