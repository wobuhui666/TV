#!/usr/bin/env python3
"""Build DEX ABI probes against independent old/new declarations, never the host API.

Requires JDK 21 and Android SDK d8. Only the probe .class file is D8 program input;
all SDK declarations are compile/classpath-only and must be absent from classes.dex.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

UPSTREAM = "4512b4c9f82534ff66acfde06ccdd68f35b074f0"
CLASSES = {
    "legacy": "com.github.catvod.fixtures.LegacyAbiFixture",
    "modern": "com.github.catvod.fixtures.ModernAbiFixture",
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def stable_archive(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, data)


def defined_classes(data):
    """Read class_def type descriptors, excluding referenced but undefined host types."""
    if not data.startswith(b"dex\n"):
        raise ValueError("D8 did not produce a DEX file")
    strings_count, strings_offset = struct.unpack_from("<II", data, 56)
    types_count, types_offset = struct.unpack_from("<II", data, 64)
    classes_count, classes_offset = struct.unpack_from("<II", data, 96)
    strings = []
    for index in range(strings_count):
        cursor = struct.unpack_from("<I", data, strings_offset + 4 * index)[0]
        while data[cursor] & 0x80:  # Skip UTF-16 length ULEB128.
            cursor += 1
        cursor += 1
        end = data.index(0, cursor)
        strings.append(data[cursor:end].decode("utf-8"))
    types = [strings[struct.unpack_from("<I", data, types_offset + 4 * index)[0]]
             for index in range(types_count)]
    return [types[struct.unpack_from("<I", data, classes_offset + 32 * index)[0]]
            for index in range(classes_count)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sdk = Path(os.environ.get("ANDROID_HOME", "/data/android-sdk"))
    parser.add_argument("--android-jar", type=Path, default=sdk / "platforms/android-37.0/android.jar")
    parser.add_argument("--d8", type=Path, default=sdk / "build-tools/37.0.0/d8")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--verify", action="store_true", help="Rebuild and require committed artifacts to match")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    sources = root / "quickjs/src/androidTest/fixtures/spider-java-compat"
    assets = root / "quickjs/src/androidTest/assets/spider-java-compat"
    for tool in (args.android_jar, args.d8):
        if not tool.is_file():
            raise SystemExit(f"Missing required SDK file: {tool}")
    android = args.android_jar.resolve()
    manifest = {
        "schema": 1,
        "upstreamReference": "FongMi/TV@" + UPSTREAM,
        "apiMode": "independent compile-only ABI declarations; no host API jar",
        "androidJarSha256": sha(android.read_bytes()),
        "javacVersion": subprocess.check_output([args.javac, "-version"], stderr=subprocess.STDOUT, text=True).strip(),
        "d8Version": subprocess.check_output([str(args.d8.resolve()), "--version"], text=True).strip(),
        "generatorSha256": sha(Path(__file__).read_bytes()),
        "minApi": 24,
        "fixtures": {},
    }
    outputs = {}
    with tempfile.TemporaryDirectory(prefix="java-spider-abi-") as folder:
        temporary = Path(folder)
        for kind, class_name in CLASSES.items():
            work = temporary / kind
            stubs, program, dex = work / "stubs", work / "program", work / "dex"
            for directory in (stubs, program, dex):
                directory.mkdir(parents=True)
            declarations = sorted((sources / "abi").rglob("*.java")) + sorted((sources / (kind + "-api")).rglob("*.java"))
            probes = sorted((sources / kind).rglob("*.java"))
            common = [args.javac, "-source", "8", "-target", "8", "-bootclasspath", str(android), "-encoding", "UTF-8"]
            subprocess.run(common + ["-d", str(stubs), *map(str, declarations)], check=True)
            api_jar = work / "compile-only-api.jar"
            stable_archive(api_jar, [(str(path.relative_to(stubs)), path.read_bytes()) for path in stubs.rglob("*.class")])
            subprocess.run(common + ["-cp", str(api_jar), "-d", str(program), *map(str, probes)], check=True)
            classes = sorted(program.rglob("*.class"))
            expected_path = class_name.replace(".", "/") + ".class"
            if [str(path.relative_to(program)) for path in classes] != [expected_path]:
                raise SystemExit("Probe must have exactly one class; no bundled helpers/lambdas: " + kind)
            subprocess.run([str(args.d8.resolve()), "--min-api", "24", "--no-desugaring", "--lib", str(android),
                            "--classpath", str(api_jar), "--output", str(dex), *map(str, classes)], check=True)
            dex_files = sorted(dex.glob("*.dex"))
            if [path.name for path in dex_files] != ["classes.dex"]:
                raise SystemExit("Expected one classes.dex: " + kind)
            descriptors = defined_classes(dex_files[0].read_bytes())
            expected_descriptor = "L" + class_name.replace(".", "/") + ";"
            if descriptors != [expected_descriptor]:
                raise SystemExit("Unexpected DEX class definitions (SDK stubs must not be bundled): " + repr(descriptors))
            jar = work / (kind + ".jar")
            stable_archive(jar, [(path.name, path.read_bytes()) for path in dex_files])
            outputs[kind + ".jar"] = jar.read_bytes()
            used_sources = declarations + probes
            manifest["fixtures"][kind] = {
                "file": kind + ".jar", "className": class_name,
                "sha256": sha(outputs[kind + ".jar"]), "bytes": len(outputs[kind + ".jar"]),
                "definedClasses": descriptors,
                "sources": {str(path.relative_to(root)): sha(path.read_bytes()) for path in used_sources},
            }
    outputs["manifest.json"] = (json.dumps(manifest, indent=2) + "\n").encode()
    if args.verify:
        differences = [name for name, data in outputs.items() if not (assets / name).is_file() or (assets / name).read_bytes() != data]
        if differences:
            raise SystemExit("Generated artifacts differ: " + ", ".join(differences))
        print("Verified both one-class DEX fixtures and provenance manifest byte for byte.")
    else:
        assets.mkdir(parents=True, exist_ok=True)
        for name, data in outputs.items():
            (assets / name).write_bytes(data)
        print(json.dumps({kind: manifest["fixtures"][kind]["sha256"] for kind in CLASSES}, indent=2))


if __name__ == "__main__":
    main()
