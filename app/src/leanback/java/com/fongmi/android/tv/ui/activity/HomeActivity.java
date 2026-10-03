package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.leanback.widget.Presenter;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.FeaturedVodRow;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.HistoryRequestState;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.databinding.AdapterHomeEmptyBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.JetStreamHomeNavView;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.FeaturedVodPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, JetStreamHomeNavView.Listener, HistoryPresenter.OnClickListener {

    private static final int HOME_HORIZONTAL_PADDING = 48;
    private static final int HOME_HORIZONTAL_SPACING = 20;

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;
    private FeaturedVodPresenter mFeaturedPresenter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private Clock mClock;
    private boolean mToolbarVisible = true;
    private boolean mRestoreRefreshFocus;
    private boolean mInitialFocusPending;
    private boolean mRefreshFeaturedFocus;
    private long mRefreshFocusGeneration;
    private int mRefreshFocusCid;
    private final HistoryRequestState mHistoryRequests = new HistoryRequestState();
    private Future<?> mHistoryTask;
    private int mHistoryCid;
    private long mHistoryFocusGeneration;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mInitialFocusPending = savedInstanceState == null;
        mResult = Result.empty();
        mClock = Clock.create(mBinding.clock);
        mBinding.progressLayout.showProgress();
        PermissionUtil.requestNotify(this);
        DLNARendererService.start(this);
        Updater.create().start(this);
        setRecyclerView();
        setNavigationView();
        setViewModel();
        setAdapter();
        setFunc();
        if (mInitialFocusPending) mBinding.nav.requestFocus();
        initConfig();
        setTitle();
        setLogo();
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                setToolbarVisible(position <= 0 || mBinding.toolbar.hasFocus());
                if (!mBinding.toolbar.hasFocus() && position >= 0 && position < mAdapter.size()) {
                    Object row = mAdapter.get(position);
                    // Keep scaled cards below the viewport edge; the hero and empty state
                    // retain their own established alignment and toolbar inset.
                    int inset = row instanceof FeaturedVodRow ? 0
                            : row instanceof EmptyHome ? ResUtil.dp2px(80)
                            : Math.max(mBinding.recycler.getPaddingTop(), ResUtil.dp2px(16));
                    mBinding.recycler.setWindowAlignmentOffset(inset);
                }
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter(HOME_HORIZONTAL_PADDING) {
            @Override
            public void onBindViewHolder(@NonNull Presenter.ViewHolder holder, Object object) {
                boolean emptyHistory = (int) object == R.string.home_history && mHistoryAdapter.size() == 0;
                holder.view.setVisibility(emptyHistory ? View.GONE : View.VISIBLE);
                ViewGroup.LayoutParams params = holder.view.getLayoutParams();
                if (params == null) params = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.height = emptyHistory ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
                holder.view.setLayoutParams(params);
                String title = (int) object == R.string.home_history ? getString(R.string.home_continue_watching)
                        : getString(R.string.home_source_recommendations, TextUtils.isEmpty(getHome().getName()) ? getString(R.string.app_name) : getHome().getName());
                super.onBindViewHolder(holder, title);
            }
        });
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(FeaturedVodRow.class, mFeaturedPresenter = new FeaturedVodPresenter(this));
        selector.addPresenter(EmptyHome.class, new EmptyHomePresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(HOME_HORIZONTAL_SPACING, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ITEM, HOME_HORIZONTAL_PADDING), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(HOME_HORIZONTAL_SPACING, FocusHighlight.ZOOM_FACTOR_NONE, HorizontalGridView.FOCUS_SCROLL_ALIGNED, HOME_HORIZONTAL_PADDING), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        // Keep the first hero at the top and stop the last row above the bottom inset.
        mBinding.recycler.setWindowAlignment(HorizontalGridView.WINDOW_ALIGN_BOTH_EDGE);
        mBinding.recycler.setWindowAlignmentPreferKeyLineOverHighEdge(false);
        updateHomeContentInsets(false);
        mBinding.recycler.setWindowAlignmentOffsetPercent(HorizontalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        mBinding.recycler.setItemAlignmentOffset(0);
        mBinding.recycler.setItemAlignmentOffsetPercent(HorizontalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
    }

    @SuppressLint("RestrictedApi")
    private void setNavigationView() {
        mBinding.nav.setListener(this);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            boolean restoreFocus = mRestoreRefreshFocus && mRefreshFocusGeneration == mHistoryFocusGeneration
                    && mRefreshFocusCid == VodConfig.getCid() && canRestoreHistoryFocus();
            boolean keepTop = !restoreFocus && mBinding.toolbar.hasFocus();
            mRestoreRefreshFocus = false;
            mAdapter.remove("progress");
            addVideo(mResult = result);
            Cache.clear().put(result);
            if (restoreFocus) requestRecyclerFocus(mRefreshFeaturedFocus ? firstFocusableRowIndex() : getRecommendIndex());
            else if (keepTop) {
                BooleanSupplier current = newFocusRequest();
                // Adapter insertions can move Leanback's selected position during layout.
                mBinding.recycler.post(() -> {
                    if (current.getAsBoolean() && mBinding.toolbar.hasFocus()) mBinding.recycler.setSelectedPosition(0);
                });
                setToolbarVisible(true);
            }
        });
    }

    private void setAdapter() {
        mHistoryCid = VodConfig.getCid();
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this, getHistorySpec()));
        mAdapter.add(R.string.home_history);
        mAdapter.add(R.string.home_recommend);
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        };
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        checkAction(getIntent());
        setFocus();
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mBinding.title.setSelected(true);
        App.post(() -> {
            if (!isFinishing() && !isDestroyed()) mBinding.title.setFocusable(true);
        }, 500);
        if (mInitialFocusPending) {
            mInitialFocusPending = false;
            mBinding.recycler.scrollToPosition(0);
            requestNavFocus();
        } else if (!mBinding.toolbar.hasFocus() && !mBinding.recycler.hasFocus()) requestNavFocus();
    }

    private void requestRecyclerFocus() {
        requestRecyclerFocus(RecyclerView.NO_POSITION);
    }

    private void requestRecyclerFocus(int position) {
        requestRecyclerFocus(position, newFocusRequest());
    }

    private void requestRecyclerFocus(int position, BooleanSupplier current) {
        mBinding.recycler.post(() -> {
            if (!current.getAsBoolean()) return;
            if (!hasFocusableRecyclerContent() || !canRequestFocus(mBinding.recycler)) {
                requestNavFocus(current);
                return;
            }
            int target = position == RecyclerView.NO_POSITION ? mBinding.recycler.getSelectedPosition() : position;
            target = Math.max(0, Math.min(target, mAdapter.size() - 1));
            // Prefer a row that actually has focusable children when possible.
            if (position == RecyclerView.NO_POSITION && !rowHasFocusable(target)) {
                int better = firstFocusableRowIndex();
                if (better >= 0) target = better;
            }
            mBinding.recycler.setSelectedPosition(target);
            int focusTarget = target;
            mBinding.recycler.postDelayed(() -> {
                if (!current.getAsBoolean()) return;
                RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(focusTarget);
                View focus = holder == null ? null : findFocusable(holder.itemView);
                if (focus != null && focus.requestFocus()) return;
                if (canRequestFocus(mBinding.recycler) && mBinding.recycler.requestFocus() && mBinding.recycler.hasFocus()) return;
                requestNavFocus(current);
            }, 50);
        });
    }

    /** Top JetStream nav (点播/直播/搜索/…) — host focus for empty home / Back-to-toolbar. */
    private void requestNavFocus() {
        requestNavFocus(newFocusRequest());
    }

    private BooleanSupplier newFocusRequest() {
        long generation = ++mHistoryFocusGeneration;
        int cid = VodConfig.getCid();
        return () -> generation == mHistoryFocusGeneration && cid == VodConfig.getCid() && canRestoreHistoryFocus();
    }

    private void requestNavFocus(BooleanSupplier current) {
        setToolbarVisible(true);
        mBinding.toolbar.post(() -> {
            if (!current.getAsBoolean()) return;
            if (!canRequestFocus(mBinding.nav)) {
                if (canRequestFocus(mBinding.toolbar)) mBinding.toolbar.requestFocus();
                return;
            }
            mBinding.nav.requestFocus();
        });
    }

    private boolean hasFocusableRecyclerContent() {
        return firstFocusableRowIndex() >= 0;
    }

    private int firstFocusableRowIndex() {
        if (mAdapter == null) return -1;
        for (int i = 0; i < mAdapter.size(); i++) {
            if (rowHasFocusable(i)) return i;
        }
        return -1;
    }

    /**
     * Header integers ({@code R.string.home_*}) and the progress marker are not focusable
     * media rows. History / featured / recommend rows are.
     */
    private boolean rowHasFocusable(int index) {
        if (mAdapter == null || index < 0 || index >= mAdapter.size()) return false;
        Object item = mAdapter.get(index);
        if (item == null || item instanceof Integer || "progress".equals(item)) return false;
        return item instanceof ListRow || item instanceof FeaturedVodRow || item instanceof Vod || item instanceof EmptyHome;
    }

    private void requestHistoryFocus(int position) {
        long generation = ++mHistoryFocusGeneration;
        int cid = VodConfig.getCid();
        int historyIndex = getHistoryIndex();
        BooleanSupplier current = () -> generation == mHistoryFocusGeneration && cid == VodConfig.getCid()
                && (position == RecyclerView.NO_POSITION || historyIndex == getHistoryIndex())
                && canRestoreHistoryFocus();
        if (position == RecyclerView.NO_POSITION || historyIndex < 0 || historyIndex >= mAdapter.size()) {
            requestRecyclerFocus(RecyclerView.NO_POSITION, current);
            return;
        }
        mBinding.recycler.post(() -> {
            if (!current.getAsBoolean()) return;
            if (!canRequestFocus(mBinding.recycler)) {
                requestRecyclerFocus(RecyclerView.NO_POSITION, current);
                return;
            }
            mBinding.recycler.setSelectedPosition(historyIndex);
            mBinding.recycler.postDelayed(() -> {
                if (!current.getAsBoolean()) return;
                RecyclerView row = findHistoryRecycler(historyIndex);
                if (row == null) {
                    requestRecyclerFocus(RecyclerView.NO_POSITION, current);
                    return;
                }
                if (row instanceof HorizontalGridView) ((HorizontalGridView) row).setSelectedPosition(position);
                else row.scrollToPosition(position);
                row.postDelayed(() -> {
                    if (!current.getAsBoolean()) return;
                    if (requestItemFocus(row, position)) return;
                    if (canRequestFocus(row) && row.requestFocus()) return;
                    requestRecyclerFocus(RecyclerView.NO_POSITION, current);
                }, 50);
            }, 50);
        });
    }

    private int getSelectedHistoryPosition(int historyIndex) {
        if (!canRestoreHistoryFocus() || historyIndex < 0 || mBinding.recycler.getSelectedPosition() != historyIndex || !mBinding.recycler.hasFocus()) return RecyclerView.NO_POSITION;
        RecyclerView row = findHistoryRecycler(historyIndex);
        if (row instanceof HorizontalGridView) return ((HorizontalGridView) row).getSelectedPosition();
        return 0;
    }

    private boolean canRestoreHistoryFocus() {
        return hasWindowFocus() && !isFinishing() && !isDestroyed()
                && getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
    }

    private int clampHistoryPosition(int position) {
        if (mHistoryAdapter.size() == 0) return RecyclerView.NO_POSITION;
        if (position < 0) return 0;
        return Math.min(position, mHistoryAdapter.size() - 1);
    }

    private RecyclerView findHistoryRecycler(int historyIndex) {
        RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(historyIndex);
        return holder == null ? null : findChildRecycler(holder.itemView);
    }

    private RecyclerView findChildRecycler(View view) {
        if (view instanceof RecyclerView && view != mBinding.recycler) return (RecyclerView) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            RecyclerView recycler = findChildRecycler(group.getChildAt(i));
            if (recycler != null) return recycler;
        }
        return null;
    }

    private boolean requestItemFocus(RecyclerView recycler, int position) {
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position);
        View target = holder == null ? null : findFocusable(holder.itemView);
        return target != null && target.requestFocus();
    }

    private View findFocusable(View view) {
        if (!canRequestFocus(view)) return null;
        if (view.isFocusable()) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View target = findFocusable(group.getChildAt(i));
            if (target != null) return target;
        }
        return null;
    }

    private boolean canRequestFocus(View view) {
        return view != null && view.isShown() && view.isEnabled();
    }

    private void setToolbarVisible(boolean visible) {
        // When toolbar is already meant to be shown, still force VISIBLE — a pending hide
        // animation's endAction can leave GONE and cause top nav to flash then disappear.
        if (mToolbarVisible == visible) {
            if (visible && mBinding.toolbar.getVisibility() != View.VISIBLE) {
                JetStreamAnimator.show(mBinding.toolbar, 0, -16, JetStreamAnimator.FOCUS_DURATION);
            }
            return;
        }
        mToolbarVisible = visible;
        if (visible) JetStreamAnimator.show(mBinding.toolbar, 0, -16, JetStreamAnimator.FOCUS_DURATION);
        else {
            // Only dump focus to recycler if there is a real focusable row; otherwise keep nav.
            if (mBinding.toolbar.hasFocus() && hasFocusableRecyclerContent()) requestRecyclerFocus();
            JetStreamAnimator.hide(mBinding.toolbar, 0, -16, View.GONE, JetStreamAnimator.FOCUS_DURATION);
        }
    }

    private void getVideo() {
        mResult = Result.empty();
        mHistoryFocusGeneration++;
        mRestoreRefreshFocus = isRefreshRemovingFocusedRow();
        int selected = mBinding.recycler.getSelectedPosition();
        mRefreshFeaturedFocus = selected >= 0 && selected < mAdapter.size() && mAdapter.get(selected) instanceof FeaturedVodRow;
        removeFeatured();
        int index = getRecommendIndex();
        boolean gone = mAdapter.indexOf("progress") == -1;
        boolean hasItem = gone && mAdapter.size() > index;
        if (hasItem) mAdapter.removeItems(index, mAdapter.size() - index);
        if (gone) mAdapter.add("progress");
        if (mRestoreRefreshFocus) requestRecyclerFocus(index);
        mRefreshFocusGeneration = mHistoryFocusGeneration;
        mRefreshFocusCid = VodConfig.getCid();
        mViewModel.homeContent();
    }

    private boolean isRefreshRemovingFocusedRow() {
        if (!mBinding.recycler.hasFocus()) return false;
        int position = mBinding.recycler.getSelectedPosition();
        if (position < 0 || position >= mAdapter.size()) return true;
        Object item = mAdapter.get(position);
        return item instanceof FeaturedVodRow || position >= getRecommendIndex();
    }

    private void addVideo(Result result) {
        setFeatured(result.getList());
        Style style = result.getStyle(getHome().getStyle());
        if (result.getList().isEmpty()) mAdapter.add(new EmptyHome());
        else if (style.isList()) mAdapter.addAll(mAdapter.size(), result.getList());
        else addGrid(result.getList(), style);
        int header = mAdapter.indexOf(R.string.home_recommend);
        if (header >= 0) mAdapter.notifyArrayItemRangeChanged(header, 1);
    }

    private void setFeatured(List<Vod> items) {
        List<Vod> featured = getFeatured(items, true);
        if (featured.isEmpty()) featured = getFeatured(items, false);
        if (!featured.isEmpty()) mAdapter.add(getFeaturedIndex(), FeaturedVodRow.create(featured));
        updateHomeContentInsets(!featured.isEmpty());
    }

    private void updateHomeContentInsets(boolean hasFeatured) {
        int top = hasFeatured ? 0 : ResUtil.dp2px(80);
        mBinding.recycler.setPadding(0, top, 0, ResUtil.dp2px(48));
        // Leanback's focus alignment must respect the same toolbar inset as layout.
        mBinding.recycler.setWindowAlignmentOffset(top);
    }

    private List<Vod> getFeatured(List<Vod> items, boolean requirePic) {
        List<Vod> featured = new ArrayList<>();
        // Prefer a real backdrop when the source provides one, without fabricating a ranking.
        for (Vod item : items) {
            if (featured.size() >= 5) break;
            if (item.isAction() || TextUtils.isEmpty(item.getBackdrop()) || TextUtils.equals(item.getBackdrop(), item.getPic())) continue;
            featured.add(item);
        }
        for (Vod item : items) {
            if (featured.size() >= 5) break;
            if (item.isAction() || featured.contains(item)) continue;
            if (requirePic && TextUtils.isEmpty(item.getPic())) continue;
            featured.add(item);
        }
        return featured;
    }

    private void removeFeatured() {
        updateHomeContentInsets(false);
        for (int i = 0; i < mAdapter.size(); i++) {
            if (!(mAdapter.get(i) instanceof FeaturedVodRow)) continue;
            mAdapter.removeItems(i, 1);
            return;
        }
    }

    private int getFeaturedIndex() {
        int index = mAdapter.indexOf(R.string.home_history);
        return index == -1 ? 0 : index;
    }

    private void addGrid(List<Vod> items, Style style) {
        VodPresenter presenter = new VodPresenter(this, style, getHomeSpec(style));
        ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
        adapter.addAll(0, items);
        mAdapter.add(new ListRow(adapter));
    }

    private int[] getHistorySpec() {
        return new int[]{Math.round((ResUtil.getScreenWidth() - ResUtil.dp2px(136)) / 3.15f), ResUtil.dp2px(112)};
    }

    private int[] getHomeSpec(Style style) {
        int column = Product.getColumn(style);
        int space = ResUtil.dp2px(HOME_HORIZONTAL_PADDING * 2) + ResUtil.dp2px(HOME_HORIZONTAL_SPACING * (column - 1));
        if (style.isOval()) space += ResUtil.dp2px(column * 16);
        return Product.getSpec(space, column, style);
    }

    private void setFunc() {
        List<JetStreamHomeNavView.NavItem> items = new ArrayList<>();
        items.add(new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_tab_home), getString(R.string.home_tab_home), R.drawable.ic_home_vod));
        items.add(new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_vod), getString(R.string.home_tab_library), R.drawable.ic_home_vod));
        items.add(new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_discover), getString(R.string.home_discover), R.drawable.ic_home_discover));
        if (LiveConfig.hasUrl()) items.add(new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_live), getString(R.string.home_live), R.drawable.ic_home_live));
        items.add(new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_search), getString(R.string.home_search), R.drawable.ic_home_search));
        items.add(new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_tab_my), getString(R.string.home_tab_my), R.drawable.ic_home_setting));
        mBinding.nav.setItems(items);
        mBinding.nav.setSelectedKey(String.valueOf(R.string.home_tab_home));
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        if (isFinishing() || isDestroyed()) return;
        cancelHistoryLoad();
        HistoryRequestState.Request request = mHistoryRequests.begin(VodConfig.getCid(), renew);
        if (mHistoryCid != request.cid()) {
            // Do not leave the previous config's history clickable while its replacement loads.
            applyHistory(new ArrayList<>(), false);
            mHistoryCid = request.cid();
        }
        mHistoryTask = Task.submit(() -> {
            List<History> items = History.get(request.cid());
            if (Thread.currentThread().isInterrupted()) return;
            App.post(() -> {
                if (isFinishing() || isDestroyed() || !mHistoryRequests.isCurrent(request, VodConfig.getCid())) return;
                mHistoryTask = null;
                applyHistory(items, request.renew());
                mHistoryRequests.applied(request, VodConfig.getCid());
            });
        });
    }

    private void cancelHistoryLoad() {
        mHistoryRequests.invalidate();
        if (mHistoryTask != null) mHistoryTask.cancel(true);
        mHistoryTask = null;
    }

    private void applyHistory(List<History> items, boolean renew) {
        int historyIndex = getHistoryIndex();
        int recommendIndex = getRecommendIndex();
        boolean exist = recommendIndex - historyIndex == 2;
        int selectedHistoryPosition = getSelectedHistoryPosition(historyIndex);
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this, getHistorySpec()));
        if ((items.isEmpty() && exist) || (renew && exist)) mAdapter.removeItems(historyIndex, 1);
        if (!items.isEmpty() && (!exist || renew)) mAdapter.add(historyIndex, new ListRow(mHistoryAdapter));
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
        int header = mAdapter.indexOf(R.string.home_history);
        if (header >= 0) mAdapter.notifyArrayItemRangeChanged(header, 1);
        if (items.isEmpty()) mPresenter.setDelete(false);
        if (selectedHistoryPosition == RecyclerView.NO_POSITION) return;
        requestHistoryFocus(clampHistoryPosition(selectedHistoryPosition));
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        if (mHistoryCid != VodConfig.getCid()) return;
        mHistoryFocusGeneration++;
        cancelHistoryLoad();
        mAdapter.removeItems(getHistoryIndex(), 1);
        History.delete(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
        getHistory();
        requestHistoryFocus(RecyclerView.NO_POSITION);
    }

    private int getHistoryIndex() {
        return mAdapter.indexOf(R.string.home_history) + 1;
    }

    private int getRecommendIndex() {
        return mAdapter.indexOf(R.string.home_recommend) + 1;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history().save(VodConfig.getCid()));
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onNavClick(String key) {
        int resId = Integer.parseInt(key);
        if (resId == R.string.home_tab_home) requestRecyclerFocus(firstFocusableRowIndex());
        else if (resId == R.string.home_vod) VodActivity.start(this, mResult);
        else if (resId == R.string.home_live) LiveActivity.start(this);
        else if (resId == R.string.home_discover) DiscoverActivity.start(this);
        else if (resId == R.string.home_keep) KeepActivity.start(this);
        else if (resId == R.string.home_push) PushActivity.start(this);
        else if (resId == R.string.home_search) SearchActivity.start(this);
        else if (resId == R.string.home_setting) SettingActivity.start(this);
        else if (resId == R.string.home_tab_my) MyActivity.start(this);
    }

    @Override
    public void onNavLongClick(String key) {
        showDialog();
    }

    @Override
    public void onItemClick(Vod item) {
        onItemClick(item, null);
    }

    @Override
    public void onItemClick(Vod item, View poster) {
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic(), poster);
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        if (item.getCid() != VodConfig.getCid()) return;
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        if (item.getCid() != VodConfig.getCid() || mHistoryAdapter.indexOf(item) < 0) return;
        mHistoryFocusGeneration++;
        cancelHistoryLoad();
        int position = mHistoryAdapter.indexOf(item);
        mHistoryAdapter.remove(item.delete());
        getHistory();
        if (mHistoryAdapter.size() > 0) {
            requestHistoryFocus(clampHistoryPosition(position));
            return;
        }
        mAdapter.removeItems(getHistoryIndex(), 1);
        mPresenter.setDelete(false);
        requestHistoryFocus(RecyclerView.NO_POSITION);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        SiteDialog.create().classic().show(this);
    }

    @Override
    public void onRefresh() {
        getVideo();
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isActionDown(event)) {
            mInitialFocusPending = false;
            mHistoryFocusGeneration++;
            if (mFeaturedPresenter != null) mFeaturedPresenter.onUserInteraction();
        }
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && mBinding.toolbar.hasFocus()) {
            requestRecyclerFocus(firstFocusableRowIndex());
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHistoryFocusGeneration++;
        mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (mBinding.progressLayout.isProgress()) {
            showContent();
            return;
        }
        if (mPresenter.isDelete()) {
            setHistoryDelete(false);
            return;
        }
        // 1) Content has focus → first Back returns to top nav (点播/设置…), do not exit.
        if (isContentFocused()) {
            mBinding.recycler.scrollToPosition(0);
            requestNavFocus();
            return;
        }
        // 2) List scrolled down while toolbar hidden → scroll top + show toolbar + focus nav.
        if (mBinding.recycler.getSelectedPosition() > 0 || !mToolbarVisible) {
            mBinding.recycler.scrollToPosition(0);
            requestNavFocus();
            return;
        }
        // 3) Already on top nav / title → exit or background.
        if (PlaybackService.isRunning()) Util.moveToBackground(this);
        else super.onBackInvoked();
    }

    private boolean isContentFocused() {
        if (mBinding.recycler.hasFocus()) return true;
        View focus = getCurrentFocus();
        if (focus == null) return false;
        if (mBinding.nav.hasFocus() || mBinding.title.hasFocus() || mBinding.toolbar.hasFocus()) return false;
        return isDescendant(mBinding.recycler, focus);
    }

    private static boolean isDescendant(View parent, View child) {
        View current = child;
        while (current != null) {
            if (current == parent) return true;
            Object p = current.getParent();
            current = p instanceof View ? (View) p : null;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        mHistoryFocusGeneration++;
        cancelHistoryLoad();
        mHistoryRequests.close();
        DLNARendererService.stop(this);
        LiveConfig.get().clear();
        VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }

    private static final class EmptyHome {
    }

    private class EmptyHomePresenter extends Presenter {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            AdapterHomeEmptyBinding binding = AdapterHomeEmptyBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            binding.choose.setOnClickListener(view -> {
                if (VodConfig.get().getSites().isEmpty()) SettingActivity.start(HomeActivity.this);
                else showDialog();
            });
            return new ViewHolder(binding.getRoot());
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, Object object) {
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder holder) {
        }
    }
}
