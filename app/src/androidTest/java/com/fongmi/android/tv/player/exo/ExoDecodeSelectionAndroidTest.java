package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Characterizes the shipping selector with declared capabilities, without codec instances. */
@RunWith(AndroidJUnit4.class)
public final class ExoDecodeSelectionAndroidTest {
    private static final String[] KEYS = {"audio_prefer", "video_prefer", "tunnel", "render", "prefer_aac", "av3a"};
    private static final TrackGroup AUDIO = new TrackGroup(new Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build());
    private static final TrackGroup VIDEO = new TrackGroup(new Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(3840).setHeight(2160)
            .setFrameRate(30).setAverageBitrate(60_000_000).build());
    private final Map<String, Object> saved = new HashMap<>();

    @Before
    public void preserveAndResetPreferences() throws Exception {
        onMain(() -> {
            SharedPreferences preferences = Prefers.getPrefers();
            Map<String, ?> values = preferences.getAll();
            for (String key : KEYS) {
                if (values.containsKey(key)) saved.put(key, values.get(key));
            }
            PlayerSetting.putAudioPrefer(false);
            PlayerSetting.putVideoPrefer(false);
            PlayerSetting.putTunnel(false);
            PlayerSetting.putPreferAAC(false);
            PlayerSetting.putAv3a(false);
        });
    }

