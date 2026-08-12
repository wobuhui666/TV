package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.failure.PlaybackFailure;
import com.fongmi.android.tv.player.failure.PlaybackFailureClassifier;
import com.fongmi.android.tv.utils.MpvLogCollector;

/**
 * Self-hosted MPV engine:
 * <ul>
 *   <li>{@link #setDecode(int)} reopens media in the same MPV instance</li>
 *   <li>{@link #rebuild()} is a no-op and returns the same player instance</li>
 * </ul>
 * Runtime type remains {@link MpvPlayer}, not {@code androidx.media3.mpvplayer.MpvPlayer}.
 */
@UnstableApi
public class MpvPlayerEngine implements PlayerEngine {

    private static final int MAX_RECOVER_ATTEMPTS = 1;

    private final Player.Listener listener;
    private final MpvPlayer player;
    private PlaySpec spec;
    private int decode;
    private int recoverAttempts;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this.listener = listener;
        this.decode = decode;
        this.player = new MpvPlayer(App.get(), decode);
        this.player.addListener(listener);
    }

    public static boolean isAvailable() {
        try {
            Class.forName("is.xyz.mpv.MPVLib");
            return MpvPlayer.isNativeAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    public Type getType() {
        return Type.MPV;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        spec = null;
        recoverAttempts = 0;
        player.removeListener(listener);
        player.release();
    }

    @Override
    public Player rebuild() {
        // FongMi contract: MPV rebuild is a no-op (same player instance).
        return player;
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        return player.addSubtitle(sub);
    }

    @Override
    public boolean setDecode(int decode) {
        this.decode = decode;
        // MpvPlayer performs stop/rebind/loadfile; PlayerManager must not rebuild it.
        player.setDecode(decode);
        return false;
    }

    @Override
    public void setSubtitleStyle() {
        player.setSubtitleStyle();
    }

    @Override
    public boolean applyAudioSettings() {
        player.applyAudioSettings();
        return true;
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        this.recoverAttempts = 0;
        player.start(spec, startPositionMs, decode);
    }

    @Override
    public boolean isLive() {
        return player.isLive();
    }

    @Override
    public boolean isVod() {
        return player.isVod();
    }

    @Override
    public PlaybackFailure classifyFailure(PlaybackException e) {
        return PlaybackFailureClassifier.classifyMpv(e, spec == null ? null : spec.getUrl(), player.getLastFileError());
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        if (e.getCause() instanceof MpvDolbyVisionException) return ErrorAction.PLATFORM;
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryCurrent();
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryCurrent() {
        if (spec == null || recoverAttempts >= MAX_RECOVER_ATTEMPTS) return ErrorAction.FATAL;
        long positionMs = Math.max(0, player.getCurrentPosition());
        recoverAttempts++;
        MpvLogCollector.logError("MpvPlayerEngine", "触发MPV恢复重试: position=" + positionMs + "ms");
        player.start(spec, positionMs, decode);
        return ErrorAction.RECOVERED;
    }
}
