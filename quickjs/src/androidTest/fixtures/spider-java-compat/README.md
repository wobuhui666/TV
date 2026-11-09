# Independent Java Spider ABI probes

These two test sources are compiled against small **independent, compile-only** declarations. They do not compile against the host's current class files or an API JAR generated from this implementation.

- `legacy-api` exposes the existing `Spider.client()`, `getProxyUrl()` overloads, public `dialog` field and `Init` descriptors. `LegacyAbiFixture` calls those from real DEX bytecode.
- `modern-api` exposes the added `Spider.dialog(JSONObject)`, `dialog(JSONObject, Dialog.Callback)`, `closeDialog()` and `Runtime` descriptors, matching the selected public methods in `FongMi/TV@4512b4c9f82534ff66acfde06ccdd68f35b074f0`. `ModernAbiFixture` implements the callback interfaces itself and calls those methods from real DEX bytecode.
- `abi` supplies compile-only shared Dialog and OkHttp return-type declarations. The stub method bodies deliberately do nothing; none is included in the fixture JAR, so they cannot make a runtime test succeed.

The generator passes **only one fixture `.class`** as D8 program input, without desugaring. Every SDK declaration is a classpath input. It then parses the DEX class definitions and rejects any result defining a class other than the one fixture. Each final JAR contains only `classes.dex`. There are no anonymous/lambda helper classes, SDK stubs, credentials or external endpoints.

Rebuild with JDK 21 and Android SDK 37.0 / build-tools 37.0.0:

```bash
ANDROID_HOME=/path/to/android-sdk \
  python3 tools/testing/generate_java_spider_compat_fixtures.py
```

Explicit `--android-jar`, `--d8` and `--javac` paths are also supported. To prove a rebuild reproduces the committed files:

```bash
ANDROID_HOME=/path/to/android-sdk \
  python3 tools/testing/generate_java_spider_compat_fixtures.py --verify
```

Artifacts and provenance are in `quickjs/src/androidTest/assets/spider-java-compat/manifest.json`: selected upstream commit, Android JAR hash, javac/D8 versions, generator hash, every source hash, JAR hash/length and defined class descriptors. These assets only enter the instrumentation test APK.

`com.github.catvod.JavaSpiderDexCompatNativeTest` checks artifact hashes, loads each JAR with Android `DexClassLoader`, verifies the fixture comes from that loader while all SDK classes come from the host, then executes the actual methods. It covers shared Init/Runtime host registration, legacy field/client/proxy behavior, both modern dialog overloads, a callback crossing from DEX to the host, Runtime's dialog host registration and owner cancellation through old `close()` and new `closeDialog()` calls. Direct source signature checks, host-only compilation, or building the test APK cannot replace executing this test on Android.

The existing `:quickjs:connectedDebugAndroidTest` CI task discovers both tests automatically alongside other native QuickJS instrumentation.
