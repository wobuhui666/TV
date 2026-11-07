#!/usr/bin/env python3
"""Rebuild Chicory 1.4.1's Android JAR with its ByteBuffer implementation under both memory names."""

import argparse
import hashlib
from pathlib import Path
import struct
import zipfile

INPUT_SHA256 = "0880cee04b2ffad2f133cfd3ba8b2119f7234db13fbf68657ea5c3e1301d5fe6"
BUFFER = "com/dylibso/chicory/runtime/ByteBufferMemory"
ARRAY = "com/dylibso/chicory/runtime/ByteArrayMemory"


def android_memory(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Expected an original Java class")
    output = bytearray(data[:10])
    count = struct.unpack_from(">H", data, 8)[0]
    offset, index, replacements = 10, 1, 0
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4,
             12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while index < count:
        tag = data[offset]
        output.append(tag)
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            value = data[offset:offset + length]
            offset += length
            if BUFFER.encode() in value:
                replacements += 1
                value = value.replace(BUFFER.encode(), ARRAY.encode())
            output.extend(struct.pack(">H", len(value)))
            output.extend(value)
        else:
            size = sizes[tag]
            output.extend(data[offset:offset + size])
            offset += size
            if tag in (5, 6):
                index += 1
        index += 1
    if not replacements:
        raise ValueError("Original ByteBufferMemory class name was not present")
    output.extend(data[offset:])
    # Standard lambda/string-concat bootstrap handles are desugared by D8. The
    # removed array implementation's VarHandle class and invokeExact calls are not.
    if b"java/lang/invoke/VarHandle" in output or b"invokeExact" in output:
        raise ValueError("Android memory still contains a polymorphic memory access")
    return bytes(output)


def prepare(source, target):
    if hashlib.sha256(source.read_bytes()).hexdigest() != INPUT_SHA256:
        raise ValueError("Update the reviewed Chicory version and checksum together")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(".part")
    with zipfile.ZipFile(source) as original:
        replacement = android_memory(original.read(BUFFER + ".class"))
        with zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as result:
            for name in sorted(original.namelist()):
                if name.endswith("/"):
                    continue
                data = replacement if name == ARRAY + ".class" else original.read(name)
                entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                result.writestr(entry, data)
    temporary.replace(target)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.input, args.output)
    print("Prepared checksum-verified Chicory Android memory implementation")
