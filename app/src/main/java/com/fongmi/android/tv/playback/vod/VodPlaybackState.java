package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.source.EpisodeTarget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VodPlaybackState {

    private final Set<String> failedIds;
    private final List<Vod> sources;
    private final List<Flag> flags;
    private VodPlayRequest pendingRequest;
    private VodPlayRequest playingRequest;
    private History history;
    private Result quality;
    private boolean selectFirstSource;
    private boolean autoFallback;
    private boolean useParse;
    private String searchKeyword;
    private int qualityPosition;
    private Vod media;
    private EpisodeTarget episodeTarget;

    public VodPlaybackState() {
        this.failedIds = new HashSet<>();
        this.sources = new ArrayList<>();
        this.flags = new ArrayList<>();
        this.quality = Result.empty();
        this.searchKeyword = "";
    }

    public void reset() {
        failedIds.clear();
        sources.clear();
        flags.clear();
        quality = Result.empty();
        history = null;
        clearPlayRequest();
        selectFirstSource = false;
        autoFallback = false;
        useParse = false;
        searchKeyword = "";
        qualityPosition = 0;
        media = null;
        episodeTarget = null;
    }

    public void addFailedId(String id) {
        if (id != null && !id.isEmpty()) failedIds.add(id);
    }

    public Vod getMedia() {
        return media;
    }

    public void setMedia(Vod media) {
        this.media = media;
    }

    public EpisodeTarget getEpisodeTarget() {
        if (episodeTarget != null) return episodeTarget;
        return hasEpisode() ? EpisodeTarget.of(getEpisode()) : new EpisodeTarget(null, "");
    }

    public void setEpisodeTarget(EpisodeTarget episodeTarget) {
        this.episodeTarget = episodeTarget;
    }

    public boolean hasEpisodeTarget() {
        return episodeTarget != null;
    }

    public boolean hasFailedId(String id) {
        return id != null && failedIds.contains(id);
    }

    public List<Vod> getSources() {
        return sources;
    }

    public void setSources(List<Vod> items) {
        sources.clear();
        sources.addAll(items);
    }

    public Vod removeFirstSource() {
        return sources.remove(0);
    }

    public boolean hasSources() {
        return !sources.isEmpty();
    }

    public List<Flag> getFlags() {
        return flags;
    }

    public void setFlags(List<Flag> items) {
        flags.clear();
        flags.addAll(items);
    }

    public boolean hasFlags() {
        return !flags.isEmpty();
    }

    public int getFlagPosition() {
        for (int i = 0; i < flags.size(); i++) if (flags.get(i).isSelected()) return i;
        return 0;
    }

    public Flag getFlag() {
        return flags.get(getFlagPosition());
    }

    public Episode getEpisode() {
        Flag flag = getFlag();
        int position = getEpisodePosition(flag);
        Episode episode = flag.getEpisodes().get(position);
        if (flag.getPosition() != position || !episode.isSelected()) flag.toggle(true, episode);
        return episode;
    }

    public boolean hasEpisode() {
        return hasFlags() && !getFlag().getEpisodes().isEmpty();
    }

    private int getEpisodePosition(Flag flag) {
        int position = flag.getPosition();
        return position >= 0 && position < flag.getEpisodes().size() ? position : 0;
    }

    public Result getQuality() {
        return quality;
    }

    public void setQuality(Result quality) {
        this.quality = quality;
    }

    public int getQualityPosition() {
        return qualityPosition;
    }

    public void setQualityPosition(int qualityPosition) {
        this.qualityPosition = qualityPosition;
    }

    public History getHistory() {
        return history;
    }

    public void setHistory(History history) {
        this.history = history;
    }

    public VodPlayRequest getPendingRequest() {
        return pendingRequest;
    }

    public void setPendingRequest(VodPlayRequest pendingRequest) {
        this.pendingRequest = pendingRequest;
    }

    public VodPlayRequest getPlayingRequest() {
        return playingRequest;
    }

    public void setPlayingRequest(VodPlayRequest playingRequest) {
        this.playingRequest = playingRequest;
        this.pendingRequest = null;
    }

    public void clearPlayRequest() {
        pendingRequest = null;
        playingRequest = null;
    }

    public boolean isSelectFirstSource() {
        return selectFirstSource;
    }

    public void setSelectFirstSource(boolean selectFirstSource) {
        this.selectFirstSource = selectFirstSource;
    }

    public boolean isAutoFallback() {
        return autoFallback;
    }

    public void setAutoFallback(boolean autoFallback) {
        this.autoFallback = autoFallback;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    public String getSearchKeyword() {
        return searchKeyword == null ? "" : searchKeyword;
    }

    public void setSearchKeyword(String searchKeyword) {
        this.searchKeyword = searchKeyword == null ? "" : searchKeyword;
    }
}
