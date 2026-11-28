#!/usr/bin/env python3
"""Check the proposed HiSilicon NV21 fix against pinned FFmpeg source on the host.

Compiles the actual mapping and buffer-copy functions before/after the patch.
The small C shim supplies only the FFmpeg types those functions use; this is
not a replacement for compiling FFmpeg or testing a device's MediaCodec output.
"""

import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile
import urllib.request


REF = "04482c8d13ac27b2a9fe93f5d388929eef8af5f4"
SOURCES = {
    "mediacodecdec_common.c": "c9af689b55d29ea3db96337a2b81f823936e91b0fcf6eb49b35a6be994519417",
    "mediacodec_sw_buffer.c": "6efe3c666e60127ace08048c6a2fb3c3081e0ea7eb314ed752703bcf1e02d321",
}


def function(source, signature):
    start = source.index(signature)
    body = source.index("{", start)
    depth = 1
    end = body + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


SHIM = r"""
#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#define FF_ARRAY_ELEMS(a) ((int)(sizeof(a) / sizeof((a)[0])))
#define FFMIN(a, b) ((a) < (b) ? (a) : (b))
#define FFALIGN(x, a) (((x) + (a) - 1) & ~((a) - 1))
#define AV_LOG_ERROR 16
#define AV_LOG_INFO 32
enum AVPixelFormat { AV_PIX_FMT_NONE = -1, AV_PIX_FMT_YUV420P,
    AV_PIX_FMT_NV12, AV_PIX_FMT_NV21, AV_PIX_FMT_MEDIACODEC };
typedef struct { int width, height; } AVCodecContext;
typedef struct { void *surface; const char *codec_name;
    int color_format, stride, slice_height, crop_top, crop_left; } MediaCodecDecContext;
typedef struct { int offset; } FFAMediaCodecBufferInfo;
typedef struct { uint8_t *data[3]; int linesize[3]; } AVFrame;
static void av_log(void *ctx, int level, const char *message, ...) {
    (void)ctx; (void)level; (void)message;
}
"""

TESTS = r"""
static int check_mapping(void) {
    struct {
        const char *codec;
        int format;
        int surface;
        enum AVPixelFormat expected;
    } cases[] = {
        {"OMX.hisi.video.decoder.hevc", 0x7fa30c00, 0, AV_PIX_FMT_NV21},
        {"OMX.hisi.video.decoder.avc", 0x7fa30c00, 0, AV_PIX_FMT_NV21},
        {"OMX.hisi.video.decoder.hevc", 0x15, 0, AV_PIX_FMT_NV12},
        {"OMX.hisi.video.decoder.hevc", 0x13, 0, AV_PIX_FMT_YUV420P},
        {"OMX.hisi.video.decoder.hevc", 0x7fa30c04, 0, AV_PIX_FMT_NV12},
        {"OMX.qcom.video.decoder.hevc", 0x7fa30c00, 0, AV_PIX_FMT_NV12},
        {"OMX.vendor.video.decoder.hevc", 0x7fa30c00, 0, AV_PIX_FMT_NV12},
        {"OMX.hisi.video.decoder.hevc", 0x7fa30c00, 1, AV_PIX_FMT_MEDIACODEC},
        {"OMX.hisi.video.decoder.hevc", 0x7f000789, 1, AV_PIX_FMT_MEDIACODEC},
        {"OMX.k3.video.decoder.avc", 0x19, 0, AV_PIX_FMT_NV12},
        {"OMX.hisi.video.decoder.hevc", 0x12345678, 0, AV_PIX_FMT_NONE},
    };
    AVCodecContext ctx = {0};
    int failures = 0;
    for (int i = 0; i < FF_ARRAY_ELEMS(cases); i++) {
        MediaCodecDecContext codec = {.codec_name = cases[i].codec,
            .surface = cases[i].surface ? &ctx : NULL, .color_format = cases[i].format};
        enum AVPixelFormat actual = mcdec_map_color_format(&ctx, &codec, cases[i].format);
        if (actual != cases[i].expected) {
            fprintf(stderr, "case %d: %s format=%x expected=%d got=%d\n",
                    i, cases[i].codec, cases[i].format, cases[i].expected, actual);
            failures++;
        }
    }
    printf("mapping: %d/%d passed\n", FF_ARRAY_ELEMS(cases) - failures, FF_ARRAY_ELEMS(cases));
    return failures;
}

static void check_copy(int destination_stride) {
    // Two visible luma rows, padded source stride and slice height, nonzero
    // buffer offset and left crop. Chroma is deliberately VU, not UV.
    uint8_t source[48], luma[16], chroma[8];
    memset(source, 0xee, sizeof(source));
    memset(luma, 0xa5, sizeof(luma));
    memset(chroma, 0xa5, sizeof(chroma));
    uint8_t y0[] = {81, 145, 41, 210}, y1[] = {16, 235, 81, 145};
    uint8_t vu[] = {240, 90, 34, 54};
    memcpy(source + 2 + 2, y0, 4);
    memcpy(source + 2 + 8 + 2, y1, 4);
    memcpy(source + 2 + 4 * 8 + 2, vu, 4);
    AVCodecContext ctx = {.width = 4, .height = 2};
    MediaCodecDecContext codec = {.stride = 8, .slice_height = 4, .crop_left = 2};
    FFAMediaCodecBufferInfo info = {.offset = 2};
    AVFrame frame = {.data = {luma, chroma, NULL},
                     .linesize = {destination_stride, destination_stride, 0}};
    ff_mediacodec_sw_buffer_copy_yuv420_semi_planar(&ctx, &codec,
            source, sizeof(source), &info, &frame);
    assert(!memcmp(luma, y0, 4));
    assert(!memcmp(luma + destination_stride, y1, 4));
    assert(!memcmp(chroma, vu, 4));
    if (destination_stride != 8) {
        assert(chroma[4] == 0xa5);
        assert(luma[2 * destination_stride] == 0xa5);
    }
}

int main(void) {
    int failures = check_mapping();
    check_copy(4);
    check_copy(6);
    check_copy(8);
    puts("semi-planar buffer copy: 3/3 passed; VU bytes preserved");
    return failures ? 1 : 0;
}
"""


