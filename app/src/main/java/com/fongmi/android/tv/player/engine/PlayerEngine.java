package com.fongmi.android.tv.player.engine;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlaybackSpecs;
import com.fongmi.android.tv.player.media.PlaySpec;

public interface PlayerEngine {

    int SOFT = 0;
    int HARD = 1;

    Type getType();

    Player getPlayer();

    default PlaybackSpecs getPlaybackSpecs() { return PlaybackSpecs.EMPTY; }

    void release();

    Player rebuild();

    boolean setDecode(int decode);

    /** Clears temporary decoder recovery when a new playback request starts. */
    default boolean resetDecodeFallback() { return false; }

    void start(PlaySpec spec, long startPositionMs);

    default boolean preload(PlaySpec spec, long position) { return false; }

    default void clearPreload() {}

    default void applyAudioEffects() {}

    default void bindPlayerView(androidx.media3.ui.PlayerView view) {}

    default void setSecondaryTrack(String format) {}

    default String getSecondaryTrack() { return null; }

    default void stop() {
        getPlayer().stop();
    }

    boolean isLive();

    boolean isVod();

    default void setSubtitleStyle() {
    }

    default boolean addSubtitle(Sub sub) {
        return false;
    }

    String getErrorMessage(PlaybackException e);

    ErrorAction handleError(PlaybackException e);

    enum ErrorAction {
        RECOVERED,
        TUNNEL,
        PLATFORM,
        DECODE,
        DECODE_TRACK,
        FATAL
    }

    enum Type {
        EXO,
        MPV
    }
}
