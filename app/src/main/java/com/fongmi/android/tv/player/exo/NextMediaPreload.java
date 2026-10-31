package com.fongmi.android.tv.player.exo;

import android.os.Handler;

import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadManagerListener;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

/** One bounded next-item buffer, sharing a network slot with current-item pre-cache. */
final class NextMediaPreload implements Player.Listener {
    private final DefaultPreloadManager manager;
    private final PreloadCoordinator coordinator;
    private final Handler handler;
    private final Runnable task = this::update;
    private final Runnable stopped = this::clearIfStopped;
    final ExoPlayer player;
    private MediaItem pending;
    private MediaItem foreground;
    private AbortableCallFactory calls;
    private PreloadBudget.Lease lease;
    private PreloadManagerListener completion;
    private long startMs;
    private boolean added;
    private boolean consumed;
    private boolean completed;
    private boolean allowed;
    private boolean released;
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener preferences = (prefs, key) -> {
        if ("preload_next".equals(key)) App.post(() -> { if (!released) update(); });
    };

    NextMediaPreload(int decode, Player.Listener listener,
                     com.fongmi.android.tv.player.subtitle.AdvancedSubtitleController subtitles,
                     PreloadCoordinator coordinator) {
        this.coordinator = coordinator;
        DefaultPreloadManager.Builder builder = new DefaultPreloadManager.Builder(App.get(),
                ignored -> allowed ? DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(startMs, 10_000) : null)
                .setMediaSourceFactory(new MediaSourceFactory(subtitles) {
                    @Override public MediaSource createMediaSource(MediaItem item) {
                        // A prepared source retains its own factory after take(). Canceling a later
                        // preload must never close the connections now used by foreground playback.
                        AbortableCallFactory request = calls;
                        return request != null && pending != null && sameMedia(item, pending)
                                ? super.createMediaSource(item, request) : super.createMediaSource(item);
                    }
                })
                .setTrackSelectorFactory(context -> {
                    com.fongmi.android.tv.player.track.DualSubtitleTrackSelector selector = new com.fongmi.android.tv.player.track.DualSubtitleTrackSelector(
                            (androidx.media3.exoplayer.trackselection.DecodeTrackSelector) ExoUtil.buildTrackSelector(decode), subtitles.secondaryRendererCount());
                    subtitles.addSelector(selector);
                    return selector;
                })
                .setRenderersFactory(subtitles.renderers(ExoUtil.buildPlaybackRenderersFactory(decode)))
                .setLoadControl(new DefaultLoadControl.Builder()
                        .setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, 64 * 1024 * 1024).build());
        manager = builder.build();
        player = ExoUtil.buildPlayer(decode, listener, builder);
        handler = new Handler(player.getApplicationLooper());
        coordinator.attach(player);
        player.addListener(this);
        Prefers.getPrefers().registerOnSharedPreferenceChangeListener(preferences);
    }

    void preload(MediaItem item, long positionMs) {
        clear();
        pending = item;
        startMs = Math.max(0, positionMs);
        update();
    }

    MediaSource take(MediaItem item) {
        if (pending == null || !added || consumed || !sameMedia(pending, item)) {
            clear();
            return null;
        }
        MediaSource source = manager.getMediaSource(pending);
        consumed = source != null;
        if (consumed) {
            handler.removeCallbacks(task);
            removeCompletionListener();
            coordinator.release(lease);
            coordinator.cancelWaiting(PreloadBudget.Owner.NEXT);
            lease = null;
            // Ownership of calls has moved to the source/player. clear() must not abort them.
        }
        return source;
    }

    static boolean sameMedia(MediaItem a, MediaItem b) {
        return a.mediaId.equals(b.mediaId) && java.util.Objects.equals(a.localConfiguration, b.localConfiguration)
                && ExoUtil.extractHeaders(a).equals(ExoUtil.extractHeaders(b));
    }

    private void update() {
        handler.removeCallbacks(task);
        if (released) return;
        updateInternal();
        if (pending != null && !consumed) handler.postDelayed(task, 1_000);
    }

    private void updateInternal() {
        if (!PreloadSetting.isNextEpisode()) { clear(); return; }
        if (pending == null || consumed) return;
        if (!coordinator.hasResources()) { pause(); return; }
        // The coordinator defers IDLE cancellation by one application-loop turn, allowing
        // synchronous stop()/take() to transfer even a partially prepared source.
        if (player.getPlaybackState() == Player.STATE_IDLE) return;
        if (added || completed) return;
        lease = coordinator.acquire(PreloadBudget.Owner.NEXT, this::pause);
        if (lease == null) return;
        allowed = true;
        calls = new AbortableCallFactory(OkHttp.player());
        AbortableCallFactory request = calls;
        completion = new PreloadManagerListener() {
            @Override public void onCompleted(MediaItem item) {
                if (calls != request || consumed) return;
                completed = true;
                coordinator.release(lease);
                lease = null;
            }
            @Override public void onError(PreloadException error) {
                if (calls == request && !consumed) clear();
            }
        };
        manager.addListener(completion);
        manager.add(pending, 0);
        added = true;
        manager.invalidate();
    }

    private void pause() {
        if (consumed) return;
        allowed = false;
        // manager.remove() cancels asynchronously; abort the actual HTTP calls first.
        if (calls != null) calls.abort();
        removeCompletionListener();
        if (pending != null && added) manager.remove(pending);
        calls = null;
        added = false;
        completed = false;
        coordinator.release(lease);
        lease = null;
    }

    private void removeCompletionListener() {
        if (completion != null) manager.removeListener(completion);
        completion = null;
    }

    void clear() {
        handler.removeCallbacks(task);
        handler.removeCallbacks(stopped);
        if (!consumed) pause();
        else {
            removeCompletionListener();
            if (pending != null && added) manager.remove(pending);
        }
        coordinator.cancelWaiting(PreloadBudget.Owner.NEXT);
        pending = null;
        calls = null;
        added = false;
        consumed = false;
        completed = false;
        allowed = false;
    }

    void release() {
        released = true;
        Prefers.getPrefers().unregisterOnSharedPreferenceChangeListener(preferences);
        player.removeListener(this);
        clear();
        manager.release();
    }

    private void clearIfStopped() {
        if (!released && player.getPlaybackState() == Player.STATE_IDLE && !consumed) clear();
    }

    @Override public void onPlaybackStateChanged(int state) {
        handler.removeCallbacks(stopped);
        // Keep synchronous stop()/take() handoff valid, then release unused completed buffers.
        if (state == Player.STATE_IDLE) handler.post(stopped);
        update();
    }
    @Override public void onIsLoadingChanged(boolean loading) { update(); }
    @Override public void onMediaItemTransition(MediaItem item, int reason) {
        if (foreground != null && !consumed && (item == null || !sameMedia(foreground, item))) clear();
        foreground = item;
    }
    @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            boolean sameItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                    && (oldPosition.mediaItem == null || newPosition.mediaItem == null
                    ? oldPosition.mediaItem == newPosition.mediaItem : sameMedia(oldPosition.mediaItem, newPosition.mediaItem));
            // Finished buffers no longer consume the network lease. Retain their source and
            // samples when only the current episode's position changes. In-flight work still
            // yields immediately to the coordinator's foreground/seek cooldown budget.
            if (!completed || !sameItem) pause();
            update();
        }
    }
}