def build_and_run(root, label):
    source = (root / "libavcodec/mediacodecdec_common.c").read_text()
    signature = "static enum AVPixelFormat mcdec_map_color_format("
    mapping = source[source.index("enum {\n    COLOR_FormatYUV420Planar"):
                     source.index(signature)] + function(source, signature)
    copy = function((root / "libavcodec/mediacodec_sw_buffer.c").read_text(),
                    "void ff_mediacodec_sw_buffer_copy_yuv420_semi_planar(")
    c_file = root / (label + ".c")
    c_file.write_text(SHIM + mapping + copy + TESTS)
    binary = root / label
    subprocess.run(["cc", "-std=c11", "-O1", "-g", "-Wall", "-Wextra",
                    "-Wno-unused-parameter", "-fsanitize=address,undefined",
                    str(c_file), "-o", str(binary)], check=True)
    return subprocess.run([str(binary)], capture_output=True, text=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path,
                        help="Existing FFmpeg source; otherwise fetch only two pinned C files")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="mpv-hisi-nv21-") as directory:
        root = Path(directory)
        (root / "libavcodec").mkdir()
        for name, expected in SOURCES.items():
            if args.source_dir:
                data = (args.source_dir / "libavcodec" / name).read_bytes()
            else:
                url = f"https://raw.githubusercontent.com/FongMi/FFmpeg/{REF}/libavcodec/{name}"
                with urllib.request.urlopen(url, timeout=30) as response:
                    data = response.read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise SystemExit(f"Unexpected source checksum: {name}")
            (root / "libavcodec" / name).write_bytes(data)
        before = build_and_run(root, "before")
        print("Before patch:", before.stdout, before.stderr, sep="\n")
        if before.returncode != 1 or "mapping: 9/11 passed" not in before.stdout:
            raise SystemExit("The baseline must fail exactly the two HiSilicon NV21 cases")
        patch = Path(__file__).resolve().parent / "patches/ffmpeg-hisi-nv21.patch"
        subprocess.run(["git", "apply", "--check", str(patch)], cwd=root, check=True)
        subprocess.run(["git", "apply", str(patch)], cwd=root, check=True)
        after = build_and_run(root, "after")
        print("After patch:", after.stdout, after.stderr, sep="\n")
        if after.returncode != 0:
            raise SystemExit("Patched mapping or buffer-copy regression failed")


if __name__ == "__main__":
    main()