    @After
    public void restorePreferences() throws Exception {
        onMain(() -> {
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : KEYS) {
                Object value = saved.get(key);
                if (value == null) editor.remove(key);
                else if (value instanceof Boolean booleanValue) editor.putBoolean(key, booleanValue);
                else if (value instanceof Integer intValue) editor.putInt(key, intValue);
                else throw new AssertionError("Unexpected preference type for " + key);
            }
            editor.apply();
        });
    }

    @Test
    public void hardModeMapsHighBitrateVideoToFfmpegWhenPlatformReportsExceedsCapabilities() throws Exception {
        onMain(() -> {
            RendererCapabilities[] renderers = video(C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_HANDLED);
            TrackSelectorResult result = select(PlayerEngine.HARD, renderers, new TrackGroupArray(VIDEO));
            assertSelected(result, 1, VIDEO);
            assertNull(result.selections[0]);
        });
    }

    @Test
    public void hardModePrefersHandledPlatformEvenWhenFfmpegIsListedFirst() throws Exception {
        onMain(() -> {
            RendererCapabilities[] renderers = {
                    capability("FfmpegVideoRenderer", C.TRACK_TYPE_VIDEO, C.FORMAT_HANDLED),
                    capability("MediaCodecVideoRenderer", C.TRACK_TYPE_VIDEO, C.FORMAT_HANDLED)};
            TrackSelectorResult result = select(PlayerEngine.HARD, renderers, new TrackGroupArray(VIDEO));
            assertSelected(result, 1, VIDEO);
            assertNull(result.selections[0]);
        });
    }

    @Test
    public void hardModeCanSelectExceedingPlatformWhenFfmpegCannotHandleFormat() throws Exception {
        onMain(() -> {
            TrackSelectorResult result = select(PlayerEngine.HARD,
                    video(C.FORMAT_EXCEEDS_CAPABILITIES, C.FORMAT_UNSUPPORTED_SUBTYPE), new TrackGroupArray(VIDEO));
            assertSelected(result, 0, VIDEO);
            assertNull(result.selections[1]);
        });
    }

    @Test
    public void hardModeWithBothSoftwarePreferencesOffSelectsPlatformAudioAndVideo() throws Exception {
        onMain(() -> {
            assertFalse(PlayerSetting.isAudioPrefer());
            assertFalse(PlayerSetting.isVideoPrefer());
            TrackSelectorResult result = select(PlayerEngine.HARD, audioAndVideo(), new TrackGroupArray(AUDIO, VIDEO));
            assertSelected(result, 0, AUDIO);
            assertSelected(result, 2, VIDEO);
            assertNull(result.selections[1]);
            assertNull(result.selections[3]);
        });
    }

    @Test
    public void softModeWithBothSoftwarePreferencesOffStillSelectsFfmpegAudioAndVideo() throws Exception {
        onMain(() -> {
            assertFalse(PlayerSetting.isAudioPrefer());
            assertFalse(PlayerSetting.isVideoPrefer());
            TrackSelectorResult result = select(PlayerEngine.SOFT, audioAndVideo(), new TrackGroupArray(AUDIO, VIDEO));
            assertSelected(result, 1, AUDIO);
            assertSelected(result, 3, VIDEO);
            assertNull(result.selections[0]);
            assertNull(result.selections[2]);
        });
    }

    @Test
    public void audioSoftwarePreferenceAloneDoesNotAlterHardSelectorVideoOrAudioMapping() throws Exception {
        onMain(() -> {
            PlayerSetting.putAudioPrefer(true);
            assertFalse(PlayerSetting.isVideoPrefer());
            TrackSelectorResult result = select(PlayerEngine.HARD, audioAndVideo(), new TrackGroupArray(AUDIO, VIDEO));
            assertSelected(result, 0, AUDIO);
            assertSelected(result, 2, VIDEO);
            assertNull(result.selections[1]);
            assertNull(result.selections[3]);
        });
    }

    private static RendererCapabilities[] video(int platformSupport, int ffmpegSupport) {
        return new RendererCapabilities[]{
                capability("MediaCodecVideoRenderer", C.TRACK_TYPE_VIDEO, platformSupport),
                capability("FfmpegVideoRenderer", C.TRACK_TYPE_VIDEO, ffmpegSupport)};
    }

    private static RendererCapabilities[] audioAndVideo() {
        return new RendererCapabilities[]{
                capability("MediaCodecAudioRenderer", C.TRACK_TYPE_AUDIO, C.FORMAT_HANDLED),
                capability("FfmpegAudioRenderer", C.TRACK_TYPE_AUDIO, C.FORMAT_HANDLED),
                capability("MediaCodecVideoRenderer", C.TRACK_TYPE_VIDEO, C.FORMAT_HANDLED),
                capability("FfmpegVideoRenderer", C.TRACK_TYPE_VIDEO, C.FORMAT_HANDLED)};
    }

    private static RendererCapabilities capability(String name, int type, int support) {
        return new RendererCapabilities() {
            @Override public String getName() { return name; }
            @Override public int getTrackType() { return type; }
            @Override public int supportsFormat(Format format) {
                return RendererCapabilities.create(MimeTypes.getTrackType(format.sampleMimeType) == type
                        ? support : C.FORMAT_UNSUPPORTED_TYPE);
            }
            @Override public int supportsMixedMimeTypeAdaptation() {
                return RendererCapabilities.ADAPTIVE_NOT_SUPPORTED;
            }
        };
    }

    private static TrackSelectorResult select(int mode, RendererCapabilities[] renderers, TrackGroupArray groups)
            throws ExoPlaybackException {
        DecodeTrackSelector selector = (DecodeTrackSelector) ExoUtil.buildTrackSelector(mode);
        selector.init(parameters -> {}, BandwidthMeter.NO_OP);
        try {
            return selector.selectTracks(renderers, groups, new MediaSource.MediaPeriodId(new Object()), Timeline.EMPTY);
        } finally {
            selector.release();
        }
    }

    private static void assertSelected(TrackSelectorResult result, int renderer, TrackGroup expected) {
        assertNotNull("Expected renderer " + renderer + " to receive a track", result.selections[renderer]);
        assertEquals(expected, result.selections[renderer].getTrackGroup());
    }

    private interface CheckedRunnable { void run() throws Exception; }

    private static void onMain(CheckedRunnable action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() instanceof Exception error) throw error;
        if (failure.get() instanceof Error error) throw error;
    }
}
