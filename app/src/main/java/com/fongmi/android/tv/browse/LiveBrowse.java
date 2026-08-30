package com.fongmi.android.tv.browse;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.db.AppDatabase;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

class LiveBrowse {

    static final String LIVE_GROUP = "LG:";
    static final String LIVE_CH = "LC:";

    private static final Map<String, String> liveNavMap = new ConcurrentHashMap<>();
    private static final Map<String, Integer> liveCountMap = new ConcurrentHashMap<>();
    private static final Map<String, LiveEntry> livePosMap = new ConcurrentHashMap<>();

    static void clear() {
        livePosMap.clear();
        liveNavMap.clear();
        liveCountMap.clear();
    }

    @NonNull
    static ImmutableList<MediaItem> getGroups() {
        return liveHome().getGroups().stream().map(group -> BrowseTree.folder(LIVE_GROUP + group.getName(), group.getName())).collect(ImmutableList.toImmutableList());
    }

    @NonNull
    static ImmutableList<MediaItem> getChannels(@NonNull String parentId) {
        BrowseTree.ChildrenCandidate candidate = prepareChannels(parentId);
        candidate.commit();
        return candidate.items();
    }

    @NonNull
    static BrowseTree.ChildrenCandidate prepareChannels(@NonNull String parentId) {
        String groupName = parentId.substring(LIVE_GROUP.length());
        Group group = liveHome().getGroups().stream().filter(item -> item.getName().equals(groupName)).findFirst().orElse(null);
        if (group == null) return BrowseTree.childrenCandidate(ImmutableList.of(), () -> indexChannels(groupName, List.of()));
        List<Channel> channels = List.copyOf(group.getChannel());
        ImmutableList<MediaItem> items = IntStream.range(0, channels.size())
                .mapToObj(i -> channelItem(groupName, channels.get(i), i)).collect(ImmutableList.toImmutableList());
        return BrowseTree.childrenCandidate(items, () -> indexChannels(groupName, channels));
    }

    private static void indexChannels(@NonNull String groupName, @NonNull List<Channel> channels) {
        liveNavMap.keySet().removeIf(key -> key.startsWith(groupName + '|'));
        livePosMap.values().removeIf(entry -> entry.groupName.equals(groupName));
        liveCountMap.put(groupName, channels.size());
        IntStream.range(0, channels.size()).forEach(i -> indexChannel(groupName, channels.get(i), i));
    }

    private static void indexChannel(@NonNull String groupName, @NonNull Channel channel, int index) {
        String key = liveNavKey(groupName, index);
        String id = LIVE_CH + key;
        liveNavMap.put(key, id);
        livePosMap.put(id, new LiveEntry(channel, groupName, index));
    }

    private static MediaItem channelItem(@NonNull String groupName, @NonNull Channel channel, int index) {
        String id = LIVE_CH + liveNavKey(groupName, index);
        return BrowseTree.playable(id, channel.getNumber() + " " + channel.getShow(), null, channel.getLogo());
    }

    @Nullable
    static MediaItem getItem(@NonNull String mediaId) {
        if (mediaId.startsWith(LIVE_GROUP)) return BrowseTree.folder(mediaId, mediaId.substring(LIVE_GROUP.length()));
        LiveEntry entry = livePosMap.get(mediaId);
        if (entry == null) return null;
        Channel channel = entry.channel();
        return BrowseTree.playable(mediaId, channel.getNumber() + " " + channel.getShow(), null, channel.getLogo());
    }

    @Nullable
    static MediaItem resolve(@NonNull String mediaId) throws Exception {
        LiveEntry entry = livePosMap.get(mediaId);
        return resolveChannel(entry != null ? entry.channel() : null, mediaId);
    }

