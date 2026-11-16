# 代理配置解析回归（2026-11-15）

测试基线为 `6d6a4a571ad8a6bd362cb05919bbfd8f0fd7a5ec`，修复组只增加 `Proxy.proxies/uris/wildcard` 的 `transient` 声明。Gson 不再为这些运行时缓存创建平台类型适配器；配置仍使用 `name/hosts/urls`，实际代理及通配优先级由 `init()` 重建。

## 环境与结果

Ubuntu、OpenJDK 21.0.13、生产依赖 Gson 2.14.0、JUnit 4.13.2、Hamcrest 1.3；SDK 37.0 的 `android.jar` 仅提供编译签名。直接用 `javac --release 21` 编译生产 `Proxy.java` 与 `ProxyParsingTest.java`，用 JUnitCore 执行，不替换生产实现。

| 对照 | 编译 | JUnit |
| --- | --- | --- |
| 基线原始 `Proxy.java` | 通过 | 4 项中 3 项失败，平台适配器防护捕获 `java.net.Proxy` 遍历 |
| 增加三个 `transient` 后 | 通过 | 4 项全部通过，0.110 秒 |

四项检查覆盖空规则数组、合法规则、注入缓存字段、空值与非法数组；合法规则序列化只保留 `name/hosts/urls`。对照确认两个生产版本仅有这三个字段声明的差异。

| 已测文件 | SHA256 |
| --- | --- |
| `catvod/src/main/java/com/github/catvod/bean/Proxy.java` | `3b16261ab35ca609fd2154c0df81d34a11180efd80c32bf0f86997d9c09ecbbd` |
| `catvod/src/test/java/com/github/catvod/bean/ProxyParsingTest.java` | `d98e8a00828d412b7abbb03c2c1b9afbcb50ec33a3a65d1bd9342286891a76eb` |

复验命令如下，`PROXY_TEST_CLASSPATH` 由上述 Android、Gson、JUnit、Hamcrest JAR 组成，`PROXY_TEST_CLASSES` 指向仓库外的编译输出目录。基线对照使用 `git show` 导出的上述提交原始生产文件，运行同一测试类。

```sh
javac --release 21 -cp "$PROXY_TEST_CLASSPATH" -d "$PROXY_TEST_CLASSES" \
    catvod/src/main/java/com/github/catvod/bean/Proxy.java \
    catvod/src/test/java/com/github/catvod/bean/ProxyParsingTest.java
java -cp "$PROXY_TEST_CLASSES:$PROXY_TEST_CLASSPATH" \
    org.junit.runner.JUnitCore com.github.catvod.bean.ProxyParsingTest
```

## 覆盖限制

本轮未运行 Gradle、APK 构建或 Android `Uri` 初始化。新增的 `ProxyConfigurationAndroidTest` 用于真实 Android HTTP/SOCKS 初始化、通配排序及输出字段检查，尚未编译或执行。最近变体复验命令为 `./gradlew :catvod:testDebugUnitTest --tests com.github.catvod.bean.ProxyParsingTest`；这里的通过结论来自独立 JVM 对照。

修复后的在线配置加载、Guard 搜索、返回键与播放实测尚未完成，不能据此宣称用户反馈的卡死已解决。此报告不包含媒体库或界面验证。
