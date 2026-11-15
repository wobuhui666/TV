# 媒体库搜索与界面验证（2026-11-14）

## 后续界面修正

用户要求去掉返回图标、恢复竖版海报并复用 App 组件。搜索、详情与图库已移除额外返回控件，遥控器 Back 保留。搜索、推荐和带图卡片直接使用现有 `adapter_rex_poster.xml`／JetStream 组件：作品海报为 2:3，剧集、预告、剧照为 16:9，演员保持圆形头像；搜索卡只显示标题与一行年份／资源信息。图片、字体、圆角、焦点描边和缩放均沿用既有组件。

浅色、深色和跟随系统沿用全局外观偏好；搜索、详情与图库原地刷新主题，保留当前页面与焦点。顶栏模糊快照在切换主题时失效重采，遮罩淡入当前主题背景。视差、渐进模糊、毛玻璃顶栏与动态标题保留。

本轮最终 `:app:compileLeanbackArm64_v8aDebugKotlin`、`:app:compileLeanbackArm64_v8aDebugJavaWithJavac` 和 `:app:assembleLeanbackArm64_v8aDebug` 均已通过，资源与 Binding 生成正常。使用 JDK 21、SDK 37.0、单个 worker、临时 1 GiB Gradle heap；较早 768 MiB 编译因内存不足失败，临时调整未修改仓库构建配置。[本轮源码与构建记录](native-ui-update.json)包含最终文件哈希和 APK 资料。

本地 APK 位于 `app/build/outputs/apk/leanbackArm64_v8a/debug/app-leanback-arm64_v8a-debug.apk`，保留 ARM64 原生库和 Python 资源，签名验证通过。它使用默认本地 Debug 签名、主包名 `com.fongmi.android.tv`，未注入 TMDB 密钥，不能直接替代此前带配置和匹配证书的 Preview 测试包；尚未安装到设备。2026-11-15 按用户要求另行构建了带有效 TMDB Key 的完整 ARM64 包：`/home/ubuntu/TV-concepts/TV-library-native-ui-apikey-20261115-arm64.apk`。TMDB 配置校验返回 HTTP 200，APK 内精确 BuildConfig 字段匹配，签名验证通过；117,039,779 字节，SHA256 `d1bf9628289c87ae315bac9c42924f988162c448a6f664197ce5bdb79636260d`。Key 只通过构建环境变量注入，未写入源码或公开记录；签名与前一本地 Debug 包一致，与旧 Preview 包不同。新版仍未安装到设备验证。

[浅色设计预览](images/search-light-design.png)与[深色设计预览](images/search-dark-design.png)使用真实《黑帮领地》海报和当前布局尺寸，由本地浏览器渲染；图片已标注设计预览，状态文案简写，不是 Android 截图或真实搜索命中。本地无已连接的 ADB 设备，新增／更新的 UI 回归尚未运行。下方旧 Android 测试、性能数据和旧截图不覆盖这轮卡片、返回控件和主题修改。

## 搜索性能修复

用户反馈输入关键词后搜索结果页卡死。旧实现每次片源回调在主线程执行三轮全量关联，并向 ScrollView 插入全部海报卡片。此次将响应解析、片源准备与关联移到后台，复用相同标题／年份／类型的准备资料，使用 RecyclerView 回收卡片。片源健康统计的哈希和系统查询也移到工作线程；配置作用域按 URL 和清理代次缓存。

Glide 与 MiSans 字体后台预热，冷请求异步等待，避免首次建卡时在主线程初始化图片库或复制字体文件。中性色直接读取共用主题色，不再为每个标题构造强调色板。筛选和退出保留取消保护，作品、年份、季集及歧义判断保持原规则。

详情沿用 App 的 JetStream 主题、MiSans 和焦点状态，保留海报视差、渐进模糊、毛玻璃顶栏与动态标题。输入页后台流式读取，最多 30 个热词和 9 条历史；热词每批 6 条。异步期间的新增／删除会合并旧历史；原生分区标题保持共用样式，同帧输入合并，关闭系统拼写检查，键盘保留 Editable 插入／删除文字，麦克风确认后启动。

## 范围与环境

