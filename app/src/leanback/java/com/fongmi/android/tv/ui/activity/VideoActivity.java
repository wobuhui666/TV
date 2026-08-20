package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityOptionsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerSeekView;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ai.subtitle.AiSubtitlePlaybackUi;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.VideoViewModel;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.custom.CustomKeyDownVod;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.JetStreamChipRow;
import com.fongmi.android.tv.ui.custom.JetStreamVideoDecor;
import com.fongmi.android.tv.ui.custom.JetStreamVodControlView;
import com.fongmi.android.tv.ui.custom.JetStreamVodDetailView;
import com.fongmi.android.tv.ui.theme.JetStreamAmbient;
import com.fongmi.android.tv.ui.dialog.ChapterDialog;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuDialog;
import com.fongmi.android.tv.ui.dialog.EditionDialog;
import com.fongmi.android.tv.ui.dialog.ParseDialog;
import com.fongmi.android.tv.ui.dialog.PlayerEngineDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.playback.PlaybackAction;
import com.fongmi.android.tv.playback.PlaybackReset;
import com.fongmi.android.tv.playback.vod.VodPlayRequest;
import com.fongmi.android.tv.playback.vod.VodPlaybackController;
import com.fongmi.android.tv.playback.vod.VodPlaybackHost;
import com.fongmi.android.tv.playback.vod.VodPlaybackMedia;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.MediaRatingHelper;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PartUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.TextFilter;
import com.fongmi.android.tv.utils.TmdbLogoHelper;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class VideoActivity extends PlaybackActivity implements VodPlaybackHost, CustomKeyDownVod.Listener, TrackDialog.Listener, ParseDialog.Listener, Clock.Callback {

    private static final String HERO_TRANSITION = "jetstream_hero";

    private ActivityVideoBinding mBinding;
    private ViewGroup.LayoutParams mFrameParams;
    private Observer<Result> mObserveDetail;
    private Observer<Result> mObservePlayer;
    private Observer<Result> mObserveSearch;
    private List<Flag> mFlagItems = new ArrayList<>();
    private List<Episode> mEpisodeItems = new ArrayList<>();
    private List<String> mArrayItems = new ArrayList<>();
    private List<Vod> mQuickItems = new ArrayList<>();
    private List<String> mPartItems = new ArrayList<>();
    private Result mQualityResult;
    private int mEpisodeSelectedPos = -1;
    private int mFlagSelectedPos = -1;
    private int mQualitySelectedPos = -1;
    private VodPlaybackController mVod;
    private CustomKeyDownVod mKeyDown;
    private VideoViewModel mViewModel;
    private History mHistory;
    private boolean fullscreen;
    private boolean useParse;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Clock mClock;
    private View mFocus1;
    private View mFocus2;
    private CharSequence mDetailTitle;
    private CharSequence mDetailTmdbRating;
    private CharSequence mDetailDoubanRating;
    private CharSequence mDetailSite;
    private CharSequence mDetailYear;
    private CharSequence mDetailArea;
    private CharSequence mDetailType;
    private CharSequence mDetailDirector;
    private CharSequence mDetailActor;
    private CharSequence mDetailRemark;
    private String mDetailContent;
    private String mTmdbLogoUrl;
    private String mTmdbLogoRequest;
    private int mRatingRequest;

    public static void push(FragmentActivity activity, String text) {
        Uri uri = UrlUtil.uri(text);
        if (FileChooser.isValid(activity, uri)) file(activity, FileChooser.getPathFromUri(uri));
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, String path) {
        if (TextUtils.isEmpty(path)) return;
        String name = new File(path).getName();
        start(activity, SiteApi.PUSH, "file://" + path, name);
    }

    public static void cast(Activity activity, History history) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic(), null, false, true);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true, false);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, false, false, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false, false, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, View poster) {
        start(activity, key, id, name, pic, null, false, false, poster);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, View poster) {
        start(activity, key, id, name, pic, mark, false, false, poster);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic, View poster) {
        start(activity, key, id, name, pic, null, true, false, poster);
    }

    public static void collect(Activity activity, String key, String id, String name, String pic, List<Vod> sources, View poster) {
        start(activity, key, id, name, pic, null, true, false, sources, poster);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast) {
        start(activity, key, id, name, pic, mark, collect, cast, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast, View poster) {
        start(activity, key, id, name, pic, mark, collect, cast, null, poster);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect, boolean cast, List<Vod> sources, View poster) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("cast", cast);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        if (sources != null && !sources.isEmpty()) intent.putParcelableArrayListExtra("sources", new ArrayList<>(sources));
        if (poster != null) {
            poster.setTransitionName(HERO_TRANSITION);
            activity.startActivity(intent, ActivityOptionsCompat.makeSceneTransitionAnimation(activity, poster, HERO_TRANSITION).toBundle());
            poster.post(() -> poster.setTransitionName(null));
        } else {
            activity.startActivity(intent);
        }
    }

    private boolean isCast() {
        return getIntent().getBooleanExtra("cast", false);
    }

    private String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    private String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    @Override
    public List<Vod> getSourceCandidates() {
        ArrayList<Vod> sources = getIntent().getParcelableArrayListExtra("sources");
        return sources == null ? new ArrayList<>() : sources;
    }

    @Override
    public String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private Episode getEpisode() {
        if (mEpisodeSelectedPos >= 0 && mEpisodeSelectedPos < mEpisodeItems.size())
            return mEpisodeItems.get(mEpisodeSelectedPos);
        return new Episode();
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : PlayerSetting.getScale();
    }

    private void setScale(int scale) {
        mVod.setScale(scale);
        mBinding.player.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
        syncJetStreamControl();
    }

    @Override
    public boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
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
        checkId();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String oldId = getId();
        super.onNewIntent(intent);
        String id = Objects.toString(intent.getStringExtra("id"), "");
        if (TextUtils.isEmpty(id) || id.equals(oldId)) return;
        saveHistory(false);
        getIntent().putExtras(intent);
        mVod.reset();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        mFrameParams = mBinding.video.getLayoutParams();
        mClock = Clock.create(mBinding.widget.clock);
        mKeyDown = CustomKeyDownVod.create(this);
        clearDetailState();
        mObserveDetail = this::onDetailObserved;
        mObservePlayer = this::onPlayerObserved;
        mObserveSearch = this::onSearchObserved;
        mR1 = this::hideControl;
        mR2 = this::updateFocus;
        mR3 = this::setTraffic;
        mR4 = this::showEmpty;
        setRecyclerView();
        setVideoView();
        setViewModel();
        checkCast();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.video.setOnClickListener(view -> onVideo());
        mBinding.video.setOnFocusChangeListener((view, hasFocus) -> {
            if (isFullscreen()) JetStreamAnimator.reset(view);
            else JetStreamAnimator.animateFocus(view, hasFocus, JetStreamAnimator.FOCUS_SCALE_VIDEO, 10);
        });
        mBinding.detail.setListener(new JetStreamVodDetailView.Listener() {
            @Override
            public void onSummary() {
                onContent();
            }

            @Override
            public void onKeep() {
                VideoActivity.this.onKeep();
            }

            @Override
            public void onChange() {
                VideoActivity.this.onChange();
            }

            @Override
            public void onFocusVideo() {
                requestFocus(mBinding.video, null);
            }

            @Override
            public void onFocusList() {
                focusFirstMediaList();
            }
        });
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.aiSubtitle.setOnClickListener(view -> AiSubtitlePlaybackUi.toggle(this, mBinding.control.action.aiSubtitle, mBinding.control.action.aiLanguage));
        mBinding.control.action.aiLanguage.setOnClickListener(view -> AiSubtitlePlaybackUi.chooseLanguage(this, mBinding.control.action.aiSubtitle, mBinding.control.action.aiLanguage));
        mBinding.control.action.speed.setUpListener(this::onSpeedAdd);
        mBinding.control.action.speed.setDownListener(this::onSpeedSub);
        mBinding.control.action.ending.setUpListener(this::onEndingAdd);
        mBinding.control.action.ending.setDownListener(this::onEndingSub);
        mBinding.control.action.opening.setUpListener(this::onOpeningAdd);
        mBinding.control.action.opening.setDownListener(this::onOpeningSub);
        mBinding.control.action.text.setUpListener(this::onSubtitleClick);
        mBinding.control.action.text.setDownListener(this::onSubtitleClick);
        mBinding.control.action.next.setOnClickListener(view -> checkNext());
        mBinding.control.action.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.replay.setOnClickListener(view -> onReplay());
        mBinding.control.action.parse.setOnClickListener(view -> onParse());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.change2.setOnClickListener(view -> onChange());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.edition.setOnClickListener(view -> onEdition());
        mBinding.control.action.chapter.setOnClickListener(view -> onChapter());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
    }

    private void setRecyclerView() {
        mBinding.flag.setOnChipClickListener(pos -> {
            if (pos >= 0 && pos < mFlagItems.size()) mVod.selectFlag(mFlagItems.get(pos));
        });
        mBinding.episode.setOnChipClickListener(pos -> {
            if (pos >= 0 && pos < mEpisodeItems.size()) {
                Episode item = mEpisodeItems.get(pos);
                if (shouldEnterFullscreen(item)) return;
                mVod.selectEpisode(item);
            }
        });
        mBinding.quality.setOnChipClickListener(pos -> {
            if (mQualityResult != null && pos >= 0 && pos < mQualityResult.getUrl().getValues().size()) {
                mQualityResult.getUrl().set(pos);
                mVod.selectQuality(mQualityResult);
            }
        });
        mBinding.array.setOnChipClickListener(pos -> {
            if (pos < 0 || pos >= mArrayItems.size()) return;
            String text = mArrayItems.get(pos);
            if (text.equals(getString(R.string.play_reverse))) onRevSort();
            else if (text.equals(getString(R.string.play_forward)) || text.equals(getString(R.string.play_backward))) {
                mVod.setRevPlay(!mHistory.isRevPlay());
                mHistory.setRevPlay(!mHistory.isRevPlay());
                Notify.show(mHistory.getRevPlayHint());
                setArrayAdapter(mEpisodeItems.size());
            } else if (mEpisodeItems.size() > 20) {
                int target = (pos - 2) * 20;
                if (target < mEpisodeItems.size()) mBinding.episode.setFocusedPosition(target);
            }
        });
        mBinding.part.setOnChipClickListener(pos -> {
            if (pos >= 0 && pos < mPartItems.size()) mVod.search(mPartItems.get(pos), false);
        });
        mBinding.quick.setOnChipClickListener(pos -> {
            if (pos >= 0 && pos < mQuickItems.size()) mVod.selectSource(mQuickItems.get(pos));
        });
    }

    private void clearDetailState() {
        mRatingRequest++;
        MediaRatingHelper.cancel();
        mDetailTitle = getName();
        mDetailTmdbRating = "";
        mDetailDoubanRating = "";
        mDetailSite = "";
        mDetailYear = "";
        mDetailArea = "";
        mDetailType = "";
        mDetailDirector = "";
        mDetailActor = "";
        mDetailRemark = "";
        mDetailContent = "";
        mTmdbLogoUrl = "";
        updateDetailView();
    }

    private void updateDetailView() {
        if (mBinding == null) return;
        mBinding.detail.setTitle(getVodName());
        mBinding.detail.setLogoUrl(mTmdbLogoUrl);
        mBinding.detail.setMetadata(mDetailTmdbRating, mDetailDoubanRating, mDetailSite, mDetailYear, mDetailArea, mDetailType, mDetailDirector, mDetailActor, mDetailRemark);
        mBinding.detail.setActions(!TextUtils.isEmpty(mDetailContent), Keep.find(getHistoryKey()) != null);
    }

    private void focusFirstMediaList() {
        for (int id : Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick)) {
            View view = findViewById(id);
            if (canRequestFocus(view)) {
                requestFocus(view, mBinding.video);
                return;
            }
        }
        requestFocus(mBinding.video, null);
    }

    private void setVideoView() {
        setSeekNextFocusDown(R.id.jetstream);
        setActionFocusBoundary(mBinding.control.action.getRoot());
        PlayerEngineDialog.setText(mBinding.control.action.player);
        mBinding.control.action.danmaku.setVisibility(DanmakuSetting.isLoad() ? View.VISIBLE : View.GONE);
        setJetStreamControl();
        applyWindowVideoStyle();
    }

    private void setPlaybackMode() {
        PlaybackAction.setPlaybackMode(player(), mBinding.control.action.player, mBinding.control.action.decode);
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
                checkPrev();
            }

            @Override
            public void onNext() {
                checkNext();
            }

            @Override
            public void onRepeat() {
                VideoActivity.this.onRepeat();
            }

            @Override
            public void onCommand(@NonNull String key) {
                onJetStreamCommand(key);
            }

            @Override
            public void onCommandLongClick(@NonNull String key) {
                onJetStreamCommandLongClick(key);
            }

            @Override
            public void onSeekTo(long positionMs) {
                if (controller() != null) controller().seekTo(positionMs);
            }

            @Override
            public void onShowControls() {
                setR1Callback();
            }
        });
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(VideoViewModel.class);
        observeForever(mViewModel.getResult(), mObserveDetail);
        observeForever(mViewModel.getPlayer(), mObservePlayer);
        observeForever(mViewModel.getSearch(), mObserveSearch);
        mVod = mViewModel.createPlaybackController(this);
    }

    private void onDetailObserved(Result result) {
        if (service() == null) return;
        mVod.onDetailResult(result);
    }

    private void onPlayerObserved(Result result) {
        if (service() == null) return;
        mVod.onPlayerResult(result);
    }

    private void onSearchObserved(Result result) {
        if (service() == null) return;
        mVod.onSearchResult(result);
    }

    @Override
    public String getVodKey() {
        return getKey();
    }

    @Override
    public String getVodId() {
        return getId();
    }

    @Override
    public String getVodName() {
        String name = Objects.toString(mDetailTitle, "");
        return name.isEmpty() ? getName() : name;
    }

    @Override
    public String getVodPic() {
        return getPic();
    }

    @Override
    public String getVodMark() {
        return getMark();
    }

    @Override
    public boolean isSiteChangeable() {
        return getSite().isChangeable();
    }

    @Override
    public boolean isHostFinishing() {
        return isFinishing() || isDestroyed();
    }

    @Override
    public boolean isPlayerEmpty() {
        return player().isEmpty();
    }

    @Override
    public boolean isFullscreenForPlayback() {
        return isFullscreen();
    }

    @Override
    public long getPlayerPosition() {
        return player().getPosition();
    }

    @Override
    public void usePushId(String id) {
        getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", id);
    }

    @Override
    public void requestDetail(String key, String id) {
        mViewModel.detailContent(key, id);
    }

    @Override
    public void requestPlayer(VodPlayRequest request) {
        mBinding.widget.title.setText(getString(R.string.detail_title, getVodName(), request.getTitle()));
        mViewModel.playerContent(request.getKey(), request.getFlag(), request.getId());
        mBinding.widget.title.setSelected(true);
        showProgress();
    }

    @Override
    public void requestSearch(List<Site> sites, String keyword) {
        mQuickItems.clear();
        mBinding.quick.setItems(new ArrayList<>(), -1);
        setRowVisibility(mBinding.quick, false);
        mViewModel.searchContent(sites, keyword, true);
    }

    @Override
    public void prepareSource(Vod item) {
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("pic", item.getPic());
        getIntent().putExtra("id", item.getId());
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        player().reset();
        player().stop();
    }

    @Override
    public void stopPlaybackForRefresh() {
        player().stop();
        player().clear();
        mClock.setCallback(null);
    }

    @Override
    public void resetPlaybackForError(String msg) {
        PlaybackReset.afterError(player(), () -> mClock.setCallback(null));
        showError(msg);
    }

    @Override
    public void replay(long position) {
        player().replay(position);
    }

    @Override
    public void startPlayback(Result result, boolean useParse, long startPositionMs, History history, Episode episode) {
        startPlayer(getHistoryKey(), result, useParse, getSite().getTimeout(), startPositionMs, VodPlaybackMedia.metadata(history, episode));
    }

    @Override
    public void loadDanmaku(Result result, History history, Episode episode) {
        VodPlaybackMedia.searchDanmaku(result, history, episode, player()::setDanmaku, player()::addDanmaku);
    }

    @Override
    public void renderDetail(Vod item, History history) {
        mHistory = history;
        mBinding.progressLayout.showContent();
        updateFullscreenViews();
        showTitleText(item.getName());
        restoreWindowVideoFocus();
        App.removeCallbacks(mR4);
        setArtwork(item.getPic());
        fetchTmdbLogo(item);
        fetchRatings(item);
        checkKeepImg();
        setText(item);
        updateKeep();
    }

    @Override
    public void renderEmptyDetail() {
        showEmpty();
    }

    @Override
    public void renderFallbackName(String name) {
        showTitleText(name);
    }

    @Override
    public void renderFlags(List<Flag> items) {
        mFlagItems = items;
        setRowVisibility(mBinding.flag, !items.isEmpty());
        List<String> texts = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < items.size(); i++) {
            texts.add(items.get(i).getShow());
            if (items.get(i).isSelected()) selected = i;
        }
        mBinding.flag.setItems(texts, selected);
        mFlagSelectedPos = selected;
    }

    @Override
    public void renderEpisodes(List<Episode> items) {
        setEpisodeAdapter(items);
    }

    @Override
    public void renderFlagSelection(Flag item) {
        int pos = mFlagItems.indexOf(item);
        mFlagSelectedPos = pos;
        mBinding.flag.setSelectedPosition(pos);
    }

    @Override
    public void renderEpisodeSelection(Episode item) {
        int pos = findEpisodePosition(item);
        mEpisodeSelectedPos = pos;
        mBinding.episode.setSelectedPosition(pos);
    }

    @Override
    public void renderReverseEpisodes(List<Episode> items, boolean scroll) {
        setEpisodeAdapter(items);
        if (scroll) mBinding.episode.setSelectedPosition(findEpisodeSelectedPosition());
    }

    @Override
    public void renderQuality(Result result, boolean visible) {
        mQualityResult = result;
        List<String> texts = new ArrayList<>();
        int selected = result.getUrl().getPosition();
        for (int i = 0; i < result.getUrl().getValues().size(); i++) {
            texts.add(result.getUrl().n(i));
        }
        mBinding.quality.setItems(texts, selected);
        setQualityVisible(visible);
    }

    @Override
    public void renderQualityVisible(boolean visible) {
        setQualityVisible(visible);
    }

    @Override
    public void renderSources(List<Vod> items) {
        mQuickItems = items;
        List<String> texts = new ArrayList<>();
        for (Vod item : items) texts.add(item.getSourceCount() > 1 ? item.getName() + " (" + item.getSourceCount() + ")" : item.getName());
        mBinding.quick.setItems(texts, -1);
        setRowVisibility(mBinding.quick, !items.isEmpty());
    }

    @Override
    public void renderHistory(History history) {
        mHistory = history;
        mBinding.control.action.opening.setText(history.getOpening() <= 0 ? getString(R.string.play_op) : Util.timeMs(history.getOpening()));
        mBinding.control.action.ending.setText(history.getEnding() <= 0 ? getString(R.string.play_ed) : Util.timeMs(history.getEnding()));
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, history.getSpeed());
        setScale(getScale());
        setPartAdapter();
        syncJetStreamControl();
    }

    @Override
    public void renderUseParse(boolean useParse) {
        setUseParse(useParse);
        boolean restoreFocus = hasFocus(mBinding.control.action.parse);
        mBinding.control.action.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
        restoreControlFocusIfHidden(restoreFocus, mBinding.control.action.parse);
        syncJetStreamControl();
    }

    @Override
    public void renderArtwork(String url) {
        setArtwork(url);
    }

    @Override
    public void renderDescription(String desc) {
        mDetailContent = desc;
        updateDetailView();
    }

    @Override
    public void onDetailFallbackScheduled() {
        App.post(mR4, 10000);
    }

    @Override
    public void onDetailFallbackCancelled() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void onSearchStarted(String keyword) {
        mBinding.part.setTag(keyword);
    }

    @Override
    public void onSearchResult() {
        App.removeCallbacks(mR4);
    }

    @Override
    public void showDetailMessage(String msg) {
        Notify.show(msg);
    }

    @Override
    public void showSwitchLine(Flag flag) {
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
    }

    @Override
    public void showSwitchSource(Vod item) {
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
    }

    @Override
    public void showEpisodeReady(Episode item) {
        Notify.show(getString(R.string.play_ready, item.getName()));
    }

    @Override
    public void showNoNext(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_prev : R.string.error_play_next);
    }

    @Override
    public void showNoPrev(boolean reversed) {
        Notify.show(reversed ? R.string.error_play_next : R.string.error_play_prev);
    }

    @Override
    public void finishVod() {
        finish();
    }

    private void checkCast() {
        if (isCast() && !isFullscreen()) enterFullscreen();
        else mBinding.progressLayout.showProgress();
    }

    private void checkId() {
        mVod.checkId();
    }

    private void showEmpty() {
        mBinding.progressLayout.showEmpty();
    }

    private void setText(Vod item) {
        mDetailContent = TextFilter.detail(item.getContent());
        mDetailYear = buildDetailText(mDetailYear, R.string.detail_year, item.getYear());
        mDetailArea = buildDetailText(mDetailArea, R.string.detail_area, item.getArea());
        mDetailType = buildDetailText(mDetailType, R.string.detail_type, item.getTypeName());
        mDetailSite = buildDetailText(mDetailSite, R.string.detail_site, getSite().getName());
        mDetailDirector = buildDetailText(mDetailDirector, R.string.detail_director, item.getDirector());
        mDetailActor = buildDetailText(mDetailActor, R.string.detail_actor, item.getActor());
        mDetailRemark = buildDetailText(mDetailRemark, 0, item.getRemarks());
        updateDetailView();
    }

    private CharSequence buildDetailText(CharSequence current, int resId, String text) {
        boolean sourceEmpty = TextUtils.isEmpty(text);
        text = TextFilter.detail(text);
        if (sourceEmpty && !TextUtils.isEmpty(current)) return current;
        if (TextUtils.isEmpty(text)) return "";
        return Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan);
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                VodActivity.start(getActivity(), getKey(), result);
                setRedirect(true);
            }
        };
    }

    private void setEpisodeAdapter(List<Episode> items) {
        mEpisodeItems = items;
        setRowVisibility(mBinding.episode, !items.isEmpty());
        List<String> texts = new ArrayList<>();
        int selected = -1;
        for (int i = 0; i < items.size(); i++) {
            Episode ep = items.get(i);
            texts.add(ep.getDesc().concat(ep.getName()));
            if (ep.isSelected()) selected = i;
        }
        mBinding.episode.setItems(texts, selected);
        mEpisodeSelectedPos = selected;
        setArrayAdapter(items.size());
        setR2Callback();
    }

    private void setQualityVisible(boolean visible) {
        setRowVisibility(mBinding.quality, visible);
        setR2Callback();
    }

    private void setArrayAdapter(int size) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.play_reverse));
        items.add(getString(mHistory.getRevPlayText()));
        setRowVisibility(mBinding.array, size > 1);
        if (mHistory.isRevSort()) for (int i = size; i > 0; i -= 20) items.add(i + "-" + Math.max(i - 19, 1));
        else for (int i = 0; i < size; i += 20) items.add((i + 1) + "-" + Math.min(i + 20, size));
        mArrayItems = items;
        mBinding.array.setItems(items, -1);
    }

    private int findEpisodePosition(Episode item) {
        for (int i = 0; i < mEpisodeItems.size(); i++) {
            if (mEpisodeItems.get(i).equals(item)) return i;
        }
        return -1;
    }

    private int findEpisodeSelectedPosition() {
        for (int i = 0; i < mEpisodeItems.size(); i++) {
            if (mEpisodeItems.get(i).isSelected()) return i;
        }
        return -1;
    }

    public void onRevSort() {
        mVod.setRevSort(!mHistory.isRevSort());
        mVod.reverseEpisode(false);
    }

    private boolean shouldEnterFullscreen(Episode item) {
        boolean enter = !isFullscreen() && item.isSelected();
        if (enter) enterFullscreen();
        return enter;
    }

    private void enterFullscreen() {
        mFocus1 = getCurrentFocus();
        if (canRequestFocus(mBinding.video)) mBinding.video.requestFocus();
        JetStreamAnimator.reset(mBinding.video);
        mBinding.video.setForeground(null);
        mBinding.video.setBackgroundColor(android.graphics.Color.BLACK);
        mBinding.video.setClipToOutline(false);
        mBinding.player.setRender(getPlaybackRender());
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        mBinding.flag.setSelectedPosition(mFlagSelectedPos);
        mKeyDown.setFull(true);
        setFullscreen(true);
        updateFullscreenViews();
        mFocus2 = null;
    }

    private void exitFullscreen() {
        mBinding.video.setForeground(JetStreamVideoDecor.windowForeground(mBinding.video));
        mBinding.video.setBackgroundResource(R.drawable.shape_video_window);
        mBinding.video.setLayoutParams(mFrameParams);
        mKeyDown.setFull(false);
        setFullscreen(false);
        updateFullscreenViews();
        applyWindowVideoStyle();
        requestFocus(getFocus1(), mBinding.video);
        mFocus2 = null;
        hideInfo();
    }

    private void applyWindowVideoStyle() {
        if (isFullscreen()) return;
        mBinding.player.setRender(PlayerSetting.RENDER_TEXTURE);
        mBinding.video.setForeground(JetStreamVideoDecor.windowForeground(mBinding.video));
        mBinding.video.setBackgroundResource(R.drawable.shape_video_window);
        mBinding.video.setClipToOutline(true);
        mBinding.video.post(() -> {
            if (isFullscreen()) return;
            mBinding.video.invalidateOutline();
            mBinding.video.postInvalidateOnAnimation();
        });
    }

    @Override
    protected int getPlaybackRender() {
        boolean mpv = service() == null ? PlayerSetting.isMpv() : player().getEngine() == PlayerSetting.ENGINE_MPV;
        return mpv ? PlayerSetting.RENDER_TEXTURE : super.getPlaybackRender();
    }

    private void restoreWindowVideoFocus() {
        mBinding.video.post(() -> {
            if (isFullscreen()) return;
            applyWindowVideoStyle();
            restoreWindowVideoVisibility();
            if (canRequestFocus(mBinding.video)) mBinding.video.requestFocus();
            mBinding.video.refreshDrawableState();
            mBinding.video.postInvalidateOnAnimation();
        });
    }

    private void restoreWindowVideoVisibility() {
        mBinding.video.setVisibility(View.VISIBLE);
        mBinding.video.setAlpha(1f);
        mBinding.video.setTranslationX(0f);
        mBinding.video.setTranslationY(0f);
    }

    private void updateFullscreenViews() {
        int visibility = isFullscreen() ? View.GONE : View.VISIBLE;
        mBinding.detail.setVisibility(visibility);
        mBinding.scroll.setVisibility(visibility);
        updateFocus();
    }

    private void onContent() {
        if (TextUtils.isEmpty(mDetailContent)) return;
        ContentDialog.create().content(mDetailContent).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
    }

    private void onVideo() {
        if (!isFullscreen()) enterFullscreen();
    }

    private void onChange() {
        mVod.manualSwitchSource();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
        syncJetStreamControl();
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
        syncJetStreamControl();
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        mVod.nextEpisode(notify);
    }

    private void checkPrev() {
        mVod.prevEpisode(true);
    }

    private void onNext(boolean notify) {
        mVod.nextEpisode(notify);
    }

    private void onPrev(boolean notify) {
        mVod.prevEpisode(notify);
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        setScale(index == array.length - 1 ? 0 : ++index);
    }

    private void onSpeed() {
        mVod.setSpeed(PlaybackAction.addSpeed(player(), mBinding.control.action.speed));
        syncJetStreamControl();
    }

    private void onSpeedAdd() {
        mVod.setSpeed(PlaybackAction.addSpeed(player(), mBinding.control.action.speed, 0.25f));
        syncJetStreamControl();
    }

    private void onSpeedSub() {
        mVod.setSpeed(PlaybackAction.subSpeed(player(), mBinding.control.action.speed, 0.25f));
        syncJetStreamControl();
    }

    private void onReset() {
        onRefresh();
    }

    private void onParse() {
        ParseDialog.create().show(this);
        hideControl(false);
    }

    private void onReplay() {
        mVod.replay();
    }

    private void onRefresh() {
        mVod.refresh();
    }

    private void onOpening() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) setOpening(position);
    }

    private void onOpeningAdd() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) + 1000));
    }

    private void onOpeningSub() {
        setOpening(Math.max(0, Math.max(0, mHistory.getOpening()) - 1000));
    }

    private boolean onOpeningReset() {
        setOpening(0);
        return true;
    }

    private void setOpening(long opening) {
        mVod.setOpening(opening);
        mBinding.control.action.opening.setText(opening <= 0 ? getString(R.string.play_op) : Util.timeMs(mHistory.getOpening()));
        syncJetStreamControl();
    }

    private void onEnding() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetEnding(position, duration)) setEnding(duration - position);
    }

    private void onEndingAdd() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) + 1000));
    }

    private void onEndingSub() {
        setEnding(Math.max(0, Math.max(0, mHistory.getEnding()) - 1000));
    }

    private boolean onEndingReset() {
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mVod.setEnding(ending);
        mBinding.control.action.ending.setText(ending <= 0 ? getString(R.string.play_ed) : Util.timeMs(mHistory.getEnding()));
        syncJetStreamControl();
    }

    private void onChoose() {
        PlayerEngineDialog.show(this, mBinding.control.action.player, player(), mBinding.widget.title.getText());
        hideControl(false);
    }

    private void onDecode() {
        mClock.setCallback(null);
        PlaybackAction.toggleDecode(player());
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl(false);
    }

    private void onEdition() {
        EditionDialog.create().player(player()).show(this);
        hideControl(false);
    }

    private void onChapter() {
        ChapterDialog.create().player(player()).show(this);
        hideControl(false);
    }

    private void onDanmaku() {
        DanmakuDialog.create().player(player()).show(this);
        hideControl(false);
    }

    private void onToggle() {
        if (isJetStreamControlVisible()) hideControl();
        else showControl(getFocus2());
    }

    private void showProgress() {
        JetStreamAnimator.show(mBinding.progress.getRoot(), 0, 0, JetStreamAnimator.FOCUS_DURATION);
        App.post(mR3, 0);
        hideCenter();
        hideError();
    }

    private void hideProgress() {
        JetStreamAnimator.hide(mBinding.progress.getRoot(), 0, 0, View.GONE, JetStreamAnimator.EXIT_DURATION);
        App.removeCallbacks(mR3);
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

    private void showControl(View view) {
        hideInfo();
        mBinding.control.jetstream.setControlsVisible(true);
        syncJetStreamControl();
        setJetStreamOverlayVisible(true);
        View focus = getJetStreamFocus(view);
        requestFocus(focus, mBinding.control.jetstream);
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
        if (isFullscreen() && service() != null && !player().isPlaying() && isPaused()) showInfo();
        else updateJetStreamVisibility();
        if (restoreVideoFocus) requestFocus(mBinding.video, null);
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

    private void setRowVisibility(View row, boolean visible) {
        if (!visible && row.hasFocus()) requestFocus(mBinding.video, null);
        row.setVisibility(visible ? View.VISIBLE : View.GONE);
        updateFocus();
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
        requestFocus(mBinding.control.jetstream, mBinding.video);
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
        if (!canRequestFocus(view) || view == mBinding.video || isLegacyControlAction(view)) return mBinding.control.jetstream;
        return view;
    }

    private void requestFocus(View target, View fallback) {
        if (target == null) return;
        target.post(() -> {
            if (canRequestFocus(target) && target.requestFocus()) return;
            if (fallback == null || fallback == target) return;
            if (canRequestFocus(fallback)) fallback.requestFocus();
        });
    }

    private boolean canRequestFocus(View view) {
        return view != null && view.isShown() && view.isEnabled();
    }

    private boolean isLegacyControlAction(View view) {
        return view == mBinding.control.action.opening || view == mBinding.control.action.ending || view == mBinding.control.action.next || view == mBinding.control.action.prev;
    }

    private void onJetStreamPlayPause() {
        if (service() == null || controller() == null) return;
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
        syncJetStreamControl();
    }

    private void onJetStreamCommand(String key) {
        switch (key) {
            case "prev" -> checkPrev();
            case "next" -> checkNext();
            case "change" -> onChange();
            case "parse" -> onParse();
            case "replay" -> onReplay();
            case "reset" -> onReset();
            case "subtitle" -> onSubtitleClick();
            case "text" -> onTrack(mBinding.control.action.text);
            case "audio" -> onTrack(mBinding.control.action.audio);
            case "video" -> onTrack(mBinding.control.action.video);
            case "danmaku" -> onDanmaku();
            case "speed" -> onSpeed();
            case "scale" -> onScale();
            case "player" -> onChoose();
            case "decode" -> onDecode();
            case "opening" -> onOpening();
            case "ending" -> onEnding();
            case "edition" -> onEdition();
            case "chapter" -> onChapter();
        }
        syncJetStreamControl();
    }

    private void onJetStreamCommandLongClick(String key) {
        switch (key) {
            case "opening" -> onOpeningReset();
            case "ending" -> onEndingReset();
        }
        syncJetStreamControl();
    }

    private void syncJetStreamControl() {
        if (mBinding == null) return;
        boolean owner = service() != null && isOwner();
        boolean playing = owner && player().isPlaying();
        boolean repeating = owner && player().isRepeatOne();
        mBinding.control.jetstream.setPlayer(controller());
        mBinding.control.jetstream.setMediaTitle(getJetStreamTitle(), getJetStreamSecondaryText(), getJetStreamTertiaryText());
        mBinding.control.jetstream.setPlaybackState(playing, repeating);
        mBinding.control.jetstream.setTopActions(true, true, true);
        syncJetStreamCommands();
        updateActionFocusBoundary(mBinding.control.action.getRoot());
    }

    private void syncJetStreamCommands() {
        setJetStreamCommand("prev", mBinding.control.action.prev, true);
        setJetStreamCommand("next", mBinding.control.action.next, true);
        setJetStreamCommand("change", mBinding.control.action.change2, true);
        setJetStreamCommand("parse", mBinding.control.action.parse, isVisible(mBinding.control.action.parse));
        setJetStreamCommand("replay", mBinding.control.action.replay, true);
        setJetStreamCommand("reset", mBinding.control.action.reset, true);
        setJetStreamCommand("subtitle", getString(R.string.play_subtitle), true, false);
        setJetStreamCommand("text", mBinding.control.action.text, isVisible(mBinding.control.action.text));
        setJetStreamCommand("audio", mBinding.control.action.audio, isVisible(mBinding.control.action.audio));
        setJetStreamCommand("video", mBinding.control.action.video, isVisible(mBinding.control.action.video));
        setJetStreamCommand("danmaku", mBinding.control.action.danmaku, isVisible(mBinding.control.action.danmaku));
        setJetStreamCommand("speed", mBinding.control.action.speed, true);
        setJetStreamCommand("scale", mBinding.control.action.scale, true);
        setJetStreamCommand("player", mBinding.control.action.player, true);
        setJetStreamCommand("decode", mBinding.control.action.decode, isVisible(mBinding.control.action.decode));
        setJetStreamCommand("opening", mBinding.control.action.opening, true);
        setJetStreamCommand("ending", mBinding.control.action.ending, true);
        setJetStreamCommand("edition", mBinding.control.action.edition, isVisible(mBinding.control.action.edition));
        setJetStreamCommand("chapter", mBinding.control.action.chapter, isVisible(mBinding.control.action.chapter));
    }

    private void setJetStreamCommand(String key, TextView view, boolean visible) {
        setJetStreamCommand(key, view.getText(), visible, view.isSelected());
    }

    private void setJetStreamCommand(String key, CharSequence label, boolean visible, boolean selected) {
        mBinding.control.jetstream.setCommand(key, label, visible, selected);
    }

    private CharSequence getJetStreamTitle() {
        CharSequence widgetTitle = mBinding.widget.title.getText();
        return TextUtils.isEmpty(widgetTitle) ? getVodName() : widgetTitle;
    }

    private CharSequence getJetStreamSecondaryText() {
        if (!TextUtils.isEmpty(mDetailRemark)) return mDetailRemark;
        return TextUtils.isEmpty(mDetailYear) ? mDetailSite : mDetailYear;
    }

    private CharSequence getJetStreamTertiaryText() {
        return TextUtils.isEmpty(mDetailDirector) ? mDetailSite : mDetailDirector;
    }

    private void hideCenter() {
        mBinding.widget.action.setImageResource(R.drawable.ic_widget_play);
        hideInfo();
    }

    private void setTraffic() {
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR3, 1000);
    }

    private void updatePausedJetStreamInfo() {
        if (isFullscreen() && service() != null && !player().isPlaying() && isPaused() && !isJetStreamControlVisible() && isJetStreamInfoVisible()) showInfo();
    }

    private void setR1Callback() {
        if (isScrubbing()) return;
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    @Override
    protected void onScrubbingChanged(boolean scrubbing) {
        if (scrubbing) App.removeCallbacks(mR1);
        else if (isJetStreamControlVisible()) setR1Callback();
    }

    private void setR2Callback() {
        App.post(mR2, 500);
    }

    private void updateFocus() {
        if (isFullscreen()) {
            mBinding.video.setNextFocusDownId(R.id.video);
            mBinding.detail.setNextFocusDownId(R.id.video);
            return;
        }
        List<View> rows = new ArrayList<>();
        for (int id : Arrays.asList(R.id.flag, R.id.quality, R.id.episode, R.id.array, R.id.part, R.id.quick)) {
            View row = findViewById(id);
            if (row != null && row.getVisibility() == View.VISIBLE && mBinding.scroll.getVisibility() == View.VISIBLE) rows.add(row);
        }
        int first = rows.isEmpty() ? R.id.video : rows.get(0).getId();
        mBinding.video.setNextFocusDownId(first);
        mBinding.detail.setNextFocusDownId(first);
        for (int i = 0; i < rows.size(); i++) {
            View row = rows.get(i);
            int up = i == 0 ? R.id.video : rows.get(i - 1).getId();
            int down = i == rows.size() - 1 ? row.getId() : rows.get(i + 1).getId();
            row.setNextFocusLeftId(row.getId());
            row.setNextFocusRightId(row.getId());
            row.setNextFocusUpId(up);
            row.setNextFocusDownId(down);
        }
    }

    private void showTitleText(String name) {
        mTmdbLogoRequest = null;
        mTmdbLogoUrl = "";
        mDetailTitle = name;
        updateDetailView();
    }

    private void fetchTmdbLogo(Vod item) {
        String apiKey = BuildConfig.TMDB_API_KEY;
        String title = item.getName();
        if (TextUtils.isEmpty(title)) return;
        String request = title + "\n" + item.getYear() + "\n" + item.getTypeName();
        mTmdbLogoRequest = request;
        TmdbLogoHelper.findLogo(apiKey, title, item.getYear(), item.getTypeName(), BuildConfig.TMDB_LOGO_SIZE, new TmdbLogoHelper.LogoCallback() {
            @Override
            public void onFound(@NonNull String logoUrl) {
                loadTmdbLogo(request, title, logoUrl);
            }

            @Override
            public void onNotFound() {
                if (isTmdbLogoRequestActive(request)) showTitleText(title);
            }

            @Override
            public void onError(@NonNull Exception error) {
                if (isTmdbLogoRequestActive(request)) showTitleText(title);
            }
        });
    }

    private void loadTmdbLogo(String request, String title, String logoUrl) {
        if (!isTmdbLogoRequestActive(request)) return;
        mDetailTitle = title;
        mTmdbLogoUrl = logoUrl;
        updateDetailView();
    }

    private boolean isTmdbLogoRequestActive(String request) {
        return !isHostFinishing() && TextUtils.equals(request, mTmdbLogoRequest);
    }

    private void fetchRatings(Vod item) {
        String title = item.getName();
        mRatingRequest++;
        MediaRatingHelper.cancel();
        mDetailTmdbRating = "";
        mDetailDoubanRating = "";
        updateDetailView();
        if (TextUtils.isEmpty(title)) return;
        int request = mRatingRequest;
        MediaRatingHelper.findTmdbRating(BuildConfig.TMDB_API_KEY, title, item.getYear(), item.getTypeName(), new MediaRatingHelper.RatingCallback() {
            @Override
            public void onFound(@NonNull MediaRatingHelper.Rating rating) {
                if (!isRatingRequestActive(request)) return;
                mDetailTmdbRating = getString(R.string.detail_rating_tmdb, rating.getText());
                updateDetailView();
            }

            @Override
            public void onNotFound() {
            }

            @Override
            public void onError(@NonNull Exception error) {
            }
        });
        MediaRatingHelper.findDoubanRating(title, item.getYear(), item.getTypeName(), new MediaRatingHelper.RatingCallback() {
            @Override
            public void onFound(@NonNull MediaRatingHelper.Rating rating) {
                if (!isRatingRequestActive(request)) return;
                mDetailDoubanRating = getString(R.string.detail_rating_douban, rating.getText());
                updateDetailView();
            }

            @Override
            public void onNotFound() {
            }

            @Override
            public void onError(@NonNull Exception error) {
            }
        });
    }

    private boolean isRatingRequestActive(int request) {
        return !isHostFinishing() && request == mRatingRequest;
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        JetStreamAmbient.push(mHistory.getVodPic());
        ImgUtil.load(this, mHistory.getVodPic(), new CustomTarget<>() {
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

    private void setPartAdapter() {
        mPartItems = PartUtil.split(mHistory.getVodName());
        mBinding.part.setItems(mPartItems, -1);
        setRowVisibility(mBinding.part, !mPartItems.isEmpty());
        setR2Callback();
    }

    private void saveHistory(boolean exit) {
        boolean owner = service() != null && isOwner();
        long position = owner ? player().getPosition() : C.TIME_UNSET;
        long duration = owner ? player().getDuration() : C.TIME_UNSET;
        if (mVod != null) mVod.saveHistory(exit, System.currentTimeMillis(), position, duration);
    }

    private void syncHistory() {
        if (mVod != null) mVod.syncHistory();
    }

    private void checkKeepImg() {
        updateDetailView();
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(mHistory.getVodPic());
        keep.setVodName(mHistory.getVodName());
        keep.setSiteName(getSite().getName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    private void updateKeep() {
        Keep keep = Keep.find(getHistoryKey());
        if (keep != null) {
            keep.setVodName(mHistory.getVodName());
            keep.setVodPic(mHistory.getVodPic());
            keep.save();
        }
    }

    private void updateVod(Vod item) {
        boolean id = !item.getId().isEmpty();
        boolean pic = !item.getPic().isEmpty();
        boolean name = !item.getName().isEmpty();
        if (id) getIntent().putExtra("id", item.getId());
        if (id) mHistory.replace(getHistoryKey());
        if (name) mHistory.setVodName(item.getName());
        if (name) showTitleText(item.getName());
        if (name) mBinding.widget.title.setText(item.getName());
        mVod.mergeFlags(item.getFlags());
        if (pic) setArtwork(item.getPic());
        if (name) fetchTmdbLogo(item);
        if (name) fetchRatings(item);
        if (pic || name) setMetadata();
        if (pic || name) syncHistory();
        if (pic || name) updateKeep();
        if (id) updateNavigationKey();
        if (name) setPartAdapter();
        setText(item);
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        setPlaybackMode();
        applyWindowVideoStyle();
    }

    @Override
    protected void onDecodeChanged() {
        setPlaybackMode();
        applyWindowVideoStyle();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
    }

    @Override
    protected void onMediaOptionsChanged() {
        setMediaOptionVisible();
    }

    @Override
    protected void onError(String msg) {
        mVod.playbackError(msg);
    }

    @Override
    protected void onReclaim() {
        mVod.reclaim(player().getPosition());
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                showProgress();
                mClock.setCallback(null);
                break;
            case Player.STATE_READY:
                hideProgress();
                player().reset();
                mClock.setCallback(this);
                applyWindowVideoStyle();
                break;
            case Player.STATE_ENDED:
                hideProgress();
                mVod.playbackEnded();
                mClock.setCallback(null);
                break;
        }
        syncJetStreamControl();
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            hideCenter();
            if (isJetStreamControlVisible()) hideControl();
        } else if (isPaused()) {
            if (isFullscreen() && !isJetStreamControlVisible()) showInfo();
            else hideInfo();
        }
        syncJetStreamControl();
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        mBinding.widget.size.setText(player().getSizeText());
        updatePausedJetStreamInfo();
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().view(mBinding.player.getSubtitleView()).player(player()).show(this);
        App.post(this::hideControl, 100);
    }

    @Override
    public void onTimeChanged(long time) {
        updatePausedJetStreamInfo();
        if (!isOwner() || !player().isVod()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position < 0 || duration <= 0) return;
        mVod.onTimeChanged(time, position, duration);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) mVod.requestDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) mVod.refresh();
        else if (event.getType() == RefreshEvent.Type.VOD) updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) player().setSub(Sub.from(event.getPath()));
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    @Override
    protected long startPositionMs() {
        return mVod == null ? C.TIME_UNSET : mVod.startPositionMs();
    }

    private void setTrackVisible() {
        boolean restoreFocus = hasFocus(mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
        PlaybackAction.setTracks(player(), mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
        restoreControlFocusIfHidden(restoreFocus, mBinding.control.action.text, mBinding.control.action.audio, mBinding.control.action.video);
        syncJetStreamControl();
    }

    private void setMediaOptionVisible() {
        boolean restoreFocus = hasFocus(mBinding.control.action.edition, mBinding.control.action.chapter);
        PlaybackAction.setMediaOptions(player(), mBinding.control.action.edition, mBinding.control.action.chapter);
        restoreControlFocusIfHidden(restoreFocus, mBinding.control.action.edition, mBinding.control.action.chapter);
        syncJetStreamControl();
    }

    private MediaMetadata buildMetadata() {
        return VodPlaybackMedia.metadata(mHistory, getEpisode());
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    public void onItemClick(Vod item) {
        mVod.selectSource(item);
    }

    @Override
    public void onParse(Parse item) {
        mVod.selectParse(item);
    }

    private void onPaused() {
        if (!isPlaybackReady()) return;
        controller().pause();
    }

    private void onPlay() {
        if (!isPlaybackReady()) return;
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
        hideCenter();
    }

    private boolean onSeekBack() {
        if (!isPlaybackReady()) return false;
        controller().seekBack();
        return true;
    }

    private boolean onSeekForward() {
        if (!isPlaybackReady()) return false;
        controller().seekForward();
        return true;
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    private View getFocus1() {
        return canRequestFocus(mFocus1) ? mFocus1 : mBinding.video;
    }

    private View getFocus2() {
        return !canRequestFocus(mFocus2) || isLegacyControlAction(mFocus2) ? mBinding.control.jetstream : mFocus2;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isFullscreen() && KeyUtil.isMenuKey(event)) onToggle();
        if (isJetStreamControlVisible()) setR1Callback();
        // 只有焦点确实在控制面板内才记忆，否则 onToggle 同帧内会把还未交接的 video 记成 mFocus2，
        // 下次弹控制条焦点落在 video 上导致方向键失效。
        if (isJetStreamControlVisible() && mBinding.control.getRoot().hasFocus()) mFocus2 = getCurrentFocus();
        if (isFullscreen() && !isJetStreamControlVisible() && mKeyDown.hasEvent(event) && isPlaybackReady()) return mKeyDown.onKeyDown(event);
        if (KeyUtil.isMediaFastForward(event)) return onSeekForward();
        if (KeyUtil.isMediaRewind(event)) return onSeekBack();
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onSeeking(long time) {
        showJetStreamInfo(isFullscreen(), true, time > 0 ? JetStreamVodControlView.ACTION_FORWARD : JetStreamVodControlView.ACTION_REWIND, player().getPositionTime(time), player().getDurationTime());
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        if (seekTo(time)) hideCenter();
        mKeyDown.reset();
    }

    @Override
    public boolean onSpeedUp() {
        if (!isPlaybackReady() || mHistory == null || !player().isPlaying()) return false;
        mBinding.widget.speed.setVisibility(View.VISIBLE);
        mBinding.widget.speed.startAnimation(ResUtil.getAnim(R.anim.forward));
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, PlayerSetting.getSpeed());
        syncJetStreamControl();
        return true;
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.speed.clearAnimation();
        mBinding.widget.speed.setVisibility(View.GONE);
        if (!isPlaybackReady() || mHistory == null) return;
        PlaybackAction.setSpeed(player(), mBinding.control.action.speed, mHistory.getSpeed());
        syncJetStreamControl();
    }

    @Override
    public void onKeyUp() {
        long position = player().getPosition();
        long duration = player().getDuration();
        if (player().canSetOpening(position, duration)) {
            mBinding.control.jetstream.showGroup(JetStreamVodControlView.GROUP_SETTINGS);
            showControl(mBinding.control.jetstream);
        } else if (player().canSetEnding(position, duration)) {
            mBinding.control.jetstream.showGroup(JetStreamVodControlView.GROUP_SETTINGS);
            showControl(mBinding.control.jetstream);
        } else {
            showControl(getFocus2());
        }
    }

    @Override
    public void onKeyDown() {
        showControl(getFocus2());
    }

    @Override
    public void onKeyCenter() {
        if (!isPlaybackReady()) return;
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
        hideControl();
    }

    @Override
    public void onSingleTap() {
        if (isFullscreen()) onToggle();
    }

    @Override
    public void onDoubleTap() {
        if (isFullscreen()) onKeyCenter();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001 && isPlaybackReady()) PlayerHelper.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
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
        } else if (isJetStreamCenterVisible()) {
            hideCenter();
        } else if (isFullscreen()) {
            exitFullscreen();
        } else {
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        mRatingRequest++;
        MediaRatingHelper.cancel();
        mClock.release();
        saveHistory(true);
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4);
        super.onDestroy();
    }
}
