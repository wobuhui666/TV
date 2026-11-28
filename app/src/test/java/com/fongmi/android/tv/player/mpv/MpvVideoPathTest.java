package com.fongmi.android.tv.player.mpv;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MpvVideoPathTest {
    @Test
    public void keepsNativeMediaCodecLayoutForColourAndCropDiagnosis() {
        assertTrue(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "hevc_mediacodec: Output MediaFormat changed to {color-format=21, stride=3840, slice-height=2176}\n"));
        assertTrue(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "h264_mediacodec: Output MediaFormat changed to {color-format=19}\n"));
        assertTrue(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "hevc_mediacodec: Output crop parameters top=0 bottom=2159 left=0 right=3839\n"));
        assertTrue(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "h264_mediacodec: Output crop parameters top=0 bottom=719 left=0 right=1279\n"));
        assertTrue(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "hevc_mediacodec: MediaCodec output layout: codec=OMX.hisi.video.decoder.hevc color-format=0x7fa30c00 surface=no width=3840 height=2160 stride=3840 slice-height=2176\n"));
        assertTrue(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "hevc_mediacodec: HiSilicon NV21 workaround applied: codec=OMX.hisi.video.decoder.hevc color-format=0x7fa30c00 output=nv21\n"));
    }

    @Test
    public void excludesFrameTrafficAndUnrelatedVerboseMessages() {
        assertFalse(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "hevc_mediacodec: Frame: width=3840 stride=3840 height=2160"));
        assertFalse(MpvVideoPath.isDecoderConfiguration("ffmpeg/video",
                "hevc_mediacodec: No output buffer available, try again later"));
        assertFalse(MpvVideoPath.isDecoderConfiguration("ffmpeg/audio",
                "hevc_mediacodec: Output MediaFormat changed to {}"));
        assertFalse(MpvVideoPath.isDecoderConfiguration("ffmpeg/video", "Opening a private URL"));
        assertFalse(MpvVideoPath.isDecoderConfiguration(null, "Output MediaFormat changed to {}"));
        assertFalse(MpvVideoPath.isDecoderConfiguration("ffmpeg/video", null));
    }

    @Test
    public void keepsDecoderTransitionsAndAudioBufferCapacityOnly() {
        assertTrue(MpvVideoPath.isNativeDiagnostics("vd", "Using hardware decoding (mediacodec-copy).\n"));
        assertTrue(MpvVideoPath.isNativeDiagnostics("vd", "Using software decoding.\n"));
        assertTrue(MpvVideoPath.isNativeDiagnostics("vd", "Attempting next decoding method after failure of hevc_mediacodec-mediacodec."));
        assertTrue(MpvVideoPath.isNativeDiagnostics("ao/audiotrack", "Setting bufferSize = 19200 (driver=9600, min=9600, max=38400)"));
        assertTrue(MpvVideoPath.isNativeDiagnostics("ao/audiotrack", "AudioTrack.getBufferSizeInFrames = 4800"));
        assertFalse(MpvVideoPath.isNativeDiagnostics("ao/audiotrack", "IEC/playbackHead offset = 42"));
        assertFalse(MpvVideoPath.isNativeDiagnostics("vd", "Opening decoder from a private path"));
        assertFalse(MpvVideoPath.isNativeDiagnostics("http", "Using hardware decoding (mediacodec)."));
    }

    @Test
    public void throttlesSamplesAndRearmsForTheNextPlayback() {
        MpvVideoPath path = new MpvVideoPath();
        assertTrue(path.takeSample(0, 5000));
        assertFalse(path.takeSample(4999, 5000));
        assertTrue(path.takeSample(5000, 5000));
        assertFalse(path.takeSample(5000, 2000));
        assertTrue(path.takeSample(7000, 2000));
        assertTrue(path.takeSample(7000, 0));
        path.reset();
        assertTrue(path.takeSample(7001, 5000));
    }

    @Test
    public void reportsMissingMetricsWithoutReadingMediaUrls() {
        Map<String, String> values = new HashMap<>();
        values.put("hwdec-current", "mediacodec-copy");
        values.put("avsync", "0.25");
        values.put("demuxer-cache-duration", "10");
        values.put("frame-drop-count", "8");
        String sample = MpvVideoPath.performance(name -> {
            assertFalse(name.contains("path") || name.contains("url") || name.contains("header"));
            return values.get(name);
        }, 3);
        assertTrue(sample.contains("audio-underruns=3"));
        assertTrue(sample.contains("hwdec-current=mediacodec-copy"));
        assertTrue(sample.contains("avsync=0.25"));
        assertTrue(sample.contains("demuxer-cache-duration=10"));
        assertTrue(sample.contains("frame-drop-count=8"));
        assertTrue(sample.contains("container-fps=?"));
    }

    private static Map<String, String> direct() {
        Map<String, String> values = new HashMap<>();
        values.put("video-format", "hevc");
        values.put("video-dec-params/w", "3840");
        values.put("video-dec-params/h", "2160");
        values.put("hwdec-current", "mediacodec");
        values.put("hwdec", "mediacodec,mediacodec-copy");
        values.put("video-dec-params/pixelformat", "mediacodec");
        values.put("video-dec-params/colormatrix", "bt.2020-ncl");
        values.put("video-dec-params/colorlevels", "limited");
        values.put("video-dec-params/primaries", "bt.2020");
        values.put("video-dec-params/gamma", "pq");
        values.put("current-vo", "gpu");
        values.put("current-gpu-context", "android");
        return values;
    }

    @Test
    public void namesTheDirectHardwarePathAndItsColourSignalling() {
        assertEquals("codec=hevc size=3840x2160 hwdec=mediacodec requested=mediacodec,mediacodec-copy"
                        + " format=mediacodec hw-format=? matrix=bt.2020-ncl levels=limited primaries=bt.2020"
                        + " transfer=pq vo=gpu context=android dolby-vision=no",
                MpvVideoPath.describe(direct()::get, -1));
    }

    @Test
    public void distinguishesCopiedAndSoftwareDecodingAndDolbyVision() {
        Map<String, String> copy = direct();
        copy.put("hwdec-current", "mediacodec-copy");
        copy.put("video-dec-params/pixelformat", "nv12");
        copy.put("video-dec-params/hw-pixelformat", " nv12 ");
        String copied = MpvVideoPath.describe(copy::get, 5);
        assertTrue(copied, copied.contains(" hwdec=mediacodec-copy ") && copied.contains(" format=nv12 hw-format=nv12 "));
        assertTrue(copied, copied.endsWith(" dolby-vision=5"));

        Map<String, String> software = direct();
        software.put("hwdec-current", "no");
        software.put("hwdec", "no");
        software.put("video-dec-params/pixelformat", "yuv420p10");
        String decoded = MpvVideoPath.describe(software::get, 0);
        assertTrue(decoded, decoded.contains(" hwdec=no requested=no format=yuv420p10 "));
        assertTrue(decoded, decoded.endsWith(" dolby-vision=0"));
    }

    @Test
    public void waitsForADecodedFrameBeforeNamingAPath() {
        Map<String, String> pending = direct();
        pending.remove("video-dec-params/pixelformat");
        assertEquals("", MpvVideoPath.describe(pending::get, -1));
        pending.put("video-dec-params/pixelformat", "  ");
        assertEquals("", MpvVideoPath.describe(pending::get, -1));
        assertEquals("", MpvVideoPath.describe(name -> null, -1));
    }

    @Test
    public void marksPropertiesTheNativeBuildDoesNotAnswer() {
        Map<String, String> sparse = new HashMap<>();
        sparse.put("video-dec-params/pixelformat", "yuv420p");
        assertEquals("codec=? size=?x? hwdec=? requested=? format=yuv420p hw-format=? matrix=? levels=? primaries=?"
                + " transfer=? vo=? context=? dolby-vision=no", MpvVideoPath.describe(sparse::get, -1));
    }

    @Test
    public void reportsEachDistinctPathOnceUntilTheNextPlayback() {
        MpvVideoPath path = new MpvVideoPath();
        assertFalse(path.isReported());
        assertNull(path.changed(""));
        assertNull(path.changed(null));
        assertFalse(path.isReported());
        assertEquals("first", path.changed("first"));
        assertTrue(path.isReported());
        assertNull(path.changed("first"));
        assertEquals("fallback", path.changed("fallback"));
        assertNull(path.changed("fallback"));
        assertEquals("first", path.changed("first"));
        path.reset();
        assertFalse(path.isReported());
        assertEquals("first", path.changed("first"));
    }
}
