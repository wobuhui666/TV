# 媒体库详情对齐与原生稳定性验证

后续隔离验证分支准备使用真正 x86_64 Conscrypt JNI，采集设备 ABI、native bridge 属性与 `ech-validation/conscrypt-lifecycle.json`；新对照尚未执行。其离线生命周期用例仅核对会话读取／关闭互斥，不覆盖 TLS 握手或证书释放，不能撤销下述 ARM64 转译主包的失败。

本轮修订详情标题栏、剧集轨道与操作组的对齐，并将官方 Conscrypt 从 2.7.0 更新至 2.9.0。12 项 TLS／ECH 单元测试、四包构建、4 项详情交互及原生读取／关闭回归通过；实际加载片源后的默认 ECH 主包仍发生原生退出，同 PID 详情与证书释放压力验收未完成，当前稳定性验收未通过。

## 版本与修订范围

基准提交为 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，叠加本次工作区修订；1990 项构建输入清单 SHA-256 为 `5348b37ba77d321f3c0cf159dcd66a7afc9764bee506484452164c7789cc75ba`。以下四个 APK 均已验证签名，实际设备结果仍待补入。

| 签名产物 | SHA-256 |
| --- | --- |
| 主包 Debug | `13f2fd98e4185eb9a04dd2cc032702c0b4f8546a23df295cc3af8ba15991dd7e` |
| 主包 AndroidTest（修正驱动，当前重跑） | `b85574f46fc6336e893baf3a3041da023dba8cc2d1fc29dcb033fab1fac1dcf1` |
| 主包 AndroidTest（首轮及离线回归） | `1418e5e1b958c8ddac52882d4da9f3350bfc37adca476e0da6aaf690d8a2b77f` |
| Preview | `bc2a659247bbdad4eefb0c1b718d851252a956a851384dc0029a4a9fb73dc26a` |
| Preview AndroidTest | `662f71bc573d1327ff53a806c4d890533445a6980a3894dd9a957a81e10df845` |

主包保留 35 个 ARM64 原生库；对比前一紧凑选集主包 `93a35b78…`，除预期更新的 `libconscrypt_jni.so` 外，其他 34 个库逐项字节一致。新的 Conscrypt ARM64 JNI SHA-256 为 `afe88a3c69561ea51d1209fbc51399d9e42e7cfdeefbf64cb57d526370d8e2e2`。20 项 Python 资源保留，17 项字节一致；另外 3 项容器／构建资源的时间戳或序列化字节不同。CPython 3.10.22 对 857 个 `.pyc` 递归比较代码字段、常量、文件名和行表，代码与依赖内容相同，构建记录的差异仅对应重建容器哈希；不声明全部 20 项字节一致，未执行这些 Python 代码。