    @Nullable
    static BrowseTree.ResolutionCandidate prepare(@NonNull String mediaId, @NonNull Source.ResolveRequest request) throws Exception {
        LiveEntry entry = livePosMap.get(mediaId);
        Channel channel = entry != null ? entry.channel() : null;
        if (channel == null || TextUtils.isEmpty(channel.getCurrent())) return null;
        Result result = LiveApi.getUrl(request, channel);
        if (TextUtils.isEmpty(result.getRealUrl())) return null;
        MediaItem item = BrowseTree.stream(mediaId, result.getRealUrl(), channel.getShow(), null, channel.getLogo());
        return BrowseTree.resolution(item, result, C.TIME_UNSET, null);
    }

    @Nullable
    static BrowseTree.NavigationCandidate navigate(@NonNull String mediaId, int delta, @NonNull Source.ResolveRequest request) throws Exception {
        if (mediaId.startsWith(LIVE_CH)) return navigateChannel(mediaId, delta, request);
        return navigateKeep(delta, request);
    }

    @Nullable
    private static BrowseTree.NavigationCandidate navigateChannel(@NonNull String mediaId, int delta, @NonNull Source.ResolveRequest request) throws Exception {
        LiveEntry current = livePosMap.get(mediaId);
        if (current == null) return null;
        Integer count = liveCountMap.get(current.groupName);
        if (count == null || count == 0) return null;
        int target = BrowseTree.wrapIndex(current.index, delta, count);
        String nextId = liveNavMap.get(liveNavKey(current.groupName, target));
        if (nextId == null) return null;
        LiveEntry next = livePosMap.get(nextId);
        return resolveChannelCandidate(next != null ? next.channel() : null, nextId, null, request);
    }

    @Nullable
    private static BrowseTree.NavigationCandidate navigateKeep(int delta, @NonNull Source.ResolveRequest request) throws Exception {
        String keep = liveHome().getKeep();
        if (TextUtils.isEmpty(keep)) return null;
        String[] splits = keep.split(AppDatabase.SYMBOL);
        if (splits.length < 2) return null;
        String groupName = splits[0];
        String channelName = splits[1];
        Group group = liveHome().getGroups().stream().filter(item -> item.getName().equals(groupName)).findFirst().orElse(null);
        if (group == null || group.getChannel().isEmpty()) return null;
        List<Channel> channels = group.getChannel();
        int current = IntStream.range(0, channels.size()).filter(i -> channels.get(i).getName().equals(channelName)).findFirst().orElse(0);
        int target = BrowseTree.wrapIndex(current, delta, channels.size());
        Channel channel = channels.get(target);
        String mediaId = LIVE_CH + liveNavKey(groupName, target);
        return resolveChannelCandidate(channel, mediaId, () -> getChannels(LIVE_GROUP + groupName), request);
    }

    @Nullable
    private static MediaItem resolveChannel(@Nullable Channel channel, @NonNull String mediaId) throws Exception {
        if (channel == null || TextUtils.isEmpty(channel.getCurrent())) return null;
        Result result = LiveApi.getUrl(channel);
        if (TextUtils.isEmpty(result.getRealUrl())) return null;
        BrowseTree.putBrowseResult(mediaId, result);
        return BrowseTree.stream(mediaId, result.getRealUrl(), channel.getShow(), null, channel.getLogo());
    }

    @Nullable
    private static BrowseTree.NavigationCandidate resolveChannelCandidate(@Nullable Channel channel, @NonNull String mediaId,
                                                                           @Nullable Runnable commit, @NonNull Source.ResolveRequest request) throws Exception {
        if (channel == null || TextUtils.isEmpty(channel.getCurrent())) return null;
        Result result = LiveApi.getUrl(request, channel);
        if (TextUtils.isEmpty(result.getRealUrl())) return null;
        MediaItem item = BrowseTree.stream(mediaId, result.getRealUrl(), channel.getShow(), null, channel.getLogo());
        return BrowseTree.candidate(item, result, commit);
    }

    private static Live liveHome() {
        LiveConfig.get().ensureLoaded();
        return LiveConfig.get().getHome();
    }

    @NonNull
    private static String liveNavKey(@NonNull String groupName, int index) {
        return groupName + '|' + index;
    }

    private record LiveEntry(Channel channel, String groupName, int index) {
    }
}
