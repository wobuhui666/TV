#!/usr/bin/env python3
"""Generate synthetic CCTV H5E HLS fixtures and verify production Java decoding.

Requires FFmpeg (libx264/AAC), ffprobe and a JDK. All media comes from FFmpeg's
testsrc2 and sine generators; this script never fetches broadcast media.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import tempfile


PACKET_SIZE = 188
MASK = 0xFFFFFFFF
START_CODE = re.compile(b"\x00\x00(?:\x00)?\x01")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def video_pes(data):
    """Extract generated MPEG2 PES packets, retaining their original TS spans."""
    if len(data) % PACKET_SIZE:
        raise ValueError("Unaligned generated TS")
    result, active = [], {}
    for offset in range(0, len(data), PACKET_SIZE):
        packet = data[offset:offset + PACKET_SIZE]
        if packet[0] != 0x47:
            raise ValueError("Missing TS sync byte")
        control = (packet[3] >> 4) & 3
        if not control & 1:
            continue
        start = offset + 4 + (1 + packet[4] if control & 2 else 0)
        end = offset + PACKET_SIZE
        pid = ((packet[1] & 31) << 8) | packet[2]
        if packet[1] & 0x40:
            active.pop(pid, None)
            if data[start:start + 3] == b"\0\0\1" and data[start + 3] & 0xF0 == 0xE0:
                header_length = 9 + data[start + 8]
                if start + header_length > end:
                    raise ValueError("Split generated PES header")
                current = {"pid": pid, "header_length": header_length, "spans": []}
                result.append(current)
                active[pid] = current
        if pid in active:
            active[pid]["spans"].append((offset, start, end - start))
    for pes in result:
        joined = b"".join(data[start:start + count] for _, start, count in pes["spans"])
        declared = int.from_bytes(joined[4:6], "big")
        if declared:
            joined = joined[:declared + 6]
        pes["header"] = joined[:pes["header_length"]]
        pes["payload"] = joined[pes["header_length"]:]
    return result


def escape_ciphertext(data):
    """Add the escape layer that production removes before its TEA decryption."""
    result, zeros = bytearray(), 0
    for value in data:
        if zeros == 2 and value <= 3:
            result.append(3)
            zeros = 0
        result.append(value)
        zeros = zeros + 1 if value == 0 else 0
    return result


def encrypt_slice(clear):
    """Independent forward TEA: preserve the original encoded NAL, including EPBs."""
    encrypted = bytearray(clear)
    key = struct.unpack_from("<4I", encrypted, 16)
    blocks = 0
    for offset in range(32, len(encrypted) - 7, 80):
        left, right = struct.unpack_from("<2I", encrypted, offset)
        total = 0
        for _ in range(16):
            total = (total + 0x9E3779B9) & MASK
            left = (left + (((right << 4) + key[0]) ^ (right + total)
                            ^ ((right >> 5) + key[1]))) & MASK
            right = (right + (((left << 4) + key[2]) ^ (left + total)
                              ^ ((left >> 5) + key[3]))) & MASK
        struct.pack_into("<2I", encrypted, offset, left, right)
        blocks += 1
    return escape_ciphertext(encrypted), blocks


def encrypt_payload(payload, statistics):
    starts = list(START_CODE.finditer(payload))
    result, cursor = bytearray(), 0
    for index, match in enumerate(starts):
        start = match.end()
        end = starts[index + 1].start() if index + 1 < len(starts) else len(payload)
        result.extend(payload[cursor:start])
        nal = bytearray(payload[start:end])
        if nal:
            kind = nal[0] & 31
            if kind == 7 and len(nal) >= 3:
                if nal[2] & 3:
                    raise ValueError("Synthetic source unexpectedly has an H5E marker")
                nal[2] |= 1
                statistics["markedSps"] += 1
            elif kind in (1, 5) and len(nal) >= 128:
                original_length = len(nal)
                nal, blocks = encrypt_slice(nal)
                statistics["encryptedSlices"] += 1
                statistics["teaBlocks"] += blocks
                statistics["addedEscapeBytes"] += len(nal) - original_length
        result.extend(nal)
        cursor = end
    result.extend(payload[cursor:])
    return result


def adaptation_minimum(data, offset):
    """Retain flags and optional fields while allowing existing stuffing to shrink."""
    if not data[offset + 3] & 0x20:
        return -1
    length = data[offset + 4]
    if length == 0:
        return 0
    flags, cursor = data[offset + 5], offset + 6
    if flags & 0x10:
        cursor += 6  # PCR
    if flags & 0x08:
        cursor += 6  # OPCR
    if flags & 0x04:
        cursor += 1  # splice_countdown
    if flags & 0x02:
        cursor += 1 + data[cursor]
    if flags & 0x01:
        cursor += 1 + data[cursor]
    if cursor > offset + 5 + length:
        raise ValueError("Malformed generated adaptation field")
    return cursor - offset - 5


def write_pes(source, result, pes, payload):
    rewritten = bytearray(pes["header"]) + payload
    if rewritten[4:6] != b"\0\0":
        rewritten[4:6] = (len(rewritten) - 6).to_bytes(2, "big")
    counts = [count for _, _, count in pes["spans"]]
    extra = len(rewritten) - sum(counts)
    if extra < 0:
        raise ValueError("Unexpected padding inside generated video PES")
    # Reuse stuffing so original PID ordering and continuity counters survive.
    for index in reversed(range(len(counts))):
        offset = pes["spans"][index][0]
        maximum = 183 - adaptation_minimum(source, offset)
        added = min(extra, maximum - counts[index])
        counts[index] += added
        extra -= added
    if extra:
        raise ValueError("Generated PES has insufficient TS stuffing for H5E fixture")
    copied = 0
    for (offset, _, _), count in zip(pes["spans"], counts):
        length = 183 - count
        result[offset + 3] = (result[offset + 3] & 0xCF) | (0x10 if length < 0 else 0x30)
        if length >= 0:
            result[offset + 4] = length
        destination = offset + PACKET_SIZE - count
        result[destination:destination + count] = rewritten[copied:copied + count]
        copied += count


def add_annex_b_padding(clear):
    """Legal trailing_zero_8bits ensure the fixture exercises ciphertext escaping."""
    for pes in video_pes(clear):
        available = sum(183 - adaptation_minimum(clear, offset) - count
                        for offset, _, count in pes["spans"])
        if available >= 32:
            result = bytearray(clear)
            write_pes(clear, result, pes, pes["payload"] + bytes(16))
            return result
    raise ValueError("No generated PES can accommodate Annex-B trailing zeros")


def encrypt_ts(clear):
    result = bytearray(clear)
    statistics = {"markedSps": 0, "encryptedSlices": 0, "teaBlocks": 0, "addedEscapeBytes": 0}
    packets = video_pes(clear)
    statistics["videoPes"] = len(packets)
    for pes in packets:
        payload = encrypt_payload(pes["payload"], statistics)
        write_pes(clear, result, pes, payload)
    if not statistics["markedSps"] or not statistics["encryptedSlices"]:
        raise ValueError("Generated stream did not exercise H5E encryption")
    return result, statistics


def run_java_transform(root, encrypted, repaired, javac, java):
    source = root / "app/src/main/java/com/fongmi/android/tv/player/exo/CctvH5eTransform.java"
    with tempfile.TemporaryDirectory(prefix="cctv-h5e-fixture-") as directory:
        directory = Path(directory)
        harness = directory / "TransformFixture.java"
        harness.write_text("""import java.nio.file.*;