界面修订沿用现有播放与焦点逻辑，调整剧集标题、轨道首卡和季选择的对齐，以及播放／快捷操作组内的居中排列。布局与操作说明见 [TV 媒体库体验](../../../media-library.md#滚动与外观)，最终设备截图待补。

Conscrypt 2.9.0 包含上游 [#1551](https://github.com/google/conscrypt/pull/1551)（`e8bac4c582538d51e057d9d60b4475a844ea6a01`）的原生会话读取与关闭互斥，以及 [#1548](https://github.com/google/conscrypt/pull/1548)（`c5285366227a0b0a2b5d54df9ebcb6dcae6b0697`）的 X509 原生分配登记与不可用时的 finalizer 回退。ECH 缺省开启、配置解析、策略反射及信任委托保持原行为；本轮不以关闭 ECH 作为修复。接口与使用边界见 [ECH 验证说明](../../../ech-validation.md)。

## 当前结果

| 检查 | 当前状态与覆盖 |
| --- | --- |
| 专项单元测试 | `ConscryptEchSocketFactoryTest` 11 项、`EchSettingsTest` 1 项，共 12 项通过；失败／错误／跳过均为 0 |
| 主包与 Preview 构建 | 两个 ARM64 变体通过，合计 998 秒 |
| 主包／Preview 测试包构建、签名与资产比较 | 初轮测试包分别 104／87 秒通过，修正驱动后仅主包测试 APK 重建 66 秒通过；签名与安装哈希核对通过，34 个非 Conscrypt 原生库字节一致；Python 代码／依赖内容比较通过 |
| 原生读取／关闭离线回归 | API 31、实际 Conscrypt 2.9.0 通过，0.321 秒；128 个 SSLEngine 各读取 128 轮、7 个原生 getter，并核对关闭后返回值；不联网、不读写设置 |
| 既有详情交互 | Preview 上 4 项通过，92.3 秒：明确选集播放、快速遥控器导航保留焦点／待播目标、35 集续播范围、切主题保留分集焦点与滚动 |
| 同 PID 真实详情进出 | 带源试验在搜索页发生原生退出，尚未完成详情往返；10 轮验收未通过 |
| 默认 ECH 与证书释放压力 | 两次实际 Conscrypt／TLS 1.3／`sni=encrypted` 预检通过；随后原生退出，32 次压力连接及显式证书副本／GC阶段未执行 |
| 最终实际截图与外网下载 | 待设备截图及下载校验 |

已执行的 Gradle任务（均退出 0；单元阶段 222 秒）：

```bash
./gradlew :catvod:testDebugUnitTest \
  --tests '*ConscryptEchSocketFactoryTest' --tests '*EchSettingsTest'
./gradlew :app:assembleLeanbackArm64_v8aDebug :app:assembleLeanbackArm64_v8aPreview
./gradlew :app:assembleLeanbackArm64_v8aDebugAndroidTest -I "$EXTERNAL_VERIFICATION_INIT"
./gradlew :app:assembleLeanbackArm64_v8aPreviewAndroidTest -PtvPreview
```

主包测试包使用仓库外的 androidTest source overlay；变量 `EXTERNAL_VERIFICATION_INIT` 指向该 overlay 的 Gradle init 文件。首轮压力测试源 SHA-256 为 `1679fa51a25c9825f4b8c2d92392098907a2b65b0c135b7f97c000e884e9c15e`，协调脚本为 `b23485713724d941a2f7860dab62898208a246c6b1da8a34b71315bf2985718b`。构建为 Debug／Preview，不等同于 Gradle Release 变体或实体设备验收。

单元测试覆盖原 factory 的回退、参数传递、异常不静默降级及信任策略委托；它们不替代 Android 原生生命周期、真实 ECH 握手或详情进出验收。

上述 4 项使用隔离 Preview 的既有 `LibraryExperienceAndroidTest`，没有把夹具结果记作主包真实来源验收。实际安装主包为 `13f2fd98…`；离线生命周期使用首轮主包测试包 `1418e5e1…`，方法为 `ConscryptLifecycleAndroidTest.shouldKeepNativeSessionReadsSafeDuringAndAfterClose`，instrumentation 报 `OK (1 test)`。它验证读取／关闭边界，不包含握手和证书释放压力。

默认 ECH 首轮试验已完成两次实际 Conscrypt／TLS 1.3／`sni=encrypted` 握手及 5 轮真实 TMDB 详情往返，但重复 UI 采证使 600 秒协调等待到期，随后 instrumentation 因测试断言结束。退出为 reason 10（USER REQUESTED），没有新增 native tombstone 或被测 PID 的 crash／ANR 退出；证书压力未开始，副本数为 0，因此该轮不计为稳定性验收通过。该轮没有成功加载片源配置，不能称为真实 aiwex 资源验收。

修正仅扩大外置测试的 UI／总时限并减少每轮重复截图，保留真实详情创建、方向键、返回与内存观察；零副本也不再标为 Registry 全覆盖。新测试源为 `10fa186f4f4ae50eade2449dc45ff96c0f787b3ffa31797d7399639a97ca418a`，协调脚本为 `fd712e71d6ec11300597c462577c523f14b406a2d98c5d5ad897bc4100756c33`。仅主包测试 APK 重建 66 秒通过，签名后为 `b85574f4…`，安装已核对该哈希；生产主包 `13f2fd98…` 与 ECH 策略不变。

修正版首次启动仍被系统存储权限对话框挡住：两次加密握手通过，但详情轮数与证书副本均为 0，测试前置条件未完成；没有新增 native／ANR 退出。精确允许 `READ_EXTERNAL_STORAGE` 后启动完整重跑，产品和测试源码未再改动。该次权限中断与首轮等待超时均保留为未完成试验，不计入最终稳定性验收。

重跑前已通过设置页的配置对话框实际加载公开 `aiwex.json`，设置界面确认点播地址为 `https://9280.kstore.vip/aiwex.json`。这确认本轮片源配置已加载，不把该确认推广为所有资源可播放。设备暂为英文界面，按钮语言与资料语言可能不同，最终截图以实际状态为准。

## 当前主包原生退出

正确加载配置并允许存储权限后，`13f2fd98…` 主包／`b85574f4…` 测试包在 PID 5577 通过两次真实 ECH 预检。2026-11-16 16:53:34 UTC，仍在搜索页时 `FinalizerDaemon`（TID 5590）收到 SIGABRT，Scudo 报 `corrupted chunk header`。ApplicationExitInfo 为 reason 5（APP CRASH(NATIVE)）、status 6；新增 tombstone 的 Cmdline 与 PID 均属于被测主包，区别于此前测试超时和权限前置中断。

此次回溯指向系统 `/apex/com.android.conscrypt/lib64/libcrypto.so` 的 ASN.1／X509 释放；这证明本次失败的释放位置，仍不证明堆损坏来源或 ECH 的因果关系。握手 peer certificate 实际为平台 `com.android.org.conscrypt` 对象，独立 `org.conscrypt` 证书副本压力尚未开始；因此不能认为已验证新版 Registry 回调，或将失败直接归于该回调。详情完成轮数、压力副本、显式 GC轮数均为 0，默认 ECH 设置已恢复，未请求播放。

原始证据保留在仓库外。退出信息 SHA-256 为 `98a9cccecace6f662c9baa6b10b8f69c5b11fbf8776f04196a337df4d94984f3`，tombstone 为 `53cb8bb7369a9e0d3eaa98f4e6ec4be1064b973b1b3ef0be6b18b752f906fa25`。当前原生稳定性问题仍需继续调查，单元／离线回归成功不覆盖该失败。

为区分真实来源／JAR 工作负载与 TLS／证书释放，另准备只运行同样 34 次 TLS 请求和显式证书压力的外置诊断模式，跳过首页与详情操作；尚未计入结果。该模式即使通过也不满足带源详情验收或覆盖上述崩溃，生产主包与默认 ECH 行为保持不变。

## 设备验收口径

计划使用完整 ARM64 APK，在已有 API 31 x86_64、`libndk_translation` 转译环境中运行；安装前比对实际 APK SHA-256。启动 SystemUI ANR 仅在确认归属后关闭并等待服务稳定，其退出记录与被测应用分开。

主包验收先完成同 PID 的 10 次真实搜索／详情往返，再在该 PID 中分 4 批执行 32 次新 TLS 连接。目标为公开小响应 [crypto.cloudflare.com](https://crypto.cloudflare.com/cdn-cgi/trace) 与 [www.cloudflare.com](https://www.cloudflare.com/cdn-cgi/trace)。每次请求检查原生产 factory、严格证书客户端、实际 socket 和同次服务端加密证据；不保存 trace 正文，不发送测试 Cookie 或认证头，不改 DoH／主题，不执行播放。

证书压力另经实际 Conscrypt `CertificateFactory` 创建至少 512 个原生证书副本，并与握手的实际 peer certificate 类型分开记录。每批显式调用 GC／finalization，核对副本弱引用回收和 finalizer witness；因此这部分是显式回收诊断，不能称为自然回收或无泄漏证明。原生分配登记的 true／false／不可用计数单独报告；API 31 正常 finalizer 回退不等于 Registry 回调路径已验证，弱引用和 witness 也不证明每次原生释放完成。

验收保留全过程 PID、异常类型、前后 ApplicationExitInfo 及新增 tombstone 归属。只恢复测试主动修改的 `ech_enabled` 键及其是否存在的状态，保留正常 App 异步写入。APK、原始日志、脚本及私有设备信息均留在仓库外；本目录只收录脱敏结果、必要校验值和经过检查的实际截图。

## 既有失败与覆盖限制

[上一份报告](../2026-11-15-library-detail-redesign/README.md#性能与原生退出)中的 `7f6446cf…` 主包原生退出仍保留。其 `FinalizerDaemon`／Scudo 和 Conscrypt 库线索不证明具体损坏来源；版本更新和此前 ECH-off 对照都不能确认根因。当前主包复现的失败也不覆盖或撤销该历史记录。

本轮不验收实体电视帧率、全部片源播放、用户登录或真实 Cookie 提交。SwiftShader／转译模拟器数据不能支持 60fps 或所有 ARM 设备稳定性的结论。
