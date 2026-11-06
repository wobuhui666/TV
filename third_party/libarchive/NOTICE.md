# libarchive Android binding

ASSRT RAR4/RAR5 subtitle packages use
[`me.zhanghai.android.libarchive:library:1.1.7`](https://github.com/zhanghai/libarchive-android/tree/v1.1.7),
resolved from Maven Central. The Android JNI wrapper is licensed under Apache-2.0;
the underlying libarchive is BSD licensed. Their original license texts are
included as [LICENSE-wrapper.txt](LICENSE-wrapper.txt) and
[LICENSE-libarchive.txt](LICENSE-libarchive.txt).

The app reads archive entries through libarchive and writes only generated local
subtitle filenames. It does not use the native disk-extraction API.

Official ARM64 and ARMv7 APKs use the unmodified JNI library from that artifact.
The disposable x86_64 emulator preview uses the matching library from the same
checksum-verified artifact; see `.github/scripts/prepare-quickjs-preview.py`.
