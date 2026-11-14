#!/usr/bin/env python3
"""Run the production pool/diagnostic JUnit tests with narrow headless Android shims.

This checks Java query/lifecycle/logging behavior and allocation costs, not Android
drawing, decoder scheduling or audible output. All generated files stay in /tmp.
"""

import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
MEDIA = Path(os.environ.get("MEDIA3_SOURCE_DIR", ROOT / ".media3"))
POOL = "libraries/ui_danmaku/src/main/java/androidx/media3/ui/danmaku/"
EXO = "libraries/exoplayer/src/main/java/androidx/media3/exoplayer/"
PACKAGE = "androidx/media3/ui/danmaku/"


def block(source, marker):
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def dependency(group, name, version):
    base = Path.home() / ".gradle/caches/modules-2/files-2.1" / group / name / version
    matches = list(base.glob(f"*/{name}-{version}.jar"))
    if not matches:
        raise SystemExit(f"Missing cached test dependency: {group}:{name}:{version}")
    return str(matches[0])


def run():
    subprocess.run(["python3", str(ROOT / "third_party/media3/apply_patches.py"), str(MEDIA)], check=True)
    analytics = (MEDIA / EXO / "analytics/AnalyticsListener.java").read_text()
    callbacks = [
        "onAudioDecoderInitialized", "onAudioInputFormatChanged", "onAudioTrackInitialized",
        "onAudioTrackReleased", "onAudioUnderrun", "onAudioSinkError", "onAudioCodecError",
        "onDroppedVideoFrames", "onAudioDisabled",
        "onVideoDecoderInitialized", "onVideoDecoderReleased", "onVideoInputFormatChanged",
        "onVideoDisabled", "onVideoCodecError",
    ]
    methods = []
    for name in callbacks:
        match = re.search(r"default void " + name + r"\([\s\S]*?\) \{\}", analytics)
        if match is None:
            raise SystemExit(f"Callback no longer matches fixed Media3: {name}")
        methods.append(match.group())
    constructor = block(analytics, "public EventTime(")
    track = block((MEDIA / EXO / "audio/AudioSink.java").read_text(), "final class AudioTrackConfig")
    stubs = {
        "android/graphics/Color.java": "package android.graphics; public class Color { public static final int WHITE=-1; }",
        "android/graphics/Shader.java": "package android.graphics; public class Shader {}",
        "android/graphics/Typeface.java": "package android.graphics; public class Typeface { public static final Typeface DEFAULT=new Typeface(); }",
        "android/graphics/Paint.java": """package android.graphics; public class Paint {
            public static final int ANTI_ALIAS_FLAG=1; public Paint(int flags) {}
            public void setTypeface(Typeface face) {} public void setFakeBoldText(boolean value) {}
            public void setTextSize(float size) {} public float measureText(String text) { return text.length(); }
        }""",
        "android/util/DisplayMetrics.java": """package android.util; public class DisplayMetrics {
            public float density=1, scaledDensity=1;
            public void setTo(DisplayMetrics other) { density=other.density; scaledDensity=other.scaledDensity; }
        }""",
        "android/util/TypedValue.java": """package android.util; public class TypedValue {
            public static final int COMPLEX_UNIT_SP=2;
            public static float applyDimension(int unit, float value, DisplayMetrics metrics) { return value*metrics.scaledDensity; }
        }""",
        "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper() { return new Looper(); } }",
        "android/os/Handler.java": """package android.os; public class Handler {
            public Handler(Looper looper) {} public boolean post(Runnable job) { return true; }
            public boolean postAtTime(Runnable job, Object token, long when) { return true; }
            public void removeCallbacksAndMessages(Object token) {}
        }""",
        "android/os/HandlerThread.java": """package android.os; public class HandlerThread {
            public HandlerThread(String name) {} public void start() {} public Looper getLooper() { return new Looper(); }
            public boolean quit() { return true; }
        }""",
        "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long uptimeMillis() { return 0; } }",
        "com/google/common/base/Preconditions.java": """package com.google.common.base; public class Preconditions {
            public static <T> T checkNotNull(T value) { if(value==null) throw new NullPointerException(); return value; }
        }""",
        "androidx/media3/common/C.java": """package androidx.media3.common; public class C {
            public static final long TIME_UNSET=-9223372036854775807L;
            public static final int ENCODING_PCM_16BIT=2; public @interface Encoding {}
            public static final int COLOR_SPACE_BT2020=6, COLOR_RANGE_LIMITED=2, COLOR_TRANSFER_ST2084=6;
        }""",
        "androidx/media3/common/Timeline.java": "package androidx.media3.common; public class Timeline { public static final Timeline EMPTY=new Timeline(); }",
        "androidx/media3/common/Format.java": """package androidx.media3.common; public class Format {
            public static final int NO_VALUE=-1; public String sampleMimeType,id,label,codecs;
            public int sampleRate=-1,channelCount=-1,width=-1,height=-1,bitrate=-1; public float frameRate=-1;
            public ColorInfo colorInfo;
            public String toString() { return id; }
            public static class Builder {
                private final Format value=new Format();
                public Builder setSampleMimeType(String mime) { value.sampleMimeType=mime; return this; }
                public Builder setSampleRate(int rate) { value.sampleRate=rate; return this; }
                public Builder setChannelCount(int channels) { value.channelCount=channels; return this; }
                public Builder setCodecs(String codecs) { value.codecs=codecs; return this; }
                public Builder setWidth(int width) { value.width=width; return this; }
                public Builder setHeight(int height) { value.height=height; return this; }
                public Builder setFrameRate(float rate) { value.frameRate=rate; return this; }
                public Builder setAverageBitrate(int bitrate) { value.bitrate=bitrate; return this; }
                public Builder setColorInfo(ColorInfo info) { value.colorInfo=info; return this; }
                public Builder setLabel(String label) { value.label=label; return this; }
                public Builder setId(String id) { value.id=id; return this; } public Format build() { return value; }
            }
        }""",
        "androidx/media3/common/ColorInfo.java": """package androidx.media3.common; public class ColorInfo {
            public int colorSpace=-1,colorRange=-1,colorTransfer=-1,lumaBitdepth=-1,chromaBitdepth=-1;
            public static class Builder {
                private final ColorInfo value=new ColorInfo();
                public Builder setColorSpace(int space) { value.colorSpace=space; return this; }
                public Builder setColorRange(int range) { value.colorRange=range; return this; }
                public Builder setColorTransfer(int transfer) { value.colorTransfer=transfer; return this; }
                public Builder setLumaBitdepth(int bits) { value.lumaBitdepth=bits; return this; }
                public Builder setChromaBitdepth(int bits) { value.chromaBitdepth=bits; return this; }
                public ColorInfo build() { return value; }
            }
        }""",
        "androidx/media3/exoplayer/DecoderReuseEvaluation.java": "package androidx.media3.exoplayer; public class DecoderReuseEvaluation {}",
        "androidx/media3/exoplayer/DecoderCounters.java": """package androidx.media3.exoplayer; public class DecoderCounters {
            public int renderedOutputBufferCount,skippedOutputBufferCount,droppedBufferCount,droppedInputBufferCount,
                maxConsecutiveDroppedBufferCount; public synchronized void ensureUpdated() {}
        }""",
        "androidx/media3/exoplayer/source/MediaSource.java": "package androidx.media3.exoplayer.source; public interface MediaSource { class MediaPeriodId {} }",
        "androidx/media3/exoplayer/audio/AudioSink.java": "package androidx.media3.exoplayer.audio; import androidx.media3.common.C; public interface AudioSink {" + track + "}",
        "androidx/media3/exoplayer/analytics/AnalyticsListener.java": """package androidx.media3.exoplayer.analytics;
            import androidx.annotation.Nullable; import androidx.media3.common.Format; import androidx.media3.common.Timeline;
            import androidx.media3.exoplayer.DecoderCounters; import androidx.media3.exoplayer.DecoderReuseEvaluation;
            import androidx.media3.exoplayer.audio.AudioSink; import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
            public interface AnalyticsListener {
        """ + "\n".join(methods) + """
            class EventTime { public final long realtimeMs,eventPlaybackPositionMs,currentPlaybackPositionMs,totalBufferedDurationMs;
                public final Timeline timeline,currentTimeline; public final int windowIndex,currentWindowIndex;
                public final MediaPeriodId mediaPeriodId,currentMediaPeriodId;
        """ + constructor + "}}",
        PACKAGE + "DanmakuPainter.java": """package androidx.media3.ui.danmaku; import android.util.DisplayMetrics; import android.graphics.Typeface;
            class DanmakuPainter { private final DisplayMetrics metrics; DanmakuPainter(DisplayMetrics metrics) { this.metrics=metrics; }
                DanmakuMeasurement measurement(int pool, int config) { return new DanmakuMeasurement(pool,config,10,1,metrics,Typeface.DEFAULT,false); }
            }""",
    }
    for name in ("Nullable", "VisibleForTesting", "ColorInt", "IntDef"):
        body = "int[] value() default {}; boolean flag() default false;" if name == "IntDef" else ""
        stubs[f"androidx/annotation/{name}.java"] = f"""package androidx.annotation; import java.lang.annotation.*;
            @Target({{ElementType.TYPE_USE,ElementType.TYPE,ElementType.METHOD,ElementType.FIELD,ElementType.PARAMETER,ElementType.LOCAL_VARIABLE}})
            public @interface {name} {{{body}}}"""
    stubs["androidx/media3/common/util/UnstableApi.java"] = "package androidx.media3.common.util; public @interface UnstableApi {}"
    classpath = os.pathsep.join([dependency("junit", "junit", "4.13.2"), dependency("org.hamcrest", "hamcrest-core", "1.3")])
    with tempfile.TemporaryDirectory(prefix="tv-playback-stutter-") as temporary:
        work = Path(temporary)
        sources = work / "src"

        def write(name, content):
            target = sources / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content)

        for name, content in stubs.items():
            write(name, content)
        for name in ("Danmaku.java", "DanmakuRenderItem.java", "DanmakuMeasurement.java", "DanmakuRenderPool.java"):
            write(PACKAGE + name, (MEDIA / POOL / name).read_text())
        legacy = subprocess.check_output(["git", "-C", str(MEDIA), "show", "HEAD:" + POOL + "DanmakuRenderPool.java"], text=True)
        write(PACKAGE + "LegacyDanmakuRenderPool.java", legacy.replace("DanmakuRenderPool", "LegacyDanmakuRenderPool"))
        for name in ("DiagnosticLog", "DiagnosticRedactor"):
            path = f"com/github/catvod/crawler/diagnostics/{name}.java"
            write(path, (ROOT / "catvod/src/main/java" / path).read_text())
        path = "com/fongmi/android/tv/player/exo/ExoPlaybackDiagnostics.java"
        write(path, (ROOT / "app/src/main/java" / path).read_text())
        for path in (PACKAGE + "DanmakuRenderPoolTest.java", "com/fongmi/android/tv/player/exo/ExoPlaybackDiagnosticsTest.java"):
            write(path, (ROOT / "app/src/test/java" / path).read_text())
        write(PACKAGE + "RenderPoolBenchmark.java", (Path(__file__).with_name("RenderPoolBenchmark.java")).read_text())
        classes = work / "classes"
        subprocess.run(["javac", "-cp", classpath, "-d", str(classes), *map(str, sources.rglob("*.java"))], check=True)
        runtime = str(classes) + os.pathsep + classpath
        subprocess.run(["java", "-cp", runtime, "org.junit.runner.JUnitCore", "androidx.media3.ui.danmaku.DanmakuRenderPoolTest",
                        "com.fongmi.android.tv.player.exo.ExoPlaybackDiagnosticsTest"], check=True)
        subprocess.run(["java", "-cp", runtime, "androidx.media3.ui.danmaku.RenderPoolBenchmark"], check=True)


if __name__ == "__main__":
    run()
