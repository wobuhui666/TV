package com.fongmi.android.tv.player.mpv;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MpvVideoPathTest {
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
