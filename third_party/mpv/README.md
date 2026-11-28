# FongMi MPV native bundle

`mpv-android-ffmpeg9-native.zip` retains MPV and libc++ from
`FongMi/mpv-android` workflow run `31346106984` at commit `99a60ad214`.
Its seven FFmpeg libraries were rebuilt for issue #134 in
[GHA run 46163185605](https://github.com/wobuhui666/TV/actions/runs/46163185605),
using the original producer recipes and exact FFmpeg commit recorded by Media3.

- FFmpeg commit: `04482c8d13ac27b2a9fe93f5d388929eef8af5f4` (`release-9.0-fongmi`)
- Architectures: `arm64-v8a`, `armeabi-v7a`
- Archive SHA-256: `1ae1a5e55e3e96e3047cf37d3c464852ee8c0ceb2922e59d063a575b07a47022`
- Original archive SHA-256: `d3f22c64ab90a1476b489c2b4f06de8296672497f2998161efb4d906f9a4a66c`
- The app keeps `classes.jar` and assets from
  `app/libs/mpv-android-lib-v0.0.3.aar`.
- The extended JNI bridge is rebuilt for FFmpeg 9, as described below.
- Source archives, patch and per-library hashes: [native-hisi-provenance.json](native-hisi-provenance.json).

The `mpv` Gradle module assembles these inputs without committing another
large derived AAR.

## Extended JNI bridge

The original AAR's `libplayer.so` imports FFmpeg 8 symbols such as
`av_jni_set_java_vm@LIBAVCODEC_62`. The FFmpeg 9 bundle exports
`av_jni_set_java_vm@LIBAVCODEC_63`, so mixing these binaries fails during
`MPVLib` initialization on Android. Rewriting symbol versions is insufficient:
the fast thumbnail code also accesses FFmpeg structures whose ABI changes
between major versions.

`mpv-android-ffmpeg9-bridge.zip` contains only the rebuilt `libplayer.so` for
`arm64-v8a` and `armeabi-v7a`. It preserves all 23 original JNI entry points,
including node properties, node commands, fast thumbnails, and thumbnail cache
management. The stock bridge in the runtime archive lacks several of these
APIs and is excluded by `mpv/build.gradle`.

- Bridge source: [Aryan447/mpvlibAndroid v0.0.3](https://github.com/Aryan447/mpvlibAndroid/tree/5d658fe77b607db7f6e4141770fe7861c888b1e0/app/src/main/jni),
  commit `5d658fe77b607db7f6e4141770fe7861c888b1e0`.
  The `.cpp` and `.h` files in `bridge/` are unchanged except for trailing
  whitespace normalization; the local Android
  makefiles link them against the bundled runtime. See `bridge/LICENSE` (MIT).
- FFmpeg 9 public headers: `FongMi/FFmpeg` commit
  `03d9533176e98bb9fbf569c1f34968e73e948dd9` on `release-9.0-fongmi`,
  preceding the runtime build; libavcodec/libavformat 63, libavutil 61,
  libswscale 10.
- MPV public header: `FongMi/mpv` commit
  `cca559b41ceb0bb7731cf6ef2e1f33276cd30c42`, matching the runtime's embedded
  `v0.41.0-dev-gcca559b41` version.
- Toolchain: official Android NDK r29 (`29.0.14206865`), Android API 24,
  shared libc++, with 16 KiB page alignment for arm64.
- Bridge archive SHA-256:
  `29b1ef4c8d0b48475c1b5dba464d46157d7ec2e13ce913a67fcca93e4b2c20d6`.

Rebuild on Linux with Python 3, Make, tar, and NDK r29:

```bash
python3 third_party/mpv/build_bridge.py --ndk /path/to/android-ndk-r29
python3 third_party/mpv/verify_bridge.py
```

The build script verifies the downloaded header sources with SHA-256,
configures headers for each target ABI, and compiles only the JNI bridge.
Intermediate files go under `mpv/build/native-bridge/`. The normal Gradle build
uses the checked-in bridge archive and does not download or compile native code.

`verify_bridge.py` checks both architectures against the original AAR's JNI
exports and the bundled runtime's versioned media symbols. As a regression
check, the original AAR bridge is rejected with its unresolved FFmpeg 8 imports.
These ELF checks complement the Android playback and thumbnail smoke tests;
they do not replace device testing.

## HiSilicon colour-format fix

[patches/ffmpeg-hisi-nv21.patch](patches/ffmpeg-hisi-nv21.patch) targets the
pinned FFmpeg runtime source above. HiSilicon's public OMX implementation reports
`0x7fa30c00` for NV21 (VU), whereas FFmpeg maps the shared vendor value to NV12
(UV). The patch selects NV21 only for `OMX.hisi.video.decoder.*` with that
specific format and without a Surface. It retains the byte-preserving copy
and leaves format selection for standard NV12, other vendors and Surface decoding unchanged.

The semi-planar copy also fixes the vertical chroma crop offset (half the luma
row count for 4:2:0) and avoids full-stride copies after a left crop. Both
original cases reproduce out-of-bounds reads under AddressSanitizer; the
patched source passes the two crop cases and the existing mapping/copy tests.

The patch is compiled into both bundled ARM ABIs. The codec logs its original
layout and an explicit `HiSilicon NV21 workaround applied` line when it matches.
V55i device validation remains pending; the original log lacks the raw format
value. The correction does not fix direct MediaCodec initialization or promise
to remove 4K copy-decoding stutter.

`settings.gradle` runs [sync_media3_runtime.py](sync_media3_runtime.py) for the
pinned Media3 composite. It validates the existing and replacement hashes
before synchronizing its shared FFmpeg copies, so packaging cannot choose the
old codec through `pickFirsts`. Recorded earlier patch hashes can be upgraded;
unknown local binary edits cause a failure before any library is replaced.
The original metadata is retained with a separate TV overlay and new hashes.

Rebuild the exact FFmpeg revision with NDK r29, Python 3.12, Meson 1.10.1,
Ninja, CMake and pkg-config (use an empty work directory for each ABI):

```bash
python3 third_party/mpv/build_hisi_ffmpeg.py --ndk /path/to/android-ndk-r29 \
  --abi arm64-v8a --work /tmp/mpv-hisi-arm64 --output /tmp/mpv-hisi-output
```

The [native workflow](../../.github/workflows/mpv-native-hisi.yml) builds both
ABIs. [verify_hisi_runtime.py](verify_hisi_runtime.py) compares exports with
the original archive, resolves all versioned media imports and verifies the
compiled patch marker. The [test APK workflow](../../.github/workflows/mpv-hisi-test.yml)
also checks hashes of all seven libraries inside each signed coexist APK.

Merge downloaded native artifacts with `package_hisi_runtime.py INPUT --run-id ID`.
When replacing an already patched runtime, also provide `--original PATH` to
the archive matching the original SHA-256 listed above. The script verifies the existing
archive before carrying forward its hashes for Media3 upgrade compatibility.

Run the source regression with Python 3, Git and a C compiler with address and
undefined-behaviour sanitizers:

```bash
python3 third_party/mpv/verify_hisi_nv21.py
# Or use an existing, unmodified checkout of the pinned FFmpeg source:
python3 third_party/mpv/verify_hisi_nv21.py --source-dir /path/to/FFmpeg
```

The script checks source hashes, extracts the actual mapping and semi-planar
copy functions, and compares the original and patched results. It is a host
regression, not a MediaCodec or complete FFmpeg build. Source links, photo
comparison and device coverage are in the [#134 investigation](../../docs/testing/reports/2026-11-28-mpv-hisi-color/README.md).
