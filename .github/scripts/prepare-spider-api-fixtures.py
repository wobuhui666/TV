#!/usr/bin/env python3
"""Rebuild the DEX regression assets from pinned public SDK inputs for CI."""

import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import urllib.request


def download(url, destination, sha256):
    with urllib.request.urlopen(url, timeout=60) as response:
        content = response.read(4 * 1024 * 1024 + 1)
    if hashlib.sha256(content).hexdigest() != sha256:
        raise SystemExit("Spider fixture dependency checksum mismatch")
    destination.write_bytes(content)


def main():
    sdk = Path(os.environ["ANDROID_HOME"])
    with tempfile.TemporaryDirectory(prefix="tv-spider-dex-") as temporary:
        root = Path(temporary)
        api = root / "catvod-api.jar"
        okhttp = root / "okhttp-jvm.jar"
        download("https://raw.githubusercontent.com/FongMi/CatVodSpider/"
                 "3d7a0fa1a6737603231f78fb7cbaea6539d9d6e3/app/libs/catvod-api.jar", api,
                 "1dadb0bff8925022a185bb724aabb17fd577b120e8fe10c9ffd9655d20ff203c")
        download("https://repo.maven.apache.org/maven2/com/squareup/okhttp3/"
                 "okhttp-jvm/5.5.0/okhttp-jvm-5.5.0.jar", okhttp,
                 "234194a04aac54858df0a750d243af7b2e39df5e4eb86e9912043ad33a9f9a52")
        subprocess.run(["python3", "tools/testing/generate_spider_api_fixtures.py", "--api", str(api),
                        "--android-jar", str(sdk / "platforms/android-37.0/android.jar"),
                        "--okhttp", str(okhttp), "--d8", str(sdk / "build-tools/37.0.0/d8")], check=True)


if __name__ == "__main__":
    main()
