# 外部源插件崩溃保护：独立静态复核

2026-10-03。只读检查 `SourcePluginRuntime.java`、`JarLoader.java` 和专项测试；本复核未修改源码、构建或操作设备。

**结论：本次所查实现未发现阻止同源三星复测的明确 blocker。静态复核中发现的取消缺口已修复；真实设备运行结果尚待主执行验证，不能据此宣称崩溃已在三星上消除。**

主执行提供的故障证据为：Samsung SM-F900F 添加外部源后，插件后台 Thread-14/18 在 `System.load → GoProxy.<clinit>` 抛出未捕获 `UnsatisfiedLinkError`；`libwexproxy.so` 实为 313 字节 XML，包含 `NoSuchKey`，不是有效 ELF。jar 有保护壳，线程实际创建路径仍须运行复测确认。

## 已复核的保护边界

- 整个 `parseJar/getSpider` 初始化入口在取得 jar 锁/进入 spider 缓存计算之前迁移到专属 ThreadGroup；同组重入直接执行，避免为嵌套初始化再启动等待线程而造成锁重入死锁。
- FutureTask 保持同步等待初始化语义；默认继承该组的插件后台线程在初始化返回后仍受该组处理。
- 只降级直接 `LinkageError`，且跳过 java/dalvik 系统帧后第一个来源帧必须为 spider。宿主来源 LinkageError、其他异常均委托原 ThreadGroup；未安装覆盖全进程的默认异常吞噬器。
- 这属于限定作用域及来源帧的启发式分类，不是严格 ClassLoader 身份证明；显式使用其他 ThreadGroup/handler、既有线程池或后续未隔离入口创建的线程可能不受保护。

## 发现并修复的取消缺口

初版只检查线程 interrupt。插件 `sleep/await` 被中断时可能清除该标志，导致反射初始化捕获异常后仍发布 loader；spider 异常分支也可能把取消产生的 SpiderNull 放入缓存。

最终版采用 `InheritableThreadLocal<AtomicBoolean>` 独立取消标记，等待者先置取消再 `cancel(true)`，并保留等待者 interrupt。JarLoader 在初始化/发布边界检查该标记；spider 的成功及异常返回路径在取消时返回 null，避免缓存 SpiderNull。最后复核确认 `invokeProxy()` 的 `methods.put()` 前也已增加取消检查。此为协作式取消防护，不承诺强制终止不响应取消的外部代码或把所有缓存提交变为原子事务。

专项测试覆盖初始化后子线程 LinkageError、宿主/其他错误委托、组内锁重入、同步错误返回、中断传播及插件清除中断标志后仍识别取消。主执行报告 R2K 共 223 单测（含 7 项专项）通过、lint 0 error、mobile 编译通过，129 个源码 SHA 与云端一致；本轮未重新执行这些检查。

## 运行验证仍需完成

在三星原源复现路径上确认：插件故障线程结束但宿主进程存活、后台错误被记录并提示、其他页面仍可操作。保护不会把 XML 修复为 native 库，也不保证该外部源功能可用；损坏下载和插件初始化失败本身仍存在。不得把主机单测结果或静态边界判断写成真实设备修复已验证。
