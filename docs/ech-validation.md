# ECH 验证说明

## 默认行为

- 共享客户端的普通 TLS 与 ECH 均使用官方 `org.conscrypt:conscrypt-android:2.9.0`，不安装全局 provider。ECH 默认开启，可在“设置 → 应用”关闭；该开关只控制 ECH 配置。
- `EchDnsResolver` 查询 DNS HTTPS 记录（类型 65），从中获取 ECHConfigList。
- DoH 默认选择腾讯 `https://doh.pub/dns-query`，未手动选择时生效；ECH 查询复用当前 DoH 选择。
- 显式选择“系统”时，ECH 查询使用 `https://1.1.1.1/dns-query`。
- 优先使用目标自己发布的 ECH 配置。目标明确没有 HTTPS 记录或没有 ECH 参数时，使用同一 DoH 查询其 A/AAAA；至少有一个地址且所有已返回地址均属于 Cloudflare 已知官方网段，才使用 `crypto.cloudflare.com` 的共享 ECH 配置。
- 只补充交给 TLS 的 ECHConfigList，不改变目标 URL、HTTP Host、TLS 内层域名、证书身份或连接地址。DoH 服务端的公开记录不会被修改。
- DoH 查询失败、无法确认 Cloudflare 归属、取不到共享配置或目标端口不是 443 时，使用普通 Conscrypt TLS 路径。
- 已尝试 ECH 的握手本身失败时，不自动降级重试；可关闭 ECH 后重新连接。
- 因此该开关不提供“必须使用 ECH，否则拒绝连接”的强制隐私模式。

## 覆盖范围与证据

- 共享 `Spider.client()`、项目共享 OkHttp，以及通过受控宿主 API 使用该客户端的 JAR 请求可经过此路径。
- 本次共享路径实测覆盖宿主客户端集成，不能推广为所有 JAR 均使用 ECH。独立或 shaded 网络栈的 JAR、native MPV、WebView 不在覆盖范围内。
- 获取到配置、调用 `setEchConfigList` 成功或普通 TLS 握手成功，都不等于 ECH 已被服务端接受。
- Conscrypt 2.9.0 没有公开的 SSLSocket ECH accepted 查询 API。
- 当前探针默认请求没有发布 ECH 配置的 `www.cloudflare.com/cdn-cgi/trace`，先核实目标自有配置缺失，再以同一 HTTPS 响应中的 Cloudflare trace `sni=encrypted` 证明补全生效。`ech_target_mode=published` 切换到有自有配置的 `crypto.cloudflare.com` 进行回归。
- 同时核对实际 Conscrypt socket、TLS 1.3/ALPN、请求目标及预期路由。
- 关闭组的预期结果为 `sni=plaintext`，开启组为 `sni=encrypted`。

## 两条验证路径

测试类为 `com.fongmi.android.tv.test.ConscryptEchProbeTest`，包含以下验证：

| 测试方法 | 验证用途 |
| --- | --- |
| `factoryOffAndOnProducePlaintextAndEncryptedServerEvidence` | 独立 factory 与真实 resolver，比较关闭/开启组 |
| `sharedSpiderClientUsesProductionEchAndRestoresSettings` | 检查共享 Spider 客户端集成与配置恢复 |

独立路径使用正常证书链和主机名验证，不增加证书放行逻辑。
另有离线拒绝型 TrustManager 检查，验证 basic、Socket、SSLEngine 三个服务端入口的拒绝异常传播。
离线委托检查不能替代真实 TLS 证书验证测试。

`ConscryptLifecycleAndroidTest.shouldKeepNativeSessionReadsSafeDuringAndAfterClose` 另做离线生命周期回归：128 个 SSLEngine 各并发读取 128 轮原生会话数据并关闭，核对关闭后的会话、ALPN、ECH 数据及密钥导出安全返回空值。该测试不联网、不读写设置，报告为 `ech-validation/conscrypt-lifecycle.json`。

同类的 `shouldKeepUpdatedTlsProviderWithoutEchConfiguration` 核对生产共享客户端与严格信任客户端的普通 socket，以及 ECH 关闭或无配置时的 TLS factory。TLS 仍启用 1.2/1.3，信任管理器保持原实例，关闭后的数据查询安全返回空值。普通 TLS 改用更新后的实现，使非 ECH 连接也使用读取与关闭互斥；独立网络栈与 DoH bootstrap 不在这项更改范围内。

