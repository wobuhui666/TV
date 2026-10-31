#!/usr/bin/env python3
"""Generate release versions and OTA manifests from actual Gradle APK outputs."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import zipfile

EPOCH = datetime(2020, 1, 1, tzinfo=timezone.utc)
MAX_VERSION_CODE = 2_100_000_000
LOCAL_VERSION_CODE = 585
BASE_VERSION_NAME = "5.8.5"
ABIS = {"arm64_v8a": "arm64-v8a", "armeabi_v7a": "armeabi-v7a"}
PACKAGE_NAME = "com.fongmi.android.tv"
COEXIST_PACKAGE_NAME = "com.wobuhui666.android.tv"


def release_version(started_at=None):
    instant = datetime.fromisoformat(started_at.replace("Z", "+00:00")) if started_at else datetime.now(timezone.utc)
    if instant.tzinfo is None:
        raise ValueError("Build start time must include a UTC offset")
    instant = instant.astimezone(timezone.utc)
    code = int((instant - EPOCH).total_seconds()) + 1_000_000
    if not LOCAL_VERSION_CODE < code <= MAX_VERSION_CODE:
        raise ValueError("Build time produces an unsupported Android version code")
    return code, BASE_VERSION_NAME + "+" + instant.strftime("%Y%m%d.%H%M%S")


def verify_apk(apk, android_abi):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError(f"Duplicate ZIP entries in {apk.name}")
        if archive.testzip() is not None:
            raise ValueError(f"Corrupt APK ZIP: {apk.name}")
        if not archive.read("AndroidManifest.xml").startswith(b"\x03\x00\x08\x00"):
            raise ValueError(f"Missing binary Android manifest: {apk.name}")
        if not archive.read("classes.dex").startswith(b"dex\n"):
            raise ValueError(f"Missing Android bytecode: {apk.name}")
        packaged_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        if packaged_abis != {android_abi}:
            raise ValueError(f"Wrong native ABI in {apk.name}: {sorted(packaged_abis)}")
    digest = hashlib.sha256()
    with apk.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return {"size": apk.stat().st_size, "sha256": digest.hexdigest()}


def build_manifest(apk_root, repository, tag, mode="leanback", desc="", coexist=False):
    if repository != "wobuhui666/TV":
        raise ValueError("OTA releases must belong to wobuhui666/TV")
    if not re.fullmatch(r"build-[1-9][0-9]*(?:-[1-9][0-9]*)?", tag):
        raise ValueError("Expected a build-<run_id>-<run_attempt> release tag")
    if mode not in ("leanback", "mobile"):
        raise ValueError("Unsupported application mode")
    root = Path(apk_root).resolve()
    package_name = COEXIST_PACKAGE_NAME if coexist else PACKAGE_NAME
    suffix = "-coexist" if coexist else ""
    expected = {f"{mode}-{abi}{suffix}.apk": abi for abi in ABIS}
    assets = {}
    version = None
    for metadata_path in sorted(root.rglob("output-metadata.json")):
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        for element in metadata.get("elements", []):
            filename = element.get("outputFile", "")
            if Path(filename).name not in expected:
                continue
            if filename != Path(filename).name:
                raise ValueError("APK outputFile must be a plain filename")
            abi = expected[filename]
            if abi in assets:
                raise ValueError(f"Multiple outputs for {abi}")
            apk = (metadata_path.parent / filename).resolve()
            if not apk.is_relative_to(root):
                raise ValueError("APK output escapes its build directory")
            if metadata.get("applicationId") != package_name:
                raise ValueError("Unexpected Android package name")
            code, name = element.get("versionCode"), element.get("versionName")
            if type(code) is not int or not LOCAL_VERSION_CODE < code <= MAX_VERSION_CODE:
                raise ValueError("Missing or unsupported CI versionCode in APK metadata")
            if not isinstance(name, str) or not name.strip():
                raise ValueError("Missing versionName in APK metadata")
            if version is not None and version != (code, name):
                raise ValueError("ABI APKs have inconsistent versions")
            version = (code, name)
            assets[abi] = {
                "url": f"https://github.com/{repository}/releases/download/{tag}/{filename}",
                **verify_apk(apk, ABIS[abi]),
            }
    if set(assets) != set(ABIS):
        raise ValueError("Both ARM64 and ARMv7 APK outputs are required")
    return {
        "schema": 1,
        "code": version[0],
        "name": version[1],
        "desc": desc,
        "packageName": package_name,
        "mode": mode,
        "assets": assets,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    version = commands.add_parser("version", help="Print TV_VERSION_CODE/TV_VERSION_NAME for GITHUB_ENV")
    version.add_argument("--started-at", help="ISO-8601 build start time; defaults to current UTC")
    manifest = commands.add_parser("manifest", help="Validate release APKs and write the OTA manifest")
    manifest.add_argument("--apk-root", type=Path, required=True)
    manifest.add_argument("--repository", required=True)
    manifest.add_argument("--tag", required=True)
    manifest.add_argument("--mode", choices=("leanback", "mobile"), default="leanback")
    manifest.add_argument("--desc", default="")
    manifest.add_argument("--coexist", action="store_true", help="Validate the com.wobuhui666.android.tv coexist APKs")
    manifest.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "version":
            code, name = release_version(args.started_at)
            print(f"TV_VERSION_CODE={code}\nTV_VERSION_NAME={name}")
        else:
            result = build_manifest(args.apk_root, args.repository, args.tag, args.mode, args.desc, args.coexist)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"OTA release validation failed: {error}\n")


if __name__ == "__main__":
    main()