import com.fongmi.android.tv.player.exo.CctvH5eTransform;
public final class TransformFixture {
    public static void main(String[] args) throws Exception {
        byte[] input = Files.readAllBytes(Paths.get(args[0]));
        Files.write(Paths.get(args[1]), CctvH5eTransform.transform(input));
    }
}
""", encoding="utf-8")
        subprocess.run([javac, "-d", str(directory), str(source), str(harness)], check=True)
        subprocess.run([java, "-cp", str(directory), "TransformFixture", str(encrypted), str(repaired)], check=True)
    return source


def frame_hash(ffmpeg, path, require_clean=True):
    result = subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-i", str(path),
                             "-map", "0:v:0", "-map", "0:a:0", "-f", "framemd5", "-"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if require_clean and (result.returncode or result.stderr):
        raise RuntimeError(f"Decode failed for {path.name}: {result.stderr.decode(errors='replace')}")
    return result.stdout, result.stderr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True, help="Generated assets/evidence directory")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--java", default="java")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    clear = output / "clear.ts"
    encrypted = output / "cdrmldcctv-synthetic.ts"
    repaired = output / "repaired.ts"
    subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25",
                    "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
                    "-t", "4", "-c:v", "libx264", "-preset", "veryfast", "-threads:v", "1",
                    "-pix_fmt", "yuv420p", "-g", "50", "-keyint_min", "50", "-sc_threshold", "0",
                    "-b:v", "400k", "-c:a", "aac", "-b:a", "64k", "-ac", "1",
                    "-mpegts_flags", "+resend_headers", "-f", "mpegts", str(clear)], check=True)
    original_hash, _ = frame_hash(args.ffmpeg, clear)
    clear.write_bytes(add_annex_b_padding(clear.read_bytes()))
    encoded, statistics = encrypt_ts(clear.read_bytes())
    statistics["annexBPaddingBytes"] = 16
    if not statistics["addedEscapeBytes"]:
        raise ValueError("Synthetic fixture failed to exercise emulation-prevention removal")
    encrypted.write_bytes(encoded)
    source = run_java_transform(root, encrypted, repaired, args.javac, args.java)
    clear_hash, _ = frame_hash(args.ffmpeg, clear)
    repaired_hash, _ = frame_hash(args.ffmpeg, repaired)
    encrypted_hash, encrypted_errors = frame_hash(args.ffmpeg, encrypted, require_clean=False)
    (output / "clear.framemd5").write_bytes(clear_hash)
    (output / "repaired.framemd5").write_bytes(repaired_hash)
    (output / "encrypted-decode-errors.txt").write_bytes(encrypted_errors)
    if clear_hash != repaired_hash:
        raise ValueError("Production transformation changed decoded video/audio frame hashes")
    if clear_hash != original_hash:
        raise ValueError("Annex-B trailing zeros changed decoded video/audio frame hashes")
    if encrypted_hash == clear_hash or not encrypted_errors:
        raise ValueError("Encrypted control did not demonstrate the original playback failure")
    probe = json.loads(subprocess.check_output([
        args.ffprobe, "-v", "error", "-count_frames", "-show_entries",
        "stream=codec_name,width,height,nb_read_frames:format=duration", "-of", "json", str(repaired)]))
    duration = float(probe["format"]["duration"])
    for name, segment in (("clear.m3u8", clear.name), ("encrypted.m3u8", encrypted.name)):
        (output / name).write_text(
            "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:5\n#EXT-X-MEDIA-SEQUENCE:0\n"
            f"#EXTINF:{duration:.6f},\n{segment}\n#EXT-X-ENDLIST\n", encoding="utf-8")
    manifest = {
        "schema": 1,
        "source": "FFmpeg lavfi testsrc2 320x180 25fps 4s + 880Hz sine AAC; no broadcast media",
        "ffmpegVersion": subprocess.check_output([args.ffmpeg, "-version"], text=True).splitlines()[0],
        "generatorSha256": sha(Path(__file__).read_bytes()),
        "productionTransformSha256": sha(source.read_bytes()),
        "encryption": statistics,
        "validation": {
            "decodedVideoAndAudioFrameHashesMatch": True,
            "clearAndRepairedDecodeErrorLines": 0,
            "encryptedControlDecodeErrorLines": len(encrypted_errors.splitlines()),
            "frameHashSha256": sha(clear_hash),
            "streams": probe["streams"],
        },
        "files": {path.name: {"bytes": path.stat().st_size, "sha256": sha(path.read_bytes())}
                  for path in (clear, encrypted, repaired)},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
