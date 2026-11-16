#!/usr/bin/env python3
"""Reuse identical ZIP compressed bodies while reproducing a signed APK exactly."""

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import struct
import zipfile


MIN_COPY_BYTES = 256 * 1024
CHUNK_BYTES = 1024 * 1024
LOCAL_HEADER = struct.Struct("<IHHHHHIIIHH")


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(CHUNK_BYTES), b""):
            digest.update(chunk)
    return digest.hexdigest()


def compressed_body(stream, info, archive_size):
    stream.seek(info.header_offset)
    header = stream.read(LOCAL_HEADER.size)
    if len(header) != LOCAL_HEADER.size:
        raise ValueError("Truncated local ZIP header")
    values = LOCAL_HEADER.unpack(header)
    if values[0] != 0x04034B50:
        raise ValueError("Invalid local ZIP header signature")
    offset = info.header_offset + LOCAL_HEADER.size + values[-2] + values[-1]
    length = info.compress_size
    if offset < 0 or length < 0 or offset + length > archive_size:
        raise ValueError("Compressed member body is outside the archive")
    return offset, length


def matching_range(first, second, first_offset, second_offset, length):
    first.seek(first_offset)
    second.seek(second_offset)
    while length:
        size = min(CHUNK_BYTES, length)
        a = first.read(size)
        b = second.read(size)
        if len(a) != size or len(b) != size:
            raise ValueError("Truncated compressed member body")
        if a != b:
            return False
        length -= size
    return True


def copy_range(source, output, offset, length):
    source.seek(offset)
    while length:
        size = min(CHUNK_BYTES, length)
        chunk = source.read(size)
        if len(chunk) != size:
            raise ValueError("Truncated literal range")
        output.write(chunk)
        length -= size


def verify_assembly(source_path, payload_path, pieces, expected_size, expected_hash):
    digest = hashlib.sha256()
    total = 0
    with source_path.open("rb") as source, payload_path.open("rb") as payload:
        streams = {"source": source, "payload": payload}
        for piece in pieces:
            if piece["destination_offset"] != total:
                raise ValueError("Non-contiguous destination pieces")
            stream = streams[piece["kind"]]
            stream.seek(piece["offset"])
            remaining = piece["length"]
            while remaining:
                size = min(CHUNK_BYTES, remaining)
                chunk = stream.read(size)
                if len(chunk) != size:
                    raise ValueError("Truncated assembly piece")
                digest.update(chunk)
                total += size
                remaining -= size
    if total != expected_size or digest.hexdigest() != expected_hash:
        raise ValueError("Splice does not reproduce the new APK exactly")


def apply_script(recipe):
    rows = "\n".join(
        f'{piece["kind"]} {piece["offset"] + 1} {piece["length"]}'
        for piece in recipe["pieces"]
    )
    return f'''#!/system/bin/sh
# Generated for one source APK and one exact signed destination APK.
set -eu
fail() {{ printf '%s\\n' "$1" >&2; exit 1; }}
[ "$#" -eq 3 ] || fail 'Usage: sh apply.sh SOURCE_APK PAYLOAD DESTINATION_APK'
source_apk=$1
payload=$2
destination=$3
verify_file() {{
    [ -f "$1" ] || fail 'Required input or output file is missing'
    measured_size=$(wc -c < "$1") || fail 'Cannot measure file size'
    [ "$measured_size" -eq "$2" ] || fail 'File size does not match the recipe'
    measured_hash=$(sha256sum < "$1") || fail 'Cannot hash file'
    measured_hash=${{measured_hash%% *}}
    [ "$measured_hash" = "$3" ] || fail 'File SHA256 does not match the recipe'
}}
verify_file "$source_apk" {recipe['source']['size']} {recipe['source']['sha256']}
verify_file "$payload" {recipe['payload']['size']} {recipe['payload']['sha256']}
# Refuse existing destinations, including aliases of either read-only input.
[ ! -e "$destination" ] && [ ! -L "$destination" ] || fail 'Destination must not already exist'
temporary="${{destination}}.splice.$$"
(set -C; : > "$temporary") || fail 'Cannot create an exclusive temporary output'
trap 'rm -f -- "$temporary"' 0
trap 'exit 1' 1 2 3 15
while IFS=' ' read -r kind start length; do
    case "$kind" in
        source) input=$source_apk ;;
        payload) input=$payload ;;
        *) fail 'Invalid generated piece kind' ;;
    esac
    tail -c "+$start" -- "$input" | head -c "$length" >> "$temporary" || fail 'Cannot copy splice piece'
done <<'SPLICE_PIECES'
{rows}
SPLICE_PIECES
verify_file "$temporary" {recipe['new']['size']} {recipe['new']['sha256']}
# Android toybox mv -n also protects a destination created during reconstruction.
mv -n -- "$temporary" "$destination" || fail 'Cannot publish reconstructed APK'
verify_file "$destination" {recipe['new']['size']} {recipe['new']['sha256']}
printf '%s\\n' 'Reconstructed APK SHA256 verified'
'''


