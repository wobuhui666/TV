package com.fongmi.android.tv.playback.vod;

import android.text.TextUtils;

import androidx.media3.common.C;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.playback.PlaybackEvents;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

public class VodHistoryPolicy {

    public History findOrCreate(String key, String mark, Vod item) {
        History history = History.find(key);
        history = history == null ? create(key, item) : history;
        if (!TextUtils.isEmpty(mark)) history.setVodRemarks(mark);
        if (Setting.isIncognito() && history.getKey().equals(key)) history.deleteSilently();
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
        if (history == null || !history.canSave() || Setting.isIncognito()) return;
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            copy.merge().save();
            PlaybackEvents.progress(copy, copy.getDuration() > 0 && copy.getPosition() >= copy.getDuration() - 1000);
            if (exit) RefreshEvent.history();
        });
    }

    public void sync(History history) {
        if (history == null || Setting.isIncognito()) return;
        History copy = copyForSave(history);
        Task.executeSerial(() -> {
            copy.save();
            PlaybackEvents.progress(copy, false);
        });
    }

    public void updateEpisode(History history, Flag flag, Episode episode) {
        if (history == null || flag == null || episode == null) return;
        boolean match = episode.matchesName(history.getEpisode());
        if (!match) history.setPosition(C.TIME_UNSET);
        if (!match) history.setDuration(C.TIME_UNSET);
        history.setVodFlag(flag.getFlag());
        history.setVodRemarks(episode.getName());
        history.setEpisodeUrl(episode.getUrl());
    }

    public void updateTime(History history, long time, long position, long duration) {
        if (!applyTime(history, time, position, duration)) return;
        if (history.canSave() && history.canScheduleSave()) sync(history);
    }

    public void saveProgress(History history, boolean exit, long time, long position, long duration) {
        applyTime(history, time, position, duration);
        save(history, exit);
    }

    private boolean applyTime(History history, long time, long position, long duration) {
        if (history == null || position < 0 || duration <= 0) return false;
        history.setCreateTime(time);
        history.setPosition(position);
        history.setDuration(duration);
        return true;
    }

    private History copyForSave(History history) {
        history.markSaveScheduled();
        return history.copy();
    }

    public long startPositionMs(History history) {
        return history == null ? C.TIME_UNSET : Math.max(history.getOpening(), history.getPosition());
    }
}
