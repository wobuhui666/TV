package com.fongmi.android.tv.player.engine;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.failure.PlaybackFailure;

public interface PlayerEngine {

    int SOFT = 0;
    int HARD = 1;

    Type getType();

    Player getPlayer();

    void release();

    Player rebuild();

    boolean setDecode(int decode);

    void start(PlaySpec spec, long startPositionMs);

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

    default boolean applyAudioSettings() {
        return false;
    }

    PlaybackFailure classifyFailure(PlaybackException e);

    ErrorAction handleError(PlaybackException e);

    enum ErrorAction {
        RECOVERED,
        TUNNEL,
        PLATFORM,
        DECODE,
        FATAL
    }

    enum Type {
        EXO,
        MPV
    }
}