固定版本从 2.7.0 更新至 2.9.0，采用上游 [#1551](https://github.com/google/conscrypt/pull/1551) 的 `NativeSsl` 读取与释放互斥，以及 [#1548](https://github.com/google/conscrypt/pull/1548) 的 X509 原生分配登记。ECH 默认值、配置解析及信任委托合同保持原行为。离线回归覆盖读取与关闭边界；X509 释放及 ECH 仍需真实请求和终结压力验证，版本更新本身不能确认先前原生退出的损坏来源。

共享路径保留项目既有的 `trustAll` 与 `hostnameVerifier(true)` 行为。
该路径只能证明 ECH 集成，不能证明证书验证安全；不得将其结果用于上述安全结论。
测试基于 `shared.newBuilder()`，保留 SSLFactory、DNS 和 interceptors。
为隔离测试连接，另建 connection pool 与 dispatcher，禁止重定向，并添加阻止凭据发送的网络 guard。

## 隔离与参数

- 测试只允许运行在 `sourceprobe` 或 `netprobe` 隔离应用包。
- 共享测试保存并恢复 ECH 设置键，包括原先不存在该键的状态。
- 同时保存并恢复 DoH snapshot，不把测试选择保留为用户配置。
- 不用测试结果覆盖生产配置，也不为测试安装全局 TLS provider。

Instrumentation 参数 `ech_doh_mode`：

| 值 | 测试使用的 ECH DoH |
| --- | --- |
| `default`（默认） | `https://1.1.1.1/dns-query` |
| `cloudflare` | 固定 `https://cloudflare-dns.com/dns-query` |
| `alidns` | 固定 `https://dns.alidns.com/dns-query`，共享测试会同步选择这个 DoH |

可选代理参数 `ech_proxy_mode=fixture` 只用于独立方法；不传或 `direct` 为直连。
它使用固定任务夹具 HTTP CONNECT 代理，仅对该夹具的 Basic 挑战回应，每次连接拒绝重复认证，不向源站发送代理凭据。
DoH bootstrap 与目标请求均走该夹具，并检查实际 HTTP 代理路由及认证发生。
共享方法拒绝 `fixture`，这项测试不能证明用户全局代理规则或其他代理兼容性。
独立方法总上限 60 秒；共享方法上限 90 秒。代理夹具必须由测试操作者先启动。

示例选择参数（追加到正常 instrumentation 命令，不是独立命令）：

```text
-e class com.fongmi.android.tv.test.ConscryptEchProbeTest#factoryOffAndOnProducePlaintextAndEncryptedServerEvidence -e ech_doh_mode default -e ech_proxy_mode fixture
-e class com.fongmi.android.tv.test.ConscryptEchProbeTest#sharedSpiderClientUsesProductionEchAndRestoresSettings -e ech_doh_mode default
```

报告位于隔离应用的 external files 目录下：

- `ech-validation/conscrypt-probe.json`：独立路径结果。
- `ech-validation/conscrypt-shared-probe.json`：共享客户端结果。
- 报告仅保存状态和必要计数，不输出 trace 正文、地址信息或凭据。

## 切换 DoH / ECH 的连接清理回归

设置变更同步清空 ECH 缓存，再由有界单线程任务关闭空闲连接。TLS 关闭可能发送 `close_notify`，不能放在设置页主线程；已有活动请求不调用 `cancelAll()`。缓存代次检查避免并发读取的旧 DoH 快照重新填入缓存。

`IdleConnectionEvictorTest` 覆盖阻塞关闭不阻塞调用者、密集切换合并、单个池异常不影响另一个池，以及真实空闲 socket 关闭时活动响应仍能完成。新增 3 项本地 JUnit 测试通过；完整 CI `37404706669` 的 `:catvod:testDebugUnitTest`、mobile 编译以及两个 TV APK/test APK 构建均通过。

设备回归类为 `com.fongmi.android.tv.test.DohSwitchRegressionTest`，仅允许在独立 `sourceprobe` 包中运行。它建立真实 Conscrypt 空闲 TLS 连接，保留另一个活动响应，在 Android 主线程严格网络策略下分别调用 DoH 和 ECH 设置变更，检查空闲连接关闭、活动响应完成并恢复设置。单独运行该类，不同时启动测试包首页或其他网络测试。结果文件为 external files 下的 `ech-validation/doh-switch-regression.json`。 `01b8748c1` 的完整 CI sourceprobe 包已在 Android 13 / ARM64 设备单独执行该类，3.509 秒完成，instrumentation `OK (1 test)`。

`WexJarNetworkProbeTest` 是按需运行的真实 JAR 搜索诊断，使用独立包私有目录中的 `jar-network-plan.json` 和 `jar-network-source.jar`，校验 JAR SHA-256，再通过宿主 `JarLoader` 调用搜索。它不读取用户的数据库；报告保留源入口、结果数、主机名、连接地址、状态码、异常类型及动态 DNS 覆盖快照。测试输入及单独保存的异常栈可能包含源信息，应留在私有任务目录，不能作为公开 CI artifact。只有显式设置 `jar_probe_capture_body=true` 才把最多 1 MiB 的搜索正文另存到隔离包私有文件，默认不存；该文件仅供人工核对错误提示卡，不能发布。搜索结果和网络可达性分开判断：零条结果不自动等于连接失败，Conscrypt socket 类型也不能单独证明服务端接受了 ECH。

## 2026-10-06 实际 JAR 源与 DoH GET 负缓存

真实设备查询 `www.cycani.org` 时，阿里节点的 GET A/AAAA 返回 RCODE 3 和异常别名，而同节点 POST A 返回正确 CNAME 和 IPv4；腾讯 GET/POST 均正常。这项有效 wire 对照与早先失败的 curl 抓包分开保存。`OkDns` 改用标准 DoH POST 发送地址查询，与 ECH 配置查询的传输方式一致，减少 GET 响应缓存的影响。修改不是 ECH 自动降级或系统 DNS 回退，也不保证修复 DoH 服务端所有错误响应。完整 CI `37409022443` 的共享网络单测、mobile 编译、两个应用与测试 APK 构建均通过。 最终 `d89e4072c` 完整包的设备 DoH 严格主线程回归再次通过，小龙在阿里 DoH / ECH 开启下返回 9 条正常结果；次元在阿里下仍为 UnknownHostException。随后对阿里两个节点改变 DNS ID、添加 no-cache 的 POST 检查仍多次得到错误别名及 RCODE 3，只有个别响应正确。因此 POST 不是该服务端异常的完整修复，次元的阿里访问仍列为未解决；腾讯此前只验证到 HTTP 200，不能把零条搜索结果写成全功能恢复。

实际 JAR 曾出现 `EchRejectedException`，后续也有同源开启 ECH 返回 9 条正常结果的路由诊断。设备阿里和腾讯提供了不同 ECH 配置 ID，但两份配置均有服务端接受证据；不能仅凭 ID 不同判定某个已过期。路由诊断确认成功时连接 Cloudflare、无宿主 hosts 覆盖；另有纯 TCP 超时，和 ECH 握手拒绝分开记录。Android VPN 的 UID 范围同时覆盖用户预览版与隔离测试包，未发现按应用分流差异。这些结果没有证明所有源持续可用，暂不据此加入自动明文重试或替换密钥。

源入口缺失与网络故障也分开处理。当前 JAR 缺少三个旧入口，其中师兄已有公开的新入口 `csp_WexAppV7Guard` / `AppV7Dsx`，实搜返回匹配搜索词的正常影片，已对用户远端配置执行单项条件更新并回读核验；其余配置保持语义不变。太狗、好盘尚无验证通过的替代入口，不猜测映射。

## 2026-10-06 Cloudflare 共享配置补全

上一版只处理站点自己发布的 ECH，未覆盖“没有发布配置但 CF 边缘能解密”的站点。本次增加自动识别与补全。

- `EchDnsResolver.resolve()` 保留仅查询目标自有配置的语义，便于获取共享来源与核验前提；宿主 TLS 路径改用 `resolveWithCloudflareFallback()`。
- A/AAAA 与 ECH 配置均从当前选定的 DoH 获取，不增加针对目标的系统 DNS 查询。CNAME 有界跟随，只采纳与问题域名关联的 answer 地址；错误、混合非 CF 地址或全空结果均不借用。
- 匹配 2026-10-06 的 Cloudflare 官方 IPv4/IPv6 网段快照，支持 IPv4-mapped IPv6；BYOIP、专用地址等不在快照内的情况可能不被识别。
- 整次补全共用 6 秒/8 次 DNS 查询预算；两种查询模式共用最多 4 个进行中任务、128 条 LRU。借用条目有效期受目标缺失记录、地址和共享配置各自期限约束。
- HTTP/SOCKS 代理仍按原配置连接。这里判断的是所选 DoH 给出的域名归属，不宣称看到了代理实际连接的目标地址；保留原站身份和证书策略，握手拒绝不自动改为明文重试。

主机已验证生产解析器自动处理 `www.cloudflare.com`、`cloudflare.com`：目标自有配置为零，`cloudflare_ech_fallback` 返回共享配置，同响应由 `plaintext` 变为 `encrypted`，TLS 1.3/HTTP2、证书验证和原域名保持正常。`crypto.cloudflare.com` 的自有配置路径也通过 OFF/ON 回归；`www.google.com` 的非 CF 地址结果不借配置。`example.com` 当前 A/AAAA 均属于 CF 网段，不能作为非 CF 负例。四个生产源码与 `c39e777` 提交一致，主机组合单测 86 项通过。

完整 CI run `37395642484` 的 preview、sourceprobe 构建成功，sourceprobe 网络单测及 mobile 编译步骤成功。生产应用来自 `c39e777` 的完整 CI APK，仅重签名且非签名内容一致。测试提交 `ed85d19` 只增加腾讯/360及 apex 参数，测试 APK 来自 CI run `37398331981`，没有替换生产应用代码。

Android 本轮完成六组 OFF/ON 对照，均得到同响应 `plaintext` / `encrypted`、HTTP 200、TLS 1.3、HTTP/2，开启组为实际 Conscrypt socket、ALPN `h2`：

| 路径 | 目标 / DoH | 证据 |
| --- | --- | --- |
| 独立与共享直连，补全 | `www.cloudflare.com` / 腾讯 | 自有配置 0，独立 ON 为 `cloudflare_ech_fallback` / 71 字节；instrumentation `OK (2 tests)` |
| 独立直连，补全 | `cloudflare.com` / 阿里 | 自有配置 0，ON 为 `cloudflare_ech_fallback` / 71 字节；`OK (1 test)` |
| 独立 HTTP CONNECT 认证夹具，补全 | `www.cloudflare.com` / 默认 Cloudflare | 同上且预期代理路由通过；`OK (1 test)` |
| 独立与共享直连，原生配置回归 | `crypto.cloudflare.com` / 阿里 | 独立 ON 为 `ech_config_available`；`OK (2 tests)` |

独立路径保持正常证书及域名校验，共享路径沿用既有策略并恢复设置与 DoH；不把共享结果作为证书安全证明。首轮 AliDNS 对 `www.cloudflare.com` 返回非 CF 的 A 和 CF 的 AAAA，独立路径按混合结果拒绝借用，共享请求为 `ConnectException`，失败证据单独保留。腾讯重测通过不改变这一拒绝规则。

本设备推荐：设置 → 应用 → DoH 选择腾讯 → 开启 ECH，无需新开关。360 只验证了 DNS 查询及地址归属，未记为 Android ECH 全链路通过。

本轮完整 CI 预览包已安装并启动首页，设备独立核验 APK SHA-256 为 `c64106b75cd7febc17fa77548d64d6f3698484001ea49072c75cc156a91857ee`。安装前后、首次启动前的 11 个设置/数据库文件哈希全部相同，并与本轮初始快照一致；用户 ECH 偏好未更改，原应用包未触碰。报告与 APK 分别为 `/home/ubuntu/CF补全ECH实测-20261006/REPORT.md`、`CF-ECH-preview-arm64.apk`，与昨天的局部编译更新包区分。正式 release run `37399371147` 已成功，发布 [build-37399371147](https://github.com/wobuhui666/TV/releases/tag/build-37399371147)，目标源码为 `ed85d19`，包含 ARM64、ARMv7 两个 APK。

## 2026-10-05 实测结果（上一版）

完整 CI 构建的 `sourceprobe` 隔离包已在 Android 13 / ARM64 设备完成直连及 HTTP CONNECT 夹具实测。源码为 `ae072fb82fbc862073b6a5050ee665114875851c`，CI run `37364398536`（attempt 2），sourceprobe 构建成功。71 项网络单测通过：DNS parser 32、resolver 14、factory 11、proxy 14；factory 新增 Android 反射式上下文信任检查，原先合计为 70 项。

| 路径 | DoH | 关闭 / 开启服务端证据 | 结果 |
| --- | --- | --- | --- |
| 独立客户端，正常证书及主机名验证，直连 | 阿里 | `plaintext` / `encrypted` | PASS |
| 真实共享 `Spider.client()`，直连 | 阿里 | `plaintext` / `encrypted` | PASS |
| 独立客户端，HTTP CONNECT 认证夹具 | 默认 Cloudflare | `plaintext` / `encrypted` | PASS |

三项实测响应均为 HTTP 200、TLS 1.3、HTTP/2；开启组均为实际 Conscrypt socket、ALPN `h2`。独立路径经真实 resolver 取得 71 字节 ECHConfigList。共享路径保留原 factory、DNS、应用 interceptors，并恢复 ECH 设置及 DoH；它没有单独插桩配置字节数。直连 instrumentation 为 `OK (2 tests)`。代理 instrumentation 为 `OK (1 test)`；实际 HTTP 代理路由匹配，关闭组认证回应 1 次，开启组 2 次（包含 DoH），源站凭据 guard 通过。该结果仅覆盖所用 HTTP CONNECT 夹具。

设置界面验证通过：缺省设置键不存在时为关闭；遥控器可聚焦、切换，重启保留切换状态，Material 使用说明可打开；测试结束已恢复关闭。`ech-settings.png` 与 `ech-info.png` 是切换过程截图，截图中的开启状态不代表默认值。

修复了 Conscrypt EngineSocket 对 `X509ExtendedTrustManager` 的内部包装会丢失反射式 ECH policy 的问题。适配器改为实现 `X509TrustManager`，保留 public Socket/SSLEngine 重载，Android 仍可反射调用上下文证书检查；未增加证书放行或全局 provider。原始 `CertificateException` 的传播有回归测试。

本设备直连 `1.1.1.1`、`1.0.0.1` 超时，`cloudflare-dns.com` 在 TLS 阶段被重置；阿里 DoH 的正常证书验证、GET/POST 均可达。应用尊重用户的 DoH 选择，不自动更换提供商。本设备使用步骤为：**设置 → 应用 → DoH 选择阿里 → 开启 ECH**，对新连接生效。

Cloudflare 站点并非全部发布 ECH 配置：本次 `www.cloudflare.com` 没有配置，`crypto.cloudflare.com` 有配置。ECH 不保证通过风控、验证码或其他站点访问策略。SOCKS、任意用户代理与全部 JAR 网络栈未验证。

## 2026-10-05 上一版预览包安装与构建来源

预览包已安装，设备核验 APK SHA-256 为 `b3743c93a58f1a7c6cde1ac52c420345383711aafc18ea0e721b195950d63b4a`，首页已启动。安装前后、首次启动前的 11 个偏好设置 / 数据库文件哈希全部相同，并与本次会话最初快照一致；原应用包未改动，未替用户更改 ECH 偏好设置。

安装包基于 CI run `37359756519`、源码 `4f2bbb6` 的预览产物，替换为实际从 `ae072fb` Java 源码经 javac / D8 编译得到的四个 factory 类。独立审查确认四个替换类与编译结果一致，其余 265 个同 DEX 类的规范化哈希、另 37 个 DEX 和其余非签名资源均未变，签名及对齐检查通过。这是本地源码编译更新，不是完整的新源码预览 CI 产物；完整 `ae072fb` sourceprobe CI 包已通过上述三项设备实测。

完整预览 CI 因 GitHub 托管 runner 未能启动而取消。正式 release run `37367866351` 在本次归档时仍排队，未记为正式发布成功。

脱敏报告、最终 JSON、界面截图及已安装 APK 位于 `/home/ubuntu/ECH实测-20261005/`，入口为 `REPORT.md`，APK 为 `TV-preview-ECH-arm64.apk`。报告区分完整 CI sourceprobe 证据和本地编译预览包来源；不包含 DNS 查询正文、完整 trace、设备地址或凭据。
