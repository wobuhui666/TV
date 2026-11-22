# 首页栏目完整筛选验证

国产电影、国产剧集和区域栏目进入列表后使用可分页的完整 TMDB 片库；首页继续使用原有 Rex 榜单、近期范围和预览数量。剧集支持综艺、影视剧、真人秀、脱口秀、纪录片、新闻、迷你剧及视频节目，类型列表来自 TMDB，另有 17 个地区、年份和排序。今日趋势选定电影或剧集后展开条件，加条件后使用片库查询，清空后恢复每日趋势。用法见 [TV 媒体库体验](../../../media-library.md)。

基准提交为 `e81c1364f8cbebc50afccd01c16fc035bf838f99`，测试对象包含本次未提交修改。源码 SHA、真实接口摘要和覆盖范围见 [verification.json](verification.json)。环境为 Linux、JDK 21、Gradle 9.1.0、AGP 9.0.1、SDK 37.0；本机约 1.9 GiB 内存、2 GiB swap。

| 检查 | 结果 |
| --- | --- |
| 专项 JUnit | 59 项通过，失败 0：查询 18、TMDB 原查询 19、Rex 栏目 10、首页策略 12 |
| Java 编译 | 本次生产文件及必要 Java 依赖、四个单测类、11 项 Android 海报墙用例的源码全部通过 |
| TMDB 真实请求 | 25 次成功：剧集 16 类、电影 19 类；综艺使用 `10764\|10767`，前两页共 40 部均含真人秀或脱口秀；七种 `with_type` 的样本详情均对应正确性质 |
| 完整列表 | 国产剧和综艺第二页各返回 20 部；国产综艺返回总数 1,587、80 页；2020 年国产电影返回总数 1,214、61 页 |
| 首页保留 | 原首页展示、首页请求和 feed 加载文件 SHA 未变；栏目断言确认首页仍取 Rex feed，进入列表后取 TMDB discover |

专项测试覆盖综艺的“或”关系、节目性质 0～6、取消地区限制、旧年份及开放日期范围、趋势恢复、原类型／平台／评分约束和首页预览上限。最初单独执行的 37 项查询与 2 项入口检查已包含在最终 59 项中，不重复累计。真实接口首次取样为 11 月 21 日，11 月 22 日补查详情及第二页；计数以取样时 TMDB 返回为准，达到接口上限的结果数不作为完整数据库计数。

源码使用 JDK `javac --release 21 -proc:none`、标准 Gradle 生成的当前 R.jar、真实 TV ARM64 Debug 依赖和本地现有 Java／Kotlin 编译输出验证；没有替换生产模型或手写 R。测试运行使用该变体的真实单测 runtime classpath 与 AGP `returnDefaultValues=true` Android jar。初次手动运行误用了编译用 Android 桩，导致 17 项 `Stub!` 失败；调整运行 classpath 后又补齐本地现有类目录，最终上述 59 项全部通过，生产源码未因这些运行配置错误而修改。

标准 `:app:testLeanbackArm64_v8aDebugUnitTest` 和 `:app:compileLeanbackArm64_v8aDebugAndroidTestJavaWithJavac` 构建链尝试未完成：默认 4 GiB 进程退出，768 MiB 报 heap 不足，1536 MiB 重试耗尽本机 swap 后被主动停止。后续只导出真实变体 classpath，完成上述独立 Java 编译及 JUnit；完整 Gradle 变体、APK 构建、lint 和设备 UI 执行均未完成。测试设备当时运行已有 MPV instrumentation，未打断它、安装新包或生成本次界面截图。

Key 保留在被 Git 忽略的 `local.properties`，文件权限 600；公开记录仅保存参数、计数及样本，不含 Key。后续 TMDB 改动使用真实内容验证的要求已写入根目录 AGENTS.md。原始日志、编译输出和凭据留在文档目录外。
