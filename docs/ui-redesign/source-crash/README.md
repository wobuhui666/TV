# Samsung 添加源后崩溃修复（R2K）

2026-10-03。三星 SM-F900F（API 33）安装 R2H 后，用户添加真实源，反复在插件后台线程发生 `UnsatisfiedLinkError`，进入 CrashActivity。`GoProxy` 把 313 字节的 XML `NoSuchKey` 响应当作 ELF 原生库加载。源地址、配置、插件 jar 均未替换；更新前已在本地私密备份配置，未清除应用数据。

## 修复

JarLoader 的 jar 初始化及 spider 实例初始化在取得内部锁之前进入专属 ThreadGroup。调用者同步等待初始化，组内重入直接执行；默认继承该组的后台线程只对直接源插件 LinkageError 降级，保留宿主进程并提示更新或更换源。宿主代码错误、其他异常仍委托原处理链，没有安装吞掉全进程异常的 handler。

取消使用独立标记，插件清除 interrupt 后仍不能把取消的初始化结果发布到 loader、proxy 或 spider 缓存。具体边界见[独立静态复核](SOURCE-PLUGIN-CRASH-REVIEW.md)。这种保护不能补回服务器上不存在的库，也不覆盖显式使用其他线程组/handler 或未隔离入口创建的线程。

## 实际设备复测

最终 R2K 原始 ARM64 debug APK 已覆盖安装到同一台三星，设备内 APK SHA-256 与产物一致：

`bdda4c638ffb03288f4218ea9b2e6780a48204a48c67a224c9735bcf24295f64`

- 保留原配置启动后进程持续存活 60 秒；观察到真实多源搜索结果。
- 再次冷启动，21:05:45 同一 `GoProxy` 坏库错误再次出现，随后明确输出 `JarLoader: Source plugin library failed on a background thread`。观察 40 秒以上，PID 始终为 `371674`，前台仍为 HomeActivity，未进入 CrashActivity。见[错误日志](r2k-cold-log.txt)及[进程/前台记录](r2k-cold-result.json)。
- Android 的前置日志处理仍写入 `FATAL EXCEPTION`，其后本次 ThreadGroup 接管该错误；不能仅按这个日志词判断应用是否死亡，必须结合相同 PID 存活与前台记录。
- 后续观察到设备进入搜索及视频播放界面；用户正在同时操作，未再强制切换页面。没有把这些画面当成所有源、完整播放或性能验收。
- 尝试由 shell 直接进入未导出的 SettingActivity 被 Android 拒绝，未修改 exported 属性；不把这条无效取证当成设置回归通过。

## 构建与测试

Codespace 执行以下任务成功；最终增量构建 1 分 53 秒。223 项单测（含 7 项插件运行域专项）全部通过，0 失败/跳过；lint 0 error、311 warning、6 hint；共享改动的 mobile Java 编译通过。129 个记录中的源码文件已与本地逐一匹配。

```sh
./gradlew :app:testLeanbackArm64_v8aDebugUnitTest \
  :app:assembleLeanbackArm64_v8aDebug \
  :app:lintLeanbackArm64_v8aDebug \
  :app:compileMobileArm64_v8aDebugJavaWithJavac \
  --no-daemon --build-cache --max-workers=2 --console=plain
```

[测试汇总](r2k-test-summary.json) · [源码哈希](r2k-source-sha256.json) · [设备安装记录](r2k-install.json)

R2I、R2J 是复核过程中的构建，从未安装到三星；实际设备安装从 R2H 直接更新至最终 R2K。APK、用户源文件、数据库备份和含私有来源内容的截图均不提交 Git。此前 R2H 的视觉覆盖限制继续保留，本次仅关闭已复现的源初始化后台库错误导致宿主退出问题。
