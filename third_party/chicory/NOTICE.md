# Chicory WebAssembly interpreter

The `wasm` module uses [`com.dylibso.chicory:runtime:1.4.1`](https://github.com/dylibso/chicory/tree/1.4.1)
and its `wasm:1.4.1` dependency from Maven Central. Chicory is Apache-2.0 licensed;
the original [license](LICENSE.txt) is included here.

The application uses the interpreter and `ByteBufferMemory`. It does not add a
native WASM library, compiler or WASI runtime. Version 1.4.1 avoids the newer
interpreter's `VarHandle` fences on Android versions below API 33.
`ByteArrayMemory` is not used.

The application-owned `WasmRuntime`, binary preflight and legacy QuickJS bridge
are in this repository. Their public scalar API and JS convenience shape follow
[FongMi/quickjs-wrapper](https://github.com/FongMi/quickjs-wrapper/tree/d432948cd72f51693aa2bfa0333674dcf6783138),
which is MIT licensed; the existing QuickJS 3.2.3 Java/JNI pair remains in use.
