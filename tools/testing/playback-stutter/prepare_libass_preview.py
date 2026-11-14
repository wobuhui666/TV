#!/usr/bin/env python3
"""Supply the same pinned libass x86_64 JNI to the disposable preview, then sign both APKs."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request
import zipfile

SHA = "1051212faf98ef956e06992b002d43cfdc81b6c60dcd32662e8d3ff52d584d65"


def main():
    with tempfile.TemporaryDirectory(prefix="tv-ass-preview-") as folder:
        work = Path(folder)
        url = "https://repo.maven.apache.org/maven2/io/github/peerless2012/ass-kt/0.5.1/ass-kt-0.5.1.aar"
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read(40 * 1024 * 1024 + 1)
        if hashlib.sha256(content).hexdigest() != SHA:
            raise SystemExit("Pinned libass checksum mismatch")
        archive = work / "ass.aar"
        archive.write_bytes(content)
        with zipfile.ZipFile(archive) as bundle:
            libraries = {"lib/x86_64/" + name: bundle.read("jni/x86_64/" + name)
                         for name in ("libass.so", "libasskt.so", "libc++_shared.so")}
        build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
        key = work / "test.jks"
        subprocess.run(["keytool", "-genkeypair", "-noprompt", "-storetype", "JKS", "-keystore", str(key),
                        "-storepass", "android", "-keypass", "android", "-alias", "androiddebugkey",
                        "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=CI comparison"], check=True)
        for index, root in enumerate(("app/build/outputs/apk/leanbackArm64_v8a/preview",
                                      "app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview")):
            root = Path(root)
            metadata = json.loads((root / "output-metadata.json").read_text())
            if not metadata["applicationId"].startswith("com.fongmi.android.tv.preview") or len(metadata["elements"]) != 1:
                raise SystemExit("Only isolated Preview APKs may be modified")
            apk = root / metadata["elements"][0]["outputFile"]
            unsigned, aligned = work / f"unsigned{index}.apk", work / f"aligned{index}.apk"
            with zipfile.ZipFile(apk) as source, zipfile.ZipFile(unsigned, "w") as output:
                for item in source.infolist():
                    if item.filename.startswith("META-INF/") or item.filename in libraries:
                        continue
                    output.writestr(item, source.read(item.filename))
                if index == 0:
                    for name, data in libraries.items():
                        output.writestr(name, data, compress_type=zipfile.ZIP_STORED)
            subprocess.run([str(build_tools / "zipalign"), "-f", "4", str(unsigned), str(aligned)], check=True)
            subprocess.run([str(build_tools / "apksigner"), "sign", "--ks", str(key), "--ks-pass", "pass:android",
                            "--key-pass", "pass:android", str(aligned)], check=True)
            subprocess.run([str(build_tools / "apksigner"), "verify", str(aligned)], check=True)
            apk.write_bytes(aligned.read_bytes())
            Path(str(apk) + ".idsig").unlink(missing_ok=True)
    print("Prepared disposable Preview with pinned native libass x86_64")


if __name__ == "__main__":
    main()
