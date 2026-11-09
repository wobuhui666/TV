#!/usr/bin/env python3
"""Add pinned x86_64 JNI libraries to the disposable emulator preview APK."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import zipfile


WRAPPER_VERSION = "3.2.3"
WRAPPER_SHA256 = "b1d0c550818f07e411b6e6f0eb21b28d83ed2e227d6129395be9e5a2167f4f0c"
LIBRARY_SHA256 = "3728422a3a48450372e0e528da3c75da74e7e86825eeb766b2d814e54e5015d1"
LIBRARY = "libquickjs-android-wrapper.so"
GLIDE_VERSION = "5.0.7"
AVIF_VERSION = "1.1.1.14d8e3c4"
AVIF_WRAPPER_SHA256 = "d46e1e215388a6ccd9cd2e333929ba3f03671e5e72a7ab1f1f0a2a35c8bd66a5"
AVIF_LIBRARY_SHA256 = "d63e4fe8cba8992b28a4497b9a174c0549ed875ec4b28115ec7cf6c55136c5a4"
AVIF_LIBRARY = "libavif_android.so"
ARCHIVE_VERSION = "1.1.7"
ARCHIVE_WRAPPER_SHA256 = "060587bbec1b6b79f8b1fd02eebfbe638174d7889a6c3cfd6ce3f34b5da4d46b"
ARCHIVE_LIBRARY = "libarchive-jni.so"
CONSCRYPT_VERSION = "2.7.0"
CONSCRYPT_WRAPPER_SHA256 = "86072ce711795e188e1e82214d023789d2983fafe342bcae83f046125cf64e7f"
CONSCRYPT_LIBRARY_SHA256 = "b5e6823520dd79f41f36cf1e36a8d72c4651823188708a6c68e2fb752be8e55d"
CONSCRYPT_LIBRARY = "libconscrypt_jni.so"


def run(*args):
    return subprocess.check_output([str(arg) for arg in args], text=True)


def main():
    versions = Path("gradle/libs.versions.toml").read_text()
    if not re.search(r'^quickjs\s*=\s*"' + re.escape(WRAPPER_VERSION) + r'"\s*$', versions, re.M):
        raise SystemExit("Update the emulator QuickJS library pin when changing the app dependency")
    if not re.search(r'^glide\s*=\s*"' + re.escape(GLIDE_VERSION) + r'"\s*$', versions, re.M):
        raise SystemExit("Update the emulator AVIF dependency pin when changing Glide")
    if not re.search(r'^conscrypt\s*=\s*"' + re.escape(CONSCRYPT_VERSION) + r'"\s*$', versions, re.M):
        raise SystemExit("Update the emulator Conscrypt pin when changing the app dependency")
    metadata_file = Path("app/build/outputs/apk/leanbackArm64_v8a/preview/output-metadata.json")
    metadata = json.loads(metadata_file.read_text())
    if not metadata["applicationId"].endswith(".preview") or len(metadata["elements"]) != 1:
        raise SystemExit("Only an isolated, single-output preview APK may be modified")
    apk = metadata_file.parent / metadata["elements"][0]["outputFile"]
    test_metadata_file = Path("app/build/outputs/apk/androidTest/leanbackArm64_v8a/preview/output-metadata.json")
    test_metadata = json.loads(test_metadata_file.read_text())
    if len(test_metadata["elements"]) != 1 or test_metadata["applicationId"] != metadata["applicationId"] + ".test":
        raise SystemExit("Expected the isolated preview instrumentation APK")
    test_apk = test_metadata_file.parent / test_metadata["elements"][0]["outputFile"]
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"

    with tempfile.TemporaryDirectory(prefix="tv-quickjs-preview-") as temporary:
        directory = Path(temporary)
        # Cached signed Gradle outputs do not necessarily recreate ~/.android/debug.keystore.
        # Sign both disposable APKs with one new key so their instrumentation signatures match.
        keystore = directory / "preview.jks"
        run("keytool", "-genkeypair", "-noprompt", "-storetype", "JKS",
            "-keystore", keystore, "-storepass", "android", "-keypass", "android",
            "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=TV CI Preview,O=Android,C=US")
        wrapper = directory / "quickjs.aar"
        url = ("https://repo.maven.apache.org/maven2/wang/harlon/quickjs/wrapper-android/"
               f"{WRAPPER_VERSION}/wrapper-android-{WRAPPER_VERSION}.aar")
        with urllib.request.urlopen(url, timeout=60) as response:
            content = response.read(16 * 1024 * 1024 + 1)
        if hashlib.sha256(content).hexdigest() != WRAPPER_SHA256:
            raise SystemExit("QuickJS wrapper checksum mismatch")
        wrapper.write_bytes(content)
        with zipfile.ZipFile(wrapper) as bundle:
            library = bundle.read("jni/x86_64/" + LIBRARY)
        if hashlib.sha256(library).hexdigest() != LIBRARY_SHA256:
            raise SystemExit("QuickJS x86_64 library checksum mismatch")

        # Glide probes AVIF before standard image decoders, even for TMDB JPEG artwork.
        # The ARM64 build strips x86_64 JNI, so the emulator needs this matching decoder too.
        avif_url = ("https://repo.maven.apache.org/maven2/org/aomedia/avif/android/avif/"
                    f"{AVIF_VERSION}/avif-{AVIF_VERSION}.aar")
        with urllib.request.urlopen(avif_url, timeout=60) as response:
            avif_content = response.read(16 * 1024 * 1024 + 1)
        if hashlib.sha256(avif_content).hexdigest() != AVIF_WRAPPER_SHA256:
            raise SystemExit("AVIF wrapper checksum mismatch")
        avif_wrapper = directory / "avif.aar"
        avif_wrapper.write_bytes(avif_content)
        with zipfile.ZipFile(avif_wrapper) as bundle:
            avif_library = bundle.read("jni/x86_64/" + AVIF_LIBRARY)
        if hashlib.sha256(avif_library).hexdigest() != AVIF_LIBRARY_SHA256:
            raise SystemExit("AVIF x86_64 library checksum mismatch")

        archive_url = ("https://repo.maven.apache.org/maven2/me/zhanghai/android/libarchive/library/"
                       f"{ARCHIVE_VERSION}/library-{ARCHIVE_VERSION}.aar")
        with urllib.request.urlopen(archive_url, timeout=60) as response:
            archive_content = response.read(16 * 1024 * 1024 + 1)
        if hashlib.sha256(archive_content).hexdigest() != ARCHIVE_WRAPPER_SHA256:
            raise SystemExit("libarchive wrapper checksum mismatch")
        archive_wrapper = directory / "libarchive.aar"
        archive_wrapper.write_bytes(archive_content)
        with zipfile.ZipFile(archive_wrapper) as bundle:
            archive_library = bundle.read("jni/x86_64/" + ARCHIVE_LIBRARY)

        # ECH uses the app's unbundled Conscrypt, whose JNI is also removed by ARM64 filtering.
        conscrypt_url = ("https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/"
                        f"{CONSCRYPT_VERSION}/conscrypt-android-{CONSCRYPT_VERSION}.aar")
        with urllib.request.urlopen(conscrypt_url, timeout=60) as response:
            conscrypt_content = response.read(16 * 1024 * 1024 + 1)
        if hashlib.sha256(conscrypt_content).hexdigest() != CONSCRYPT_WRAPPER_SHA256:
            raise SystemExit("Conscrypt wrapper checksum mismatch")
        conscrypt_wrapper = directory / "conscrypt.aar"
        conscrypt_wrapper.write_bytes(conscrypt_content)
        with zipfile.ZipFile(conscrypt_wrapper) as bundle:
            conscrypt_library = bundle.read("jni/x86_64/" + CONSCRYPT_LIBRARY)
        if hashlib.sha256(conscrypt_library).hexdigest() != CONSCRYPT_LIBRARY_SHA256:
            raise SystemExit("Conscrypt x86_64 library checksum mismatch")

        library_entry = "lib/x86_64/" + LIBRARY
        avif_entry = "lib/x86_64/" + AVIF_LIBRARY
        archive_entry = "lib/x86_64/" + ARCHIVE_LIBRARY
        conscrypt_entry = "lib/x86_64/" + CONSCRYPT_LIBRARY
        prepared = []
        for index, original in enumerate((apk, test_apk)):
            unsigned = directory / f"unsigned-{index}.apk"
            with zipfile.ZipFile(original) as source, zipfile.ZipFile(unsigned, "w") as output:
                for item in source.infolist():
                    if index == 0 and item.filename in (library_entry, avif_entry, archive_entry, conscrypt_entry):
                        continue
                    if re.match(r"META-INF/[^/]+\.(SF|RSA|DSA|EC)$", item.filename, re.I):
                        continue
                    if item.filename.upper() == "META-INF/MANIFEST.MF":
                        continue
                    output.writestr(item, source.read(item.filename))
                if index == 0:
                    output.writestr(library_entry, library, compress_type=zipfile.ZIP_STORED)
                    output.writestr(avif_entry, avif_library, compress_type=zipfile.ZIP_STORED)
                    output.writestr(archive_entry, archive_library, compress_type=zipfile.ZIP_STORED)
                    output.writestr(conscrypt_entry, conscrypt_library, compress_type=zipfile.ZIP_STORED)
            aligned = directory / f"preview-{index}.apk"
            run(build_tools / "zipalign", "-P", "16", "-f", "4", unsigned, aligned)
            run(build_tools / "apksigner", "sign", "--ks", keystore,
                "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
                "--key-pass", "pass:android", aligned)
            run(build_tools / "apksigner", "verify", aligned)
            prepared.append((original, aligned))
        with zipfile.ZipFile(prepared[0][1]) as bundle:
            assert hashlib.sha256(bundle.read(library_entry)).hexdigest() == LIBRARY_SHA256
            assert hashlib.sha256(bundle.read(avif_entry)).hexdigest() == AVIF_LIBRARY_SHA256
            assert bundle.read(archive_entry) == archive_library
            assert hashlib.sha256(bundle.read(conscrypt_entry)).hexdigest() == CONSCRYPT_LIBRARY_SHA256
        for original, aligned in prepared:
            original.write_bytes(aligned.read_bytes())
            Path(str(original) + ".idsig").unlink(missing_ok=True)
    print("Prepared isolated preview with QuickJS, AVIF, libarchive and Conscrypt x86_64 JNI")


if __name__ == "__main__":
    main()
