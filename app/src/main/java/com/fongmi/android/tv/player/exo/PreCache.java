package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.PriorityTaskManager;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.preload.PreCacheHelper;

import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.net.OkHttp;

@UnstableApi
public class PreCache implements Player.Listener {

    private static final long TICK_MS = 5000;
    // Progressive cloud-drive endpoints commonly reject or throttle concurrent byte ranges.
    private static final int PROGRESSIVE_PARALLEL_DOWNLOAD_COUNT = 1;

    private final PriorityTaskManager priorityTaskManager;
    private final Runnable task;

    private PreCacheHelper helper;
    private AbortableCallFactory callFactory;
    private Handler handler;
    private HandlerThread worker;
    private MediaItem mediaItem;
    private ExoPlayer player;
    private long lastStartMs;
    private long seekStartMs;

    public PreCache() {
        priorityTaskManager = new PriorityTaskManager();
        task = this::check;
    }

    public void start(ExoPlayer player, MediaItem mediaItem) {
        stop();
        if (!PreloadSetting.isPreload() || !canPreCache(mediaItem)) return;
        this.player = player;
        this.mediaItem = mediaItem;
        this.handler = new Handler(player.getApplicationLooper());
        registerPriorities();
        this.player.addListener(this);
        clearSeek();
        lastStartMs = C.TIME_UNSET;
        schedule();
    }

    public void stop() {
        cancel();
        if (player != null) player.removeListener(this);
        releaseHelper();
        unregisterPriorities();
        handler = null;
        mediaItem = null;
        player = null;
        clearSeek();
        lastStartMs = C.TIME_UNSET;
    }

    public void release() {
        stop();
        releaseWorker();
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) {
            check();
        } else if (state == Player.STATE_BUFFERING) {
            pauseForPlayback();
        } else if (isStopped(state)) {
            cancel();
            releaseHelper();
        }
    }

    @Override
    public void onIsLoadingChanged(boolean isLoading) {
        if (isLoading) pauseForPlayback();
        else check();
    }

    @Override
    public void onPositionDiscontinuity(@NonNull Player.PositionInfo oldPosition, @NonNull Player.PositionInfo newPosition, int reason) {
        if (!isSeek(reason)) return;
        pauseForPlayback();
        markSeek(newPosition.positionMs);
        schedule();
    }

    private void check() {
        cancel();
        if (update()) schedule();
    }

    private boolean update() {
        if (player == null || mediaItem == null) return false;
        if (!PreloadSetting.isPreload()) {
            stop();
            return false;
        }
        int state = player.getPlaybackState();
        if (isStopped(state)) return false;
        if (!PreCachePolicy.canStart(state, player.isLoading())) return true;
        if (player.isCurrentMediaItemLive()) {
            stop();
            return false;
        }
        long startMs = PreCachePolicy.getStart(player.getCurrentPosition(), seekStartMs);
        long lengthMs = PreCachePolicy.getLength(player.getDuration(), startMs, PreloadSetting.getPreloadDurationMs());
        if (lengthMs <= 0) {
            clearSeek();
            return true;
        }
        if (!PreCachePolicy.shouldPreCache(startMs, lastStartMs, hasSeek(), PreloadSetting.getPreloadDurationMs())) return true;
        ensureHelper();
        helper.preCache(startMs, lengthMs);
        lastStartMs = startMs;
        clearSeek();
        return true;
    }

    private void schedule() {
        if (handler != null) handler.postDelayed(task, TICK_MS);
    }

    private void cancel() {
        if (handler != null) handler.removeCallbacks(task);
    }

    private void pauseForPlayback() {
        cancel();
        // The active range is canceled below.  Treat the next ready window as a fresh request;
        // otherwise a short buffering event can leave the helper stopped until the position has
        // advanced by the full preload step.
        lastStartMs = C.TIME_UNSET;
        // Cancel an in-flight range request as soon as playback needs the connection. The
        // priority manager only blocks at downloader checkpoints, so leaving the request alive
        // can still delay a seek on slow cloud-drive endpoints.
        releaseHelper();
    }

    private PreCacheHelper createHelper(MediaItem mediaItem) {
        callFactory = new AbortableCallFactory(OkHttp.player());
        DataSource.Factory upstreamFactory = MediaSourceFactory.createUpstreamDataSourceFactory(ExoUtil.extractHeaders(mediaItem), callFactory);
        return new PreCacheHelper.Factory(MediaSourceFactory.getCache(), upstreamFactory, ExoUtil.buildRenderersFactory(), getWorker().getLooper())
                .setUpstreamPriorityTaskManager(priorityTaskManager)
                .setProgressiveParallelDownloadCount(PROGRESSIVE_PARALLEL_DOWNLOAD_COUNT)
                .create(mediaItem);
    }

    private void abortUpstream() {
        if (callFactory != null) callFactory.abort();
    }

    private void ensureHelper() {
        if (helper == null && mediaItem != null) helper = createHelper(mediaItem);
    }

    private void releaseHelper() {
        if (helper != null) helper.release(false);
        abortUpstream();
        helper = null;
        callFactory = null;
    }

    private void registerPriorities() {
        if (player == null) return;
        player.setPriorityTaskManager(priorityTaskManager);
    }

    private void unregisterPriorities() {
        if (player != null) player.setPriorityTaskManager(null);
    }

    private boolean canPreCache(MediaItem mediaItem) {
        if (mediaItem == null || mediaItem.localConfiguration == null) return false;
        MediaItem.LocalConfiguration local = mediaItem.localConfiguration;
        String scheme = local.uri.getScheme();
        boolean isHttp = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        int contentType = Util.inferContentTypeForUriAndMimeType(local.uri, local.mimeType);
        return isHttp && PreCachePolicy.supportsContentType(contentType);
    }

    private void markSeek(long startMs) {
        seekStartMs = startMs;
    }

    private void clearSeek() {
        seekStartMs = C.TIME_UNSET;
    }

    private boolean hasSeek() {
        return seekStartMs != C.TIME_UNSET;
    }

    private HandlerThread getWorker() {
        if (worker != null) return worker;
        worker = new HandlerThread("CurrentMediaPreCache");
        worker.start();
        return worker;
    }

    private void releaseWorker() {
        if (worker == null) return;
        worker.quitSafely();
        worker = null;
    }

    private boolean isStopped(int state) {
        return state == Player.STATE_ENDED || state == Player.STATE_IDLE;
    }

    private boolean isSeek(int reason) {
        return reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT;
    }
}
