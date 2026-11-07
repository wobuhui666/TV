# 新旧爬虫接口兼容

本实现对接 FongMi/CatVodSpider 2026-10-05 发布的 SDK 和开发文档，保留 TV 原有的 Java、QuickJS、Python 入口。参考 SDK 提交为 `3d7a0fa1a6737603231f78fb7cbaea6539d9d6e3`；核对时 FongMi/TV 公开源码仍在 `c616c0aa3613e87529791587a9f71b78c278c991`，不能将本实现称为已公开的上游 TV 实现。

## 接口行为

Java `Spider.wasm()` 和 QuickJS 的全局／实例 `wasm` 提供独立的 WASM 解释器接口，
保留原有 SDK 与 QuickJS JNI；详细调用、取消和资源范围见 [WASM 说明](spider-wasm.md)。

| 类型 | 新接口 | 旧接口兼容措施 |
| --- | --- | --- |
| Java JAR | 在 `init` 前注入 `net`、`local`，提供分页、结果、加密、Activity 和代理辅助方法 | 保留 `Spider.client()`、`safeDns()` 和所有旧入口；旧 JAR 自带的 Result/Vod/Crypto 等辅助类优先由该 JAR 加载 |
| QuickJS | `net`、按站点存储、分页、代理；数据入口可返回对象或 Promise | 保留旧字符串返回、`req/http/getProxy`、三参数 local，以及代理数组和布尔返回 |
| Python | 原生网络适配器、按站点存储、分页和字典代理参数 | 保留 requests 的 fetch/post 与 Response 行为、缓存方法、显式 `getProxyUrl(True/False)`；源自定义的 net/local 属性保留 |

新网络请求继承 App 的代理、重定向、DNS 和 ECH 客户端。关闭某个源只取消该源拥有的请求、WebSocket、缓存任务和子会话。`local` 与 HTTP 缓存分开，清 HTTP 缓存不会清除站点持久数据。需要逐跳代理选择的重定向继续走 App 的现有实现。

增加宿主辅助类时，普通父优先加载可能遮住旧 JAR 中同名但不同版本的类。`SpiderClassLoader` 仅对本次新增的 Result、Vod、Class、Filter、Sub、Danmaku、Crypto 采用源内优先查找；Spider、Net、Local、OkHttp 和已有宿主类保持父优先，避免类身份冲突。

## 原始 HTTP 请求体

`net.req` / `net.http` 的 `body` 支持文本和 **0～255 整数数组**，数组按原始字节发送，不经过字符编码。使用 `body` 时必须提供 `Content-Type`，且不能同时提供 `data`。`buffer` 只控制响应格式，与请求体无关。协议参考 [FongMi 二进制请求体支持](https://github.com/FongMi/TV/commit/ff40eddb972d312a65a7ae9d0893d1086e5329f0)。

Java 将以下选项 JSON 传入 `net.req(url, options)`；Python 和 QuickJS 可直接使用相同结构的字典/对象：

```json
{"method":"POST","headers":{"Content-Type":"application/octet-stream"},"body":[0,127,128,255]}
```

Python 的 `bytes` 可用 `list(payload)` 转成整数列表，QuickJS 的 `ArrayBuffer` 可用 `Array.from(new Uint8Array(payload))` 转成数组。Java 从 `byte[]` 生成 JSON 数组时，用 `value & 255` 转成无符号整数。空数组发送空请求体；越界、非整数和嵌套元素在发送前拒绝。`req` 返回现有的 `error` 字段，`json` 按原有约定抛出请求错误。

原有文本 `body` 继续遵循声明的字符集，JSON/表单仍通过 `data` 发送。WebSocket 的 `body` 保持文本语义，HTTP 字节数组不会隐式变成 WebSocket 消息。Python/QuickJS 的请求、异步 HTTP/JSON 和批量接口共用已有 JSON 桥接，不改变旧源的全局 `req` / `http` 或 `requests` 辅助方法。

## 验证入口

- JVM：`:catvod:testDebugUnitTest`、`:quickjs:testDebugUnitTest`。
- Python 协议：`python -m unittest discover -s chaquo/src/test/python -v`。
- Android：`SpiderJavaPythonApiTest`、`QuickJsSpiderApiTest` 检查真实虚拟机、网络与存储桥接。
- 已配置旧 JS：`QuickJsLegacySourcesTest` 读取测试包私有目录内的脚本和校验计划，不把配置凭据写入仓库。
- 同一旧接口源 JAR：`CialloSourceCompatibilityTest` 在隔离 sourceprobe 包内加载 `files/ciallo-source.jar`，参数 `ciallo_source=Girigiri|Sorani`、`ciallo_sha256=<SHA256>`。测试只调用旧 API，可在升级前后两个 App 上运行。播放信息留在私有 `source-validation/playback-cases.json`，可供已有 Exo/MPV 播放测试使用。

协议测试和解析成功不能代替真实 App 加载、原生桥接或视频解码。设备报告应注明 App 提交、JAR 哈希和具体执行的测试，外部站点错误与接口回归分开记录。
