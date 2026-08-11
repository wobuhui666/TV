package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.drm.FrameworkMediaDrm;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleRuntime;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.MpvLogCollector;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class PlaybackActivity extends BaseActivity implements MediaController.Listener, Player.Listener, ServiceConnection {

    private final List<ServiceReadyObserver<?>> serviceReadyObservers = new ArrayList<>();
    private final List<Runnable> foreverObserverRemovers = new ArrayList<>();
    private ListenableFuture<MediaController> mControllerFuture;
    private MediaController mController;
    private PlaybackService mService;
    private TextView seekPreview;
    private boolean initialized;
    private boolean audioOnly;
    private boolean scrubbing;
    private boolean redirect;
    private boolean debugViewVisible;
    private boolean bound;
    private boolean stop;
    private boolean lock;

    protected MediaController controller() {
        return mController;
    }

    protected PlaybackService service() {
        return mService;
    }

    protected boolean isPlaybackReady() {
        return mService != null && mController != null;
    }

    protected PlayerManager player() {
        return mService.player();
    }

    public void reloadAiSubtitleAudioPipeline() {
        if (mService == null || !isOwner() || player().isReleased()) return;
        player().rebuildAudioPipeline();
    }

    protected boolean isRedirect() {
        return redirect;
    }

    protected void setRedirect(boolean redirect) {
        this.redirect = redirect;
        if (isBindingOwner()) mService.setNavigationCallback(redirect ? null : getNavigationCallback(), getPlaybackKey());
    }

    protected void updateNavigationKey() {
        if (isBindingOwner()) mService.setNavigationCallback(getNavigationCallback(), getPlaybackKey());
    }

    protected boolean isAudioOnly() {
        return audioOnly;
    }

    protected void setAudioOnly(boolean audioOnly) {
        this.audioOnly = audioOnly;
    }

    protected boolean isStop() {
        return stop;
    }

    protected void setStop(boolean stop) {
        this.stop = stop;
    }

    protected boolean isLock() {
        return lock;
    }

    protected void setLock(boolean lock) {
        this.lock = lock;
    }

    protected abstract PlaybackService.NavigationCallback getNavigationCallback();

    protected abstract PlayerSeekView getSeekView();

    protected abstract PlayerView getPlayerView();

    protected abstract String getPlaybackKey();

    protected int getPlaybackRender() {
        return PlayerSetting.getRender();
    }

    protected boolean isOwner() {
        String key = getPlaybackKey();
        return key == null || (mService != null && key.equals(player().getKey()));
    }

    protected boolean isBindingOwner() {
        return mService != null && mService.ownsBinding(getNavigationCallback());
    }

    protected <T> void observeForever(LiveData<T> liveData, Observer<T> observer) {
        liveData.observeForever(observer);
        foreverObserverRemovers.add(() -> liveData.removeObserver(observer));
    }

    protected <T> void observeWhenServiceReady(LiveData<T> liveData, Observer<T> observer) {
        ServiceReadyObserver<T> serviceObserver = new ServiceReadyObserver<>(observer);
        serviceReadyObservers.add(serviceObserver);
        observeForever(liveData, serviceObserver);
    }

    public boolean isDebugViewVisible() {
        return debugViewVisible;
    }

    public void toggleDebugView() {
        debugViewVisible = !debugViewVisible;
    }

    public void hideDebugView() {
        debugViewVisible = false;
    }

    public void chooseOtherPlayer(CharSequence title) {
        PlayerManager player = player();
        PlayerHelper.choose(this, player.getUrl(), player.getHeaders(), player.isVod(), player.getPosition(), title);
        setRedirect(true);
    }

    protected void setSeekNextFocusDown(int id) {
        View timeBar = getSeekView().findViewById(androidx.media3.ui.R.id.exo_progress);
        if (timeBar != null) timeBar.setNextFocusDownId(id);
    }

    protected void setActionFocusBoundary(View view) {
        updateActionFocusBoundary(view);
    }

    protected void updateActionFocusBoundary(View view) {
        List<View> views = new ArrayList<>();
        collectActionFocusViews(view, views, true);
        for (int i = 0; i < views.size(); i++) {
            View item = views.get(i);
            int previous = views.get(i == 0 ? views.size() - 1 : i - 1).getId();
            int next = views.get(i == views.size() - 1 ? 0 : i + 1).getId();
            item.setNextFocusLeftId(previous);
            item.setNextFocusRightId(next);
            item.setNextFocusDownId(item.getId());
        }
    }

    private void collectActionFocusViews(View view, List<View> views, boolean root) {
        if (view == null) return;
        if (!root && (view.getVisibility() != View.VISIBLE || !view.isEnabled())) return;
        if (!root && view.isFocusable() && view.getId() != View.NO_ID) views.add(view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) collectActionFocusViews(group.getChildAt(i), views, false);
    }

    protected boolean isIdle() {
        return isPlaybackState(Player.STATE_IDLE);
    }

    protected boolean isEnded() {
        return isPlaybackState(Player.STATE_ENDED);
    }

    protected boolean isBuffering() {
        return isPlaybackState(Player.STATE_BUFFERING);
    }

    protected boolean isPaused() {
        return mController != null && !isBuffering() && !isIdle();
    }

    private boolean isPlaybackState(int state) {
        return mController != null && mController.getPlaybackState() == state;
    }

    protected void onServiceConnected() {
    }

    protected void onPrepare() {
    }

    protected void onTracksChanged() {
    }

    protected void onDecodeChanged() {
    }

    protected void onMediaOptionsChanged() {
    }

    protected void onError(String msg) {
    }

    protected void onPlayingChanged(boolean isPlaying) {
    }

    protected void onStateChanged(int state) {
    }

    protected void onSizeChanged(VideoSize size) {
    }

    protected void onReclaim() {
    }

    protected long startPositionMs() {
        return C.TIME_UNSET;
    }

    protected boolean seekTo(long deltaMs) {
        MediaController controller = mController;
        if (mService == null || controller == null) return false;
        PlayerManager player = player();
        long targetMs = Math.max(0, player.getPosition() + deltaMs);
        long durationMs = player.getDuration();
        boolean seekToEnd = durationMs > 0 && targetMs >= durationMs;
        controller.seekTo(seekToEnd ? durationMs : targetMs);
        if (!seekToEnd) controller.play();
        return seekToEnd;
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, MediaMetadata metadata) {
        startPlayer(key, result, useParse, timeout, startPositionMs(), metadata);
    }

    protected void startPlayer(String key, Result result, boolean useParse, long timeout, long startPositionMs, MediaMetadata metadata) {
        MpvLogCollector.log("PlaybackActivity", "startPlayer: key=" + key + ", resultKey=" + result.getKey() + ", flag=" + result.getFlag() + ", useParse=" + useParse + ", parse=" + result.getParse() + ", format=" + result.getFormat() + ", realUrl=" + result.getRealUrl());
        if (result.getDrm() != null && !FrameworkMediaDrm.isCryptoSchemeSupported(result.getDrm().getUUID())) {
            onError(ResUtil.getString(R.string.error_play_drm));
        } else if (result.hasMsg()) {
            onError(result.getMsg());
        } else if (result.getRealUrl().isEmpty()) {
            onError(ResUtil.getString(R.string.error_play_url));
        } else if (result.needParse() || useParse) {
            attachSurface("startPlayer:parse");
            player().parse(key, result, useParse, metadata, startPositionMs);
        } else {
            attachSurface("startPlayer:play");
            player().start(PlaySpec.from(result, key, metadata), timeout, startPositionMs);
        }
    }

    private void bindPlaybackService() {
        startService(new Intent(this, PlaybackService.class));
        bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), this, BIND_AUTO_CREATE);
        buildControllerAsync();
        bound = true;
    }

    private void buildControllerAsync() {
        SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
        mControllerFuture = new MediaController.Builder(this, token).setListener(this).buildAsync();
        mControllerFuture.addListener(this::onControllerConnected, ContextCompat.getMainExecutor(this));
    }

    private void onControllerConnected() {
        try {
            mController = mControllerFuture.get();
            getSeekView().setPlayer(mController);
            mController.addListener(this);
            updateKeyIncrement();
        } catch (Exception ignored) {
        }
    }

    private void addSeekListener() {
        seekPreview = findViewById(R.id.seek_preview);
        getSeekView().getTimeBar().addListener(new TimeBar.OnScrubListener() {
            @Override
            public void onScrubStart(@NonNull TimeBar timeBar, long position) {
                PlaybackActivity.this.showSeekPreview(position);
                PlaybackActivity.this.setScrubbing(true);
            }

            @Override
            public void onScrubMove(@NonNull TimeBar timeBar, long position) {
                PlaybackActivity.this.showSeekPreview(position);
                PlaybackActivity.this.setScrubbing(true);
            }

            @Override
            public void onScrubStop(@NonNull TimeBar timeBar, long position, boolean canceled) {
                PlaybackActivity.this.onScrubStop(canceled);
            }
        });
    }

    protected boolean isScrubbing() {
        return scrubbing;
    }

    protected void onScrubStop(boolean canceled) {
        if (!canceled && mController != null && mController.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) mController.play();
        setScrubbing(false);
        hideSeekPreview();
    }

    private void setScrubbing(boolean scrubbing) {
        if (this.scrubbing == scrubbing) return;
        this.scrubbing = scrubbing;
        if (!scrubbing) hideSeekPreview();
        onScrubbingChanged(scrubbing);
    }

    protected void onScrubbingChanged(boolean scrubbing) {
    }

    private void updateKeyIncrement() {
        long durationMs = mController == null ? C.TIME_UNSET : mController.getDuration();
        long incrementMs = getKeyTimeIncrementMs(durationMs);
        TimeBar timeBar = getSeekView().getTimeBar();
        timeBar.setKeyTimeIncrement(incrementMs);
        timeBar.setEnabled(canSeek(durationMs));
    }

    private boolean canSeek(long durationMs) {
        return mController != null && durationMs > 0 && mController.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
    }

    private void showSeekPreview(long positionMs) {
        if (seekPreview == null) return;
        long durationMs = mController == null ? C.TIME_UNSET : mController.getDuration();
        long boundedPositionMs = getBoundedSeekPosition(positionMs, durationMs);
        seekPreview.setText(getSeekPreviewText(boundedPositionMs, durationMs));
        seekPreview.setVisibility(View.VISIBLE);
    }

    private long getBoundedSeekPosition(long positionMs, long durationMs) {
        long boundedPositionMs = Math.max(0, positionMs);
        return durationMs > 0 ? Math.min(boundedPositionMs, durationMs) : boundedPositionMs;
    }

    private String getSeekPreviewText(long positionMs, long durationMs) {
        String position = Util.formatForHours(positionMs);
        return durationMs > 0 ? position + " / " + Util.formatForHours(durationMs) : position;
    }

    private void hideSeekPreview() {
        if (seekPreview != null) seekPreview.setVisibility(View.GONE);
    }

    private long getKeyTimeIncrementMs(long durationMs) {
        if (durationMs > TimeUnit.HOURS.toMillis(3)) {
            return TimeUnit.MINUTES.toMillis(5);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(30)) {
            return TimeUnit.MINUTES.toMillis(1);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(15)) {
            return TimeUnit.SECONDS.toMillis(30);
        } else if (durationMs > TimeUnit.MINUTES.toMillis(10)) {
            return TimeUnit.SECONDS.toMillis(15);
        } else {
            return TimeUnit.SECONDS.toMillis(10);
        }
    }

    private PendingIntent buildSessionIntent() {
        Intent intent = new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        Bundle extras = getIntent().getExtras();
        if (extras != null) intent.putExtras(extras);
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private boolean shouldReclaim() {
        return mService != null && !isOwner();
    }

    private boolean canActivate() {
        return mService != null && !isFinishing() && !isDestroyed() && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private boolean canDispatch() {
        return canActivate() && isBindingOwner();
    }

    private void claimBinding() {
        if (mService == null) return;
        mService.claimBinding(getNavigationCallback(), this::closePiP);
        mService.setSessionActivity(buildSessionIntent());
    }

    private void activateService() {
        if (!canActivate()) return;
        claimBinding();
        if (!isRedirect()) updateNavigationKey();
        dispatchPendingObservers();
        if (initialized) return;
        initialized = true;
        onServiceConnected();
        applyDanmaku();
    }

    private void closePiP() {
        if (!isInPictureInPictureMode()) return;
        detach();
        finish();
    }

    private void attachSurface(String reason) {
        MpvLogCollector.log("PlaybackActivity", "attachSurface: reason=" + reason + ", hasService=" + (mService != null) + ", viewHasPlayer=" + (getPlayerView().getPlayer() != null));
        if (mService != null && getPlayerView().getPlayer() == null) getPlayerView().setPlayer(player().getPlayer());
        applyDanmaku();
    }

    private void detachSurface(String reason) {
        MpvLogCollector.log("PlaybackActivity", "detachSurface: reason=" + reason + ", viewHasPlayer=" + (getPlayerView().getPlayer() != null));
        getPlayerView().setPlayer(null);
    }

    private void setRender(String reason) {
        int render = getPlaybackRender();
        MpvLogCollector.log("PlaybackActivity", "setRender: reason=" + reason + ", render=" + render);
        getPlayerView().setRender(render);
        detachSurface("setRender");
        attachSurface("setRender");
    }

    private void configurePlayerView() {
        PlayerView playerView = getPlayerView();
        AiSubtitleRuntime.get().attachPlayerView(playerView);
        playerView.setRender(getPlaybackRender());
        playerView.setDanmakuOkHttpClient(OkHttp.player());
        playerView.setDanmakuEnabled(DanmakuSetting.isShow());
        playerView.setDanmakuConfig(DanmakuSetting.getConfig());
        playerView.getSubtitleView().setStyle(getCaptionStyle());
        playerView.getSubtitleView().setApplyEmbeddedStyles(true);
        playerView.getSubtitleView().setApplyEmbeddedFontSizes(false);
        if (PlayerSetting.getSubtitlePosition() != 0) playerView.getSubtitleView().setBottomPosition(PlayerSetting.getSubtitlePosition());
        if (PlayerSetting.getSubtitleTextSize() != 0) playerView.getSubtitleView().setFractionalTextSize(PlayerSetting.getSubtitleTextSize());
    }

    private CaptionStyleCompat getCaptionStyle() {
        CaptioningManager manager = (CaptioningManager) getSystemService(Context.CAPTIONING_SERVICE);
        if (PlayerSetting.isCaption() && manager != null) return CaptionStyleCompat.createFromCaptionStyle(manager.getUserStyle());
        return new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
    }

    private void applyDanmaku() {
        if (mService == null || !isOwner()) return;
        getPlayerView().setDanmakuSource(player().getSelectedDanmakuUri());
    }

    private void releasePlaybackService() {
        if (mService != null) releaseService(isOwner());
        detach();
    }

    private void releaseService(boolean owner) {
        mService.removePlayerCallback(mPlayerCallback);
        if (!mService.releaseBinding(getNavigationCallback())) return;
        if (mService.hasMediaClient() || mService.hasPlayerCallback()) {
            if (owner) mService.suspend();
            mService.resetSessionActivity();
        } else if (owner) {
            mService.shutdown();
        }
    }

    private void detach() {
        AiSubtitleRuntime.get().detachPlayerView(getPlayerView());
        releaseController();
        releaseBinding();
    }

    private void releaseController() {
        if (mControllerFuture != null) MediaController.releaseFuture(mControllerFuture);
        if (mController != null) mController.removeListener(this);
        if (mController != null) getSeekView().setPlayer(null);
        mControllerFuture = null;
        mController = null;
    }

    private void releaseBinding() {
        if (!bound) return;
        bound = false;
        if (mService != null) mService.removePlayerCallback(mPlayerCallback);
        unbindService(this);
        mService = null;
    }

    private void clearForeverObservers() {
        foreverObserverRemovers.forEach(Runnable::run);
        foreverObserverRemovers.clear();
        serviceReadyObservers.clear();
    }

    private void dispatchPendingObservers() {
        if (!canDispatch()) return;
        serviceReadyObservers.forEach(ServiceReadyObserver::dispatch);
    }

    private void pausePlayback() {
        if (mController != null) mController.pause();
        else if (mService != null && !player().isReleased()) player().pause();
    }

    private final PlaybackService.PlayerCallback mPlayerCallback = new PlaybackService.PlayerCallback() {

        @Override
        public void onPrepare() {
            if (canDispatch() && isOwner()) PlaybackActivity.this.onPrepare();
        }

        @Override
        public void onTracksChanged() {
            if (canDispatch() && isOwner()) {
                AiSubtitleRuntime.get().onTracksChanged(player().getCurrentTracks());
                PlaybackActivity.this.onTracksChanged();
            }
        }

        @Override
        public void onDecodeChanged() {
            if (canDispatch() && isOwner()) PlaybackActivity.this.onDecodeChanged();
        }

        @Override
        public void onMediaOptionsChanged() {
            if (canDispatch() && isOwner()) PlaybackActivity.this.onMediaOptionsChanged();
        }

        @Override
        public void onError(String msg) {
            if (canDispatch() && isOwner()) PlaybackActivity.this.onError(msg);
        }

        @Override
        public void onPlayerRebuild(Player player) {
            boolean owner = canDispatch() && isOwner();
            MpvLogCollector.log("PlaybackActivity", "onPlayerRebuild: isOwner=" + owner + ", player=" + player.getClass().getSimpleName());
            if (owner) setRender("onPlayerRebuild");
        }

        @Override
        public void onDanmakuSourceChanged(Uri uri) {
            if (canDispatch() && isOwner()) getPlayerView().setDanmakuSource(uri);
        }

        @Override
        public void onDanmakuConfigChanged(DanmakuConfig config) {
            if (canDispatch() && isOwner()) getPlayerView().setDanmakuConfig(config);
        }

        @Override
        public void onDanmakuEnabledChanged(boolean enabled) {
            if (canDispatch() && isOwner()) getPlayerView().setDanmakuEnabled(enabled);
        }

        @Override
        public void onDanmakuSent(String text) {
            if (canDispatch() && isOwner()) getPlayerView().sendDanmaku(text);
        }
    };

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        configurePlayerView();
        bindPlaybackService();
        addSeekListener();
    }

    @Override
    public void onEvents(@NonNull Player player, @NonNull Player.Events events) {
        if (!canDispatch() || !isOwner()) return;
        if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) updateKeyIncrement();
        if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) AiSubtitleRuntime.get().onPlaybackPositionDiscontinuity();
    }

    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!canDispatch() || !isOwner()) return;
        if (isPlaying) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else if (!isBuffering()) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        onPlayingChanged(isPlaying);
    }

    @Override
    public void onPlaybackStateChanged(int state) {
        if (canDispatch() && isOwner()) onStateChanged(state);
    }

    @Override
    public void onVideoSizeChanged(@NonNull VideoSize size) {
        if (canDispatch() && isOwner()) onSizeChanged(size);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mService = ((PlaybackService.LocalBinder) binder).getService();
        mService.addPlayerCallback(mPlayerCallback);
        activateService();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        initialized = false;
        mService = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        activateService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        claimBinding();
        setRedirect(false);
        dispatchPendingObservers();
        MpvLogCollector.log("PlaybackActivity", "onResume: shouldReclaim=" + shouldReclaim() + ", isOwner=" + isOwner());
        if (shouldReclaim()) {
            detachSurface("onResume:reclaim");
            onReclaim();
        } else {
            attachSurface("onResume");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isRedirect()) pausePlayback();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (isBindingOwner() && isOwner() && (isFinishing() || PlayerSetting.isBackgroundOff())) pausePlayback();
    }

    @Override
    protected void onDestroy() {
        clearForeverObservers();
        super.onDestroy();
        releasePlaybackService();
    }

    private final class ServiceReadyObserver<T> implements Observer<T> {

        private final Observer<T> observer;
        private T pendingValue;
        private boolean pending;

        private ServiceReadyObserver(Observer<T> observer) {
            this.observer = observer;
        }

        @Override
        public void onChanged(T value) {
            if (canDispatch()) deliver(value);
            else {
                pendingValue = value;
                pending = true;
            }
        }

        private void deliver(T value) {
            pendingValue = null;
            pending = false;
            observer.onChanged(value);
        }

        private void dispatch() {
            if (pending) deliver(pendingValue);
        }
    }
}
