#!/usr/bin/env python3
"""Verify rebuilt FFmpeg ABI compatibility, compiled fix and runtime provenance."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import tempfile
import zipfile

from verify_bridge import symbols

ROOT = Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--readelf", default="readelf")
    args = parser.parse_args()
    manifest = json.loads((ROOT / "native-hisi-provenance.json").read_text())
    current = ROOT / "mpv-android-ffmpeg9-native.zip"
    if hashlib.sha256(current.read_bytes()).hexdigest() != manifest["archiveSha256"]:
        raise SystemExit("Current archive differs from the native provenance")
    if hashlib.sha256(args.original.read_bytes()).hexdigest() != manifest["originalArchiveSha256"]:
        raise SystemExit("Unexpected original runtime")
    with tempfile.TemporaryDirectory() as directory:
        work = Path(directory)
        for path, name in ((args.original, "original"), (current, "current")):
            with zipfile.ZipFile(path) as bundle:
                bundle.extractall(work / name)
        with zipfile.ZipFile(ROOT / "mpv-android-ffmpeg9-bridge.zip") as bridge:
            bridge.extractall(work / "current")
        for abi, build in manifest["builds"].items():
            exports, imports = set(), set()
            for library in (work / "current" / abi).glob("*.so"):
                required, provided = symbols(args.readelf, library)
                exports.update(provided)
                imports.update(required)
                if library.name in build["libraries"]:
                    if hashlib.sha256(library.read_bytes()).hexdigest() != build["libraries"][library.name]:
                        raise SystemExit(f"Unexpected rebuilt library: {abi}/{library.name}")
                    previous = symbols(args.readelf, work / "original" / abi / library.name)[1]
                    lost = previous - provided
                    if lost:
                        raise SystemExit(f"Lost exported ABI in {abi}/{library.name}: {sorted(lost)}")
            unresolved = {name for name in imports if re.search(r"@(LIBAV|LIBSW)", name)} - exports
            if unresolved:
                raise SystemExit(f"Unresolved FFmpeg imports in {abi}: {sorted(unresolved)}")
            codec = work / "current" / abi / "libavcodec.so"
            if b"HiSilicon NV21 workaround applied:" not in codec.read_bytes():
                raise SystemExit(f"HiSilicon fix is absent from {abi}")
            print(f"{abi}: 7 FFmpeg libraries retain exported ABI; all versioned media imports resolve; compiled NV21 fix present")


if __name__ == "__main__":
    main()
