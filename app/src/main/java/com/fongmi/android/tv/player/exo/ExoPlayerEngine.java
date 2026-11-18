package com.fongmi.android.tv.player.exo;

import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.PlaybackSpecs;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleRuntime;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.diagnostics.DiagnosticLog;

import java.util.concurrent.TimeUnit;

public class ExoPlayerEngine implements PlayerEngine {

    private final ErrorMsgProvider provider;
    private final Player.Listener listener;
    private final PreCache preCache;
    private final PreloadCoordinator preloadCoordinator;
    private final ExoDecodeFallback decodeFallback;
    private ExoPlayer player;
    private PlaySpec spec;
    private int decode;
    private NextMediaPreload next;
    private com.fongmi.android.tv.player.subtitle.AdvancedSubtitleController subtitles;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this.subtitles = new com.fongmi.android.tv.player.subtitle.AdvancedSubtitleController();
        this.preloadCoordinator = new PreloadCoordinator();
        this.decodeFallback = new ExoDecodeFallback(decode);
        this.next = new NextMediaPreload(decode, decode, listener, subtitles, preloadCoordinator);
        this.player = next.player;
        this.provider = new ErrorMsgProvider();
        this.preCache = new PreCache(preloadCoordinator);
        this.listener = listener;
        this.decode = decode;
    }

    @Override
    public Type getType() {
        return Type.EXO;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public PlaybackSpecs getPlaybackSpecs() {
        return PlaybackSpecs.fromFormats(player.getVideoFormat(), player.getAudioFormat());
    }

    @Override
    public void release() {
        AiSubtitleRuntime.get().stopSession();
        preCache.release();
        next.release();
        preloadCoordinator.detach();
        player.release();
        subtitles.release();
    }

    @Override
    public Player rebuild() {
        preCache.stop();
        next.release();
        preloadCoordinator.detach();
        // Exo may synchronously report a renderer-release timeout before clearing listeners.
        // That error belongs to the retired player, not the replacement being constructed.
        player.removeListener(listener);
        player.release();
        subtitles.release();
        subtitles = new com.fongmi.android.tv.player.subtitle.AdvancedSubtitleController();
        next = new NextMediaPreload(decodeFallback.getAudioDecode(), decodeFallback.getVideoDecode(),
                listener, subtitles, preloadCoordinator);
        player = next.player;
        return player;
    }

    @Override
    public boolean setDecode(int decode) {
        this.decode = decode;
        decodeFallback.setDecode(decode);
        return true;
    }

    @Override
    public boolean resetDecodeFallback() {
        return decodeFallback.reset();
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        AiSubtitleRuntime.get().startSession(player);
        startInternal(startPositionMs);
    }

    @Override
    public void stop() {
        AiSubtitleRuntime.get().stopSession();
        preCache.stop();
        subtitles.clear();
        player.stop();
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        if (ExoTunnelFallback.shouldFallback(PlayerSetting.isTunnelingEnabled(), e.errorCode)) {
            return ErrorAction.TUNNEL;
        }
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> retryDecoder(e);
            case PlaybackException.ERROR_CODE_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryDecoder(PlaybackException error) {
        // Explicit software mode and unidentified failures retain the existing mode retry.
        if (decode != HARD || !(error instanceof ExoPlaybackException rendererError)
                || rendererError.type != ExoPlaybackException.TYPE_RENDERER) return ErrorAction.DECODE;
        int type = rendererError.rendererFormat == null ? C.TRACK_TYPE_UNKNOWN
                : MimeTypes.getTrackType(rendererError.rendererFormat.sampleMimeType);
        if (type == C.TRACK_TYPE_UNKNOWN && rendererError.rendererIndex >= 0
                && rendererError.rendererIndex < player.getRendererCount()) {
            type = player.getRendererType(rendererError.rendererIndex);
        }
        if (type != C.TRACK_TYPE_AUDIO && type != C.TRACK_TYPE_VIDEO) return ErrorAction.DECODE;
        if (!decodeFallback.tryFallback(type)) return ErrorAction.FATAL;
        DiagnosticLog.record("playback", "decoderFallback track="
                + (type == C.TRACK_TYPE_AUDIO ? "audio" : "video"));
        return ErrorAction.DECODE_TRACK;
    }

    private void startInternal(long position) {
        preCache.stop();
        MediaItem item = MediaItemFactory.from(spec);
        subtitles.activate(item);
        androidx.media3.exoplayer.source.MediaSource prepared = next.take(item);
        if (prepared != null) {
            player.setMediaSource(prepared, position == C.TIME_UNSET ? 0 : position);
        } else if (position == C.TIME_UNSET) player.setMediaItem(item);
        else player.setMediaItem(item, position);
        preCache.start(player, item);
        player.prepare();
        player.play();
    }

    @Override public boolean preload(PlaySpec spec, long position) {
        next.preload(MediaItemFactory.from(spec), position);
        return true;
    }

    @Override public void clearPreload() { next.clear(); }

    @Override public void bindPlayerView(androidx.media3.ui.PlayerView view) { subtitles.bind(view); }
    @Override public void setSecondaryTrack(String format) { subtitles.select(format); }
    @Override public String getSecondaryTrack() { return subtitles.selected(); }
    @Override public void setSubtitleStyle() { subtitles.refreshStyle(); }

    private ErrorAction seekToDefaultPosition() {
        player.seekToDefaultPosition();
        player.prepare();
        return ErrorAction.RECOVERED;
    }

    private ErrorAction retryFormat(int errorCode) {
        String format = ExoUtil.getMimeType(errorCode);
        spec.setFormat(format);
        // An extension-less HLS URL may first be opened as progressive media.  Reusing that
        // attempt's synthetic/end position pins a live playlist at its trailing edge forever.
        startInternal(MimeTypes.APPLICATION_M3U8.equals(format) ? C.TIME_UNSET : player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}
