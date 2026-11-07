# Java／QuickJS 来源的 WASM 支持

WASM 用于来源的计算、解密或签名模块。解释器已随 App 打包，无需安装新的运行时。
现有 QuickJS Java/JNI 3.2.3、新旧网络接口和 Java Spider 入口保留。
底层使用 Apache-2.0 的 Chicory 1.4.1 与 `ByteBufferMemory`，不增加 WASM 原生库。

## JavaScript

来源对象缺少 `wasm` 属性时，宿主会补上 `this.wasm`；来源自定义属性保持原样。
所有来源也可直接访问全局 `wasm`。

```javascript
let instance;

export default {
    async init(ext) {
        instance = await this.wasm.create(ext.wasmUrl, {
            env: {
                double(owner, value) {
                    // 同步回调可通过 owner.read/write 访问 WASM 内存。
                    return value * 2;
                }
            }
        });
    },
    home() {
        const result = instance.call('compute', 21);
        return {list: [{vod_id: String(result), vod_name: 'WASM result'}]};
    },
    destroy() {
        if (instance) instance.close();
    }
};
```

`wasm.create` 支持 HTTP(S) URL、`ArrayBuffer`、TypedArray／DataView 或字节数组。
URL 使用现有 `net.http(..., {buffer: 1})`；TypedArray 按实际 offset／length 截取。
已有二进制可同步调用 `wasm.instantiate(bytes, imports)`。

实例提供 `call(name, ...args)`、`callTimed(name, timeoutMs, ...args)`、`read(offset, length)`、
`write(offset, bytes)`、`byteLength`、`grow(pages)`、`close()`。
`exports` 包含可直接调用的导出函数和导出内存；`signatures` 提供函数的 params/results 类型表。
无返回值为 `undefined`，单值直接返回，多值返回数组。I64 参数和返回值使用 `BigInt`；
I32/F32/F64 使用 Number，保留 NaN、Infinity 和负零。

`wasm.memory(initialPages, maximumPages)` 可创建导入的线性内存。函数 imports 必须同步，
返回 Promise 会明确报错。普通实例支持重新进入导出函数；递归及重入均有深度限制。

还提供 `WebAssembly.Module`、`Instance`、`Memory`、`validate`、`compile`、`instantiate`
和 CompileError／LinkError／RuntimeError 的基础兼容层。`Module`／`Instance`／`Memory`
额外提供 `close()`。由于现有 JNI 只能复制 ArrayBuffer，`Memory.buffer` 是调用前后同步的镜像；
内存增长会换一个 buffer，但旧 buffer 不会像浏览器实现那样被 detach。
不应依赖旧视图在 grow 后自动失效的行为。

## Java JAR 来源

宿主 Java `Spider.wasm()` 懒加载并返回属于该来源的 `com.fongmi.quickjs.WasmRuntime`。
使用可读二进制加载模块，资源可用 try-with-resources 管理：

```java
WasmRuntime runtime = wasm();
try (WasmRuntime.Module module = runtime.compile(binary);
     WasmRuntime.Instance instance = runtime.instantiate(module)) {
    Object[] result = instance.call("compute", 21);
}
```

Java I32/I64/F32/F64 参数分别为 Integer／Long／Float／Double。
`runtime.function(Signature, HostFunction)` 创建同步导入函数；imports 按 module/name 分组。
`Instance.memory()` 提供 `pages/read/write/grow`。来源退役时宿主关闭其 WASM 资源，
即使旧来源没有调用 `super.destroy()`，仍会清理；旧 JAR 不需要使用新增接口。

## 支持范围与预算

- 数值类型 I32、I64、F32、F64；同步函数导入和最多一个线性内存。内部有界 table、
  全局变量、间接调用及普通数据段由解释器处理。
- SharedMemory／memory64／WASI／GC／引用类型接口，以及导入／导出的 table、global、tag
  不在本轮兼容范围，遇到时明确拒绝。Passive data 与共享导入内存的组合也明确拒绝，
  避免解释器把不同实例的 passive segment 混用。
- 单模块二进制最多 16 MiB，模块合计 32 MiB；一个来源最多 32 个模块、64 个实例、64 个内存。
  单内存最多 64 MiB，最大页数的预留合计 128 MiB；未给出最大值的内存按 64 MiB 预留。
  这限制的是解释器线性内存，使用 `Memory.buffer` 的 JS 镜像和 JNI 临时副本会另占内存。
- 在解析前检查分配驱动的向量、类型、locals、数据段和指令长度。单模块最多 250,000 条指令、
  8,192 个单函数 locals；执行调用／重入最多 128 层，操作数栈亦有上限。
- 默认一次操作最多 10 秒；来源请求超时、线程取消或来源销毁会中止正在运行的纯 WASM。
  导入函数自己也必须响应取消。编译预检和解析前后检查截止时间，解析器的有限解析阶段
  无法被强制打断。

## 验证

`build-validation.yml` 执行 `:wasm:testDebugUnitTest`、应用／库回归和 TV／手机构建。
`tv-ui-verify.yml` 的 `wasm=true` 分别在 API 24、API 34 上执行真实 QuickJS JNI → WASM，
每个系统运行 11 项 WASM 回归及 2 项旧 QuickJS／DEX 来源兼容回归。
用例包含 binary HTTP、TypedArray offset、Java 来源生命周期、标量、函数 imports、
内存、错误、跨来源隔离、超时后继续调用和销毁。
