# 媒体库首页大卡与新剧数据验证

最新布局修订已移除媒体库整排分类／筛选，将现有小海报放在大卡上方，并固定大卡为近一月热门综艺、近一月热门剧集、备受欢迎电影和每日新番。本轮 20 项首页／栏目检查、完整 Java 编译及新版 APK 校验通过，源码和产物见 `verification.json` 的 `layoutRevision`。下述首版 60 项测试和原 APK 为历史快照，不包含这次布局修订。

## 当前布局修订

媒体库整排“全部／电影／剧集／高分／筛选找片”不再加入页面或作为焦点回退。现有三个小海报架先展示，大卡固定为近一月热门综艺、近一月热门剧集、备受欢迎电影、每日新番；每张大卡下方继续保留独立小横图。收藏锚点跟随完整最近播放区，没有历史时跟随主视觉，避免删除分类行后移到页尾。

标准 `:app:processLeanbackArm64_v8aDebugResources` 已通过，未触发生产源码编译。使用其真实 R.jar、原完整 Main/model/SDK 运行依赖，通过 JDK `javac` 编译本轮 Controller、Catalog、RexSections 与原测试文件（7.471 秒）；未使用自定义 R 或模型替身。`PosterHomeCatalogTest` 12 项及 `RexHomeCatalogTest` 8 项通过，共 20 项，失败为 0。该检查不是重新执行完整 Gradle 变体；Android 界面回归未执行。

本轮新包基于首版冻结来源加本轮明确文件生成，标准资源、完整生产 Java、新主程序 DEX、打包与签名实际执行。原 86 个 Kotlin 源与 469 个真实编译输出逐 SHA 校验未变，R 字段为 non-final，新增 API 无 Kotlin 调用，故明确复用 Main Kotlin 编译输出。

外部依赖／SDK／编译选项未变，5 份原 ExtDex 输出按完整标准 Gradle `dex-renamer-state` 映射从原已验证 APK 逐字节恢复；其余 33 份原 DEX 对应 SHA 全匹配。这 5 份的 32787 个实际 class definitions 中没有 App namespace，不能用旧 App 定义覆盖本轮布局代码。422 个依赖归档、原输出及映射校验后仅复用精确 ExtDex 任务，其余构建照常执行；没有手工修改成品 APK。

本轮新版调试包为 `library-home-order-arm64-v8a-debug.apk`，120078353 字节，SHA-256 `c0c919bb4bb1c260ea759efa49b14767e8fb06e1a8043d466575222bdbe619de`。本机完整下载哈希与远端相同，签名通过，App R 的新字段、英／简／繁标签与大卡布局均入包；35 个 ARM64 native 库及 20 项 Python 资源逐项与原包一致。

后续并行日历／选源器更改不在本包中；Android 界面回归、Preview 与 lint 本轮未执行，目标设备仍有其他 MPV 测试占用。首版下方 APK 不能代替本轮包。

## 首版恢复快照

媒体库首页大卡恢复、当前 Rex 中文分区榜单及近期 TMDB 回退已通过 **60 项变体专项 JUnit**，失败／错误／跳过均为 0。TV ARM64 Debug 构建通过，完整下载 SHA-256、签名、35 个 ARM64 native 库、20 项 Python 资源及大卡布局校验通过。Android 界面回归未执行。

## 版本与行为

基准提交为 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，包含已有未提交修改；产物来自 2017 文件的冻结构建清单及两处现有并行编译修复。15 项本轮首页／Rex／测试输入及两修复 SHA 见 [verification.json](verification.json)。后续并行选源器变更未纳入此包，原工作区未被回滚。

媒体库恢复栏目大横图和下方独立小卡，放在普通海报行之前。默认及剧集显示华语剧集、韩剧、华语综艺和每日新番，电影／高分随分类切换；简洁模式保留原海报行。国产剧海报行与华语剧大卡共用一次 Rex 请求，首页最多 12 部，栏目共享缓存最多 20 部；完整栏目页直接请求 feed。

Rex 中文分区保留当期榜单顺序，不用西方节目补位，manifest 仅接受近七天日期；TMDB 回退限定华语 CN／韩剧 KR 近 30 天播出的作品，允许旧剧新季。国产电影热门限定近一年已上映作品，高分榜保留历年作品。用法见 [TV 媒体库体验](../../../../LIBRARY_EXPERIENCE.md)。

2026-11-19 公开 manifest 为 `20261119`，中文剧集 feed 前三部为《今天是太阳雨》（11 月 6 日）、《折月亮》（11 月 5 日）、《走马上任》（11 月 2 日），与用户参考图一致。URL、生成时间与榜单窗口见 [rex-live.json](rex-live.json)；这项检查不代表这些作品均有播放资源。

## 构建与检查

环境为隔离 Linux x86_64 开发目录、JDK 21、Gradle 9.1.0、AGP 9.0.1、SDK 37.0、固定 Media3 `342f6590e02ac390e0bd6b4488b8a1bff55747c1`。原生产补丁检查及构建源码清单校验通过；未复制未使用的上游测试媒体。构建使用 1536 MiB heap／G1GC、768 MiB metaspace、单 worker；JUnit 256 MiB 单 fork。配置方法见 [本地构建说明](../../../../LOCAL_BUILD_ENV.md)。

| 检查 | 结果与范围 |
| --- | --- |
| `:app:testLeanbackArm64_v8aDebugUnitTest` | 60 项通过，案例累计 5.879 秒：取消 1、区域编排 4、Rex 映射 11、日期 4、TMDB 查询 20、首页策略 12、Rex 栏目策略 8 |
| 测试源码 | 仓库外 init 脚本只编译上述 7 个原 Java 测试文件；生产 main、标准 SDK／依赖未替换。另 2 个日历 Kotlin 测试文件编译但未执行。早期 24 项纯查询／日期检查是这 60 项的子集，不重复累加 |
| `:app:assembleLeanbackArm64_v8aDebug` | 通过，5058 秒；包名 `com.fongmi.android.tv`，版本 `5.8.7`／587，调试签名 |
| 产物校验 | 117073095 字节；完整下载 SHA 与远端一致，本机及远端签名验证通过；35 个 ARM64 库、20 项 Python 资源、大卡布局存在 |
| 静态与文档 | 本轮 `git diff --check` 和使用说明相对链接通过 |
| Android／Preview／lint | Android 界面回归、Preview／测试包构建与 lint 本轮未执行 |

APK SHA-256：`2843229e39a89f5489f6b101a0660cda293a145893b20dda395fe8cd37b37f51`。生成文件留在仓库外的验证目录及被忽略的 `app/build/outputs/apk/leanbackArm64_v8a/debug/`，未放入文档树。

## 覆盖限制

目标设备仍有另一项活动的 MPV instrumentation，旧 Preview 签名也与当前 debug 证书不同。本轮未安装或运行 Preview，未打断该测试、切换前台或清空数据。因此没有当前实际首页截图，也不宣称遥控器／触摸或实体电视验收通过。新增 Android 用例保留用于后续验证，其源码本轮未编译执行。

未跑全量单元测试、完整 Java 测试源码编译、全量 lint、全部第三方源或真实播放资源。原始日志和私有配置保留在文档树外；测试计数、源码 SHA、产物及签名摘要集中在 [verification.json](verification.json)。
