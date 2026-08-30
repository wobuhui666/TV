package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

import java.util.concurrent.TimeUnit;

public class VodHistoryPolicy {

    private static final long MAX_UNKNOWN_DURATION_POSITION_MS = TimeUnit.DAYS.toMillis(1);

    public History findOrCreate(String key, String mark, Vod item) {
        History history = History.find(key);
        history = history == null ? create(key, item) : history;
        if (!TextUtils.isEmpty(mark)) history.setVodRemarks(mark);
        if (Setting.isIncognito() && history.getKey().equals(key)) history.delete();
        history.setVodName(item.getName());
        return history;
    }

    private History create(String key, Vod item) {
        History history = new History();
        history.setKey(key);
        history.setCid(VodConfig.getCid());
        history.setVodName(item.getName());
        history.findEpisode(item.getFlags());
        return history;
    }

    public void save(History history) {
        save(history, false);
    }

    public void save(History history, boolean exit) {
        if (history != null && history.canSave() && !Setting.isIncognito()) Task.execute(() -> {
            history.merge().save();
            if (exit) RefreshEvent.history();
        });
    }

    public void sync(History history) {
        if (history != null && !Setting.isIncognito()) Task.execute(history::save);
    }

    public void updateEpisode(History history, Flag flag, Episode episode) {
        if (history == null || flag == null || episode == null) return;
        if (!episode.matchesName(history.getEpisode())) {
            history.setPosition(C.TIME_UNSET);
            history.setDuration(C.TIME_UNSET);
        }
        history.setVodFlag(flag.getFlag());
        history.setVodRemarks(episode.getName());
        history.setEpisodeUrl(episode.getUrl());
    }

    public void updateTime(History history, long time, long position, long duration) {
        if (history == null || position < 0 || duration <= 0) return;
        history.setCreateTime(time);
        history.setPosition(position);
        history.setDuration(duration);
        if (history.canSave() && history.canSync()) sync(history);
    }

    /**
     * Returns a source-provided position only when local history has no usable progress.
     * A null result means the controller must keep the current local value.
     */
    public Long acceptedResultPosition(History history, Long sourcePosition) {
        if (history == null || sourcePosition == null || sourcePosition < 0) return null;
        if (history.getPosition() > 0) return null;
        long duration = history.getDuration();
        if (duration > 0 && sourcePosition >= duration) return null;
        if (duration <= 0 && sourcePosition >= MAX_UNKNOWN_DURATION_POSITION_MS) return null;
        return sourcePosition;
    }

    public long startPositionMs(History history) {
        if (history == null) return C.TIME_UNSET;
        long position = Math.max(history.getOpening(), history.getPosition());
        return position < 0 ? C.TIME_UNSET : position;
    }
}
