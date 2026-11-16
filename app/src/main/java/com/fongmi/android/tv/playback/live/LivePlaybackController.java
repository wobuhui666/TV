package com.fongmi.android.tv.playback.live;

import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.media3.common.C;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Result;

public class LivePlaybackController {

    private final LiveNavigationPolicy navigationPolicy;
    private final LiveFallbackPolicy fallbackPolicy;
    private final LivePlaybackState state;
    private final LivePlaybackHost host;
    private long channelGeneration;

    public LivePlaybackController(LivePlaybackHost host, LivePlaybackState state) {
        this.state = state;
        this.host = host;
        this.navigationPolicy = new LiveNavigationPolicy(this, state, host);
        this.fallbackPolicy = new LiveFallbackPolicy(this, state, host);
    }

    public void reset() {
        channelGeneration++;
        state.reset();
    }

    public void selectGroup(Group group) {
        state.setGroup(group);
        host.renderGroupSelection(group);
        host.renderGroupChannels(group);
    }

    public void selectChannel(Channel channel) {
        if (channel == null) return;
        channelGeneration++;
        state.setChannel(channel);
        host.renderChannelSelection(channel);
        refresh();
    }

    public boolean selectEpg(EpgData data) {
        return selectEpg(data, C.TIME_UNSET);
    }

    public boolean selectEpg(EpgData data, long startPositionMs) {
        Channel channel = state.getChannel();
        if (channel == null || data == null) return false;
        if (data.isSelected()) {
            requestCatchup(data, startPositionMs);
            return true;
        } else if (channel.hasCatchup() || channel.isRtsp()) {
            host.showCatchupReady(channel, data);
            host.renderEpgSelection(channel, data);
            requestCatchup(data, C.TIME_UNSET);
            return true;
        }
        return false;
    }

    public void refresh() {
        refresh(C.TIME_UNSET);
    }

    public void refresh(long startPositionMs) {
        Channel channel = state.getChannel();
        if (channel == null) return;
        LiveConfig.get().setKeep(channel);
        LivePlayRequest request = LivePlayRequest.live(channel, startPositionMs);
        state.setPendingRequest(request);
        host.requestUrl(request);
        host.showProgress();
        host.stopPlaybackForRefresh();
    }

    public void onUrlResult(Result result) {
        LivePlayRequest request = state.getPendingRequest();
        if (request == null) {
            if (result.hasMsg()) host.resetPlaybackForError(result.getMsg());
            return;
        }
        if (!request.matches(state.getChannel())) return;
        String realUrl = result.getRealUrl();
        if (TextUtils.isEmpty(realUrl)) {
            state.clearPendingRequest();
            playbackError(result.getMsg());
            return;
        }
        long position = result.hasPosition() ? result.getPosition() : request.getPosition();
        state.setResult(result);
        state.clearPendingRequest();
        host.startPlayback(result, position, request.getChannel());
    }

    public void reclaim(long position) {
        Result result = state.getResult();
        Channel channel = state.getChannel();
        if (result == null || channel == null) return;
        host.startPlayback(result, position, channel);
    }

    public void playbackError(String msg) {
        host.resetPlaybackForError(msg);
        fallbackPolicy.playbackError();
    }

    public void playbackEnded() {
        fallbackPolicy.playbackEnded();
    }

    public void prevChannel() {
        navigationPolicy.moveChannel(-1);
    }

    public void nextChannel() {
        navigationPolicy.moveChannel(1);
    }

    public void prevLine() {
        switchLine(false, true);
    }

    public void nextLine(boolean show) {
        switchLine(true, show);
    }

    @Nullable
    public LiveLineSelection getLineSelection() {
        Channel channel = state.getChannel();
        return channel == null || channel.getUrls().size() <= 1 ? null : new LiveLineSelection(channel, channelGeneration);
    }

    public boolean isCurrentLineSelection(LiveLineSelection selection) {
        return selection != null && selection.matches(state.getChannel(), channelGeneration);
    }

    public boolean selectLine(LiveLineSelection selection, int index) {
        if (!isCurrentLineSelection(selection) || !selection.contains(index)) return false;
        Channel channel = state.getChannel();
        if (channel.getIndex() == index) return false;
        channel.setIndex(index);
        host.renderLineSelection(channel, false);
        refresh();
        return true;
    }

    private void switchLine(boolean next, boolean show) {
        Channel channel = state.getChannel();
        if (channel == null || channel.isOnly()) return;
        channel.switchLine(next);
        host.renderLineSelection(channel, show);
        refresh();
    }

    private void requestCatchup(EpgData data, long startPositionMs) {
        Channel channel = state.getChannel();
        if (channel == null) return;
        LivePlayRequest request = LivePlayRequest.catchup(channel, data, startPositionMs);
        state.setPendingRequest(request);
        host.requestCatchupUrl(request);
        host.stopPlaybackForRefresh();
    }
}
