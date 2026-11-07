# Chicory WebAssembly interpreter

The `wasm` module uses [`com.dylibso.chicory:runtime:1.4.1`](https://github.com/dylibso/chicory/tree/1.4.1)
and its `wasm:1.4.1` dependency from Maven Central. Chicory is Apache-2.0 licensed;
the original [license](LICENSE.txt) is included here.

The application uses the interpreter and `ByteBufferMemory`. It does not add a
native WASM library, compiler or WASI runtime. Version 1.4.1 avoids the newer
interpreter's `VarHandle` fences on Android versions below API 33.

The published JAR also contains an unused `ByteArrayMemory` implementation with
polymorphic MethodHandle calls, which D8 rejects for API 24 even if the app uses
another memory factory. `prepare_android.py` checks the original JAR SHA-256,
copies its original `ByteBufferMemory` bytecode under the ABI-compatible
`ByteArrayMemory` name, and replaces only that one class. This keeps default
factory references valid and uses the same Android-compatible implementation
under both names. All other class contents remain unchanged. The Gradle build
generates the JAR; generated artifacts are not committed.

Reproduce it with:

```bash
python3 third_party/chicory/prepare_android.py \
  --input runtime-1.4.1.jar --output runtime-android-1.4.1.jar
```

The application-owned `WasmRuntime`, binary preflight and legacy QuickJS bridge
are in this repository. Their public scalar API and JS convenience shape follow
[FongMi/quickjs-wrapper](https://github.com/FongMi/quickjs-wrapper/tree/d432948cd72f51693aa2bfa0333674dcf6783138),
which is MIT licensed; the existing QuickJS 3.2.3 Java/JNI pair remains in use.
