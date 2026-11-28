#!/usr/bin/env python3
"""Keep Media3's shared FFmpeg copies identical to the patched MPV runtime.

Refuses unknown local binary changes before writing any file. The original
consumer provenance is retained, with the TV overlay recorded separately.
"""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent
MEDIA3_REF = "342f6590e02ac390e0bd6b4488b8a1bff55747c1"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def synchronize(media3, runtime_root=ROOT):
    manifest = json.loads((runtime_root / "native-hisi-provenance.json").read_text())
    archive = runtime_root / "mpv-android-ffmpeg9-native.zip"
    if digest(archive.read_bytes()) != manifest["archiveSha256"]:
        raise RuntimeError("MPV runtime archive checksum does not match provenance")
    if digest((runtime_root / "patches/ffmpeg-hisi-nv21.patch").read_bytes()) != manifest["patchSha256"]:
        raise RuntimeError("The bundled native runtime was built with a different patch")
    pending = []
    base = media3 / "libraries/decoder_ffmpeg/src/main"
    with zipfile.ZipFile(archive) as bundle:
        for name, original_hash in manifest["originalLibraries"].items():
            abi, library = name.split("/")
            data = bundle.read(name)
            expected = manifest["builds"][abi]["libraries"][library]
            if digest(data) != expected:
                raise RuntimeError(f"Runtime library checksum mismatch: {name}")
            target = base / "jniLibs" / name
            if target.is_symlink() or not target.is_file():
                raise RuntimeError(f"Invalid Media3 library target: {name}")
            actual = digest(target.read_bytes())
            if actual not in (original_hash, expected):
                raise RuntimeError(f"Unknown local Media3 binary changes: {name}")
            if actual != expected:
                pending.append((target, data))
    metadata = base / "resources/META-INF/androidx.media3.decoder.ffmpeg/native-dependencies.properties"
    original_text = metadata.read_text()
    marker = "\n# TV MPV shared runtime overlay\n"
    text = original_text.split(marker)[0].rstrip() + marker
    text += f"tv.mpv.runtime.sha256={manifest['archiveSha256']}\n"
    text += f"tv.mpv.hisi.patch.sha256={manifest['patchSha256']}\n"
    text += f"tv.mpv.native.github.run.id={manifest['nativeBuildRun']}\n"
    for abi, build in manifest["builds"].items():
        for library, checksum in build["libraries"].items():
            # Properties use the last occurrence, preserving the upstream values above.
            text += f"sha256.{abi}.{library}={checksum}\n"
    if text != original_text:
        pending.append((metadata, text.encode()))
    for target, data in pending:
        temporary = target.with_name(target.name + ".hisi-tmp")
        temporary.write_bytes(data)
        temporary.replace(target)
    return len(pending)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("media3", type=Path)
    args = parser.parse_args()
    media3 = args.media3.resolve()
    head = subprocess.check_output(["git", "-C", str(media3), "rev-parse", "HEAD"], text=True).strip()
    if head != MEDIA3_REF:
        raise SystemExit("Unexpected Media3 baseline for the shared FFmpeg runtime")
    count = synchronize(media3)
    print(f"MPV/Media3 HiSilicon runtime synchronized ({count} files updated)")


if __name__ == "__main__":
    main()
