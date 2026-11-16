# 连续集数的社区跳过匹配

测试基于提交 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec` 加本次未提交的 `CommunitySkipRuntime`、`EpisodeIdentityResolver` 与对应测试改动。环境为 Ubuntu、JDK 21、JUnit4、真实 Gson / OkHttp / MockWebServer / Okio / Kotlin 依赖；本机约 909 MiB 内存。

## 修复与结果

- 来源线路的真实集号完整覆盖 1–N，且 N 超过 TMDB 每个正常季的集数时，查询唯一 Absolute 分组转换季集。缺号、重复号、生成序号、明确季号、特别篇和冲突分组不作为连续编号依据。
- 11 个生产 Java 文件在隔离 JVM 环境编译通过。6 个测试类的 69 项 JUnit 测试通过：身份识别、来源编号、服务回退、区间解析、跳过策略及会话快照。
- 一次 MockWebServer 集成检查通过：171 集来源目录经真实 Runtime、身份解析器、API 和策略，得到 S1E4，返回片头结束 `135009 ms` 和片尾结束 `1393016 ms`；回退后不重复跳过。
- `git diff --check` 通过。

JVM 检查仅将 Android 平台、App 回调、设置和模型替换为仓库外桩件，不包含 Android 播放器、实际 seek、界面或设备验证。完整 Gradle 变体测试未完成：首次检查中断，256 MiB 堆、单 worker 的重试仍长时间停在配置阶段，随后手动终止；没有生成或安装 APK。

## 中间层优化前的公开数据与网络

TMDB `73223` 的第 1 季为 170 集，第 2 季为 26 集，另有特别篇。官方 Absolute 分组将连续第 4 集对应到 S1E4、第 171 集对应到 S2E1。使用明确 TMDB ID、HTTP/1.1 和代理无密钥读取的生产解析器检查通过，详情与分组共两次请求，下一集复用缓存。

直接读取 IntroDB 确认 S1E4 的片头区间为 `45045–135009 ms`，片尾为 `1303050–1393016 ms`。一次 Java 公共服务查询在 6 秒预算内返回空区间，因此没有确认当前网络下完整自动跳过。

同一默认 TMDB 代理 `tapi.coolmarket.eu.org` 的请求对比，每项使用相同的 8 秒期限：

| 请求 | HTTP/2 | HTTP/1.1 |
| --- | --- | --- |
| 搜索「黑色五叶草」 | 超时，未收到响应头 | 超时，未收到响应头 |
| `tv/73223` 详情及季集分组摘要 | 200，约 250 ms | 200，约 136 ms |

搜索的 DNS、连接、TLS 和请求发送均已完成，等待搜索响应时超时；HTTP/2 的取消是超时后的结果。直连官方的无密钥搜索约 103 ms 返回 401，仅证明端点可达，不代表已验证带有效密钥的搜索。无法区分该代理的转发、缓存或上游问题，也没有依据将故障归因于地区或强制使用 HTTP/1.1。

## 验证边界

来源缺少可靠连续编号，或目录尚未超过任一正常季长度时，继续保持多季不猜测的行为。截图来源的原始播放列表、电视上的网络设置及安装版本未取得，不能将上述结果视为该电视完整播放验收。

## Groq 与 Workers AI 部署结果

随后按用户授权完成中间层优化和两端部署。实际 Vercel 项目关联的仓库为 [zhhshss/tttt](https://github.com/zhhshss/tttt)，部署源码提交为 `c5c55583e4c07014fe262ecfc37c4269d9b892e4`；最初提供的公开仓库 `wobuhui666/tttt` 当前账号没有写权限。Vercel 部署状态为 READY，Cloudflare 更新现有 `tmdb-proxy-worker`，继续使用 `t.coolmarket.eu.org/*` 和 `tapicf.coolmarket.eu.org/*` 路由。

- 规则优先移除年份、季集、更新、画质和配音说明，保留“1917”、续作编号、标点及“原声带”等实际标题。缓存查询先于模型调用，并合并重复请求。
- 主模型为 Groq `qwen/qwen3.8-27b`，明确配置 `reasoning_effort=none`、小输出预算；直接模型检查约 138 ms 返回正确片名，没有 reasoning 内容。
- 兜底为 Cloudflare Workers AI `@cf/meta/llama-3.1-8b-instruct-fast`。Cloudflare 使用原生 AI 绑定，Vercel 调用带独立令牌的 Worker 内部接口；Cloudflare 管理令牌没有放入应用配置。
- 单个 Groq 请求默认 1200 ms，整条名称处理默认 2200 ms，代理请求默认总预算 6500 ms。超时与错误返回本地名称或正常错误响应，不等待原来的两次 15 秒模型调用。
- 客户端默认地址同步为 `https://t.coolmarket.eu.org/`，并通过受限的 `X-Tmdb-Query` 名称清理声明衔接原有严格身份核验。年份、季集、核心数字及标点继续核验，缓存保留名称清理依据。

中间层真实源码的 29 项模拟服务测试、语法检查及差异检查通过，覆盖模型回退、思考参数、缓存、并发取消、鉴权隔离、流式响应和标题误删。应用侧 68 项专项 JVM 测试通过，编译了 10 个真实生产文件；平台、偏好和共享网络工厂仅使用 5 个仓库外桩件。另已更新现有发现页测试的 7 处默认图片域名预期。

部署后的公开请求结果：

| 检查 | 结果 |
| --- | --- |
| Cloudflare 复杂标题、本地规则 | 200，约 564 ms，正确 TMDB 条目 `73223` |
| Cloudflare 带来源包装的标题、Groq | 200，约 388 ms，正确条目 |
| Vercel 复杂标题、本地规则 | 200，约 299 ms，正确条目 |
| Vercel 带来源包装的标题、Groq | 200，约 318 ms，正确条目 |
| 受保护的 Worker 内部接口、实际 Workers AI | 200，约 667 ms，返回正确片名 |
| 受控 Groq 失败、真实 Worker 兜底及 TMDB | 200，约 802 ms，使用 Workers AI 并得到正确条目 |
| “原声带 (2022) 1080p” | 保留“原声带”，200，约 420 ms |
| 两端图片代理 | 200，相同 22576 字节 JPEG，约 430 / 698 ms |

真实生产身份解析器使用新域名及相同 8 秒总预算，复杂标题的连续第 4 集约 1417 ms 正确匹配 S1E4，连续第 171 集复用缓存后约 15 ms 匹配 S2E1，合计仅 3 次 HTTP 请求。IntroDB 本次再查询返回 200，约 365 ms，片头及片尾区间与前述记录一致。

本次从测试主机分别验证了 Cloudflare 路由和 Vercel 源站，保留已有分流配置；没有在电信设备现场验证 DNS 分流。TV 客户端修改已验证源码与 JVM 行为，但未构建新 APK、未安装至用户电视，也未验证实际播放器 seek。

随后按用户授权将 Cloudflare Worker 改名为 `tttt`，与 Vercel 项目名称一致；上文的 `tmdb-proxy-worker` 为改名前名称。迁移保留原有模块、配置、密钥，以及 `t.coolmarket.eu.org/*` 和 `tapicf.coolmarket.eu.org/*` 路由，旧 Worker 已删除。迁移后的两条域名路由搜索均返回 200 和正确条目 `73223`，受保护的实际 Workers AI 调用返回 200；当前两端项目名称均为 `tttt`。


## 2026-11-15 独立 PR 验证

本次从基线 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec` 建立独立工作树，仅纳入社区连续集号匹配、反代标题清理、默认 TMDB 域名、对应测试与本报告。未纳入主工作树的媒体库、界面或播放器实验。本节为新的验证记录，上文的部署与测试结果保留为历史证据。

Ubuntu 24.04、OpenJDK 21.0.13.0.3；14 个真实生产 Java 文件和 7 个真实测试类通过独立 `javac` 编译，82 项 JUnit4 测试全部通过，零失败／跳过，JUnit 执行用时 4.205 秒。覆盖连续／倒序目录、缺集／重复／生成号拒绝、Absolute 映射和冲突、标题声明边界、缓存与取消、服务回退、区间解析、单次跳过、会话快照及 TMDB 路由判定。`DiscoverApiTest` 仅同步 7 处默认图片域名预期，本轮没有执行该测试类。

独立检查使用真实 Gson 2.14.0、OkHttp JVM 5.5.0、MockWebServer／MockWebServer3 5.5.0、Okio JVM 3.18.1、Kotlin stdlib 2.2.21、JUnit 4.13.2、Hamcrest 1.3。既有 Gradle 缓存提供其余依赖，缺少的 `okhttp-jvm-5.5.0.jar` 与 `mockwebserver-5.5.0.jar` 从 Maven Central 下载至仓库外；使用 JVM 平台依赖，未加载 Android AAR。

13 个仓库外 shim 仅提供编译和宿主机测试所需的环境：

| shim 路径 | 作用与边界 |
| --- | --- |
| `androidx/annotation/NonNull.java`、`Nullable.java` | 空注解，保留编译签名 |
| `androidx/media3/common/C.java` | 仅提供实际 `TIME_UNSET` 常量 |
| `com/fongmi/android/tv/App.java` | `post` 同步执行；不验证 Android Looper／线程调度 |
| `com/fongmi/android/tv/BuildConfig.java` | TMDB 密钥为空；实际测试通过注入 MockWebServer 工厂执行 |
| `com/fongmi/android/tv/setting/Setting.java` | 提供 TMDB 模式常量，默认反代及开启社区跳过；不验证偏好存储 |
| `com/fongmi/android/tv/bean/Episode.java` | 提供来源标签、URL 和生成标签标志；不执行 Parcelable 或 Android 模型功能 |
| `com/fongmi/android/tv/bean/Flag.java`、`Vod.java` | 仅提供编译接口；目录测试直接调用真实 Runtime 的标签检查方法 |
| `com/fongmi/android/tv/bean/History.java` | 保存测试用字段与 getter／setter；不验证 Room 或真实 History 策略 |
| `com/github/catvod/utils/Prefers.java` | 返回默认值，写入为空；不验证存储 |
| `com/github/catvod/net/OkHttp.java` | 提供真实 OkHttpClient 和无 DNS override；不加载应用共享网络配置 |
| `com/github/catvod/net/CloudflareRouteFallback.java` | 直接继续请求；不验证 Cloudflare 路由恢复 |

`EpisodeIdentityResolver`、`CommunitySkipRuntime`、`TmdbNetwork`、`Trans` 及下表生产类均为隔离工作树的原始源码，没有改写被测方法。此检查不包含 Android 播放器、实际 seek、设备网络、电视画面、Android 调度或完整 Gradle 变体；不代表这些功能已验收。

宿主机入口为 `python3 /tmp/tv-community-pr-verify.py /home/ubuntu/.zcf/TV/pr-community-skip-20261115`。临时脚本、完整实际命令 argv、13 份 shim 原文、依赖校验值及编译／JUnit 输出保留在仓库外的 `/tmp/tv-community-pr-verification/`，其中 `manifest.json` 登记本节所有来源。实际编译及运行命令如下；`PR_WORKTREE` 为上述隔离目录，`JVM_WORK` 为上述验证目录，`JVM_CP` 为前述八个依赖 JAR 的完整路径，以冒号连接。

```bash
javac -J-Xmx192m -encoding UTF-8 -cp "$JVM_CP" -d "$JVM_WORK/classes" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/CommunitySkipRuntime.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/EpisodeIdentityResolver.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/CommunitySkipApi.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/CommunitySkipParser.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/EpisodeIdentity.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/PlaybackEpisodeSnapshot.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/SegmentSkipPolicy.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/player/skip/SkipSegment.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/source/EpisodeLabel.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/source/EpisodeLabelParser.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/bean/DiscoverMediaKey.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/utils/TmdbEndpoint.java" \
    "$PR_WORKTREE/app/src/main/java/com/fongmi/android/tv/utils/TmdbNetwork.java" \
    "$PR_WORKTREE/catvod/src/main/java/com/github/catvod/utils/Trans.java" \
    "$PR_WORKTREE/app/src/test/java/com/fongmi/android/tv/player/skip/CommunitySkipApiTest.java" \
    "$PR_WORKTREE/app/src/test/java/com/fongmi/android/tv/player/skip/CommunitySkipEpisodeOrderTest.java" \
    "$PR_WORKTREE/app/src/test/java/com/fongmi/android/tv/player/skip/CommunitySkipParserTest.java" \
    "$PR_WORKTREE/app/src/test/java/com/fongmi/android/tv/player/skip/EpisodeIdentityResolverTest.java" \
    "$PR_WORKTREE/app/src/test/java/com/fongmi/android/tv/player/skip/PlaybackEpisodeSnapshotTest.java" \
    "$PR_WORKTREE/app/src/test/java/com/fongmi/android/tv/player/skip/SegmentSkipPolicyTest.java" \
    "$PR_WORKTREE/app/src/test/java/com/fongmi/android/tv/utils/TmdbEndpointTest.java" \
    "$JVM_WORK/shims/androidx/media3/common/C.java" \
    "$JVM_WORK/shims/com/fongmi/android/tv/App.java" \
    "$JVM_WORK/shims/com/fongmi/android/tv/BuildConfig.java" \
    "$JVM_WORK/shims/com/fongmi/android/tv/setting/Setting.java" \
    "$JVM_WORK/shims/com/fongmi/android/tv/bean/Episode.java" \
    "$JVM_WORK/shims/com/fongmi/android/tv/bean/Flag.java" \
    "$JVM_WORK/shims/com/fongmi/android/tv/bean/Vod.java" \
    "$JVM_WORK/shims/com/github/catvod/utils/Prefers.java" \
    "$JVM_WORK/shims/com/github/catvod/net/OkHttp.java" \
    "$JVM_WORK/shims/com/github/catvod/net/CloudflareRouteFallback.java" \
    "$JVM_WORK/shims/androidx/annotation/Nullable.java" \
    "$JVM_WORK/shims/androidx/annotation/NonNull.java" \
    "$JVM_WORK/shims/com/fongmi/android/tv/bean/History.java"

java -Xmx192m -cp "$JVM_WORK/classes:$JVM_CP" org.junit.runner.JUnitCore \
    com.fongmi.android.tv.player.skip.CommunitySkipApiTest \
    com.fongmi.android.tv.player.skip.CommunitySkipEpisodeOrderTest \
    com.fongmi.android.tv.player.skip.CommunitySkipParserTest \
    com.fongmi.android.tv.player.skip.EpisodeIdentityResolverTest \
    com.fongmi.android.tv.player.skip.PlaybackEpisodeSnapshotTest \
    com.fongmi.android.tv.player.skip.SegmentSkipPolicyTest \
    com.fongmi.android.tv.utils.TmdbEndpointTest
```

以下 SHA-256 登记精确被测源码；它们对应本次隔离工作区增量，不能用基线号代替。

| 生产／测试文件 | SHA-256 |
| --- | --- |
| `app/src/main/java/com/fongmi/android/tv/player/skip/CommunitySkipRuntime.java` | `b68f0a011b8ba53227ba97e215b87a8a7a60e2b0bd77c81a26d07f237adf9dd1` |
| `app/src/main/java/com/fongmi/android/tv/player/skip/EpisodeIdentityResolver.java` | `f0dfcc57596be12374950aac16feabfe3754673751432edd434d175fcba7d48a` |
| `app/src/main/java/com/fongmi/android/tv/player/skip/CommunitySkipApi.java` | `1bba1547813a68a3d90a8aff3f8fd67b290726a78947cfe31e1d72439cdbf4e7` |
| `app/src/main/java/com/fongmi/android/tv/player/skip/CommunitySkipParser.java` | `39f9510f71fa9a479a9a775303bfec913ff6705e62b643f435bdb3d9b018bd4f` |
| `app/src/main/java/com/fongmi/android/tv/player/skip/EpisodeIdentity.java` | `1eddd6198046ba7b9e355992161cb6dcc59706a6f1f20a3a769f8f9dc2d716dc` |
| `app/src/main/java/com/fongmi/android/tv/player/skip/PlaybackEpisodeSnapshot.java` | `898a57ba1e55b00d570088695c9033a3f795f9f030040407b40d18a25789fcb2` |
| `app/src/main/java/com/fongmi/android/tv/player/skip/SegmentSkipPolicy.java` | `2781fca28c627023901ed94541bcdcdfef5191c24b0507316fdca57bf980f535` |
| `app/src/main/java/com/fongmi/android/tv/player/skip/SkipSegment.java` | `d916f3cf5a63b85d51aaf917aa9f8fa8471d90defb34ebc8b23bfdad6ad8e8a5` |
| `app/src/main/java/com/fongmi/android/tv/source/EpisodeLabel.java` | `262a537c963fbcc78edff3e404a25052b04be5de1ebaba47db59ee7acb34e256` |
| `app/src/main/java/com/fongmi/android/tv/source/EpisodeLabelParser.java` | `df6bde514ae7b5af17a0c77c810329115687902780cc38ef9a71eb4b1a7e0d79` |
| `app/src/main/java/com/fongmi/android/tv/bean/DiscoverMediaKey.java` | `0b4aa5082238012390874ccf80bd75237937dd4960c364b5d340ad47f74fd6fc` |
| `app/src/main/java/com/fongmi/android/tv/utils/TmdbEndpoint.java` | `3905712d22f083e3a4050fd50caa1f669b1e4e4ed4b11b6d54723ceab28f76cd` |
| `app/src/main/java/com/fongmi/android/tv/utils/TmdbNetwork.java` | `5b77388182b15ef1864b6657af6d01185999802583f0834b1c3b7d45e5da864c` |
| `catvod/src/main/java/com/github/catvod/utils/Trans.java` | `1205d9c4dba0961bea75d04c55564f6cc97b48c6bbd362b8f2fbf91221c5626e` |
| `app/src/test/java/com/fongmi/android/tv/player/skip/CommunitySkipApiTest.java` | `a51bc458243e6271a78b8a3388710b459f07e4b784c6d3b51e2e5bae8f980360` |
| `app/src/test/java/com/fongmi/android/tv/player/skip/CommunitySkipEpisodeOrderTest.java` | `8e35fb93ae7a2d912ae5ae97979bc639cad83d9fdef20887867fb160d43d6969` |
| `app/src/test/java/com/fongmi/android/tv/player/skip/CommunitySkipParserTest.java` | `a6cb1387a4b7b0e8fed793f2fcae5568b53d5e0e7557d5d01be0e453a26d6e9d` |
| `app/src/test/java/com/fongmi/android/tv/player/skip/EpisodeIdentityResolverTest.java` | `92b15c69072cef703586fce2c9e4e28d912769d7e72f54fe3f6f94067d994ff1` |
| `app/src/test/java/com/fongmi/android/tv/player/skip/PlaybackEpisodeSnapshotTest.java` | `d6818bfe48a484cc9abbbee331025a0344ed12f9a8a0995c1b443d80733b0d7c` |
| `app/src/test/java/com/fongmi/android/tv/player/skip/SegmentSkipPolicyTest.java` | `1e5cab5c7d60911a3f701af60b292dafd3ee3a644cc5da5a840c2319114c9884` |
| `app/src/test/java/com/fongmi/android/tv/utils/TmdbEndpointTest.java` | `245fded6832b2d3a41ce4d82b3357aadf11533f41a93d88094f328c151bf1044` |
