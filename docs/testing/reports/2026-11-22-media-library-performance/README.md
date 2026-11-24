# 媒体库选集与选源性能

详情选集的焦点描边改为绘制阶段更新，导航回调复用并读取最新状态。「全部剧集」复用可回收侧栏，和全屏选集、线路选择一起按可见条目生成标题。片源分集匹配在后台完成，使用请求开始时的季集与历史上下文；取消或切季后仍由代次检查丢弃旧响应。资源列表未变化的搜索响应不刷新整页。

共用选源与线路选择改为一次遍历取最优项，同分保留原列表顺序；同来源的季信息与评分不再逐线路重复读取。可靠性评分读取不扫描或维护持久化索引，实际播放结果继续更新评分。首页栏目差异更新、海报回收与取色、搜索后台匹配／批量提交／退出取消流程也做了代码检查；本次未改动这些流程。用法见[媒体库说明](../../../media-library.md)。

测试基准为 `e81c1364f8cbebc50afccd01c16fc035bf838f99`，包含本次未提交修改；文件 SHA 与测量摘要见 [verification.json](verification.json)。环境为 Linux x86_64、JDK 21、Kotlin／Compose 编译器 2.2.21、SDK 37.0，本机约 1.9 GiB 内存。使用 TV ARM64 Debug 实际依赖、当前 Gradle 生成的 R 与绑定源码及 AGP 的单测 Android jar，通过分批 Kotlin 编译、`javac --release 21 -proc:none` 和 JUnitCore 验证。

