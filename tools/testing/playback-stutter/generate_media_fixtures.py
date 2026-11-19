#!/usr/bin/env python3
"""Create public synthetic SDR playback fixtures; never downloads source media.

Run on a CI runner with FFmpeg/libx264/libx265. The default four media files
total roughly 290 MiB. The manifest measures encoded video packet bytes,
rather than treating the container bitrate or CBR padding as video workload.
"""

import argparse
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys


FPS = 30
GOP = FPS * 2
MIN_DURATION = 22
SPECS = (
    {"id": "baseline1080p", "file": "baseline1080p.mp4", "width": 1920, "height": 1080,
     "encoder": "libx264", "codec": "h264", "targetVideoBitrate": 8_000_000, "noiseStrength": 8},
    {"id": "high1080p", "file": "high1080p.mp4", "width": 1920, "height": 1080,
     "encoder": "libx264", "codec": "h264", "targetVideoBitrate": 25_000_000, "noiseStrength": 18},
    {"id": "high2160p", "file": "high2160p.mp4", "width": 3840, "height": 2160,
     "encoder": "libx265", "codec": "hevc", "targetVideoBitrate": 45_000_000, "noiseStrength": 12},
)


def command(arguments, *, capture=False):
    return subprocess.run(arguments, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def font_path(argument):
    candidates = [Path(argument)] if argument else [
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path("/usr/share/fonts/dejavu/DejaVuSans.ttf"),
    ]
    for path in candidates:
        if path.is_file():
            return path.resolve()
    raise ValueError("DejaVuSans.ttf is required; install fonts-dejavu-core or pass --font")


def video_command(spec, output, duration):
    bitrate = spec["targetVideoBitrate"]
    source = f"testsrc2=size={spec['width']}x{spec['height']}:rate={FPS}"
    noise = f"noise=alls={spec['noiseStrength']}:allf=t+u:all_seed=314159"
    result = [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "warning", "-y",
        "-filter_threads", "2", "-filter_complex_threads", "2",
        "-f", "lavfi", "-i", source,
        "-f", "lavfi", "-i", "sine=frequency=997:sample_rate=48000",
        "-map", "0:v:0", "-map", "1:a:0", "-t", str(duration),
        "-vf", noise, "-c:v", spec["encoder"], "-preset", "ultrafast",
        "-pix_fmt", "yuv420p", "-b:v", str(bitrate),
        "-maxrate", str(bitrate), "-bufsize", str(bitrate * 2),
        "-g", str(GOP), "-keyint_min", str(GOP), "-bf", "2", "-threads:v", "2",
        "-color_range", "tv", "-colorspace", "bt709",
        "-color_primaries", "bt709", "-color_trc", "bt709",
    ]
    if spec["encoder"] == "libx264":
        # VBR HRD and a genuinely complex source: do not insert CBR filler NALs.
        result += ["-profile:v", "high", "-x264-params",
                   "threads=2:lookahead-threads=1:sync-lookahead=0:rc-lookahead=0:"
                   f"keyint={GOP}:min-keyint={GOP}:scenecut=0:bframes=2:b-adapt=0:"
                   "open-gop=0:nal-hrd=vbr"]
    else:
        # Bound both x265 worker pools and frame parallelism for CI memory use.
        result += ["-profile:v", "main", "-tag:v", "hvc1", "-x265-params",
                   f"pools=2:frame-threads=2:keyint={GOP}:min-keyint={GOP}:scenecut=0:"
                   "bframes=2:b-adapt=0:rc-lookahead=4:open-gop=0:repeat-headers=1:"
                   "hrd=0:strict-cbr=0:log-level=warning"]
    result += ["-c:a", "aac", "-b:a", "192k", "-threads:a", "1", "-ac", "2", "-ar", "48000",
               "-metadata", "title=" + spec["id"], "-movflags", "+faststart", str(output)]
    return result


def ass_timestamp(milliseconds):
    centiseconds = milliseconds // 10
    hours, remainder = divmod(centiseconds, 360_000)
    minutes, remainder = divmod(remainder, 6_000)
    seconds, hundredths = divmod(remainder, 100)
    return f"{hours}:{minutes:02}:{seconds:02}.{hundredths:02}"


def dense_ass(duration):
    lines = [
        "[Script Info]", "ScriptType: v4.00+", "PlayResX: 1920", "PlayResY: 1080", "WrapStyle: 2",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
        "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
        "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: Default,DejaVu Sans,42,&H0000FFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,"
        "100,100,0,0,1,2,1,2,24,24,24,1",
        "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
    ]
    end_ms = round(duration * 1000)
    for index, start in enumerate(range(0, end_ms, 300)):
        end = min(end_ms, start + 1800)
        y = 140 + index % 6 * 145
        x = 180 + index % 3 * 100
        text = f"{{\\fad(80,120)\\move({x},{y},{x + 700},{y},0,1400)}}Synthetic ASS overlay {index:03}"
        lines.append(f"Dialogue: 0,{ass_timestamp(start)},{ass_timestamp(end)},Default,,0,0,0,,{text}")
    return "\n".join(lines) + "\n"


def ass_command(video, subtitle, font, output):
    return [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "warning", "-y",
        "-i", str(video), "-i", str(subtitle), "-map", "0:v:0", "-map", "0:a:0", "-map", "1:s:0",
        "-c", "copy", "-metadata:s:s:0", "language=eng", "-metadata:s:s:0", "title=Synthetic animated ASS",
        "-disposition:s:0", "default", "-attach", str(font),
        "-metadata:s:t:0", "mimetype=application/x-truetype-font",
        "-metadata:s:t:0", "filename=DejaVuSans.ttf", str(output),
    ]


def probe_file(path, spec, duration):
    probe = json.loads(command([
        "ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path),
    ], capture=True).stdout)
    video = next(stream for stream in probe["streams"] if stream["codec_type"] == "video")
    audio = next(stream for stream in probe["streams"] if stream["codec_type"] == "audio")
    packet_data = json.loads(command([
        "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_packets",
        "-show_entries", "packet=size,duration_time", "-of", "json", str(path),
    ], capture=True).stdout)["packets"]
    packet_bytes = sum(int(packet["size"]) for packet in packet_data)
    packet_duration = sum(float(packet.get("duration_time", 0)) for packet in packet_data)
    actual_duration = float(probe["format"]["duration"])
    if packet_duration <= 0:
        packet_duration = float(video.get("duration", actual_duration))
    actual_bitrate = round(packet_bytes * 8 / packet_duration)
    if video["codec_name"] != spec["codec"] or (video["width"], video["height"]) != (spec["width"], spec["height"]):
        raise ValueError(f"Wrong video format in {path.name}")
    if video["pix_fmt"] != "yuv420p" or video.get("color_transfer") != "bt709":
        raise ValueError(f"Expected 8-bit SDR BT.709 in {path.name}")
    fps = Fraction(video.get("avg_frame_rate", video["r_frame_rate"]))
    if abs(float(fps) - FPS) > 0.01 or actual_duration < duration - 0.1:
        raise ValueError(f"Wrong frame rate or duration in {path.name}")
    if audio["codec_name"] != "aac" or int(audio["sample_rate"]) != 48_000 or audio["channels"] != 2:
        raise ValueError(f"Expected AAC stereo 48 kHz in {path.name}")
    if actual_bitrate < spec["targetVideoBitrate"] * 0.75:
        raise ValueError(f"{path.name}: actual video bitrate {actual_bitrate} is below 75% of target "
                         f"{spec['targetVideoBitrate']}; no padded low-complexity fixture is accepted")
    return {
        "id": spec["id"], "file": path.name, "sha256": sha256(path), "bytes": path.stat().st_size,
        "width": video["width"], "height": video["height"], "codec": video["codec_name"],
        "profile": video.get("profile"), "pixelFormat": video["pix_fmt"],
        "fps": float(fps), "fpsFraction": str(fps), "duration": actual_duration,
        "targetVideoBitrate": spec["targetVideoBitrate"], "actualVideoBitrate": actual_bitrate,
        "encodedVideoPacketBytes": packet_bytes, "videoPacketDuration": packet_duration,
        "audioCodec": audio["codec_name"], "audioSampleRate": int(audio["sample_rate"]),
        "audioChannels": audio["channels"], "colorSpace": video.get("color_space"),
        "colorPrimaries": video.get("color_primaries"), "colorTransfer": video.get("color_transfer"),
        "subtitleStreams": sum(stream["codec_type"] == "subtitle" for stream in probe["streams"]),
        "attachmentStreams": sum(stream["codec_type"] == "attachment" for stream in probe["streams"]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True, help="Generated artifacts outside the source tree")
    parser.add_argument("--font", help="DejaVuSans.ttf attachment; defaults to common system locations")
    parser.add_argument("--duration", type=float, default=MIN_DURATION, help="Seconds per file, at least 22")
    parser.add_argument("--dry-run", action="store_true", help="Print commands without creating media")
    args = parser.parse_args()
    if not MIN_DURATION <= args.duration <= 60:
        parser.error("--duration must be between 22 and 60 seconds")
    for name in ("ffmpeg", "ffprobe"):
        if shutil.which(name) is None:
            parser.error(f"{name} is required")
    try:
        font = font_path(args.font)
        output = args.output_dir.resolve()
        commands = [video_command(spec, output / spec["file"], args.duration) for spec in SPECS]
        commands.append(ass_command(output / "high1080p.mp4", output / "dense.ass", font,
                                    output / "high1080p-ass.mkv"))
        if args.dry_run:
            print(json.dumps({"commands": commands, "font": str(font), "fontSha256": sha256(font),
                              "duration": args.duration, "specifications": SPECS}, indent=2))
            return
        output.mkdir(parents=True, exist_ok=True)
        manifest_path = output / "manifest.json"
        if manifest_path.exists():
            raise ValueError("Output already contains a manifest; use a fresh output directory")
        entries = []
        for spec, encode in zip(SPECS, commands):
            print(f"Generating {spec['file']} ({spec['targetVideoBitrate'] / 1_000_000:g} Mbps)", flush=True)
            command(encode)
            entries.append(probe_file(output / spec["file"], spec, args.duration))
        subtitle = output / "dense.ass"
        subtitle.write_text(dense_ass(args.duration), encoding="utf-8")
        command(commands[-1])
        ass_spec = {**SPECS[1], "id": "high1080p-ass", "file": "high1080p-ass.mkv"}
        ass = probe_file(output / ass_spec["file"], ass_spec, args.duration)
        if ass["subtitleStreams"] != 1 or ass["attachmentStreams"] != 1:
            raise ValueError("ASS fixture must contain one subtitle stream and one font attachment")
        ass.update({"copiedVideoFrom": "high1080p.mp4", "subtitleFile": subtitle.name,
                    "subtitleSha256": sha256(subtitle), "fontAttachment": font.name,
                    "fontSha256": sha256(font), "subtitleIntervalMs": 300})
        entries.append(ass)
        manifest = {
            "schemaVersion": 1, "source": "FFmpeg testsrc2 plus seeded temporal noise and synthetic sine",
            "generation": {"fps": FPS, "gopFrames": GOP, "bFrames": 2, "duration": args.duration,
                           "ffmpeg": command(["ffmpeg", "-version"], capture=True).stdout.splitlines()[0],
                           "ffprobe": command(["ffprobe", "-version"], capture=True).stdout.splitlines()[0],
                           "commands": commands, "encodedBitrateMinimumTargetRatio": 0.75,
                           "videoPadding": "No CBR filler mode; x264 nal-hrd=vbr, x265 strict-cbr=0"},
            "files": entries,
        }
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        print(json.dumps({"manifest": str(manifest_path), "files": len(entries),
                          "mediaBytes": sum(entry["bytes"] for entry in entries)}, indent=2))
    except (ValueError, subprocess.CalledProcessError, OSError) as error:
        print(f"Fixture generation failed: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
