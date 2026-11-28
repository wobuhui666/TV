#!/usr/bin/env python3
"""Rebuild the bundled FFmpeg revision with the HiSilicon NV21 fix (NDK r29).

Uses the original producer's build recipes and pinned dependency archives.
Does not update mpv, the JNI bridge, or any application dependency.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
FFMPEG_REF = "04482c8d13ac27b2a9fe93f5d388929eef8af5f4"
PRODUCER_REF = "99a60ad2141d5ace94453590903c2c6b9a0a2443"
SOURCES = {
    "producer": (f"https://codeload.github.com/FongMi/mpv-android/tar.gz/{PRODUCER_REF}",
                 "139e0bd58175cc7f5284b694a72f60330a17e93893e3489c6a28f794c556e2a6"),
    "ffmpeg": (f"https://codeload.github.com/FongMi/FFmpeg/tar.gz/{FFMPEG_REF}",
               "2799c096143c8133eb51c7f7bc6c7f83c76633c2456e7833cae89bd441be8a18"),
    "mbedtls": ("https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-3.6.7/mbedtls-3.6.7.tar.bz2",
                "a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6"),
    "dav1d": ("https://codeload.github.com/videolan/dav1d/tar.gz/54706fc6bc0cdecab7e9593974a4039cc038fca7",
              "9edb11a2108b375cc58370354e705feebc93430bb780130363815c1e1ac0c250"),
    "libxml2": ("https://gitlab.gnome.org/GNOME/libxml2/-/archive/v2.15.3/libxml2-v2.15.3.tar.gz",
                "0da50c1415f4ec0364569d2119b1436ba837b31df44af28569d234272c23cf1f"),
    "libaribcaption": ("https://codeload.github.com/xqq/libaribcaption/tar.gz/refs/tags/v1.1.1",
                      "278d03a0a662d00a46178afc64f32535ede2d78c603842b6fd1c55fa9cd44683"),
    "freetype2": ("https://codeload.github.com/freetype/freetype/tar.gz/refs/tags/VER-2-14-3",
                  "dc49de6b01a266eef4876a4dd34d9842c475d3e28ff2eff63bd2fb760ab56261"),
    "dlg": ("https://codeload.github.com/nyorain/dlg/tar.gz/395ccad2c1e0daae535c4d20bb0a3f2424648e17",
            "263a4a3777d9839e34cd41db988310659e0c00eab37beb7aedd30511a460133b"),
}
LIBRARIES = ["libavcodec.so", "libavdevice.so", "libavfilter.so", "libavformat.so",
             "libavutil.so", "libswresample.so", "libswscale.so"]
MARKER = b"HiSilicon NV21 workaround applied:"


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fetch(name, cache, target):
    url, expected = SOURCES[name]
    archive = cache / (name + ".tar")
    if not archive.exists():
        temporary = archive.with_suffix(".part")
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            shutil.copyfileobj(response, output)
        temporary.replace(archive)
    if sha256(archive) != expected:
        raise RuntimeError(f"Unexpected {name} source checksum")
    if target.exists():
        raise RuntimeError(f"Use a fresh work directory: {target}")
    target.mkdir(parents=True)
    with tarfile.open(archive) as source:
        for member in source.getmembers():
            parts = Path(member.name).parts
            if len(parts) < 2:
                continue
            member.name = str(Path(*parts[1:]))
            # data_filter rejects paths and links escaping the target directory.
            source.extract(member, target, filter="data")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--abi", choices=["arm64-v8a", "armeabi-v7a"], required=True)
    parser.add_argument("--work", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jobs", type=int, default=2)
    args = parser.parse_args()
    ndk, work, output = args.ndk.resolve(), args.work.resolve(), args.output.resolve()
    if "29.0.14206865" not in (ndk / "source.properties").read_text():
        raise RuntimeError("The bundled runtime requires NDK r29 (29.0.14206865)")
    cache = work / "archives"
    cache.mkdir(parents=True, exist_ok=True)
    producer = work / "producer"
    fetch("producer", cache, producer)
    scripts = producer / "buildscripts"
    for name in SOURCES:
        if name not in ("producer", "dlg"):
            fetch(name, cache, scripts / "deps" / name)
    dlg = scripts / "deps/freetype2/subprojects/dlg"
    if dlg.exists():
        dlg.rmdir()  # The archive contains only an empty Git submodule placeholder.
    fetch("dlg", cache, dlg)
    sdk = scripts / "sdk"
    sdk.mkdir(exist_ok=True)
    (sdk / "android-ndk-r29").symlink_to(ndk, target_is_directory=True)
    patch = ROOT / "patches/ffmpeg-hisi-nv21.patch"
    source = scripts / "deps/ffmpeg"
    subprocess.run(["git", "apply", "--check", str(patch)], cwd=source, check=True)
    subprocess.run(["git", "apply", str(patch)], cwd=source, check=True)
    arch = "arm64" if args.abi == "arm64-v8a" else "armv7l"
    environment = dict(os.environ, cores=str(args.jobs))
    subprocess.run(["bash", str(scripts / "buildall.sh"), "--arch", arch, "ffmpeg"],
                   env=environment, cwd=scripts, check=True)
    target = output / args.abi
    target.mkdir(parents=True, exist_ok=True)
    prefix = scripts / "prefix" / arch
    strip = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    hashes = {}
    for name in LIBRARIES:
        library = target / name
        shutil.copyfile(prefix / "lib" / name, library)
        subprocess.run([str(strip), "--strip-unneeded", str(library)], check=True)
        hashes[name] = sha256(library)
    if MARKER not in (target / "libavcodec.so").read_bytes():
        raise RuntimeError("The rebuilt codec does not contain the HiSilicon fix")
    manifest = {"ffmpegCommit": FFMPEG_REF, "producerCommit": PRODUCER_REF,
                "ndk": "29.0.14206865", "androidApi": 24, "abi": args.abi,
                "patchSha256": sha256(patch), "sources": SOURCES, "libraries": hashes}
    (output / f"provenance-{args.abi}.json").write_text(json.dumps(manifest, indent=2) + "\n")
    shutil.copyfile(source / f"_build_{arch}/ffbuild/config.mak", output / f"config-{args.abi}.mak")
    print(json.dumps({"abi": args.abi, "libraries": hashes}, indent=2))


if __name__ == "__main__":
    main()
