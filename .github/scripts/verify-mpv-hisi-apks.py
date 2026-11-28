#!/usr/bin/env python3
"""Stage the isolated #134 APKs only after checking native hashes and signing."""

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

root = Path.cwd()
tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
native = json.loads((root / "third_party/mpv/native-hisi-provenance.json").read_text())
destination = root / "dist"
destination.mkdir(exist_ok=True)
package = "com.wobuhui666.android.tv.mpv134"
results = []
certificates = set()
with zipfile.ZipFile(root / "third_party/mpv/mpv-android-ffmpeg9-native.zip") as runtime, \
        zipfile.ZipFile(root / "third_party/mpv/mpv-android-ffmpeg9-bridge.zip") as bridge:
    for flavor, abi in (("arm64_v8a", "arm64-v8a"), ("armeabi_v7a", "armeabi-v7a")):
        candidates = list((root / f"app/build/outputs/apk/leanback{flavor[0].upper() + flavor[1:]}/release").glob("*.apk"))
        if len(candidates) != 1:
            raise SystemExit(f"Expected one {abi} APK, got {candidates}")
        apk = candidates[0]
        badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
        actual = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
        if actual is None or actual.group(1) != package:
            raise SystemExit("The test package must coexist with both production packages")
        signed = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
        certificate = re.search(r"(?:Signer #[0-9]+:?|V[0-9]+(?:\.[0-9]+)? Signer:) certificate SHA-256 digest: (\S+)", signed)
        if certificate is None:
            raise SystemExit("No verified APK signing certificate")
        certificates.add(certificate.group(1))
        with zipfile.ZipFile(apk) as bundle:
            entries = bundle.namelist()
            if len(entries) != len(set(entries)) or bundle.testzip() is not None:
                raise SystemExit("Invalid APK ZIP entries")
            if {name.split('/')[1] for name in entries if name.startswith('lib/') and name.endswith('.so')} != {abi}:
                raise SystemExit("Unexpected packaged ABI")
            for library, expected in native["builds"][abi]["libraries"].items():
                if hashlib.sha256(bundle.read(f"lib/{abi}/{library}")).hexdigest() != expected:
                    raise SystemExit(f"APK contains stale FFmpeg: {abi}/{library}")
            if bundle.read(f"lib/{abi}/libmpv.so") != runtime.read(f"{abi}/libmpv.so"):
                raise SystemExit("The test unexpectedly changed libmpv")
            if bundle.read(f"lib/{abi}/libplayer.so") != bridge.read(f"{abi}/libplayer.so"):
                raise SystemExit("The test unexpectedly changed the extended JNI bridge")
        target = destination / f"TV-MPV134-{abi}-coexist.apk"
        shutil.copyfile(apk, target)
        results.append({"file": target.name, "sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                        "size": target.stat().st_size, "abi": abi})
if len(certificates) != 1:
    raise SystemExit("The two APKs have different signing certificates")
report = {"sourceCommit": os.environ["GITHUB_SHA"], "workflowRun": os.environ["GITHUB_RUN_ID"],
          "applicationId": package, "versionName": os.environ["TV_VERSION_NAME"],
          "signingCertificateSha256": next(iter(certificates)),
          "nativeBuildRun": native["nativeBuildRun"], "nativeArchiveSha256": native["archiveSha256"],
          "files": results, "v55iTested": False}
(destination / "build-verification.json").write_text(json.dumps(report, indent=2) + "\n")
(destination / "SHA256SUMS").write_text("".join(f"{row['sha256']}  {row['file']}\n" for row in results))
shutil.copyfile(root / "third_party/mpv/native-hisi-provenance.json", destination / "native-provenance.json")
print(json.dumps(report, indent=2))
