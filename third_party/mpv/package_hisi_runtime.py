#!/usr/bin/env python3
"""Merge checked GHA FFmpeg outputs into the existing MPV runtime archive."""

import argparse
import hashlib
import json
from pathlib import Path
import tempfile
import zipfile

from build_hisi_ffmpeg import FFMPEG_REF, LIBRARIES, MARKER, PRODUCER_REF, ROOT, sha256

ABIS = ("arm64-v8a", "armeabi-v7a")
ORIGINAL = "d3f22c64ab90a1476b489c2b4f06de8296672497f2998161efb4d906f9a4a66c"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--original", type=Path, help="Original unpatched archive, when updating an existing fix")
    args = parser.parse_args()
    archive = ROOT / "mpv-android-ffmpeg9-native.zip"
    manifest_path = ROOT / "native-hisi-provenance.json"
    previous_libraries = {}
    if manifest_path.exists():
        previous = json.loads(manifest_path.read_text())
        if sha256(archive) != previous["archiveSha256"] or previous["originalArchiveSha256"] != ORIGINAL:
            raise SystemExit("Existing runtime differs from its recorded provenance")
        previous_libraries = previous.get("previousLibraries", {})
        with zipfile.ZipFile(archive) as current:
            for abi, build in previous["builds"].items():
                for library, checksum in build["libraries"].items():
                    name = f"{abi}/{library}"
                    if hashlib.sha256(current.read(name)).hexdigest() != checksum:
                        raise SystemExit(f"Existing runtime library checksum mismatch: {name}")
                    previous_libraries[name] = sorted(set(previous_libraries.get(name, []) + [checksum]))
    source_archive = args.original or archive
    if sha256(source_archive) != ORIGINAL:
        raise SystemExit("Expected the original runtime archive before merging")
    replacements, original, provenance = {}, {}, {}
    patch_hash = sha256(ROOT / "patches/ffmpeg-hisi-nv21.patch")
    for abi in ABIS:
        matches = list(args.input.rglob(f"provenance-{abi}.json"))
        if len(matches) != 1:
            raise SystemExit(f"Expected one provenance file for {abi}")
        manifest = json.loads(matches[0].read_text())
        if (manifest["ffmpegCommit"] != FFMPEG_REF or manifest["producerCommit"] != PRODUCER_REF
                or manifest["abi"] != abi or manifest["ndk"] != "29.0.14206865"
                or manifest["androidApi"] != 24 or manifest["patchSha256"] != patch_hash):
            raise SystemExit(f"Native build provenance mismatch: {abi}")
        provenance[abi] = manifest
        for name in LIBRARIES:
            path = matches[0].parent / abi / name
            if sha256(path) != manifest["libraries"][name]:
                raise SystemExit(f"Native artifact checksum mismatch: {abi}/{name}")
            data = path.read_bytes()
            if name == "libavcodec.so" and MARKER not in data:
                raise SystemExit(f"Missing compiled HiSilicon correction: {abi}")
            replacements[f"{abi}/{name}"] = data
    with tempfile.TemporaryDirectory(dir=ROOT) as directory:
        merged = Path(directory) / archive.name
        with zipfile.ZipFile(source_archive) as source, zipfile.ZipFile(merged, "w", zipfile.ZIP_DEFLATED) as target:
            for name in source.namelist():
                data = source.read(name)
                if name in replacements:
                    original[name] = hashlib.sha256(data).hexdigest()
                    data = replacements[name]
                info = zipfile.ZipInfo(name, (2026, 11, 28, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                target.writestr(info, data)
        merged.replace(archive)
    result = {"originalArchiveSha256": ORIGINAL, "archiveSha256": sha256(archive),
              "nativeBuildRun": args.run_id, "patchSha256": patch_hash,
              "originalLibraries": original, "previousLibraries": previous_libraries,
              "builds": provenance}
    manifest_path.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Updated {archive.name}: {result['archiveSha256']}")


if __name__ == "__main__":
    main()
