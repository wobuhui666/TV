package com.fongmi.android.tv.player.exo;

import androidx.media3.common.MediaItem;
import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleRuntime;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.concurrent.TimeUnit;

public class ExoPlayerEngine implements PlayerEngine {

    private final ErrorMsgProvider provider;
    private final Player.Listener listener;
    private final PreCache preCache;
    private ExoPlayer player;
    private PlaySpec spec;
    private int decode;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this.player = ExoUtil.buildPlayer(decode, listener);
        this.provider = new ErrorMsgProvider();
        this.preCache = new PreCache();
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
    public void release() {
        AiSubtitleRuntime.get().stopSession();
        preCache.release();
        player.release();
    }

    @Override
    public Player rebuild() {
        preCache.stop();
        player.release();
        return player = ExoUtil.buildPlayer(decode, listener);
    }

    @Override
    public boolean setDecode(int decode) {
        this.decode = decode;
        return true;
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
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            default -> ErrorAction.FATAL;
        };
    }

    private void startInternal(long position) {
        MediaItem item = MediaItemFactory.from(spec, decode);
        if (position == C.TIME_UNSET) player.setMediaItem(item);
        else player.setMediaItem(item, position);
        preCache.start(player, item);
        player.prepare();
        player.play();
    }

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
