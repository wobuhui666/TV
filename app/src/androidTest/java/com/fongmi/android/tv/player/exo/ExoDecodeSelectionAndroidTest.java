package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelectorResult;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

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

    @Test
    public void audioDecoderRecoverySelectsSoftwareAudioAndKeeps4kVideoOnPlatform() throws Exception {
        onMain(() -> {
            TrackSelectorResult result = select(PlayerEngine.SOFT, PlayerEngine.HARD,
                    audioAndVideo(), new TrackGroupArray(AUDIO, VIDEO));
            assertSelected(result, 1, AUDIO);
            assertSelected(result, 2, VIDEO);
            assertNull(result.selections[0]);
            assertNull(result.selections[3]);
        });
    }

    @Test
    public void videoDecoderRecoveryDoesNotChangeWorkingAudioRenderer() throws Exception {
        onMain(() -> {
            TrackSelectorResult result = select(PlayerEngine.HARD, PlayerEngine.SOFT,
                    audioAndVideo(), new TrackGroupArray(AUDIO, VIDEO));
            assertSelected(result, 0, AUDIO);
            assertSelected(result, 3, VIDEO);
            assertNull(result.selections[1]);
            assertNull(result.selections[2]);
        });
    }

    @Test
    public void softwareCodecFilteringAffectsOnlyTheFailedTrackType() throws Exception {
        MediaCodecInfo hardware = MediaCodecInfo.newInstance("vendor.decoder", MimeTypes.VIDEO_H264,
                MimeTypes.VIDEO_H264, null, true, false, false, false, false);
        MediaCodecInfo software = MediaCodecInfo.newInstance("software.decoder", MimeTypes.VIDEO_H264,
                MimeTypes.VIDEO_H264, null, false, true, false, false, false);
        List<MediaCodecInfo> codecs = List.of(hardware, software);
        MediaCodecSelector delegate = (mime, secure, tunneling) -> codecs;
        MediaCodecSelector audioRecovery = ExoUtil.softwareCodecSelector(delegate, true, false);
        assertEquals(List.of(software), audioRecovery.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false));
        assertSame(codecs, audioRecovery.getDecoderInfos(MimeTypes.VIDEO_H264, false, false));
        MediaCodecSelector videoRecovery = ExoUtil.softwareCodecSelector(delegate, false, true);
        assertSame(codecs, videoRecovery.getDecoderInfos(MimeTypes.AUDIO_AAC, false, false));
        assertEquals(List.of(software), videoRecovery.getDecoderInfos(MimeTypes.VIDEO_H264, false, false));
        assertSame(delegate, ExoUtil.softwareCodecSelector(delegate, false, false));
    }

    @Test
    public void rendererRecoveryStaysBoundedAcrossRebuildsAndResetsForNewPlayback() throws Exception {
        onMain(() -> {
            ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, new Player.Listener() {});
            try {
                ExoPlaybackException error = decoderError(AUDIO.getFormat(0), C.INDEX_UNSET);
                assertEquals(PlayerEngine.ErrorAction.DECODE_TRACK, engine.handleError(error));
                engine.rebuild();
                assertEquals(PlayerEngine.ErrorAction.FATAL, engine.handleError(error));
                assertEquals(PlayerEngine.ErrorAction.DECODE_TRACK,
                        engine.handleError(decoderError(VIDEO.getFormat(0), C.INDEX_UNSET)));
                assertTrue(engine.resetDecodeFallback());
                engine.rebuild();
                assertFalse(engine.resetDecodeFallback());
                assertEquals(PlayerEngine.ErrorAction.DECODE_TRACK, engine.handleError(error));
                engine.setDecode(PlayerEngine.SOFT);
                engine.rebuild();
                assertEquals(PlayerEngine.ErrorAction.DECODE, engine.handleError(error));
            } finally {
                engine.release();
            }
        });
    }

    @Test
    public void missingRendererFormatCanRecoverUsingTheActualRendererType() throws Exception {
        onMain(() -> {
            ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, new Player.Listener() {});
            try {
                ExoPlayer player = (ExoPlayer) engine.getPlayer();
                int audio = C.INDEX_UNSET;
                for (int i = 0; i < player.getRendererCount(); i++) {
                    if (player.getRendererType(i) == C.TRACK_TYPE_AUDIO) { audio = i; break; }
                }
                assertTrue(audio >= 0);
                assertEquals(PlayerEngine.ErrorAction.DECODE_TRACK, engine.handleError(decoderError(null, audio)));
            } finally {
                engine.release();
            }
        });
    }

    @Test
    public void unknownRendererFailuresKeepTheExistingModeRetry() throws Exception {
        onMain(() -> {
            ExoPlayerEngine engine = new ExoPlayerEngine(PlayerEngine.HARD, new Player.Listener() {});
            try {
                assertEquals(PlayerEngine.ErrorAction.DECODE,
                        engine.handleError(decoderError(null, C.INDEX_UNSET)));
                assertFalse(engine.resetDecodeFallback());
            } finally {
                engine.release();
            }
        });
    }

    @Test
    public void managerKeepsAudioRecoveryAcrossRebuildsAndClearsItForStartAndParse() throws Exception {
        onMain(() -> {
            int savedEngine = PlayerSetting.getEngine();
            PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
            int[] rebuilds = {0};
            PlayerManager.Callback callback = (PlayerManager.Callback) Proxy.newProxyInstance(
                    PlayerManager.Callback.class.getClassLoader(), new Class<?>[]{PlayerManager.Callback.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("onPlayerRebuild")) rebuilds[0]++;
                        return null;
                    });
            PlayerManager manager = new PlayerManager(callback);
            try {
                manager.start(PlaySpec.from("first", "file:///decoder-recovery-first.mp4", Map.of(),
                        MediaMetadata.EMPTY), 60_000, 12_000);
                Player.Listener listener = field(manager, "listener");
                ExoPlayerEngine engine = field(manager, "engine");
                ExoPlaybackException error = decoderError(AUDIO.getFormat(0), C.INDEX_UNSET);
                listener.onPlayerError(error);
                assertEquals(1, rebuilds[0]);
                assertEquals(PlayerEngine.HARD, (int) field(manager, "decode"));
                assertEquals(PlayerEngine.ErrorAction.FATAL, engine.handleError(error));
                manager.rebuildAudioPipeline();
                assertEquals(PlayerEngine.ErrorAction.FATAL, engine.handleError(error));

                manager.start(PlaySpec.from("second", "file:///decoder-recovery-second.mp4", Map.of(),
                        MediaMetadata.EMPTY), 60_000);
                assertEquals(PlayerEngine.ErrorAction.DECODE_TRACK, engine.handleError(error));
                assertEquals(PlayerEngine.HARD, (int) field(manager, "decode"));

                // Stop the parser before its queued WebView work runs, then deliver its result.
                manager.parse("third", Result.empty(), false, MediaMetadata.EMPTY);
                ((ParseJob) field(manager, "parseJob")).stop();
                manager.onParseSuccess(Map.of(), "file:///decoder-recovery-third.mp4", "");
                assertEquals(PlayerEngine.ErrorAction.DECODE_TRACK, engine.handleError(error));
                assertEquals(PlayerEngine.HARD, (int) field(manager, "decode"));
            } finally {
                manager.release();
                PlayerSetting.putEngine(savedEngine);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static ExoPlaybackException decoderError(Format format, int rendererIndex) {
        return ExoPlaybackException.createForRenderer(new IllegalStateException("test decoder failure"),
                "test", rendererIndex, format, C.FORMAT_HANDLED, null, false,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED);
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
        return select(mode, mode, renderers, groups);
    }

    private static TrackSelectorResult select(int audioMode, int videoMode,
            RendererCapabilities[] renderers, TrackGroupArray groups) throws ExoPlaybackException {
        DecodeTrackSelector selector = (DecodeTrackSelector) ExoUtil.buildTrackSelector(audioMode, videoMode);
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