| 检查 | 结果 |
| --- | --- |
| 选集、匹配、搜索索引专项 JVM | 18 个测试类、256 项通过，失败 0；包括来源／线路同分顺序、缺集与歧义、原始文件坐标、元数据关联及搜索目录操作 |
| 生产源码编译 | 7 个修改文件及必需依赖通过，包括 Compose 页面、Java 两个调用入口和后台选源控制器 |
| 新增设备用例 | 1200 集的定位与续播目标、10000 条目的按需标题、阻塞匹配时主线程响应及回调线程、评分读取无写入／更新与清空；本轮仅验证源码编译；后续已在 API 34 模拟器执行并通过，见 [GitHub Actions 验证](#github-actions-验证) |
| 选源计算微基准 | 120 个来源、每源 12 条线路、每线 3 集；基线中位 17.0589 ms，优化后 3.9440 ms，每次均保留首个同分来源 |

微基准只替换本次修改的 `SmartSourceSelector` 与 HEAD 对应实现，使用相同当前模型、解析器、依赖和夹具。每轮预热 25 次，采集 7 组各 15 次的平均耗时；运行三轮并交替基线／优化顺序，再取三轮中位值。各轮中位为基线 17.0589／17.5141／16.9663 ms，优化 3.9440／3.6903／4.1346 ms。该结果仅反映 JVM 计算，不包含网络、持久化设置、解码、UI 帧或实体电视操作，不作为整体开播速度或流畅度提升比例。

初次手动回归混入旧 Java 编译产物，出现 22 项失败；显式重新编译当前解析器、匹配器和模型后，256 项全部通过。整组 Kotlin 编译曾耗尽 512 MiB 堆，改为按真实依赖分批编译后通过。没有替换生产类型、手写 R 或修改测试断言来绕过失败。原始日志和编译输出留在文档树外。

本轮本机没有完成完整 Gradle 变体构建、APK、lint、Android 测试执行或真实片源实播；这些检查后续由 GitHub Actions 完成，见 [GitHub Actions 验证](#github-actions-验证)。电视上的帧时间、内存峰值和网络加载延迟没有测量。

## 片源搜索复用（后续修订）

上述选集与选源修改先单独提交，文件 SHA 与 `verification.json` 顶层的 `source_sha256` 一致。本节修订在其之上，只涉及片源搜索与标题匹配，修订后的文件 SHA 记录在 `search_reuse.source_sha256`。

打开详情时先用已知标题搜索片源；TMDB 资料或片源详情到达后，原先会停止并重新搜索全部片源，被取消的爬虫请求不响应中断时还会继续占用六个并发名额。现在资料细化后若请求（片源实例、关键词及顺序）不变，则沿用进行中或已完成的搜索，并用已返回的摘要重新核对。标题、别名、季数、片源优先级或配置变化，搜索被 30 秒期限截断，或保留的摘要超过 2000 条时，仍重新搜索；单个片源的失败不再因资料细化而重试。

标题核对原先在主线程对每条搜索结果重新解析作品标题和最多 20 个别名：剧集第一季解析三组查询，电影一组。现在作品标题与别名在资料变化时解析一次，搜索响应、候选排序和后台详情确认共用；每条结果只解析自身标题。

| 检查 | 结果 |
| --- | --- |
| 专项 JVM | 18 个测试类、274 项通过，失败 0：原 16 个类 259 项（新增 3 项，核对预处理结论等价、别名细化后重新接受、未知标题拒绝），`DiscoverSourceSearchTest` 7 项（新增 2 项请求比较），`BoundedSearchBatchTest` 8 项 |
| 生产源码编译 | `source` 包、`DiscoverSourceSearch`、`BoundedSearchBatch`、`LibraryResourcesController`、`LibraryDetailActivity` 通过 `javac --release 21 -proc:none` |
| 设备用例源码 | `LibraryExperienceAndroidTest` 新增 3 项：请求相同时每个片源只搜索一次、标题变化后用新关键词重新搜索、片源详情到达后重新核对已返回的摘要；本机只通过 `javac` 源码编译 |
| 标题匹配微基准 | 一批 40 条搜索结果：1 个别名 5.89 → 1.18 ms，20 个别名 38.16 → 0.68 ms；两种实现接受的条数相同（8 条、10 条） |

微基准在同一份当前类文件上比较 `LibrarySourceMatcher.match(Vod, …)` 与 `PreparedWork.match(PreparedSource)`：预热 200 次，采集 7 组各 15 次的平均耗时，运行三轮并交替顺序，表中为三轮中位值的中位数。一次性的作品预处理另计，1 个别名约 0.12 ms，20 个别名约 0.88 ms。结果只反映本机 JVM 的计算量，电视处理器上的绝对耗时会更高；搜索复用节省的请求数和等待时间取决于片源与网络，没有测量。

本机没有运行完整 Gradle 变体、Kotlin 编译、APK、lint 或设备测试。沿用与重新搜索的判定在 JVM 上只覆盖请求比较和标题匹配；控制器在真实搜索中的行为由上述 3 项设备用例核对，执行结果见下节。

TV appearance and touch verification 工作流的 `compact_home` 回归原先固定核对 `LibraryExperienceAndroidTest` 48 项，现改为 53 项，并加入 `LibraryResourcesControllerAndroidTest` 1 项和 `SourceReliabilityStoreAndroidTest` 2 项；总数由清单计算，为 88 项。

## GitHub Actions 验证

以下运行使用分支 `perf/issue134-library-loading` 的提交 `e59e1dd425`；此后该分支只增加本节文档。设备为 API 34 x86_64 模拟器，经 `libndk_translation` 运行 ARM64 的隔离 Preview 包，片源、TMDB 与图片均为离线夹具。

| 运行 | 结果 |
| --- | --- |
| TV appearance and touch verification（`compact_home`），[45065742337](https://github.com/wobuhui666/TV/actions/runs/45065742337) | Preview 变体单测 164 个类、1273 项通过；设备 88/88 通过：首页与发现 32 项，媒体库 56 项（`LibraryExperienceAndroidTest` 53、`LibraryResourcesControllerAndroidTest` 1、`SourceReliabilityStoreAndroidTest` 2） |
| 同一工作流，基准 `e81c1364f8`，[45065746619](https://github.com/wobuhui666/TV/actions/runs/45065746619) | 单测 1266 项通过；设备 80/80 通过（修改前的固定清单） |
| 同一工作流，对照提交 `4bc6bc2e6d`（选集与选源修改加新设备用例，不含搜索复用），[45068027343](https://github.com/wobuhui666/TV/actions/runs/45068027343) | 设备 86/88：失败的只有两项统计搜索次数的用例，两个片源各被搜索两次（期望 2 次请求，实际 4 次） |
| Build isolated TV validation APKs，[45065744488](https://github.com/wobuhui666/TV/actions/runs/45065744488)（第 2 次尝试） | `:app:testLeanbackArm64_v8aDebugUnitTest` 1273 项，连同 `catvod` 244 项、`quickjs` 28 项、`wasm` 19 项共 1564 项通过；TV 与 mobile Debug 包及测试包构建通过；lint 对比基准 `e81c1364f8`，错误 108 → 108，无新增错误 |

对照运行说明两项用例能区分修改前后的行为：修改前打开详情，TMDB 资料或片源详情到达后会把每个片源再搜索一次；修改后只搜索一次。标题变化后重新搜索的用例在两种实现上都通过。

构建运行的第 1 次尝试在 lint 步骤下载 Media3 的测试依赖时被 Maven Central 限流，单测与构建当时已通过，重跑后 lint 通过。lint 对比的警告总数为 388 → 388，报告另列出 2 条位于 `app/src/main/AndroidManifest.xml` 的「新增」警告，本分支没有修改该文件。

没有覆盖：实体电视（包括 issue 134 的华为智慧屏）上的帧时间与内存、真实片源的搜索耗时和实际开播时间、超过 2000 条搜索摘要的配置。
