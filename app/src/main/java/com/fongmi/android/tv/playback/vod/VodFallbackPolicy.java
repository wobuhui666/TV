package com.fongmi.android.tv.playback.vod;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.SourceSelectionSetting;
import com.fongmi.android.tv.source.EpisodeTarget;
import com.fongmi.android.tv.source.SmartSourceSelector;
import com.fongmi.android.tv.source.SourceAggregator;
import com.fongmi.android.tv.source.SourceReliabilityStore;
import com.fongmi.android.tv.source.SourceSelectionMode;

import java.util.ArrayList;
import java.util.List;

public class VodFallbackPolicy {

    private final VodPlaybackController controller;
    private final VodPlaybackState state;
    private final VodPlaybackHost host;
    private final SourceAggregator aggregator;
    private final SourceReliabilityStore reliability;

    public VodFallbackPolicy(VodPlaybackController controller, VodPlaybackState state, VodPlaybackHost host) {
        this.controller = controller;
        this.state = state;
        this.host = host;
        this.aggregator = new SourceAggregator();
        this.reliability = new SourceReliabilityStore();
    }

    public void playbackError() {
        fallbackToNextLineOrSource();
    }

    public void emptyFlag() {
        fallbackToNextLineOrSource();
    }

    public void emptyDetail() {
        fallbackToNextSource(false);
    }

    public void manualSwitchSource() {
        fallbackToNextSource(true);
    }

    public void search(String keyword, boolean autoFallback) {
        state.setSearchKeyword(keyword);
        state.setAutoFallback(autoFallback);
        state.setSelectFirstSource(autoFallback);
        host.onSearchStarted(keyword);
        host.requestSearch(getSearchableSites(), keyword);
    }

    public void onSearchResult(Result result) {
        List<Vod> items = new ArrayList<>(result.getList());
        items.removeIf(this::mismatch);
        if (SourceSelectionSetting.getMode() == SourceSelectionMode.SMART) aggregator.mergeInto(state.getSources(), items);
        else state.setSources(items);
        host.renderSources(state.getSources());
        if (state.isSelectFirstSource()) nextSource();
        if (items.isEmpty()) return;
        host.onSearchResult();
    }

    private void fallbackToNextLineOrSource() {
        if (!host.isSiteChangeable()) return;
        if (fallbackToNextLine()) return;
        fallbackToNextSource(false);
    }

    private boolean fallbackToNextLine() {
        int position = state.getFlagPosition() + 1;
        EpisodeTarget target = state.getEpisodeTarget();
        for (int i = position; i < state.getFlags().size(); i++) {
            Flag flag = state.getFlags().get(i);
            if (SourceSelectionSetting.getMode() == SourceSelectionMode.SMART && SmartSourceSelector.findEpisode(flag, target) == null) continue;
            host.showSwitchLine(flag);
            controller.selectFlag(flag);
            return true;
        }
        return false;
    }

    private void fallbackToNextSource(boolean force) {
        if (!state.hasSources()) search(host.getVodName(), true);
        else if (state.isAutoFallback() || force) nextSource();
    }

    private void nextSource() {
        if (!state.hasSources()) return;
        Vod item;
        if (SourceSelectionSetting.getMode() == SourceSelectionMode.SMART && state.hasEpisode()) {
            List<Vod> options = new ArrayList<>();
            for (Vod source : state.getSources()) options.addAll(source.getSourceOptions());
            item = SmartSourceSelector.selectSource(options, state.getEpisodeTarget(), host.getVodKey(), reliability, state.getFlag().getFlag());
            if (item == null) return;
            options.removeIf(source -> sameSource(source, item));
            item.setSourceCandidates(null);
            state.setSources(aggregator.aggregate(options));
        } else {
            item = state.removeFirstSource();
        }
        host.renderSources(state.getSources());
        host.showSwitchSource(item);
        state.addFailedId(sourceKey(item));
        state.setSelectFirstSource(false);
        controller.fallbackSource(item);
    }

    private List<Site> getSearchableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (isPass(site)) sites.add(site);
        return sites;
    }

    private boolean isPass(Site item) {
        if (state.isAutoFallback() && !item.isChangeable()) return false;
        if (state.isAutoFallback() && SourceSelectionSetting.getMode() == SourceSelectionMode.SMART && !SourceSelectionSetting.isCrossSiteEnabled()) return item.getKey().equals(host.getVodKey());
        return item.isSearchable();
    }

    private boolean mismatch(Vod item) {
        if (SourceSelectionSetting.getMode() != SourceSelectionMode.SMART) {
            if (host.getVodId().equals(item.getId()) || state.hasFailedId(item.getId())) return true;
            return state.isAutoFallback() ? !item.getName().equals(state.getSearchKeyword()) : !item.getName().contains(state.getSearchKeyword());
        }
        if (host.getVodKey().equals(item.getSiteKey()) && host.getVodId().equals(item.getId())) return true;
        if (state.hasFailedId(sourceKey(item))) return true;
        return !com.fongmi.android.tv.source.MediaMatcher.queryMatches(state.getSearchKeyword(), item);
    }

    private String sourceKey(Vod item) {
        return SourceSelectionSetting.getMode() == SourceSelectionMode.SMART ? item.getSiteKey() + "::" + item.getId() : item.getId();
    }

    private boolean sameSource(Vod first, Vod second) {
        return first.getSiteKey().equals(second.getSiteKey()) && first.getId().equals(second.getId());
    }
}
