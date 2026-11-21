# aiwex 真实配置验证（2026-11-14）

用户授权使用 `https://9280.kstore.vip/aiwex.json` 实测搜索结果页。测试应用为已交付的 `com.fongmi.android.tv.preview`，底提交 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec` 加工作区修改；基线 APK SHA256：`cea0fe43cc1825447af9e44944d0915b74f11e3b723838557fb84b8b8ab090c3`。基线诊断仅重编 AndroidTest，沿用生产配置、TMDB 和 Spider 请求，不替换网络响应。诊断后修复了代理规则解析并构建新包，在线复测因 Box 余额耗尽未完成。[最终状态](results.json)记录已完成检查和覆盖限制。

## 配置与插件核查

17:56 UTC 重新请求配置 HTTP 200，JSON 有效，34,815 字节；100 个独立片源，92 个可搜索，全部为 type 3／CSP Guard。共享 Spider JAR HTTP 200，1,043,146 字节，ZIP／DEX 有效，MD5 与声明一致；包含 32 位 ARM 和 ARM64 原生库，没有 x86 库。两次下载哈希相同。

以上仅证明文件可下载、完整性匹配，不能证明影视源能返回内容。默认第一站是提示页面，不作为影视可用性样本。文件哈希与统计见 [公开检查摘要](config-inspection.json)，配置正文、扩展字段、请求头、原生包及原始日志留在仓库外。

## Android 实测

API 24 首次实际导入因环境 DNS 失败：`UnknownHostException` 指向 DoH 域名，配置回调约 53 秒后报错，主线程探针最大 26 ms；尚未执行片源，不作为 Guard 可用性结论。云端网络需要已有 HTTP 代理，后续在隔离模拟器设置系统代理后重测。

官方 API 30 Google APIs x86_64 rev16 已安装。静态检查包含 ARM32／ARM64 guest 库，两套 `libndk_translation.so` 导出 `NativeBridgeItf`；实际读取系统属性确认 `ro.dalvik.vm.native.bridge=libndk_translation.so`，ABI 列表含 ARM64／ARM。这些检查不证明 Guard 已执行。

无硬件虚拟化的双核环境出现系统服务 ANR：`com.android.networkstack.process` 执行 `TetheringService` 超时，被终止后引发 SystemServer 的 `IllegalStateException: Lost network stack`。完整 APK 在 8.192 秒内传入设备，但安装命令返回 `cmd: Can't find service: package`，退出码 20；包管理服务已消失，APK 尚未被解析或安装，不能将该失败归因于应用或 Guard。

启动控制另有检查时限问题：系统进程、包管理和 Activity 的四项检查用了 11.179–18.568 秒，超过旧脚本的 10 秒时限。已接管保留模拟器并修正就绪检查，避免独立复查误判后关闭实例。随后仅将同一手机 AVD 从双核改为单核，保留 2 GB 内存、960 × 540／160 dpi 和已有 userdata。单核包管理／Activity 检查降至 1.861／1.370 秒，但未让系统服务持续稳定。

单核首次完整包安装返回 `SecurityException: Caller has no access to session`，退出码 255。后续固定 ADB root 身份并实际确认 `id -u=0`，完整包传输用时 71.108 秒；安装仍因包管理服务消失返回退出码 20，应用未安装或运行。已停止在该 API 30 环境重复安装，转用 API 31。Android 11 AOSP 前台服务执行超时为固定 20 秒，未通过调试豁免屏蔽系统 ANR。

后续使用官方 API 31 Google APIs x86_64 rev14，单核、2 GB 内存、960 × 540／160 dpi。通过可写系统修改 `/system/build.prop`，重启后实际读取 SDK 为 31、ABI 含 ARM64、NativeBridge 为 `libndk_translation.so`、`ro.hw_timeout_multiplier=10`。Android 12 支持该因子，系统前台服务执行时限为 200 秒；应用自身的 30 秒搜索限时及 250 ms 主线程探针参考值不变。该差异是模拟器调试环境配置，不能用来证明电视实机速度。[实际环境证据](api31-environment.json)保留读取结果。

完整开机标志实际为 1 后，原始完整 APK 和匹配的 AndroidTest 安装成功，并已执行在线测试正文。首轮等待配置 90,078 ms，成功／错误回调均未到达，未进入关键词搜索或返回键测试；主线程探针最大延迟 654 ms，1,801 个样本，超过 250 ms 参考值。延迟采样栈只有 Looper／MessageQueue，不能归因于片源、JNI 或搜索界面。[首轮实际结果](api31-initial-result.json)保留这些结果。

首轮驱动未保存配置任务的 Future 和执行阶段，无法区分提交异常、数据库初始化、内部任务或迟到回调。随后仅补充 AndroidTest 诊断，90 秒保存观察点，必要时继续观察至最多 180 秒；应用包、请求参数和性能参考值不变。复测确认设备内基线 APK 哈希仍为 CEA0，配置查找用时 64,059 ms，任务提交用时 14,455 ms；180,649 ms 后仍未收到成功／错误回调。外层提交正常完成，内部配置任务仍运行，栈已进入 `Proxy.arrayFrom`／Gson 类型解析，表明配置响应已取得，不能归因为 DNS 或 Guard 搜索失败。关键词搜索和返回键仍未执行，原始诊断日志保存在仓库外。

## 解析修复与验证

`Proxy` 的 `proxies`、`uris`、`wildcard` 是运行时缓存，旧实现却让 Gson 为它们创建系统类适配器，即使解析空数组也会遍历 `java.net.Proxy` 等类型。已将三个字段标为 `transient`；配置合同仍为 `name/hosts/urls`，实际代理和通配排序继续由 `init()` 重建。同一配置初始化路径的 Header、Doh、Rule 无同类缓存，无需修改。

[独立回归](parser-regression.json)使用生产 Gson 2.14.0、JDK 21、JUnit4：只移除这三个 `transient` 的基线 4 项中 3 项失败，修复后 4 项全部通过。本地使用 SDK 37 API 签名再次得到同样结果；这些测试证明平台适配器排除和输入合同，未运行 Android `Uri`。最近的 `:catvod:testDebugUnitTest --tests com.github.catvod.bean.ProxyParsingTest` 构建检查通过；因云端中断未再读取其 JUnit XML。完整 Preview 和 AndroidTest 构建通过，真实 Android 初始化回归已补但尚未执行。

新包 `TV-aiwex-parser-fix-20261114-arm64.apk` 为 116,711,547 字节，保留 35 个 ARM64 库和 Python 资源，签名检查通过。SHA256：`d72e15065cd37fdba3825bddeaa5b3a200d139006d55509f3c3076f86724312e`。文件仍在云端测试机，尚未传回或安装复测，不能将构建成功视为修复后的在线验收。

## 尚未完成的实测

构建后云端测试机自动休眠，中断了运行中的模拟器；唤醒后的执行工具明确拒绝命令，原因是 Box 钱包余额耗尽。未进行充值或升级。本地没有已连接的 ADB 设备，也没有可接续完整 ARM64 实测的现成模拟器。

API 24 使用移除 ARM 库与 Python 资源的模拟器包，1,629 个 DEX／资源／清单条目与完整 APK 一致；这种包不能据此证明 ARM 插件或真实解码可用。[逐次执行摘要](execution-attempts.json)记录 API 24／30 的阶段结果。当前真实片源搜索、结果页、返回键和播放均未取得验证数据，不能确认用户反馈的卡死已解决。恢复测试机执行或提供可连接的 ARM 设备后，仍需安装新包并完成这些检查。
