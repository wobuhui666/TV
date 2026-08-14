package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.databinding.ActivityCastBinding;
import com.fongmi.android.tv.dlna.CastAction;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.JetStreamVodControlView;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Traffic;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jupnp.support.contentdirectory.DIDLParser;

public class CastActivity extends PlaybackActivity implements CustomKeyDownVod.Listener, TrackDialog.Listener {

    private ActivityCastBinding mBinding;
    private DLNARendererService mRenderer;
    private CustomKeyDownVod mKeyDown;
    private String mPlaybackKey;
    private CastAction mAction;
    private Runnable mR1;
    private Runnable mR2;
    private Clock mClock;
    private boolean bound;
    private long position;
    private int scale;

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCastBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected String getPlaybackKey() {
        return mPlaybackKey;
    }

    @Override
    protected PlayerView getPlayerView() {
        return mBinding.player;
    }

    @Override
    protected PlayerSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        mBinding.control.action.decode.setText(player().getDecodeText());
        mBinding.control.action.speed.setText(player().getSpeedText());
        syncJetStreamControl();
        setAction(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (mRenderer != null) mRenderer.setDlnaActive(true);
        if (intent.hasExtra(CastAction.KEY_EXTRA)) setAction(intent);
        else finish();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        bound = bindService(new Intent(this, DLNARendererService.class), mRendererConnection, Context.BIND_AUTO_CREATE);
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownVod.create(this);
        mKeyDown.setFull(true);
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        setVideoView();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.control.action.speed.setUpListener(this::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(this::onSpeedSub);
        mBinding.control.action.text.setUpListener(this::onSubtitleClick);
        mBinding.control.action.text.setDownListener(this::onSubtitleClick);
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
    }

    private void setVideoView() {
        setSeekNextFocusDown(R.id.jetstream);
        setScale(scale = PlayerSetting.getScale());
        setActionFocusBoundary(mBinding.control.action.getRoot());
        PlayerEngineDialog.setText(mBinding.control.action.player);
        setJetStreamControl();
    }

    private void setJetStreamControl() {
        mBinding.control.jetstream.setListener(new JetStreamVodControlView.Listener() {
            @Override
            public void onPlayPause() {
                onJetStreamPlayPause();
            }

            @Override
            public void onPrevious() {
            }

            @Override
            public void onNext() {
            }

            @Override
            public void onRepeat() {
            }

            @Override
            public void onCommand(@NonNull String key) {
                onJetStreamCommand(key);
            }

            @Override
            public void onCommandLongClick(@NonNull String key) {
            }

            @Override
            public void onSeekTo(long positionMs) {
                if (!isPlaybackReady() || player().isEmpty()) return;
                controller().seekTo(positionMs);
            }

            @Override
            public void onShowControls() {
                setR1Callback();
            }
        });
        mBinding.control.jetstream.setTransportActions(false, false, false);
    }

    private String getName() {
        try {
            return new DIDLParser().parse(mAction.getCurrentURIMetaData()).getItems().get(0).getTitle();
        } catch (Exception e) {
            return mAction.getCurrentURI();
        }
    }

    private void setAction(Intent intent) {
        mAction = intent.getParcelableExtra(CastAction.KEY_EXTRA);
        if (mAction == null) return;
        mBinding.widget.title.setText(getName());
        mBinding.widget.title.setSelected(true);
        resetMedia();
        syncJetStreamControl();
        start();
    }

    private void resetMedia() {
        hideError();
        hideControl();
        hideInfo();
        player().setSpeed(1.0f);
        player().setRepeatOne(false);
        syncJetStreamControl();
    }

    private void start() {
        mPlaybackKey = mAction.getCurrentURI();
        startPlayer(mPlaybackKey, mAction.result(), false, Constant.TIMEOUT_PLAY, buildMetadata());
    }

    private void setPlaybackMode() {
        PlayerEngineDialog.setText(mBinding.control.action.player, player());
        mBinding.control.action.decode.setText(player().getDecodeText());
        syncJetStreamControl();
    }

    private void setScale(int scale) {
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
        syncJetStreamControl();
    }

    private void onScale() {
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        scale = scale == array.length - 1 ? 0 : ++scale;
        setScale(scale);
    }

    private void onSpeed() {
        mBinding.control.action.speed.setText(player().addSpeed());
        syncJetStreamControl();
    }

    private void onSpeedAdd() {
        mBinding.control.action.speed.setText(player().addSpeed(0.25f));
        syncJetStreamControl();
    }

    private void onSpeedSub() {
        mBinding.control.action.speed.setText(player().subSpeed(0.25f));
        syncJetStreamControl();
    }

    private void onReset() {
        if (player().isEmpty()) return;
        position = player().getPosition();
        start();
    }

    private void onChoose() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player(), mBinding.widget.title.getText());
        hideControl(false);
    }

    private void onDecode() {
        if (player().isEmpty()) return;
        position = player().getPosition();
        player().toggleDecode();
        syncJetStreamControl();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl(false);
    }

    private void onToggle() {
        if (isJetStreamControlVisible()) hideControl();
        else showControl();
    }

    private void showProgress() {
        JetStreamAnimator.show(mBinding.progress.getRoot(), 0, 0, JetStreamAnimator.FOCUS_DURATION);
        App.post(mR2, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        JetStreamAnimator.hide(mBinding.progress.getRoot(), 0, 0, View.GONE, JetStreamAnimator.EXIT_DURATION);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.text.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.text.setText("");
    }

    private void showInfo() {
        if (service() == null || isJetStreamControlVisible()) return;
        showJetStreamInfo(true, true, JetStreamVodControlView.ACTION_PLAY, player().getPositionTime(0), player().getDurationTime());
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
        mBinding.control.jetstream.setInfoState(false, false, "", "", JetStreamVodControlView.ACTION_PLAY, "", "");
        updateJetStreamVisibility();
    }

    private void showControl() {
        hideInfo();
        syncJetStreamControl();
        mBinding.control.jetstream.setControlsVisible(true);
        setJetStreamOverlayVisible(true);
        App.post(() -> {
            if (canRequestFocus(mBinding.control.jetstream) && mBinding.control.jetstream.requestFocus()) return;
            if (canRequestFocus(mBinding.video)) mBinding.video.requestFocus();
        }, 25);
        setR1Callback();
    }

    private void hideControl() {
        hideControl(true);
    }

    private void hideControl(boolean restoreFocus) {
        boolean restoreVideoFocus = restoreFocus && mBinding.control.getRoot().hasFocus();
        mBinding.control.jetstream.setControlsVisible(false);
        mBinding.control.jetstream.showGroup(null);
        App.removeCallbacks(mR1);
        if (service() != null && !player().isPlaying() && isPaused()) showInfo();
        else updateJetStreamVisibility();
        if (restoreVideoFocus) requestVideoFocusLater();
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
        hideInfo();
    }

    private void showJetStreamInfo(boolean top, boolean center, String action, CharSequence position, CharSequence duration) {
        syncJetStreamControl();
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
        mBinding.control.jetstream.setInfoState(top, center, mBinding.widget.size.getText(), mBinding.widget.clock.getText(), action, position, duration);
        updateJetStreamVisibility();
    }

    private void updateJetStreamVisibility() {
        setJetStreamOverlayVisible(isJetStreamControlVisible() || isJetStreamInfoVisible());
    }

    private void setJetStreamOverlayVisible(boolean visible) {
        View root = mBinding.control.getRoot();
        if (visible) {
            root.animate().cancel();
            if (root.getVisibility() != View.VISIBLE || root.getAlpha() < 1f || root.getTranslationX() != 0f || root.getTranslationY() != 0f) {
                JetStreamAnimator.show(root, 0, 0, JetStreamAnimator.FOCUS_DURATION);
            } else {
                root.setVisibility(View.VISIBLE);
            }
        } else {
            JetStreamAnimator.hide(root, 0, 0, View.GONE, JetStreamAnimator.EXIT_DURATION);
        }
    }

    private void requestVideoFocusLater() {
        mBinding.video.post(() -> {
            if (canRequestFocus(mBinding.video)) mBinding.video.requestFocus();
        });
    }

    private void requestControlFocusLater() {
        mBinding.control.jetstream.post(() -> {
            if (canRequestFocus(mBinding.control.jetstream) && mBinding.control.jetstream.requestFocus()) return;
            if (canRequestFocus(mBinding.video)) mBinding.video.requestFocus();
        });
    }

    private boolean canRequestFocus(View view) {
        return view != null && view.isShown() && view.isEnabled();
    }

    private boolean hasFocus(View... views) {
        for (View view : views) {
            if (view != null && view.hasFocus()) return true;
        }
        return false;
    }

    private void restoreControlFocusIfHidden(boolean restoreFocus, View... views) {
        if (!restoreFocus) return;
        for (View view : views) if (canRequestFocus(view) && view.hasFocus()) return;
        requestControlFocusLater();
    }

    private boolean isJetStreamControlVisible() {
        return mBinding.control.jetstream.isControlsVisible();
    }

    private boolean isJetStreamInfoVisible() {
        return mBinding.control.jetstream.isInfoVisible();
    }

    private boolean isJetStreamCenterVisible() {
        return mBinding.control.jetstream.isCenterInfoVisible();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private void setR1Callback() {
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    private void onJetStreamPlayPause() {
        if (!isPlaybackReady()) return;
        if (player().isPlaying()) onPaused();
        else onPlay();
        syncJetStreamControl();
    }

    private void onJetStreamCommand(String key) {
        switch (key) {
            case "reset" -> onReset();
            case "subtitle" -> onSubtitleClick();
            case "text" -> onTrack(mBinding.control.action.text);
            case "audio" -> onTrack(mBinding.control.action.audio);
            case "video" -> onTrack(mBinding.control.action.video);
            case "speed" -> onSpeed();
            case "scale" -> onScale();
            case "player" -> onChoose();
            case "decode" -> onDecode();
        }
        syncJetStreamControl();
    }

    private void syncJetStreamControl() {
        if (mBinding == null) return;
        boolean owner = service() != null && isOwner();
        boolean playing = owner && player().isPlaying();
        mBinding.control.jetstream.setPlayer(controller());
        mBinding.control.jetstream.setMediaTitle(getJetStreamTitle(), getJetStreamSecondaryText(), getJetStreamTertiaryText());
        mBinding.control.jetstream.setPlaybackState(playing, false);
        mBinding.control.jetstream.setTransportActions(false, false, false);
        mBinding.control.jetstream.setTopInfoSubtitleVisible(false);
        mBinding.control.jetstream.setCommandGroup(JetStreamVodControlView.GROUP_PLAYLIST, R.drawable.ic_push_cast, getString(R.string.push), true, "reset");
        mBinding.control.jetstream.setCommandGroup(JetStreamVodControlView.GROUP_CAPTIONS, R.drawable.msr_closed_caption, getString(R.string.play_subtitle), true, "subtitle", "text", "audio", "video");
        mBinding.control.jetstream.setCommandGroup(JetStreamVodControlView.GROUP_SETTINGS, R.drawable.msr_settings, getString(R.string.setting_section_playback), true, "speed", "scale", "player", "decode");
        syncJetStreamCommands();
        updateActionFocusBoundary(mBinding.control.action.getRoot());
    }

    private void syncJetStreamCommands() {
        setJetStreamCommand("reset", mBinding.control.action.reset, true);
        setJetStreamCommand("subtitle", getString(R.string.play_subtitle), true, false);
        setJetStreamCommand("text", mBinding.control.action.text, isVisible(mBinding.control.action.text));
        setJetStreamCommand("audio", mBinding.control.action.audio, isVisible(mBinding.control.action.audio));
        setJetStreamCommand("video", mBinding.control.action.video, isVisible(mBinding.control.action.video));
        setJetStreamCommand("speed", mBinding.control.action.speed, true);
        setJetStreamCommand("scale", mBinding.control.action.scale, true);
        setJetStreamCommand("player", mBinding.control.action.player, true);
        setJetStreamCommand("decode", mBinding.control.action.decode, isVisible(mBinding.control.action.decode));
    }

    private void setJetStreamCommand(String key, TextView view, boolean visible) {
        setJetStreamCommand(key, view.getText(), visible, view.isSelected());
    }

    private void setJetStreamCommand(String key, CharSequence label, boolean visible, boolean selected) {
        mBinding.control.jetstream.setCommand(key, label, visible, selected);
    }

    private CharSequence getJetStreamTitle() {
        CharSequence title = mBinding.widget.title.getText();
        return title == null ? "" : title;
    }

    private CharSequence getJetStreamSecondaryText() {
        CharSequence size = mBinding.widget.size.getText();
        return size == null ? "" : size;
    }

    private CharSequence getJetStreamTertiaryText() {
        return "";
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.PLAYER) onReset();
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
    }

    private void setTrackVisible() {
        boolean restoreFocus = hasFocus(mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
        mBinding.control.action.text.setVisibility(player().haveTrack(C.TRACK_TYPE_TEXT) || player().isVod() ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(player().haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.video.setVisibility(player().haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
        restoreControlFocusIfHidden(restoreFocus, mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
        syncJetStreamControl();
    }

    private MediaMetadata buildMetadata() {
        return PlayerManager.buildMetadata(mBinding.widget.title.getText().toString(), "", "");
    }

    private void onPaused() {
        if (!isPlaybackReady()) return;
        controller().pause();
    }

    private void onPlay() {
        if (!isPlaybackReady()) return;
        if (isEnded()) controller().seekTo(0);
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
        hideCenter();
    }

    private void consumePendingSeek() {
        if (service() == null || player().isEmpty() || mRenderer == null) return;
        long seekMs = mRenderer.consumePendingSeekMs();
        if (seekMs >= 0) player().seekTo(seekMs);
    }

    private final ServiceConnection mRendererConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bound) return;
            mRenderer = ((DLNARendererService.LocalBinder) binder).getService();
            mRenderer.setDlnaActive(true);
            consumePendingSeek();
            syncJetStreamControl();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mRenderer = null;
        }
    };

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onStop() {
            finish();
        }
    };

    @Override
    protected void onPrepare() {
        setPosition();
        setPlaybackMode();
        consumePendingSeek();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onError(String msg) {
        if (mRenderer != null) mRenderer.notifyError();
        player().resetTrack();
        player().reset();
        player().stop();
        showError(msg);
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                break;
            case Player.STATE_READY:
                hideProgress();
                player().reset();
                break;
            case Player.STATE_ENDED:
                checkEnded();
                break;
        }
    }

    private void setPosition() {
        if (position <= 0) return;
        player().seekTo(position);
        position = 0;
    }

    private void checkEnded() {
        CastAction next = mRenderer != null ? mRenderer.consumeNext() : null;
        if (next == null) return;
        mAction = next;
        mBinding.widget.title.setText(getName());
        mBinding.widget.title.setSelected(true);
        resetMedia();
        start();
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
        syncJetStreamControl();
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        syncJetStreamControl();
        if (isPlaying) {
            hideCenter();
        } else if (isPaused()) {
            showInfo();
        }
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.player.getSubtitleView()).player(player()).show(this);
        App.post(this::hideControl, 100);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) onToggle();
        if (isJetStreamControlVisible()) setR1Callback();
        if (!isJetStreamControlVisible() && mKeyDown.hasEvent(event) && isPlaybackReady()) return mKeyDown.onKeyDown(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onSeeking(long time) {
        if (player().isEmpty()) return;
        showJetStreamInfo(true, true, time > 0 ? JetStreamVodControlView.ACTION_FORWARD : JetStreamVodControlView.ACTION_REWIND, player().getPositionTime(time), player().getDurationTime());
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (!isPlaybackReady() || player().isEmpty()) return;
        mKeyDown.reset();
        seekTo(time);
    }

    @Override
    public boolean onSpeedUp() {
        if (!isPlaybackReady() || !player().isPlaying()) return false;
        mBinding.widget.speed.setVisibility(View.VISIBLE);
        mBinding.widget.speed.startAnimation(ResUtil.getAnim(R.anim.forward));
        mBinding.control.action.speed.setText(player().setSpeed(PlayerSetting.getSpeed()));
        syncJetStreamControl();
        return true;
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.speed.clearAnimation();
        mBinding.widget.speed.setVisibility(View.GONE);
        if (!isPlaybackReady()) return;
        mBinding.control.action.speed.setText(player().setSpeed(1.0f));
        syncJetStreamControl();
    }

    @Override
    public void onKeyUp() {
        showControl();
    }

    @Override
    public void onKeyDown() {
        showControl();
    }

    @Override
    public void onKeyCenter() {
        if (!isPlaybackReady()) return;
        if (player().isPlaying()) onPaused();
        else onPlay();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        onKeyCenter();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (isJetStreamControlVisible()) {
            hideControl();
        } else if (isJetStreamCenterVisible() || isVisible(mBinding.widget.center)) {
            hideCenter();
        } else {
            super.onBackInvoked();
        }
    }

    private void releaseRenderer() {
        if (mRenderer != null) mRenderer.setDlnaActive(false);
        if (bound) unbindService(mRendererConnection);
        mRenderer = null;
        bound = false;
    }

    @Override
    protected void onDestroy() {
        mClock.release();
        releaseRenderer();
        App.removeCallbacks(mR1, mR2);
        super.onDestroy();
    }
}
