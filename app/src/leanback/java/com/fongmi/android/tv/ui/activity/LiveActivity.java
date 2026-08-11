package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ai.subtitle.AiSubtitlePlaybackUi;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Group;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityLiveBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.PassListener;
import com.fongmi.android.tv.model.LiveViewModel;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.playback.live.LivePlayRequest;
import com.fongmi.android.tv.playback.live.LivePlaybackController;
import com.fongmi.android.tv.playback.live.LivePlaybackHost;
import com.fongmi.android.tv.playback.live.LivePlaybackMedia;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.adapter.ChannelAdapter;
import com.fongmi.android.tv.ui.adapter.EpgDataAdapter;
import com.fongmi.android.tv.ui.adapter.GroupAdapter;
import com.fongmi.android.tv.ui.custom.CustomKeyDownLive;
import com.fongmi.android.tv.ui.custom.CustomLiveListView;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.JetStreamVodControlView;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.PassDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Traffic;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class LiveActivity extends PlaybackActivity implements GroupAdapter.OnClickListener, ChannelAdapter.OnClickListener, EpgDataAdapter.OnClickListener, CustomKeyDownLive.Listener, CustomLiveListView.Callback, TrackDialog.Listener, PassListener, ConfigListener, LiveListener, LivePlaybackHost {

    private ActivityLiveBinding mBinding;
    private ChannelAdapter mChannelAdapter;
    private EpgDataAdapter mEpgDataAdapter;
    private GroupAdapter mGroupAdapter;
    private LivePlaybackController mLive;
    private Observer<Result> mObserveUrl;
    private CustomKeyDownLive mKeyDown;
    private Observer<Epg> mObserveEpg;
    private LiveViewModel mViewModel;
    private String mPlaybackKey;
    private List<Group> mHides;
    private Channel mChannel;
    private View mOldView;
    private Group mGroup;
    private Runnable mR0;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Clock mClock;
    private View mFocus2;
    private long mSeekTime;
    private final Runnable mSeekRunnable = () -> seek(mSeekTime);
    private int count;

    public static void start(Context context) {
        Intent intent = new Intent(context, LiveActivity.class).putExtra("empty", LiveConfig.isEmpty());
        if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private boolean isEmpty() {
        return getIntent().getBooleanExtra("empty", true);
    }

    @Nullable
    private Group getKeep() {
        if (mGroupAdapter.getItemCount() == 0) return null;
        return mGroupAdapter.get(0);
    }

    private Live getHome() {
        return LiveConfig.get().getHome();
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLiveBinding.inflate(getLayoutInflater());
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
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
        PlaybackAction.setSpeedText(player(), mBinding.control.action.speed);
        syncJetStreamControl();
        checkLive();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownLive.create(this);
        mObserveUrl = this::onUrlObserved;
        mObserveEpg = this::setEpg;
        mHides = new ArrayList<>();
        mR0 = this::setSelected;
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        mR3 = this::hideInfo;
        mR4 = this::hideUI;
        setRecyclerView();
        setVideoView();
        setViewModel();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.group.setListener(this);
        mBinding.channel.setListener(this);
        mBinding.epgData.setListener(this);
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.aiSubtitle.setOnClickListener(view -> AiSubtitlePlaybackUi.toggle(this, mBinding.control.action.aiSubtitle, mBinding.control.action.aiLanguage));
        mBinding.control.action.aiLanguage.setOnClickListener(view -> AiSubtitlePlaybackUi.chooseLanguage(this, mBinding.control.action.aiSubtitle, mBinding.control.action.aiLanguage));
        mBinding.control.action.speed.setUpListener(this::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(this::onSpeedSub);
        mBinding.control.action.text.setUpListener(this::onSubtitleClick);
        mBinding.control.action.text.setDownListener(this::onSubtitleClick);
        mBinding.control.action.home.setOnClickListener(view -> onHome());
        mBinding.control.action.line.setOnClickListener(view -> onLine());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.config.setOnClickListener(view -> onConfig());
        mBinding.control.action.action.setOnClickListener(view -> onAction());
        mBinding.control.action.invert.setOnClickListener(view -> onInvert());
        mBinding.control.action.across.setOnClickListener(view -> onAcross());
        mBinding.control.action.change.setOnClickListener(view -> onChange());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.group.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                int safePosition = clampPosition(position, mGroupAdapter.getItemCount());
                if (safePosition != RecyclerView.NO_POSITION) onChildSelected(child, mGroup = mGroupAdapter.get(safePosition));
            }
        });
    }

    private void setRecyclerView() {
        mBinding.group.setItemAnimator(null);
        mBinding.channel.setItemAnimator(null);
        mBinding.epgData.setItemAnimator(null);
        mBinding.group.setAdapter(mGroupAdapter = new GroupAdapter(this));
        mBinding.channel.setAdapter(mChannelAdapter = new ChannelAdapter(this));
        mBinding.epgData.setAdapter(mEpgDataAdapter = new EpgDataAdapter(this));
    }

    private void setVideoView() {
        setScale(LiveSetting.getScale());
        setSeekNextFocusDown(R.id.jetstream);
        setActionFocusBoundary(mBinding.control.action.getRoot());
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.invert.setSelected(LiveSetting.isInvert());
        mBinding.control.action.across.setSelected(LiveSetting.isAcross());
        mBinding.control.action.change.setSelected(LiveSetting.isChange());
        setJetStreamControl();
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
        syncJetStreamControl();
    }

    private void setScale(int scale) {
        LiveSetting.putScale(scale);
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
        syncJetStreamControl();
    }

    private void setJetStreamControl() {
        mBinding.control.jetstream.setListener(new JetStreamVodControlView.Listener() {
            @Override
            public void onPlayPause() {
                onJetStreamPlayPause();
            }

            @Override
            public void onPrevious() {
                prevChannel();
            }

            @Override
            public void onNext() {
                nextChannel();
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
                if (controller() != null && !player().isLive()) controller().seekTo(positionMs);
            }

            @Override
            public void onShowControls() {
                setR1Callback();
            }
        });
        mBinding.control.jetstream.setTransportActions(true, true, false);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(LiveViewModel.class);
        mLive = mViewModel.createPlaybackController(this);
        observeWhenServiceReady(mViewModel.url(), mObserveUrl);
        mViewModel.xml().observe(this, this::setEpg);
        observeWhenServiceReady(mViewModel.epg(), mObserveEpg);
        mViewModel.live().observe(this, live -> {
            mViewModel.parseXml(live);
            setGroup(live);
            setWidth(live);
        });
    }

    private void onUrlObserved(Result result) {
        if (service() == null) return;
        mLive.onUrlResult(result);
    }

    private void checkLive() {
        if (isEmpty()) {
            LiveConfig.get().init().load(getCallback());
        } else {
            getLive();
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                getLive();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private void getLive() {
        mBinding.control.action.home.setText(LiveConfig.isOnly() ? getString(R.string.live_refresh) : getHome().getName());
        syncJetStreamControl();
        mViewModel.parse(getHome());
        showProgress();
    }

    private void setGroup(Live live) {
        List<Group> items = new ArrayList<>();
        for (Group group : live.getGroups()) (group.isHidden() ? mHides : items).add(group);
        mGroupAdapter.addAll(items);
        setPosition(LiveConfig.get().findKeepPosition(items));
    }

    private void setWidth(Live live) {
        int padding = ResUtil.dp2px(52);
        if (live.getWidth() == 0) for (Group item : live.getGroups()) live.setWidth(Math.max(live.getWidth(), ResUtil.getTextWidth(item.getName(), 16)));
        int width = live.getWidth() == 0 ? 0 : Math.min(live.getWidth() + padding, ResUtil.getScreenWidth() / 4);
        setWidth(mBinding.group, width);
    }

    private Group setWidth(Group group) {
        int logo = ResUtil.dp2px(60);
        int padding = ResUtil.dp2px(64);
        if (group.isKeep()) group.setWidth(0);
        if (group.getWidth() == 0) for (Channel item : group.getChannel()) group.setWidth(Math.max(group.getWidth(), (item.getLogo().isEmpty() ? 0 : logo) + ResUtil.getTextWidth(item.getNumber() + item.getName(), 16)));
        int width = group.getWidth() == 0 ? 0 : Math.min(group.getWidth() + padding, ResUtil.getScreenWidth() / 2);
        setWidth(mBinding.channel, width);
        return group;
    }

    private void setWidth(Epg epg) {
        int padding = ResUtil.dp2px(52);
        if (epg.getList().isEmpty()) return;
        int minWidth = ResUtil.getTextWidth(epg.getList().get(0).getTime(), 14);
        if (epg.getWidth() == 0) for (EpgData item : epg.getList()) epg.setWidth(Math.max(epg.getWidth(), ResUtil.getTextWidth(item.getTitle(), 16)));
        int maxWidth = ResUtil.getScreenWidth() / 2;
        int minContentWidth = Math.min(minWidth + padding, maxWidth);
        int width = epg.getWidth() == 0 ? 0 : Math.clamp(epg.getWidth() + padding, minContentWidth, maxWidth);
        setWidth(mBinding.epgData, width);
    }

    private void setWidth(View view, int width) {
        view.post(() -> {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params.width == width) return;
            params.width = width;
            view.setLayoutParams(params);
        });
    }

    private void setPosition(int[] position) {
        if (position == null || position.length < 2 || position[0] == -1) return;
        int groupPosition = clampPosition(position[0], mGroupAdapter.getItemCount());
        if (groupPosition == RecyclerView.NO_POSITION) return;
        mGroup = mGroupAdapter.get(groupPosition);
        int channelPosition = clampPosition(position[1], mGroup.getChannel().size());
        if (channelPosition != RecyclerView.NO_POSITION) mGroup.setPosition(channelPosition);
        mLive.selectGroup(mGroup);
        if (channelPosition == RecyclerView.NO_POSITION) return;
        mLive.selectChannel(mGroup.current());
    }

    private void setPosition() {
        if (mChannel == null) return;
        mGroup = mChannel.getGroup();
        int position = mGroupAdapter.indexOf(mGroup);
        if (position == -1) return;
        int channelPosition = clampPosition(mGroup.getPosition(), mGroup.getChannel().size());
        if (channelPosition == RecyclerView.NO_POSITION) return;
        mGroup.setPosition(channelPosition);
        boolean change = mBinding.group.getSelectedPosition() != position;
        if (change) mBinding.group.setSelectedPosition(position);
        if (change) mChannelAdapter.addAll(mGroup.getChannel());
        mBinding.channel.setSelectedPosition(channelPosition);
    }

    private int clampPosition(int position, int size) {
        if (size <= 0) return RecyclerView.NO_POSITION;
        if (position < 0) return 0;
        return Math.min(position, size - 1);
    }

    private void requestFocusLater(View view) {
        view.post(() -> {
            if (canRequestFocus(view) && view.requestFocus()) return;
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
        requestFocusLater(mBinding.control.jetstream);
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child, Group group) {
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        onItemClick(group);
        resetPass();
    }

    private void setSelected() {
        mChannelAdapter.setSelected(mChannel);
        notifyItemChanged(mBinding.channel, mChannelAdapter);
    }

    private void setSelected(EpgData item) {
        mEpgDataAdapter.setSelected(item);
        notifyItemChanged(mBinding.epgData, mEpgDataAdapter);
    }

    private void checkPlay() {
        if (!isPlaybackReady()) return;
        if (player().isPlaying()) onPaused();
        else onPlay();
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl(false);
    }

    private void onHome() {
        if (LiveConfig.isOnly()) {
            setLive(getHome());
            hideControl();
        } else {
            LiveDialog.create().show(this);
            hideControl(false);
        }
    }

    private void onLine() {
        nextLine(false);
        syncJetStreamControl();
    }

    private void onScale() {
        int index = LiveSetting.getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        PlaybackAction.addSpeed(player(), mBinding.control.action.speed);
        syncJetStreamControl();
    }

    private void onSpeedAdd() {
        PlaybackAction.addSpeed(player(), mBinding.control.action.speed, 0.25f);
        syncJetStreamControl();
    }

    private void onSpeedSub() {
        PlaybackAction.subSpeed(player(), mBinding.control.action.speed, 0.25f);
        syncJetStreamControl();
    }

    private void onConfig() {
        HistoryDialog.create().live().readOnly().show(this);
        hideControl(false);
    }

    private void onAction() {
        checkPlay();
    }

    private void onInvert() {
        LiveSetting.putInvert(!LiveSetting.isInvert());
        mBinding.control.action.invert.setSelected(LiveSetting.isInvert());
        syncJetStreamControl();
    }

    private void onAcross() {
        LiveSetting.putAcross(!LiveSetting.isAcross());
        mBinding.control.action.across.setSelected(LiveSetting.isAcross());
        syncJetStreamControl();
    }

    private void onChange() {
        LiveSetting.putChange(!LiveSetting.isChange());
        mBinding.control.action.change.setSelected(LiveSetting.isChange());
        syncJetStreamControl();
    }

    private void onChoose() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player(), mBinding.widget.title.getText());
        hideControl(false);
    }

    private void onDecode() {
        PlaybackAction.toggleDecode(player());
        syncJetStreamControl();
    }

    private void hideUI() {
        App.removeCallbacks(mR4);
        if (isGone(mBinding.recycler)) return;
        JetStreamAnimator.hide(mBinding.recycler, -18, 0, View.GONE, JetStreamAnimator.PANEL_DURATION);
        setPosition();
        requestFocusLater(mBinding.video);
    }

    private void showUI() {
        if (isVisible(mBinding.recycler) || mGroupAdapter.getItemCount() == 0) return;
        JetStreamAnimator.show(mBinding.recycler, -18, 0, JetStreamAnimator.PANEL_DURATION);
        setPosition();
        setUITimer();
        hideEpg();
        requestFocusLater(mBinding.channel);
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            mLive.nextChannel();
        }

        @Override
        public void onPrev() {
            mLive.prevChannel();
        }

        @Override
        public void onStop() {
            finish();
        }
    };

    @Override
    protected void onPrepare() {
        setPlaybackMode();
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
        mLive.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        mLive.reclaim(player().getPosition());
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
                mLive.playbackEnded();
                updatePlayControl(false);
                break;
        }
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying || isPaused()) updatePlayControl(isPlaying);
        if (isPlaying && isJetStreamControlVisible()) hideControl();
        syncJetStreamControl();
    }

    private void updatePlayControl(boolean isPlaying) {
        mBinding.control.action.action.setText(isPlaying ? R.string.pause : R.string.play);
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
        syncJetStreamControl();
    }

    @Override
    public void showEpg(Channel item) {
        if (mChannel == null || mChannel.getData(mViewModel.getZoneId()).getList().isEmpty() || mEpgDataAdapter.getItemCount() == 0 || !mChannel.equals(item) || !mChannel.getGroup().equals(mGroup)) return;
        int position = clampPosition(mChannel.getData(mViewModel.getZoneId()).getSelected(), mEpgDataAdapter.getItemCount());
        if (position == RecyclerView.NO_POSITION) return;
        mBinding.epgData.setSelectedPosition(position);
        mBinding.epgData.setVisibility(View.VISIBLE);
        mBinding.channel.setVisibility(View.GONE);
        mBinding.group.setVisibility(View.GONE);
        requestFocusLater(mBinding.epgData);
    }

    @Override
    public void hideEpg() {
        mBinding.channel.setVisibility(View.VISIBLE);
        mBinding.group.setVisibility(View.VISIBLE);
        mBinding.epgData.setVisibility(View.GONE);
        requestFocusLater(mBinding.channel);
    }

    @Override
    public void showProgress() {
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

    private void showControl(View view) {
        hideInfo();
        syncJetStreamControl();
        mBinding.control.jetstream.setControlsVisible(true);
        setJetStreamOverlayVisible(true);
        App.post(() -> {
            View focus = getJetStreamFocus(view);
            if (canRequestFocus(focus) && focus.requestFocus()) return;
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
        updateJetStreamVisibility();
        if (restoreVideoFocus) requestFocusLater(mBinding.video);
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
        mBinding.widget.center.setVisibility(View.GONE);
        if (isJetStreamCenterVisible()) hideInfo();
    }

    private void showInfo() {
        if (mChannel == null) return;
        setInfo();
        if (isJetStreamControlVisible()) return;
        showJetStreamInfo(true, false, JetStreamVodControlView.ACTION_PLAY, "", "");
        setR3Callback();
    }

    private void hideInfo() {
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
        mBinding.widget.bottom.setVisibility(View.GONE);
        mBinding.control.jetstream.setInfoState(false, false, "", "", JetStreamVodControlView.ACTION_PLAY, "", "");
        updateJetStreamVisibility();
        App.removeCallbacks(mR3);
    }

    private void showJetStreamInfo(boolean top, boolean center, String action, CharSequence position, CharSequence duration) {
        syncJetStreamControl();
        mBinding.widget.top.setVisibility(View.GONE);
        mBinding.widget.center.setVisibility(View.GONE);
        mBinding.widget.bottom.setVisibility(View.GONE);
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

    private boolean isJetStreamControlVisible() {
        return mBinding.control.jetstream.isControlsVisible();
    }

    private boolean isJetStreamInfoVisible() {
        return mBinding.control.jetstream.isInfoVisible();
    }

    private boolean isJetStreamCenterVisible() {
        return mBinding.control.jetstream.isCenterInfoVisible();
    }

    private View getJetStreamFocus(View view) {
        if (!canRequestFocus(view)) return mBinding.control.jetstream;
        return view == mBinding.control.jetstream ? view : mBinding.control.jetstream;
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private void setR1Callback() {
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    private void setR3Callback() {
        App.post(mR3, Constant.INTERVAL_HIDE);
    }

    private void onToggle() {
        if (isJetStreamControlVisible()) hideControl();
        else if (isVisible(mBinding.recycler)) hideUI();
        else showUI();
        hideInfo();
    }

    private void resetPass() {
        this.count = 0;
    }

    private void setArtwork() {
        ImgUtil.load(this, mChannel.getLogo(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.player.setDefaultArtwork(resource);
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.player.setDefaultArtwork(errorDrawable);
            }
        });
    }

    @Override
    public void onItemClick(Group item) {
        mLive.selectGroup(item);
        if (!item.isKeep() || ++count < 5 || mHides.isEmpty()) return;
        PassDialog.create().show(this);
        App.removeCallbacks(mR4);
        resetPass();
    }

    @Override
    public void onItemClick(Channel item) {
        if (!item.getData(mViewModel.getZoneId()).getList().isEmpty() && item.isSelected() && mChannel != null && mChannel.equals(item) && mChannel.getGroup().equals(mGroup)) {
            showEpg(item);
        } else if (mGroup != null) {
            int position = mChannelAdapter.indexOf(item);
            if (position == -1) position = clampPosition(mBinding.channel.getSelectedPosition(), mChannelAdapter.getItemCount());
            if (position == RecyclerView.NO_POSITION) return;
            mGroup.setPosition(position);
            mLive.selectChannel(item.group(mGroup));
            hideUI();
        }
    }

    @Override
    public boolean onLongClick(Channel item) {
        if (mGroup == null || mGroup.isHidden()) return false;
        boolean exist = Keep.exist(item.getName());
        Notify.show(exist ? R.string.keep_del : R.string.keep_add);
        if (exist) delKeep(item);
        else addKeep(item);
        return true;
    }

    @Override
    public void onItemClick(EpgData item) {
        mLive.selectEpg(item, player().getPosition());
    }

    private void addKeep(Channel item) {
        Group keep = getKeep();
        if (keep == null) return;
        keep.add(item);
        Keep record = new Keep();
        record.setKey(item.getName());
        record.setType(1);
        record.save();
    }

    private void delKeep(Channel item) {
        int position = mChannelAdapter.indexOf(item);
        boolean restoreFocus = mBinding.channel.hasFocus();
        if (mGroup.isKeep()) {
            mChannelAdapter.remove(item);
            int target = clampPosition(position, mChannelAdapter.getItemCount());
            if (target == RecyclerView.NO_POSITION) {
                requestFocusLater(mBinding.group);
            } else {
                mGroup.setPosition(target);
                mBinding.channel.setSelectedPosition(target);
                if (restoreFocus) requestFocusLater(mBinding.channel);
            }
        }
        Group keep = getKeep();
        if (keep != null) keep.getChannel().remove(item);
        Keep.delete(item.getName());
    }

    private void setInfo() {
        if (mChannel == null) return;
        mViewModel.getEpg(mChannel);
        mBinding.widget.play.setText("");
        mBinding.widget.name.setMaxEms(48);
        mChannel.loadLogo(mBinding.widget.logo);
        mBinding.widget.title.setSelected(true);
        mBinding.widget.line.setText(mChannel.getLine());
        mBinding.widget.name.setText(mChannel.getShow());
        mBinding.widget.title.setText(mChannel.getShow());
        mBinding.control.action.line.setText(mChannel.getLine());
        mBinding.widget.number.setText(mChannel.getNumber());
        boolean restoreFocus = hasFocus(mBinding.control.action.line);
        mBinding.widget.line.setVisibility(mChannel.getLineVisible());
        mBinding.control.action.line.setVisibility(mChannel.getLineVisible());
        restoreControlFocusIfHidden(restoreFocus, mBinding.control.action.line);
        syncJetStreamControl();
    }

    private void setEpg(Epg epg) {
        if (mChannel == null || !mChannel.getTvgId().equals(epg.getKey())) return;
        EpgData data = epg.getEpgData();
        boolean hasTitle = !data.getTitle().isEmpty();
        mEpgDataAdapter.addAll(epg.getList());
        if (hasTitle) mBinding.widget.title.setText(getString(R.string.detail_title, mChannel.getShow(), data.getTitle()));
        mBinding.widget.name.setMaxEms(hasTitle ? 12 : 48);
        mBinding.widget.play.setText(data.format());
        setWidth(epg);
        setMetadata();
        syncJetStreamControl();
    }

    private void setEpg(boolean success) {
        if (mChannel != null && success) mViewModel.getEpg(mChannel);
    }

    private void start(Result result, long startPositionMs) {
        mPlaybackKey = result.getRealUrl();
        startPlayer(mPlaybackKey, result, false, getHome().getTimeout(), startPositionMs, buildMetadata());
    }

    private void stopPlayer() {
        player().clear();
        player().stop();
    }

    @Override
    public int getGroupCount() {
        return mGroupAdapter.getItemCount();
    }

    @Override
    public int getGroupPosition() {
        return clampPosition(mBinding.group.getSelectedPosition(), mGroupAdapter.getItemCount());
    }

    @Override
    @Nullable
    public Group getGroup(int position) {
        int safePosition = clampPosition(position, mGroupAdapter.getItemCount());
        return safePosition == RecyclerView.NO_POSITION ? null : mGroupAdapter.get(safePosition);
    }

    @Override
    public boolean isPlayerLive() {
        return player().isLive();
    }

    @Override
    public ZoneId getZoneId() {
        return mViewModel.getZoneId();
    }

    @Override
    public void requestUrl(LivePlayRequest request) {
        mViewModel.getUrl(request.getChannel(), request.getPosition());
    }

    @Override
    public void requestCatchupUrl(LivePlayRequest request) {
        mViewModel.getUrl(request.getChannel(), request.getCatchupData(), request.getPosition());
        hideUI();
    }

    @Override
    public void stopPlaybackForRefresh() {
        stopPlayer();
    }

    @Override
    public void startPlayback(Result result, long position, Channel channel) {
        start(result, position);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        PlaybackReset.afterError(player());
        showError(msg);
    }

    @Override
    public void renderGroupSelection(Group group) {
        int position = mGroupAdapter.indexOf(group);
        if (position == -1) return;
        mGroup = group;
        mBinding.group.setSelectedPosition(position);
    }

    @Override
    public void renderGroupChannels(Group group) {
        mChannelAdapter.addAll(setWidth(group).getChannel());
        int position = clampPosition(group.getPosition(), mChannelAdapter.getItemCount());
        if (position == RecyclerView.NO_POSITION) return;
        group.setPosition(position);
        mBinding.channel.setSelectedPosition(position);
    }

    @Override
    public void renderChannelSelection(Channel channel) {
        App.post(mR0, 100);
        mChannel = channel;
        setArtwork();
        showInfo();
    }

    @Override
    public void renderLineSelection(Channel channel, boolean show) {
        if (show) showInfo();
        else setInfo();
    }

    @Override
    public void renderEpgSelection(Channel channel, EpgData data) {
        setSelected(data);
    }

    @Override
    public void showCatchupReady(Channel channel, EpgData data) {
        mBinding.widget.title.setText(getString(R.string.detail_title, channel.getShow(), data.getTitle()));
        Notify.show(getString(R.string.play_ready, data.getTitle()));
    }

    private void resetAdapter() {
        boolean restoreFocus = mBinding.recycler.hasFocus() || mBinding.epgData.hasFocus() || mBinding.channel.hasFocus() || mBinding.group.hasFocus();
        boolean restoreControlFocus = hasFocus(mBinding.control.action.line);
        mBinding.control.action.line.setVisibility(View.GONE);
        restoreControlFocusIfHidden(restoreControlFocus, mBinding.control.action.line);
        mBinding.widget.title.setText("");
        mBinding.widget.play.setText("");
        mEpgDataAdapter.clear();
        mChannelAdapter.clear();
        mGroupAdapter.clear();
        mHides.clear();
        mChannel = null;
        mGroup = null;
        syncJetStreamControl();
        if (restoreFocus) requestFocusLater(mBinding.video);
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.player.getSubtitleView()).player(player()).show(this);
        App.post(this::hideControl, 100);
    }

    @Override
    public void setConfig(Config config) {
        Config current = LiveConfig.get().getConfig();
        LiveConfig.load(config, getCallback(current));
    }

    private Callback getCallback(Config config) {
        return new Callback() {
            @Override
            public void start() {
                showProgress();
            }

            @Override
            public void success() {
                setLive(getHome());
            }

            @Override
            public void error(String msg) {
                LiveConfig.load(config, new Callback());
                Notify.show(msg);
                hideProgress();
            }
        };
    }

    @Override
    public void setLive(Live item) {
        if (item.isSelected()) item.getGroups().clear();
        LiveConfig.get().setHome(item);
        player().reset();
        player().clear();
        player().stop();
        resetAdapter();
        hideControl();
        mLive.reset();
        getLive();
    }

    @Override
    public void setPass(String pass) {
        unlock(pass);
    }

    private void unlock(String pass) {
        boolean first = true;
        int position = mGroupAdapter.getItemCount();
        Iterator<Group> iterator = mHides.iterator();
        while (iterator.hasNext()) {
            Group item = iterator.next();
            if (pass != null && !pass.equals(item.getPass())) continue;
            mGroupAdapter.add(mGroupAdapter.getItemCount(), item);
            if (first) mBinding.group.setSelectedPosition(position);
            if (first) onItemClick(mGroup = item);
            iterator.remove();
            first = false;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case LIVE -> setLive(getHome());
            case PLAYER -> mLive.refresh(player().getPosition());
        }
    }

    private void setTrackVisible() {
        boolean restoreFocus = hasFocus(mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video, mBinding.control.action.speed);
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video, mBinding.control.action.speed);
        restoreControlFocusIfHidden(restoreFocus, mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video, mBinding.control.action.speed);
        syncJetStreamControl();
    }

    private MediaMetadata buildMetadata() {
        return LivePlaybackMedia.metadata(mChannel, mBinding.widget.play.getText());
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    private void onJetStreamPlayPause() {
        checkPlay();
        syncJetStreamControl();
    }

    private void onJetStreamCommand(String key) {
        switch (key) {
            case "home" -> onHome();
            case "line" -> onLine();
            case "config" -> onConfig();
            case "subtitle" -> onSubtitleClick();
            case "text" -> onTrack(mBinding.control.action.text);
            case "audio" -> onTrack(mBinding.control.action.audio);
            case "video" -> onTrack(mBinding.control.action.video);
            case "speed" -> onSpeed();
            case "scale" -> onScale();
            case "player" -> onChoose();
            case "decode" -> onDecode();
            case "invert" -> onInvert();
            case "across" -> onAcross();
            case "change" -> onChange();
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
        mBinding.control.jetstream.setTransportActions(true, true, false);
        mBinding.control.jetstream.setTopInfoSubtitleVisible(true);
        mBinding.control.jetstream.setCommandGroup(JetStreamVodControlView.GROUP_PLAYLIST, R.drawable.msr_live_tv, getString(R.string.setting_live), true, "home", "line", "config");
        mBinding.control.jetstream.setCommandGroup(JetStreamVodControlView.GROUP_CAPTIONS, R.drawable.msr_closed_caption, getString(R.string.play_subtitle), true, "subtitle", "text", "audio", "video");
        mBinding.control.jetstream.setCommandGroup(JetStreamVodControlView.GROUP_SETTINGS, R.drawable.msr_settings, getString(R.string.setting_section_playback), true, "speed", "scale", "player", "decode", "invert", "across", "change");
        syncJetStreamCommands();
        updateActionFocusBoundary(mBinding.control.action.getRoot());
    }

    private void syncJetStreamCommands() {
        setJetStreamCommand("home", mBinding.control.action.home, true);
        setJetStreamCommand("line", mBinding.control.action.line, isVisible(mBinding.control.action.line));
        setJetStreamCommand("config", mBinding.control.action.config, true);
        setJetStreamCommand("subtitle", getString(R.string.play_subtitle), true, false);
        setJetStreamCommand("text", mBinding.control.action.text, isVisible(mBinding.control.action.text));
        setJetStreamCommand("audio", mBinding.control.action.audio, isVisible(mBinding.control.action.audio));
        setJetStreamCommand("video", mBinding.control.action.video, isVisible(mBinding.control.action.video));
        setJetStreamCommand("speed", mBinding.control.action.speed, true);
        setJetStreamCommand("scale", mBinding.control.action.scale, true);
        setJetStreamCommand("player", mBinding.control.action.player, true);
        setJetStreamCommand("decode", mBinding.control.action.decode, isVisible(mBinding.control.action.decode));
        setJetStreamCommand("invert", mBinding.control.action.invert, true);
        setJetStreamCommand("across", mBinding.control.action.across, true);
        setJetStreamCommand("change", mBinding.control.action.change, true);
    }

    private void setJetStreamCommand(String key, TextView view, boolean visible) {
        setJetStreamCommand(key, view.getText(), visible, view.isSelected());
    }

    private void setJetStreamCommand(String key, CharSequence label, boolean visible, boolean selected) {
        mBinding.control.jetstream.setCommand(key, label, visible, selected);
    }

    private CharSequence getJetStreamTitle() {
        CharSequence title = mBinding.widget.title.getText();
        if (title != null && title.length() > 0) return title;
        return mChannel == null ? "" : mChannel.getShow();
    }

    private CharSequence getJetStreamSecondaryText() {
        CharSequence play = mBinding.widget.play.getText();
        if (play != null && play.length() > 0) return play;
        return mChannel == null ? "" : mChannel.getNumber();
    }

    private CharSequence getJetStreamTertiaryText() {
        return mChannel == null ? "" : mChannel.getLine();
    }

    private void prevChannel() {
        mLive.prevChannel();
    }

    private void nextChannel() {
        mLive.nextChannel();
    }

    private void prevLine() {
        mLive.prevLine();
    }

    private void nextLine(boolean show) {
        mLive.nextLine(show);
    }

    private void seek(long time) {
        if (!isPlaybackReady()) return;
        mKeyDown.reset();
        seekTo(time);
    }

    private void onPaused() {
        if (!isPlaybackReady()) return;
        controller().pause();
    }

    private void onPlay() {
        if (!isPlaybackReady()) return;
        controller().play();
    }

    private View getFocus2() {
        return canRequestFocus(mFocus2) ? getJetStreamFocus(mFocus2) : mBinding.control.jetstream;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isJetStreamControlVisible()) setR1Callback();
        if (isJetStreamControlVisible() && mBinding.control.getRoot().hasFocus()) mFocus2 = getCurrentFocus();
        if (mKeyDown.hasEvent(event) && isPlaybackReady()) mKeyDown.onKeyDown(event);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void setUITimer() {
        App.post(mR4, Constant.INTERVAL_HIDE);
    }

    @Override
    public boolean dispatch(boolean check) {
        return !check || isGone(mBinding.recycler) && !isJetStreamControlVisible();
    }

    @Override
    public void onShow(String number) {
        mBinding.widget.digital.setText(number);
        mBinding.widget.digital.setVisibility(View.VISIBLE);
    }

    @Override
    public void onFind(String number) {
        mBinding.widget.digital.setVisibility(View.GONE);
        setPosition(LiveConfig.get().findByChannelNumber(number, mGroupAdapter.unmodifiableList()));
    }

    @Override
    public void onSeeking(long time) {
        if (player().isLive()) return;
        showJetStreamInfo(false, true, time > 0 ? JetStreamVodControlView.ACTION_FORWARD : JetStreamVodControlView.ACTION_REWIND, player().getPositionTime(time), player().getDurationTime());
        setR3Callback();
        hideProgress();
    }

    @Override
    public void onKeyUp() {
        if (LiveSetting.isInvert()) nextChannel();
        else prevChannel();
    }

    @Override
    public void onKeyDown() {
        if (LiveSetting.isInvert()) prevChannel();
        else nextChannel();
    }

    @Override
    public void onKeyLeft(long time) {
        if (player().isLive()) prevLine();
        else postSeek(time);
    }

    @Override
    public void onKeyRight(long time) {
        if (player().isLive()) nextLine(true);
        else postSeek(time);
    }

    // 复用同一个 Runnable 让 App.post 的 removeCallbacks 去重生效，连点方向键只保留最后一次 seek。
    private void postSeek(long time) {
        mSeekTime = time;
        App.post(mSeekRunnable, 250);
    }

    @Override
    public void onKeyCenter() {
        hideInfo();
        showUI();
    }

    @Override
    public void onMenu() {
        showControl(getFocus2());
    }

    @Override
    public void onSingleTap() {
        onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isVisible(mBinding.recycler)) hideUI();
        else if (isJetStreamControlVisible()) hideControl();
        else onMenu();
    }

    @Override
    protected void onStart() {
        super.onStart();
        AiSubtitlePlaybackUi.refresh(mBinding.control.action.aiSubtitle, mBinding.control.action.aiLanguage);
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
        } else if (isJetStreamInfoVisible()) {
            hideInfo();
        } else if (isVisible(mBinding.recycler)) {
            hideUI();
        } else {
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        mClock.release();
        Source.get().exit();
        App.removeCallbacks(mR0, mR1, mR2, mR3, mR4, mSeekRunnable);
        super.onDestroy();
    }
}