def create_splice(source_path, new_path, output_dir):
    source_path = source_path.resolve(strict=True)
    new_path = new_path.resolve(strict=True)
    if not source_path.is_file() or not new_path.is_file():
        raise ValueError("Source and new APK must be regular files")
    output_dir.mkdir(parents=True, exist_ok=True)
    output_dir = output_dir.resolve(strict=True)
    output_paths = [output_dir / name for name in ("payload.bin", "recipe.json", "apply.sh")]
    for path in output_paths:
        if path.exists() or path.is_symlink() or path.resolve() in (source_path, new_path):
            raise ValueError("Output files must not already exist or alias an input APK")

    source_size = source_path.stat().st_size
    new_size = new_path.stat().st_size
    source_hash = sha256_file(source_path)
    new_hash = sha256_file(new_path)
    copies = []
    with source_path.open("rb") as source, new_path.open("rb") as new:
        candidates = defaultdict(list)
        with zipfile.ZipFile(source_path) as archive:
            for info in archive.infolist():
                if info.compress_size >= MIN_COPY_BYTES:
                    offset, length = compressed_body(source, info, source_size)
                    key = (info.filename, info.compress_type, info.compress_size, info.CRC)
                    candidates[key].append(offset)
        with zipfile.ZipFile(new_path) as archive:
            for info in archive.infolist():
                if info.compress_size < MIN_COPY_BYTES:
                    continue
                new_offset, length = compressed_body(new, info, new_size)
                key = (info.filename, info.compress_type, info.compress_size, info.CRC)
                for source_offset in candidates[key]:
                    if matching_range(source, new, source_offset, new_offset, length):
                        copies.append((new_offset, length, source_offset))
                        break

    copies.sort()
    pieces = []
    payload_path, recipe_path, script_path = output_paths
    created = []
    try:
        with new_path.open("rb") as new, payload_path.open("xb") as payload:
            created.append(payload_path)
            cursor = 0
            for new_offset, length, source_offset in copies:
                if new_offset < cursor:
                    raise ValueError("Compressed member bodies overlap")
                if new_offset > cursor:
                    literal_length = new_offset - cursor
                    pieces.append({"kind": "payload", "offset": payload.tell(),
                                   "length": literal_length, "destination_offset": cursor})
                    copy_range(new, payload, cursor, literal_length)
                pieces.append({"kind": "source", "offset": source_offset,
                               "length": length, "destination_offset": new_offset})
                cursor = new_offset + length
            if cursor < new_size:
                pieces.append({"kind": "payload", "offset": payload.tell(),
                               "length": new_size - cursor, "destination_offset": cursor})
                copy_range(new, payload, cursor, new_size - cursor)

        recipe = {
            "format": "tv-apk-splice-v1",
            "minimumCompressedBodyBytes": MIN_COPY_BYTES,
            "source": {"file": source_path.name, "size": source_size, "sha256": source_hash},
            "new": {"file": new_path.name, "size": new_size, "sha256": new_hash},
            "payload": {"file": payload_path.name, "size": payload_path.stat().st_size,
                        "sha256": sha256_file(payload_path)},
            "pieces": pieces,
        }
        verify_assembly(source_path, payload_path, pieces, new_size, new_hash)
        if sha256_file(source_path) != source_hash or sha256_file(new_path) != new_hash:
            raise ValueError("An input APK changed while creating the splice")
        with recipe_path.open("x") as output:
            created.append(recipe_path)
            output.write(json.dumps(recipe, indent=2) + "\n")
        with script_path.open("x") as output:
            created.append(script_path)
            output.write(apply_script(recipe))
        script_path.chmod(0o755)
    except BaseException:
        for path in created:
            path.unlink(missing_ok=True)
        raise
    return {"newBytes": new_size, "payloadBytes": recipe["payload"]["size"],
            "reusedCompressedBytes": sum(length for _, length, _ in copies),
            "pieces": len(pieces), "newSha256": new_hash}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--new", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(create_splice(args.source, args.new, args.output_dir)))


if __name__ == "__main__":
    main()
