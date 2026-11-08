# FongMi JS and Java crawler compatibility

This change adds the selected public crawler APIs from
`FongMi/TV@4512b4c9f82534ff66acfde06ccdd68f35b074f0` while retaining the existing
Java JAR, QuickJS and Python hosts. It builds on the Python live SDK adaptation.

## Java interfaces

`Spider.dialog(JSONObject)`, `Spider.dialog(JSONObject, Dialog.Callback)` and
`Spider.closeDialog()` use the existing owner-scoped dialog implementation.
The public `Spider.dialog` field remains available for previously compiled JARs.
`com.github.catvod.Runtime` delegates context, activity, toast and dialog operations
to `Init`, so old and new callers share the same host registration and cleanup.
Both names and the public callback descriptors are retained under R8.

## JavaScript formats

A standard source may default-export a class extending `Spider` from the `catvod`
module. Its `homeContent`, `homeVideoContent`, `categoryContent`, `detailContent`,
`searchContent`, `playerContent`, `liveContent`, `manualVideoCheck`, `isVideoFormat`,
`proxy`, `action`, `danmaku` and lifecycle methods run on the original instance.
This preserves private fields and inherited defaults. Standard detail receives
the complete ID list; data methods may return objects or Promises of objects.

Existing factories receiving the site object, exported objects, `__jsEvalReturn`
sources and `.fwd` sources retain their previous method names, arguments and
JSON-string results. The adapter leaves frozen exported objects intact.
The old global `req/http`, proxy helpers and three-argument local storage remain.

The `catvod` module exposes the source-scoped network/local helpers, JS evaluation,
WASM, dialog, proxy and comment URLs, paging/links, text/HTML and crypto helpers.
`net.map` bounds concurrency and keeps order and JS result identity. `openStream`
reads bounded pieces of the original HTTP response; URL and iterable `stream`
bodies hand off an InputStream to the local proxy without collecting a live stream.
`connect` delivers persistent text/binary WebSocket events with source/session
cleanup. Closing generated bodies cancels pending request, cache and sleep work.

Real-time JS comment subscriptions use the existing `SiteApi` and `LiveDanmaku`
player ownership checks. Stop removes the emitter before queued callbacks run;
source destruction and a cancelled asynchronous start release subscriptions.
Ordinary HTTP/XML comments and Logvar continue their existing paths.

## Verification

Host JS tests check new class and old factory/object/eval-return formats, method
contracts, ordered mapping, stream/socket facades and comment lifecycle. JVM tests
check shared Runtime/Init host state and bounded/cancellable generated bodies.

The QuickJS library Android instrumentation executes the actual loader and JNI
with standard and legacy scripts, real HTTP and WebSocket loopback services,
generated InputStream bodies, comment cleanup and modern helpers. Separately
compiled legacy and modern DEX JAR fixtures execute against the actual host;
fixture generation excludes SDK stubs from their DEX and records checksums.
The existing CPython native regressions continue to execute in the same CI run.

GitHub Actions builds TV ARM64, TV ARMv7 and mobile ARM64 debug packages, runs
application/library regressions and compares lint to the pre-change base.
Results must be associated with the final commit, rather than the earlier Python
run. Individual external sources, account authorization and physical-device
playback are outside these fixture checks.

Node and downloadable runtime/profile plugins are not included in this change.
