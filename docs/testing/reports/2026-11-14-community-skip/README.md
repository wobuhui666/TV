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

## 2026-11-15：短提示与 fast 主接口

按用户授权将主接口改为 `https://nnnn.114514heihei.eu.org/v1/chat/completions`，请求模型为 `fast`，失败、超时或标题验证不通过后依次使用 Groq 和 Workers AI。源码提交为 `d6a08b3e551a18be42c430e28fb93de002813c9d`，已推送实际关联仓库 `zhhshss/tttt`。Vercel 部署 `dpl_H1JtHye6rEUJVRnA1bchTeJGdu3J` 为 READY；Cloudflare 部署 ID 为 `b9b1fd29e47f4843849a2146880c9f8c`。两个平台项目名称仍为 `tttt`，两条现有 Cloudflare 路由保持不变。

- 固定英文系统提示经 `o200k_base` / `o200k_harmony` 测量为 69 tokens，`cl100k_base` 为 70 tokens。代表性中文标题请求的服务端总输入用量为 156–160 tokens，包含用户输入及聊天模板；更长名称的总输入量仍会增加。
- `fast` 是服务端模型别名，成功检查当前返回 `openai/gpt-oss-20b`。主请求使用 `max_completion_tokens=96`、`reasoning_effort=low`，仍有少量思考 token。`none` 探测返回了较慢的其他模型及错误片名，因此未采用；Groq 备用仍使用明确验证过的 `reasoning_effort=none`。
- 三级模型共享 2500 ms 预算，主接口 1200 ms、Groq 600 ms；两级均耗满期限后，Workers AI 约有 700 ms。保持规则与缓存优先，缓存包含主接口地址和模型，正常返回原名不会继续调用备用模型。
- `PRIMARY_API_KEY` 通过平台私有配置注入，源码凭据检查通过；Vercel 的 Workers AI 回退仍使用独立内部令牌。
- 41 项中间层测试、语法与差异检查通过，覆盖三级顺序、有效原名、异常输出、HTTP 错误、实际取消、共享期限、缓存及 Vercel 适配。应用新增 `primary` 来源兼容与严格校验测试，69 项专项 JVM 测试通过。

| 线上检查 | 结果 |
| --- | --- |
| Cloudflare 独立域名，`[ANi] 黑色五叶草` | 200，3225 ms，`primary`，正确条目 `73223` |
| Vercel 源站，`[字幕组] 黑色五叶草` | 200，1392 ms，`primary`，正确条目 `73223` |
| 当前域名，同一来源标题 | 200，70 ms，`primary` 缓存命中，正确条目 `73223` |
| 受控主接口 429，实际 Groq 与 TMDB | 200，225 ms，`groq`，正确条目 |
| 受控两级 API 503，实际 Workers AI 与 TMDB | 200，389 ms，`workers-ai`，正确条目 |
| 两级 HTTP 请求耗满期限并实际取消，随后实际 Workers AI 与 TMDB | 200，2046 ms，`workers-ai`，正确条目 |

真实客户端身份解析器另以 `[ANi] 黑色五叶草 (2017) 更新至171集 1080p 国语版` 查询当前域名，连续第 4 集正确匹配 S1E4（2966 ms），连续第 171 集复用缓存后匹配 S2E1（19 ms），共 3 次 HTTP 请求，满足原有共享 8 秒期限。

受控回退检查使用同一提交的真实共享逻辑，局部模拟指定 API 的故障，其余 Groq、受保护 Worker 和 TMDB 请求访问真实服务；未修改生产密钥或路由来制造故障。时间为测试主机单次样本，不代表电信设备现场延迟。客户端兼容改动仍为源码与 JVM 验证，本次未构建或安装 APK，也未验证电视实际 seek。

## 2026-11-20：Vercel 亚太区域

Vercel 项目 `tttt` 的函数区域从美国弗吉尼亚（`iad1`）切换为香港（`hkg1`），项目默认区域与仓库 `vercel.json` 均已设置。区域配置提交为 `d400209dabee830da4302bd757cfcd8d9042cd6d`，仅提交区域配置及对应 README 段落，保留工作树中已有的模型与校验改动。

基于切换前的实际生产部署 `dpl_3TZDXvHedW7hRN1JBE1hFkp8RN5q` 重新部署，9 个源文件与原部署一致，包含当时已经上线的标题校验及 Workers AI / NVIDIA 回退；没有用较旧的 Git 源码替换线上逻辑。新部署 `dpl_5Cxat81MSqLzqFJMHR3JjVrwWrCY` 状态为 READY，部署区域和项目默认区域均为 `["hkg1"]`，生产别名已指向该部署。

