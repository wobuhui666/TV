package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DolbyVisionOutputPolicy;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.decoder.av3a.Av3aAudioRenderer;
import androidx.media3.decoder.av3a.Av3aLibrary;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.util.EventLogger;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.ai.subtitle.AiAudioOutputProvider;
import com.fongmi.android.tv.ai.subtitle.AiAudioTrackBufferSizeProvider;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleRuntime;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleSettings;
import com.fongmi.android.tv.ai.subtitle.PcmTapAudioProcessor;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExoUtil {

    public static ExoPlayer buildPlayer(int decode, Player.Listener listener) {
        return buildPlayer(decode, listener, null);
    }

    static ExoPlayer buildPlayer(int decode, Player.Listener listener, androidx.media3.exoplayer.source.preload.DefaultPreloadManager.Builder preloader) {
        ExoPlayer.Builder builder = new ExoPlayer.Builder(App.get())
                .setTrackSelector(buildTrackSelector(decode))
                .setRenderersFactory(buildPlaybackRenderersFactory(decode))
                .setMediaSourceFactory(buildMediaSourceFactory())
                // Sony's Dolby Vision OMX stack regularly needs >500 ms to flush/release. The
                // Media3 default reports a false fatal timeout during AI AudioSink rebuilds.
                .setReleaseTimeoutMs(3_000L);
        ExoPlayer player = preloader == null ? builder.build() : preloader.buildExoPlayer(builder);
        player.addAnalyticsListener(new ExoPlaybackDiagnostics());
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    public static String getMimeType(int errorCode) {
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED || errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return MimeTypes.APPLICATION_M3U8;
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return MimeTypes.APPLICATION_OCTET_STREAM;
        return null;
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    private static int getRenderMode(int decode) {
        return decode == PlayerEngine.HARD ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
    }

    static TrackSelector buildTrackSelector(int decode) {
        DecodeTrackSelector trackSelector = decode == PlayerEngine.SOFT
                ? new SoftwareTrackSelector(App.get()) : new DecodeTrackSelector(App.get());
        int decodeMode = getDecodeMode(decode);
        trackSelector.setRendererDecodePreferences(decodeMode, decodeMode);
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (PlayerSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        else if (PlayerSetting.isAv3a()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AV3A);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(decode == PlayerEngine.HARD && PlayerSetting.isTunnelingEnabled()
                && !com.fongmi.android.tv.setting.AudioEffectSetting.enabled());
        if (decode == PlayerEngine.SOFT) builder.setAudioOffloadPreferences(AudioOffloadPreferences.DEFAULT);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    static RenderersFactory buildPlaybackRenderersFactory(int decode) {
        return buildRenderersFactory(getRenderMode(decode), PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer(), decode == PlayerEngine.SOFT);
    }

    private static @C.DecodeMode int getDecodeMode(int decode) {
        return decode == PlayerEngine.HARD ? C.DECODE_HARDWARE : C.DECODE_SOFTWARE;
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer(), false);
    }

    private static RenderersFactory buildRenderersFactory(int renderMode, boolean audioPrefer, boolean videoPrefer, boolean softwareOnly) {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, softwareOnly);
            }

            @Override
            protected void buildAudioRenderers(Context context, int extensionRendererMode, MediaCodecSelector mediaCodecSelector, boolean enableDecoderFallback, AudioSink audioSink, Handler eventHandler, AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
                super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out);
                // AV3A is opt-in (default off) and its native library loads lazily. When the setting
                // is off, or libav3aJNI is unavailable on this device, the renderer chain is identical
                // to stock, so machines without AV3A support are unaffected. This renderer only claims
                // audio/av3a; every normal audio track stays on the standard chain built by super.
                if (PlayerSetting.isAv3a() && Av3aLibrary.isAvailable()) {
                    out.add(0, new Av3aAudioRenderer(eventHandler, eventListener, audioSink));
                }
            }
        };
        int extensionMode = audioPrefer || videoPrefer ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER : renderMode;
        // Exo keeps its Profile-7 fallback independent from MPV's hwdec codec allow-list.
        boolean dv7 = PlayerSetting.isDv7HevcFallback();
        return factory.setEnableDecoderFallback(true)
                .setMediaCodecSelector(softwareOnly ? (mimeType, secure, tunneling) -> softwareDecoders(
                        MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)) : MediaCodecSelector.DEFAULT)
                .setDolbyVisionOutputPolicy(dv7 ? DolbyVisionOutputPolicy.ASSUME_UNSUPPORTED : DolbyVisionOutputPolicy.AUTO)
                .setExtensionRendererMode(extensionMode);
    }

    static List<MediaCodecInfo> softwareDecoders(List<MediaCodecInfo> decoders) {
        // Media3 uses the platform softwareOnly flag on API 29+, with its device-aware
        // classification on older releases. Do not infer software from "not accelerated".
        return decoders.stream().filter(info -> info.softwareOnly).collect(Collectors.toUnmodifiableList());
    }

    private static final class SoftwareTrackSelector extends DecodeTrackSelector {
        SoftwareTrackSelector(Context context) { super(context); }

        @Override protected boolean isRendererAllowed(RendererCapabilities renderer, TrackGroup group) {
            // The fork's SOFTWARE preference otherwise excludes every MediaCodec renderer.
            // Keep its FFmpeg preference, but permit our software-filtered platform codecs.
            return true;
        }

        @Override protected void selectAllTracks(ExoTrackSelection.Definition[] definitions,
                MappedTrackInfo info, int[][][] supports, int[] mixedSupports, Parameters parameters)
                throws ExoPlaybackException {
            super.selectAllTracks(definitions, info, supports, mixedSupports, parameters);
            requireSoftwareTrack(info, supports, definitions, parameters, C.TRACK_TYPE_AUDIO);
            requireSoftwareTrack(info, supports, definitions, parameters, C.TRACK_TYPE_VIDEO);
        }
    }

    private static void requireSoftwareTrack(MappedTrackInfo info, int[][][] supports,
            ExoTrackSelection.Definition[] selections, DefaultTrackSelector.Parameters parameters, int type)
            throws ExoPlaybackException {
        if (parameters.disabledTrackTypes.contains(type)) return;
        int rendererIndex = C.INDEX_UNSET;
        Format unsupported = null;
        for (int r = 0; r < info.getRendererCount(); r++) {
            if (info.getRendererType(r) != type || parameters.getRendererDisabled(r)) continue;
            rendererIndex = r;
            if (selections[r] != null) return;
            TrackGroupArray groups = info.getTrackGroups(r);
            for (int g = 0; g < groups.length; g++) {
                TrackGroup group = groups.get(g);
                TrackSelectionOverride override = parameters.overrides.get(group);
                if (override != null && override.trackIndices.isEmpty()) return;
                for (int t = 0; t < group.length; t++) {
                    Format format = group.getFormat(t);
                    if ((format.roleFlags & C.ROLE_FLAG_TRICK_PLAY) != 0) continue;
                    // A playable alternative or a user constraint is not a missing decoder.
                    if (RendererCapabilities.getFormatSupport(supports[r][g][t]) >= C.FORMAT_EXCEEDS_CAPABILITIES) return;
                    unsupported = format;
                }
            }
        }
        // Respect explicit renderer disables, including an audio-only or video-only player.
        if (rendererIndex == C.INDEX_UNSET) return;
        TrackGroupArray unmapped = info.getUnmappedTrackGroups();
        for (int g = 0; g < unmapped.length; g++) {
            TrackGroup group = unmapped.get(g);
            if (group.type != type) continue;
            TrackSelectionOverride override = parameters.overrides.get(group);
            if (override != null && override.trackIndices.isEmpty()) return;
            for (int t = 0; t < group.length; t++) {
                Format format = group.getFormat(t);
                if ((format.roleFlags & C.ROLE_FLAG_TRICK_PLAY) == 0) unsupported = format;
            }
        }
        if (unsupported != null) {
            // No codec is a format error, not a successful READY state with zero A/V renderers.
            throw ExoPlaybackException.createForRenderer(new IllegalStateException("No software decoder for "
                            + unsupported.sampleMimeType), info.getRendererName(rendererIndex), rendererIndex,
                    unsupported, C.FORMAT_UNSUPPORTED_SUBTYPE, null, false,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED);
        }
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, boolean softwareOnly) {
        boolean aiSubtitle = AiSubtitleSettings.isEnabled();
        boolean effects = com.fongmi.android.tv.setting.AudioEffectSetting.enabled();
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(aiSubtitle || effects ? false : enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        ArrayList<AudioProcessor> processors = new ArrayList<>();
        // ASR receives the original PCM, before EQ/normalization changes its spectral content.
        if (aiSubtitle) processors.add(new PcmTapAudioProcessor(AiSubtitleRuntime.get().createPcmSink()));
        if (effects) processors.add(new com.fongmi.android.tv.player.audio.AudioEffectProcessor());
        if (!processors.isEmpty()) builder.setAudioProcessors(processors.toArray(new AudioProcessor[0]));
        if (aiSubtitle) {
            // Match the reference application's audio-lookahead design: playback starts normally,
            // while the renderer is allowed to fill several seconds of decoded PCM ahead of the
            // AudioTrack play head. ASR works on that future audio and late results are discarded.
            // A null-capabilities provider deliberately disables encoded passthrough for this
            // AI-enabled sink only. Otherwise E-AC3/JOC can bypass AudioProcessors completely.
            builder.setAudioOutputProvider(new AiAudioOutputProvider(
                    new AudioTrackAudioOutputProvider.Builder(null)
                            .setAudioTrackBufferSizeProvider(new AiAudioTrackBufferSizeProvider())
                            .build(),
                    AiSubtitleRuntime.get().createAudioClockSink()));
        } else if (softwareOnly || effects || !PlayerSetting.isAudioPassThrough()) {
            builder.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null).build());
        }
        return builder.build();
    }

    private static MediaSource.Factory buildMediaSourceFactory() {
        return new MediaSourceFactory();
    }
}
