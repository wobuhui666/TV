package com.fongmi.android.tv.player.skip;

import com.fongmi.android.tv.bean.History;

import java.util.Objects;

record PlaybackEpisodeSnapshot(int cid, String key, String flag, String episode, String url) {

    static PlaybackEpisodeSnapshot from(History history) {
        return new PlaybackEpisodeSnapshot(history.getCid(), history.getKey(), history.getVodFlag(),
                history.getVodRemarks(), history.getEpisodeUrl());
    }

    boolean matches(History history) {
        return history != null && cid == history.getCid() && Objects.equals(key, history.getKey())
                && Objects.equals(flag, history.getVodFlag()) && Objects.equals(episode, history.getVodRemarks())
                && Objects.equals(url, history.getEpisodeUrl());
    }
}