- 底提交：`6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，加未提交工作区修改。[56 个已核对文件](tested-ui-sources.json)限定一致范围；整个并行工作区不属于此一致性声明。
- JDK 21、Android SDK 37；API 24 x86_64 模拟器，960 × 540、160 dpi、软件 GPU。
- 隔离应用 `com.fongmi.android.tv.preview`。模拟器移除 ARM 库和 Python 资源，保留代码和界面资源；交付使用完整 ARM64 APK。
- TMDB、图片、片源和建议接口使用离线夹具。密钥、APK、原始私有日志在仓库外。

## 结果

| 检查 | 结果 |
| --- | --- |
| 12 类 Gradle 单测 | 91 项通过，涵盖解析、匹配、季集、作用域和并发批次 |
| TV Preview／AndroidTest、手机 ARM64 Debug | 构建通过；手机沿用同一 Compose BOM 补充共享 Kotlin 编译需要的 Runtime |
| 作品融合、手动选源返回焦点、预获取全屏交接 | 3 项通过；媒体请求使用夹具，未验证真实 ARM 解码 |
| 网络／缓存回调、缓存取消、阻塞探针 | 2 项通过；取消后零回调，300 ms 人为阻塞能被检测 |
| 大结果压力 | 240 个作品、8 源共 1,920 条资源关联完成，准备资料 240 份；创建卡片 `<64`、附着卡片 `<40` 断言通过。最大 Handler 延迟 **791 ms，未通过 250 ms 预算** |
| 输入页 5,000 条缓存、完整 30 词／9 历史 | 最大 Handler 延迟 **515 ms，未通过 250 ms 预算**；后续光标、退格、清空断言未执行，不计为通过 |
| TV lint | 基线／当前均 108 个错误，新增错误 0；警告 348 → 375，新增警告 30（部分旧警告消失），Hint 均 6 |

最终 6 项核心／功能检查为 5 通过、1 项响应预算失败；另 1 项输入压力预算失败。大结果最后观察完成时间为 29,537 ms（45 秒内）。导航独立记录 5,451 ms、最大 Handler 延迟 3,572 ms，无导航阈值；输入页启动（含布局）记录 6,930／10,095 ms。结果页残余栈包含 TextView 样式／文字度量、焦点／主题附着和中性色设置读取，不能将全部延迟归因于 GPU。

首轮同步启动测试未进入压力正文，后改用仓库已有异步启动方式，截图移到测量结束后。最终仅因 lint 对不可变字符串列表结构比较的误报增加精确注解。重打包后资源、清单相同；两个 DEX 存在元数据／调试信息及生成 EventIndex 初始化顺序差异，方法与 16 个订阅项的语义比较一致。因此保留先前 Android 结果，**最终 APK 未直接重跑 Android 检查**。此前 13 项界面回归已通过，最终未整组重跑。

Gradle 使用 `:app:testLeanbackArm64_v8aDebugUnitTest`（12 类筛选）、`:app:assembleLeanbackArm64_v8aPreview`、`:app:assembleLeanbackArm64_v8aPreviewAndroidTest -PtvPreview`、`:app:assembleMobileArm64_v8aDebug` 和 `:app:lintLeanbackArm64_v8aDebug`。逐项结果、性能和重打包证据见 [公开摘要](results.json)。

## 界面与交付

[最新搜索页](images/search-results.png)显示一个作品、两个资源与来源补充，使用小型离线夹具；它不是 240 作品压力截图。[详情页](images/detail.png)来自此前 13 项界面检查，海报与顶栏布局沿用相同实现。

交付文件在仓库外：`TV-library-search-fix-20261114-arm64.apk`，116,711,495 字节，35 个 ARM64 原生库及 Python 资源；包名 `com.fongmi.android.tv.preview`，版本 `5.8.7-library-search-fix-20261114-preview`。签名验证通过，与此前测试包证书相同。SHA256：`cea0fe43cc1825447af9e44944d0915b74f11e3b723838557fb84b8b8ab090c3`。

## 覆盖限制

已优化确认的同步重活，尚不能声称完全流畅。两个响应预算仍未达标，导航也有独立停顿数据。软件 GPU、框架初始化及源码采样不能代替用户电视的 ANR／主线程栈；真实片源、公网、ARM 解码和实机流畅度需要安装完整包复测。
