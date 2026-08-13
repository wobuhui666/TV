package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DolbyVisionOutputPolicy;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.decoder.av3a.Av3aAudioRenderer;
import androidx.media3.decoder.av3a.Av3aLibrary;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DecodeTrackSelector;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
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
import java.util.Map;
import java.util.stream.Collectors;

public class ExoUtil {

    public static ExoPlayer buildPlayer(int decode, Player.Listener listener) {
        ExoPlayer player = new ExoPlayer.Builder(App.get())
                .setTrackSelector(buildTrackSelector(decode))
                .setRenderersFactory(buildPlaybackRenderersFactory(decode))
                .setMediaSourceFactory(buildMediaSourceFactory())
                // Sony's Dolby Vision OMX stack regularly needs >500 ms to flush/release. The
                // Media3 default reports a false fatal timeout during AI AudioSink rebuilds.
                .setReleaseTimeoutMs(3_000L)
                .build();
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

    private static TrackSelector buildTrackSelector(int decode) {
        DecodeTrackSelector trackSelector = new DecodeTrackSelector(App.get());
        int decodeMode = getDecodeMode(decode);
        trackSelector.setRendererDecodePreferences(decodeMode, decodeMode);
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (PlayerSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        else if (PlayerSetting.isAv3a()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AV3A);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(PlayerSetting.isTunnelingEnabled());
        builder.setForceHighestSupportedBitrate(true);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    private static RenderersFactory buildPlaybackRenderersFactory(int decode) {
        return buildRenderersFactory(getRenderMode(decode), PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer());
    }

    private static @C.DecodeMode int getDecodeMode(int decode) {
        return decode == PlayerEngine.HARD ? C.DECODE_HARDWARE : C.DECODE_SOFTWARE;
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, PlayerSetting.isAudioPrefer(), PlayerSetting.isVideoPrefer());
    }

    private static RenderersFactory buildRenderersFactory(int renderMode, boolean audioPrefer, boolean videoPrefer) {
        DefaultRenderersFactory factory = new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
                return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams);
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
                .setDolbyVisionOutputPolicy(dv7 ? DolbyVisionOutputPolicy.ASSUME_UNSUPPORTED : DolbyVisionOutputPolicy.AUTO)
                .setExtensionRendererMode(extensionMode);
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
        boolean aiSubtitle = AiSubtitleSettings.isEnabled();
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(aiSubtitle ? false : enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (aiSubtitle) builder.setAudioProcessors(new AudioProcessor[]{new PcmTapAudioProcessor(AiSubtitleRuntime.get().createPcmSink())});
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
        } else if (!PlayerSetting.isAudioPassThrough()) {
            builder.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null).build());
        }
        return builder.build();
    }

    private static MediaSource.Factory buildMediaSourceFactory() {
        return new MediaSourceFactory();
    }
}
