package com.fongmi.android.tv.browse;

import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.extractor.Source;
import com.google.common.collect.ImmutableList;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BrowseTree {

    private static final String CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final int CONTENT_STYLE_LIST = 1;

    private static final String ROOT = "ROOT";
    private static final String VOD = "VOD";
    private static final String LIVE = "LIVE";
    private static final Map<String, PreparedResult> browseResultMap = new ConcurrentHashMap<>();
    private static final MediaItem ROOT_ITEM = folder(ROOT, "影視");
    private static final MediaItem VOD_FOLDER = folder(VOD, "點播");
    private static final MediaItem LIVE_FOLDER;

    static {
        Bundle extras = new Bundle();
        extras.putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST);
        MediaMetadata meta = new MediaMetadata.Builder().setTitle("直播").setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).setExtras(extras).build();
        LIVE_FOLDER = new MediaItem.Builder().setMediaId(LIVE).setMediaMetadata(meta).build();
    }

    public static void clear() {
        browseResultMap.clear();
        clearVod();
        clearLive();
    }

    public static void clearVod() {
        clearPending(VodBrowse.VOD_PLAY);
        clearPending(VodBrowse.VOD_EP);
        clearPending(VodBrowse.VOD_SEARCH);
        VodBrowse.clear();
    }

    public static void clearLive() {
        clearPending(LiveBrowse.LIVE_CH);
        LiveBrowse.clear();
    }

    public static MediaItem getRootItem() {
        return ROOT_ITEM;
    }

    @NonNull
    public static ImmutableList<MediaItem> getChildren(@NonNull String parentId, int page, int pageSize) {
        return page(getChildrenInternal(parentId), page, pageSize);
    }

    @NonNull
    public static ChildrenCandidate prepareChildren(@NonNull String parentId, int page, int pageSize) {
        if (parentId.startsWith(LiveBrowse.LIVE_GROUP)) {
            ChildrenCandidate candidate = LiveBrowse.prepareChannels(parentId);
            return childrenCandidate(page(candidate.items(), page, pageSize), candidate.commitAction());
        }
        return childrenCandidate(page(getChildrenInternal(parentId), page, pageSize), null);
    }

    @NonNull
    private static ImmutableList<MediaItem> getChildrenInternal(@NonNull String parentId) {
        return switch (parentId) {
            case ROOT -> ImmutableList.of(VOD_FOLDER, LIVE_FOLDER);
            case VOD -> VodBrowse.getHistory();
            case LIVE -> LiveBrowse.getGroups();
            default -> {
                if (parentId.startsWith(LiveBrowse.LIVE_GROUP)) yield LiveBrowse.getChannels(parentId);
                yield ImmutableList.of();
            }
        };
    }

    @Nullable
    public static MediaItem getItem(@NonNull String mediaId) {
        return switch (mediaId) {
            case ROOT -> ROOT_ITEM;
            case VOD -> VOD_FOLDER;
            case LIVE -> LIVE_FOLDER;
            default -> mediaId.startsWith(LiveBrowse.LIVE_GROUP) || mediaId.startsWith(LiveBrowse.LIVE_CH) ? LiveBrowse.getItem(mediaId) : VodBrowse.getItem(mediaId);
        };
    }

    @NonNull
    public static ImmutableList<MediaItem> search(@NonNull String query) {
        SearchCandidate candidate = VodBrowse.prepareSearchResults(query);
        candidate.commit();
        return candidate.items();
    }

    @NonNull
    public static SearchCandidate prepareSearch(@NonNull String query) {
        return VodBrowse.prepareSearchResults(query);
    }

    @NonNull
    public static ImmutableList<MediaItem> getSearchResult(@NonNull String query, int page, int pageSize) {
        return VodBrowse.getSearchResult(query, page, pageSize);
    }

    @Nullable
    public static MediaItem resolve(@NonNull String mediaId) throws Exception {
        if (mediaId.startsWith(LiveBrowse.LIVE_CH)) return LiveBrowse.resolve(mediaId);
        return VodBrowse.resolve(mediaId);
    }

    @NonNull
    public static ResolutionCandidate prepareOrKeep(@NonNull MediaItem item) throws Exception {
        return prepareOrKeep(item, Source.get().beginResolve());
    }

    @NonNull
    public static ResolutionCandidate prepareOrKeep(@NonNull MediaItem item, @NonNull Source.ResolveRequest request) throws Exception {
        ResolutionCandidate candidate = mediaItemCandidate(item.mediaId, request);
        if (candidate != null) return candidate;
        if (isBrowseMediaId(item.mediaId)) throw new IllegalStateException("Unable to resolve media item: " + item.mediaId);
        return resolution(item, null, C.TIME_UNSET, null);
    }

    @Nullable
    private static ResolutionCandidate mediaItemCandidate(@NonNull String mediaId, @NonNull Source.ResolveRequest request) throws Exception {
        if (mediaId.startsWith(LiveBrowse.LIVE_CH)) return LiveBrowse.prepare(mediaId, request);
        return VodBrowse.prepare(mediaId, request);
    }

    private static boolean isBrowseMediaId(@NonNull String mediaId) {
        return mediaId.startsWith(LiveBrowse.LIVE_CH)
                || mediaId.startsWith(VodBrowse.VOD_PLAY)
                || mediaId.startsWith(VodBrowse.VOD_EP)
                || mediaId.startsWith(VodBrowse.VOD_SEARCH);
    }

    @NonNull
    public static MediaItem resolveOrKeep(@NonNull MediaItem item) {
        try {
            MediaItem resolved = resolve(item.mediaId);
            return resolved != null ? resolved : item;
        } catch (Exception e) {
            return item;
        }
    }

    @Nullable
    public static NavigationCandidate navigate(@NonNull String mediaId, int delta) throws Exception {
        return navigate(mediaId, delta, Source.get().beginResolve());
    }

    @Nullable
    public static NavigationCandidate navigate(@NonNull String mediaId, int delta, @NonNull Source.ResolveRequest request) throws Exception {
        NavigationCandidate vod = VodBrowse.navigate(mediaId, delta, request);
        if (vod != null) return vod;
        return LiveBrowse.navigate(mediaId, delta, request);
    }

    public static boolean saveProgress(long position, long duration) {
        return VodBrowse.saveProgress(position, duration);
    }

    @Nullable
    public static Result consumeBrowseResult(@NonNull String mediaId) {
        return consumeBrowseResult(mediaId, C.TIME_UNSET);
    }

    @Nullable
    public static Result consumeBrowseResult(@NonNull String mediaId, long generation) {
        PreparedResult prepared = browseResultMap.get(mediaId);
        if (prepared == null || prepared.generation() != generation || !browseResultMap.remove(mediaId, prepared)) return null;
        prepared.commit();
        return prepared.result();
    }

    public static void discardBrowseResult(long generation) {
        browseResultMap.entrySet().removeIf(entry -> entry.getValue().generation() == generation);
    }

    static void putBrowseResult(@NonNull String mediaId, @NonNull Result result) {
        browseResultMap.put(mediaId, new PreparedResult(result, null, C.TIME_UNSET));
    }

    static NavigationCandidate candidate(@NonNull MediaItem item, @NonNull Result result, @Nullable Runnable historyCommit) {
        return new NavigationCandidate(item, result, historyCommit);
    }

    static ResolutionCandidate resolution(@NonNull MediaItem item, @Nullable Result result, long resumePositionMs, @Nullable Runnable commitAction) {
        return new ResolutionCandidate(item, result, commitAction, resumePositionMs);
    }

    static SearchCandidate searchCandidate(@NonNull ImmutableList<MediaItem> items, @Nullable Runnable commitAction) {
        return new SearchCandidate(items, commitAction);
    }

    static ChildrenCandidate childrenCandidate(@NonNull ImmutableList<MediaItem> items, @Nullable Runnable commitAction) {
        return new ChildrenCandidate(items, commitAction);
    }

    public record NavigationCandidate(@NonNull MediaItem item, @NonNull Result result, @Nullable Runnable historyCommit) {

        public void commit() {
            if (historyCommit != null) historyCommit.run();
        }
    }

    public static final class ResolutionCandidate {

        private final MediaItem item;
        private final Result result;
        private final Runnable commitAction;
        private final long resumePositionMs;
        private PreparedResult staged;

        private ResolutionCandidate(@NonNull MediaItem item, @Nullable Result result, @Nullable Runnable commitAction, long resumePositionMs) {
            this.item = item;
            this.result = result;
            this.commitAction = commitAction;
            this.resumePositionMs = resumePositionMs;
        }

        @NonNull
        public MediaItem item() {
            return item;
        }

        public long resumePositionMs() {
            return resumePositionMs;
        }

        public void stage(long generation) {
            if (result == null) return;
            staged = new PreparedResult(result, commitAction, generation);
            browseResultMap.put(item.mediaId, staged);
        }

        public void rollback() {
            if (staged != null) browseResultMap.remove(item.mediaId, staged);
        }

    }

    private record PreparedResult(@NonNull Result result, @Nullable Runnable commitAction, long generation) {

        private void commit() {
            if (commitAction != null) commitAction.run();
        }

    }

    public record SearchCandidate(@NonNull ImmutableList<MediaItem> items, @Nullable Runnable commitAction) {

        public void commit() {
            if (commitAction != null) commitAction.run();
        }
    }

    public record ChildrenCandidate(@NonNull ImmutableList<MediaItem> items, @Nullable Runnable commitAction) {

        public void commit() {
            if (commitAction != null) commitAction.run();
        }
    }

    static int wrapIndex(int current, int delta, int count) {
        return ((current + delta) % count + count) % count;
    }

    private static void clearPending(@NonNull String prefix) {
        browseResultMap.keySet().removeIf(key -> key.startsWith(prefix));
    }

    @NonNull
    static ImmutableList<MediaItem> page(@NonNull ImmutableList<MediaItem> items, int page, int pageSize) {
        if (pageSize <= 0) return items;
        long fromLong = Math.max(0, page) * (long) pageSize;
        if (fromLong >= items.size()) return ImmutableList.of();
        int from = (int) fromLong;
        return ImmutableList.copyOf(items.subList(from, Math.min(items.size(), from + pageSize)));
    }

    static MediaItem folder(@NonNull String id, @NonNull String title) {
        return build(id, true, false, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, title, null, null, null);
    }

    static MediaItem playable(@NonNull String id, @NonNull String title, @Nullable String subtitle, @Nullable String art) {
        return build(id, false, true, MediaMetadata.MEDIA_TYPE_VIDEO, title, subtitle, art, null);
    }

    static MediaItem stream(@NonNull String id, @NonNull String url, @NonNull String title, @Nullable String subtitle, @Nullable String art) {
        return build(id, false, true, MediaMetadata.MEDIA_TYPE_VIDEO, title, subtitle, art, Uri.parse(url));
    }

    private static MediaItem build(@NonNull String id, boolean browsable, boolean playable, int mediaType, @NonNull String title, @Nullable String subtitle, @Nullable String art, @Nullable Uri uri) {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder().setTitle(title).setIsBrowsable(browsable).setIsPlayable(playable).setMediaType(mediaType);
        if (!TextUtils.isEmpty(subtitle)) metadata.setSubtitle(subtitle);
        if (!TextUtils.isEmpty(art)) metadata.setArtworkUri(Uri.parse(art));
        if (!TextUtils.isEmpty(subtitle) && uri != null) metadata.setArtist(subtitle);
        MediaItem.Builder builder = new MediaItem.Builder().setMediaId(id).setMediaMetadata(metadata.build());
        if (uri != null) builder.setUri(uri);
        return builder.build();
    }
}
