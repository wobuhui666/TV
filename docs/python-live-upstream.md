# Python live source SDK compatibility

The Python live bundle uses source-scoped proxies, continuous HTTP bodies, long-lived WebSockets,
JavaScript signatures and real-time comments. These APIs are added to the existing Python 3.10
host, retaining the legacy requests helpers, JAR API, proxy shapes, dialog and WASM ownership.
The reference implementation is pinned to `FongMi/TV@4512b4c9f82534ff66acfde06ccdd68f35b074f0`.

## Public source bundle

The original ten files are stored without changes in
[`zhhshss/tv-python-sources@25ff7031`](https://github.com/zhhshss/tv-python-sources/tree/25ff7031e94b14c94ce7e8894394f04f3c1a0907/packs/live-python-20261107-a09dd575).
The ZIP SHA-256 is `a09dd5759fa64c7cd79b366a3ad586d6a395c687979d96787777a922c66d8c9b`.
The existing `gh-proxy.com/https://raw.githubusercontent.com/` CDN format is used with that
immutable commit. Main scripts, helper modules and JSON snapshots were each downloaded from
both raw GitHub and the CDN and matched the original SHA-256 and byte length.

The configuration adds three live entries (央视频, 央视官网 and 广东广电) and one game-live site
(斗鱼, 虎牙 and YY). The three `game_live_*.py` helpers stay beside `game_live.py`; they are not
independent sites. New source keys and live names avoid existing-entry collisions. Only the
new entries are appended; existing sites, live lists, order, parser URLs and JARs are retained.
WebDAV credentials and the private production configuration are never included in this repository
or the public source bundle.

## Added compatibility

| API | Behavior |
| --- | --- |
| `proxyUrl`, `danmakuUrl` | Source-scoped URLs; old `getProxyUrl` overloads remain available. |
| `proxy` / `localProxy` | Modern and legacy Python implementations are dispatched without recursive base-method calls. |
| `net.map` | Bounded, ordered mapping; Python objects are retained and cancellation follows the source call. |
| `net.openStream` / `net.stream` | Incremental URL and generator bodies with real Java `InputStream` handoff. |
| `net.connect` | Persistent text/binary WebSocket events, reconnect and idempotent cancellation; one-message `net.ws` remains separate. |
| `js.eval` / `evalAsync` | Isolated JSON globals, JSON/Promise results and built-in MD5 with native execution deadlines and cancellation. |
| `danmaku(id, emit)` | A closeable real-time subscription routed through `SiteApi` and the existing Media3 danmaku controller. |
| Cache and downloads | Default values, text cache, compare-before-invalidate, bounded/ranged downloads and atomic destination publication. |
| Python modules | Site/source/generation namespaces prevent same-basename sources and helpers from replacing each other. |

Generator reads enter an independent cancellable scope. Closing a downstream body cancels
pending ordinary requests, cached fetches, stream reads and retry sleeps, without waiting on
the blocking reader's lock. Normal EOF does not leave an interrupt on the server worker.

Live comment subscriptions are invalidated on source changes, disabling, view detachment,
replay and activity destruction. Queued comments are checked again on the delivery thread.
Source RGB/gradient colors, type and text size are passed to the existing renderer. Ordinary
HTTP/XML comment loading and Logvar search retain their existing paths.

`pywasm==1.0.8` matches the bundle's older WASM memory/export API. `numpy==1.26.2` is pinned
alongside it so Android wheel resolution does not drift. The current Chaquopy packaging and
Python 3.10 runtime remain in use.

## Validation

Local focused checks cover the original four sources and helpers, legacy API dispatch, namespace
isolation, protocol packets, incremental FLV reads, ordinary-request cancellation, JavaScript
globals/MD5 and native-budget code compilation. They use loopback data or explicit host-boundary
stubs and do not establish that upstream broadcast services are currently playable.

`build-validation.yml` checks the committed implementation with:

- Python, JavaScript, catvod, QuickJS, WASM and application regressions.
- TV and mobile ARM64 APK builds; optional TV ARMv7 build with the same Python dependencies.
- Complete lint reports compared against the supplied pre-change commit.
- With `python_live_native=true`, an API 29 x86_64 emulator executes independent QuickJS and
  CPython library instrumentation. The x86_64 Python flavor is injected by CI and does not change
  the application's two default ARM flavors.

The native tests exercise the actual wrapper/JNI runtime, not a skipped-Python UI APK. They cover
JavaScript synchronous/Promise results, globals, MD5, infinite-loop deadlines and cleanup, plus
actual CPython loading, proxy streams, namespaces, WebSockets, generated bodies, callback stop
and packaged WASM dependencies. The workflow uploads their XML/HTML reports separately from
the application build artifacts. Physical-device playback, source account authorization and
individual live-channel availability require separate evidence.