Vercel 源站健康检查返回 200，响应执行标记含 `cle1::hkg1`；搜索「黑色五叶草 (2017) 1080p 国语版」返回 200（1165 ms）及正确条目 `73223`，实际查询词为「黑色五叶草」。当前主域名和 Cloudflare 独立域名使用浏览器客户端标识查询健康接口均返回 200；测试主机的 Python 默认客户端标识被 Cloudflare 返回 403 / 1010。未调整 Cloudflare 防火墙、Worker、DNS 分流或模型配置。此次仅修改区域配置，未重复运行模型单测，也未构建客户端 APK。

## 2026-11-21：共享缓存与日本东京

按用户要求将 Vercel 区域调整为日本东京（`hnd1`），并在两个平台部署缓存优化。最终源码提交为 `10244748173a28c1d2dbc3e035cb16d885de125d`；缓存实现提交为 `0189d5a`，另将原已上线的标题校验与 Workers AI / NVIDIA 回退整理为独立基线提交，保留原有行为。Vercel 部署 `dpl_njDj4dqxB7DUmtzWcEo6vyQp9Kdx` 为 READY，项目与部署区域均为 `["hnd1"]`，健康检查执行标记含 `cle1::hnd1`。Cloudflare 部署 ID 为 `3ceaa263284d44d1b5a8d0c3aaaf970c`，原有两条域名路由仍指向 `tttt`。

- 搜索与列表默认缓存 1 小时，详情 1 天，名称清理 7 天；成功的旧搜索结果可继续使用 1 天，旧详情 7 天，并后台刷新。失败、空结果或模型全部失败不会覆盖有效旧结果，空结果与模型失败结果只短缓存 30 秒。
- 明确标签不同的同名搜索、共享缓存读取及最终 TMDB 请求均合并；缓存命中前不调用模型。Cloudflare 使用 Cache API 保存名称及公开元数据，Vercel 在内存未命中时通过受保护的内部缓存接口复用，读取最多等待 250 ms。该存储按 Cloudflare 接入节点保存，可能提前淘汰。
- 图片启用 Cloudflare 边缘缓存与 Vercel CDN，默认 1 年。公开元数据可使用 CDN；带 Bearer 的请求、会话与账户状态参数、账户接口和错误响应不进入公共 CDN 或旧结果回退。共享缓存键为哈希，按凭据及策略隔离；缓存接口要求内部令牌，正文限制 1 MiB、存储期限最多 8 天。
- 后台刷新与共享写入使用平台 `waitUntil`；Vercel 固定 `@vercel/functions` 依赖。Node.js `24.22.3` 下 91 项测试全部通过，覆盖有效期、跨实例复用、名称校验、空结果、隐私参数、后台生命周期、并发取消及图片二进制内容。语法、凭据检查及差异检查通过。

| 真实检查 | 首次 | 缓存命中 |
| --- | --- | --- |
| Vercel 同名搜索，先带标签再用原名 | 200，1217 ms，MISS | 200，222 ms，原始结果缓存 HIT；随后同 URL 确认 CDN HIT |
| Cloudflare 同名搜索 | 200，313 ms，MISS | 200，25 ms，HIT |
| Vercel 原始图片，1356640 字节 | 200，1247 ms，MISS | 200，96 ms，CDN HIT |
| Cloudflare 同一图片 | 200，563 ms，MISS | 200，54 ms，HIT |
| 两个独立核心实例，实际共享缓存与 TMDB | 121 ms，TMDB 1 次 | 16 ms，HIT，模型与 TMDB 均 0 次 |
| 两个独立核心实例，实际 `fast` 清理来源标签 | 1195 ms，模型 1 次 | 13 ms，HIT，模型与 TMDB 均 0 次 |

两个平台的图片正文 SHA-256 相同。真实共享缓存另验证了临时独立键的写入 204、读取 200，以及未鉴权 401；切换搜索参数后仍复用名称缓存，只访问 TMDB。该跨实例测试使用同一提交的真实核心与真实 Cloudflare / TMDB / 模型服务，区别于表中的公开 Vercel 源站测量。自然过期的后台刷新通过模拟时钟验证，未等待完整的一小时或一天。上述时间为测试主机单次样本；本次未修改或构建 Android 客户端。
