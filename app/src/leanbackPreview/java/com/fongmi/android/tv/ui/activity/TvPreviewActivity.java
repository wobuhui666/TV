package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.appcompat.widget.LinearLayoutCompat;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.diagnostics.DiagnosticManager;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.adapter.WordAdapter;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.*;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.ui.theme.RexTheme;
import com.fongmi.android.tv.ui.home.PosterHomeController;
import com.fongmi.android.tv.ui.presenter.RexPosterPresenter;
import com.fongmi.android.tv.ui.presenter.RexSpotlightPresenter;
import com.fongmi.android.tv.ui.rex.RexHomeCatalog;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.flexbox.FlexDirection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.io.IOException;

/** Offline fixtures composed of the same TV layouts and controls shipped in the app. */
public class TvPreviewActivity extends BaseActivity implements JetStreamSettingView.Listener, CustomKeyDownVod.Listener {
    public static volatile TvPreviewActivity current;
    public JetStreamHomeNavView nav;
    public JetStreamChipRow chips;
    public RecyclerView grid;
    public JetStreamSettingView settings;
    public JetStreamVodDetailView detail;
    public String lastAction = "";
    public int lastChip = -1;
    public int cardClicks;
    public int cardLongClicks;
    public int singleTaps;
    public int doubleTaps;
    public volatile long seekTime;
    public volatile int seekEvents;
    public volatile int directClicks;
    public JetStreamSwitch nativeSwitch;
    public JetStreamPlaybackSectionTitleView retainedTitle;
    public AlertDialog previewDialog;
    public JetStreamVodControlView controls;
    public CustomKeyDownVod gestures;
    public String page = "home";
    public Intent lastNavigation;
    public int navigationCount;
    public ArrayObjectAdapter rexPage;
    public JetStreamVerticalGridView rexGrid;
    public int rexSpotlightPosition;
    public int rexMixedPosition;
    private PosterHomeController posterHome;
    private boolean retainPageOnThemeChange;
    private View rexToolbar;
    private boolean rexToolbarVisible;
    private FrameLayout root;

    @Override protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override protected ViewBinding getBinding() {
        root = new JetStreamPageRootLayout(this);
        return () -> root;
    }

    @Override protected boolean customWall() { return false; }

    @Override protected void initView(Bundle state) {
        try {
            DiagnosticManager.start(this);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot start preview crash diagnostics", error);
        }
        current = this;
        showPage("rex-home");
    }

    public int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private LinearLayoutCompat column() {
        root.removeAllViews();
        root.addView(new JetStreamPageBackgroundView(this), new FrameLayout.LayoutParams(-1, -1));
        LinearLayoutCompat layout = new JetStreamPageContentLayout(this);
        layout.setOrientation(LinearLayoutCompat.VERTICAL);
        layout.setPadding(dp(32), dp(24), dp(32), dp(24));
        root.addView(layout, new FrameLayout.LayoutParams(-1, -1));
        return layout;
    }

    public void showPage(String name) {
        if (posterHome != null) { posterHome.close(); posterHome = null; }
        page = name;
        settings = null;
        retainPageOnThemeChange = name.equals("retained-controls");
        if (name.equals("rex-home")) {
            showRexHome();
            return;
        }
        if (name.equals("settings")) {
            root.removeAllViews();
            settings = new JetStreamSettingView(this);
            settings.setListener(this);
            root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
            settings.showSection(JetStreamSettingView.SECTION_APP);
            settings.requestInitialFocus();
            return;
        }
        if (name.equals("native-grid-probe")) {
            root.removeAllViews();
            View content = getLayoutInflater().inflate(R.layout.activity_home, root, false);
            root.addView(content);
            nav = content.findViewById(R.id.nav);
            setupNavigation();
            ((TextView) content.findViewById(R.id.title)).setText("TV 预览");
            ((TextView) content.findViewById(R.id.clock)).setText("20:26");
            JetStreamVerticalGridView nativeGrid = content.findViewById(R.id.recycler);
            nativeGrid.setNumColumns(5);
            nativeGrid.setAdapter(new CardAdapter(40));
            ((ProgressLayout) content.findViewById(R.id.progressLayout)).showContent();
            grid = nativeGrid;
            nav.requestFocus();
            return;
        }
        if (name.equals("native-controls") || retainPageOnThemeChange) {
            LinearLayoutCompat layout = column();
            directClicks = 0;
            retainedTitle = new JetStreamPlaybackSectionTitleView(this);
            retainedTitle.setText("保留播放页 · 分组标题");
            layout.addView(retainedTitle, new LinearLayoutCompat.LayoutParams(-1, dp(48)));
            JetStreamButtonTextView cancel = new JetStreamButtonTextView(this);
            cancel.setText("取消");
            cancel.setFocusable(true); cancel.setFocusableInTouchMode(true);
            cancel.setOnClickListener(view -> directClicks++);
            layout.addView(cancel, new LinearLayoutCompat.LayoutParams(dp(220), dp(48)));
            JetStreamDialogButton confirm = new JetStreamDialogButton(this);
            confirm.setText("确定");
            confirm.setFocusable(true); confirm.setFocusableInTouchMode(true);
            confirm.setOnClickListener(view -> directClicks++);
            LinearLayoutCompat.LayoutParams buttons = new LinearLayoutCompat.LayoutParams(dp(220), dp(48));
            buttons.topMargin = dp(16);
            layout.addView(confirm, buttons);
            nativeSwitch = new JetStreamSwitch(this);
            nativeSwitch.setText("提示开关");
            nativeSwitch.setFocusable(true); nativeSwitch.setFocusableInTouchMode(true);
            layout.addView(nativeSwitch, new LinearLayoutCompat.LayoutParams(dp(260), dp(48)));
            cancel.requestFocus();
            return;
        }
        if (name.equals("library")) {
            root.removeAllViews();
            View content = getLayoutInflater().inflate(R.layout.activity_keep, root, false);
            root.addView(content);
            grid = content.findViewById(R.id.recycler);
            grid.setHasFixedSize(true);
            grid.setItemAnimator(null);
            grid.setLayoutManager(new GridLayoutManager(this, Product.getColumn()));
            grid.addItemDecoration(new SpaceItemDecoration(Product.getColumn(), 16));
            KeepAdapter adapter = new KeepAdapter(new KeepAdapter.OnClickListener() {
                public void onItemClick(Keep item) { lastAction = item.getKey(); }
                public void onItemDelete(Keep item) { }
                public boolean onLongClick() { return true; }
            });
            List<Keep> keeps = new ArrayList<>();
            for (Vod vod : RexHomeCatalog.mixed(RexPreviewFixtures.feeds()).subList(0, 20)) {
                Keep keep = new Keep();
                keep.setKey(vod.getId()); keep.setType(Keep.TYPE_DISCOVER);
                keep.setVodName(vod.getName()); keep.setVodPic(vod.getPic());
                keep.setSiteName("离线片库");
                keeps.add(keep);
            }
            adapter.setItems(keeps);
            grid.setAdapter(adapter);
            ((ProgressLayout) content.findViewById(R.id.progressLayout)).showContent();
            return;
        }
        if (name.equals("search")) {
            root.removeAllViews();
            View content = getLayoutInflater().inflate(R.layout.activity_search, root, false);
            root.addView(content);
            ((TextView) content.findViewById(R.id.keyword)).setText("星际");
            RecyclerView keyboard = content.findViewById(R.id.keyboard);
            TextView keyword = content.findViewById(R.id.keyword);
            keyboard.setAdapter(new com.fongmi.android.tv.ui.adapter.KeyboardAdapter(new com.fongmi.android.tv.ui.adapter.KeyboardAdapter.OnClickListener() {
                public void onTextClick(String text) { keyword.append(text); }
                public void onIconClick(int id) { lastAction = "keyboard-" + id; }
                public boolean onLongClick(int id) { lastAction = "keyboard-long-" + id; return true; }
            }));
            RecyclerView words = content.findViewById(R.id.wordRecycler);
            words.setItemAnimator(null);
            words.setHasFixedSize(false);
            words.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
            WordAdapter adapter = new WordAdapter(value -> lastAction = "word-" + value);
            adapter.setItems(Word.objectFrom("{\"data\":[{\"title\":\"星际旅途\"},{\"title\":\"山海之间\"},{\"title\":\"追光的人\"},{\"title\":\"夏日来信\"},{\"title\":\"城市之声\"},{\"title\":\"云端之上\"}]}").getData());
            words.setAdapter(adapter);
            return;
        }
        if (name.equals("playback")) {
            root.removeAllViews();
            View video = new View(this);
            video.setBackgroundColor(0xFF172238);
            root.addView(video, new FrameLayout.LayoutParams(-1, -1));
            gestures = CustomKeyDownVod.create(this);
            gestures.setFull(true);
            video.setOnTouchListener((view, event) -> gestures.onTouchEvent(event));
            controls = new JetStreamVodControlView(this);
            controls.setMediaTitle("星际旅途", "第 3 集 · 4K", "离线播放控件预览");
            controls.setPlaybackState(false, false);
            controls.setControlsVisible(true);
            root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
            controls.requestFocus();
            return;
        }
        LinearLayoutCompat layout = column();
        if (name.equals("detail")) {
            detail = new JetStreamVodDetailView(this);
            detail.setTitle("星际旅途 · 测试影片");
            detail.setMetadata("TMDB 8.6", "豆瓣 8.8", "本地预览", "2026", "中国", "科幻 / 冒险", "导演：林远", "演员：陈星、王月", "一场跨越星海的旅途，从熟悉的家园走向未知。离线预览数据，用于检查长文本、元数据和操作按钮。");
            detail.setActions(true, false);
            detail.setListener(new JetStreamVodDetailView.Listener() {
                public void onWatch() { lastAction = "watch"; }
                public void onSummary() { lastAction = "summary"; showDialog(); }
                public void onKeep() { lastAction = "keep"; }
                public void onChange() { lastAction = "change"; }
                public void onFocusVideo() { }
                public void onFocusList() { chips.requestFocus(); }
            });
            layout.addView(detail, new LinearLayoutCompat.LayoutParams(-1, dp(310)));
            addChips(layout);
            detail.requestFocus();
            return;
        }
        nav = new JetStreamHomeNavView(this);
        setupNavigation();
        layout.addView(nav, new LinearLayoutCompat.LayoutParams(-1, dp(48)));
        JetStreamSectionHeaderView title = new JetStreamSectionHeaderView(this);
        title.setText("为你推荐 · 离线预览");
        layout.addView(title, new LinearLayoutCompat.LayoutParams(-1, dp(58)));
        addChips(layout);
        grid = new JetStreamPageRecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 5));
        grid.setAdapter(new CardAdapter(40));
        grid.setPadding(dp(4), dp(8), dp(4), dp(12));
        layout.addView(grid, new LinearLayoutCompat.LayoutParams(-1, 0, 1));
        nav.requestFocus();
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private void showRexHome() {
        root.removeAllViews();
        View content = getLayoutInflater().inflate(R.layout.activity_home, root, false);
        root.addView(content);
        nav = content.findViewById(R.id.nav);
        nav.setItems(Arrays.asList(
                new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_tab_home), getString(R.string.home_tab_home), R.drawable.ic_home_vod),
                new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_vod), getString(R.string.home_tab_library), R.drawable.ic_home_vod),
                new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_discover), getString(R.string.home_discover), R.drawable.ic_home_discover),
                new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_search), getString(R.string.home_search), R.drawable.ic_home_search),
                new JetStreamHomeNavView.NavItem(String.valueOf(R.string.home_tab_my), getString(R.string.home_tab_my), R.drawable.ic_home_setting)));
        nav.setSelectedKey(String.valueOf(R.string.home_tab_home));
        nav.setListener(new JetStreamHomeNavView.Listener() {
            public void onNavClick(String key) { lastAction = key; }
            public void onNavLongClick(String key) { lastAction = "long-" + key; }
        });
        ((TextView) content.findViewById(R.id.title)).setText(R.string.app_name);
        rexGrid = content.findViewById(R.id.recycler);
        grid = rexGrid;
        CustomSelector selector = new CustomSelector();
        posterHome = new PosterHomeController(this, visible -> { });
        posterHome.register(selector);
        rexPage = new ArrayObjectAdapter(selector);
        rexGrid.setAdapter(new ItemBridgeAdapter(rexPage));
        rexGrid.setVerticalSpacing(dp(16));
        rexGrid.setWindowAlignment(HorizontalGridView.WINDOW_ALIGN_BOTH_EDGE);
        rexGrid.setWindowAlignmentPreferKeyLineOverHighEdge(false);
        rexGrid.setWindowAlignmentOffset(dp(80));
        rexGrid.setWindowAlignmentOffsetPercent(HorizontalGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED);
        rexGrid.setItemAlignmentOffset(0);
        rexGrid.setItemAlignmentOffsetPercent(HorizontalGridView.ITEM_ALIGN_OFFSET_PERCENT_DISABLED);
        rexToolbar = content.findViewById(R.id.toolbar);
        rexToolbarVisible = true;
        rexGrid.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override public void onChildViewHolderSelected(RecyclerView parent, RecyclerView.ViewHolder child, int position, int subposition) {
                if (parent != rexGrid) return;
                setRexToolbarVisible(position <= 0 || rexToolbar.hasFocus());
                if (!rexToolbar.hasFocus() && position >= 0 && position < rexPage.size()) {
                    int inset = posterHome != null && posterHome.isHero(rexPage.get(position)) ? 0
                            : Math.max(rexGrid.getPaddingTop(), dp(16));
                    rexGrid.setWindowAlignmentOffset(inset);
                }
            }
        });
        posterHome.attachSnapshot(rexPage, RexPreviewFixtures.feeds());
        for (int i = 0; i < rexPage.size(); i++) {
            if (!(rexPage.get(i) instanceof ListRow row)) continue;
            if (row.getAdapter().getPresenter(row) instanceof RexSpotlightPresenter) rexSpotlightPosition = i;
            if (row.getAdapter().getPresenter(row) instanceof RexPosterPresenter) rexMixedPosition = i;
        }
        ((ProgressLayout) content.findViewById(R.id.progressLayout)).showContent();
        nav.requestFocus();
    }

    public void selectRexRow(boolean spotlight) {
        rexGrid.requestFocus();
        rexGrid.setSelectedPosition(spotlight ? rexSpotlightPosition : rexMixedPosition);
    }

    private void setRexToolbarVisible(boolean visible) {
        if (rexToolbarVisible == visible) {
            if (visible && rexToolbar.getVisibility() != View.VISIBLE) JetStreamAnimator.show(rexToolbar, 0, -16, JetStreamAnimator.FOCUS_DURATION);
            return;
        }
        rexToolbarVisible = visible;
        if (visible) JetStreamAnimator.show(rexToolbar, 0, -16, JetStreamAnimator.FOCUS_DURATION);
        else JetStreamAnimator.hide(rexToolbar, 0, -16, View.GONE, JetStreamAnimator.FOCUS_DURATION);
    }

    public HorizontalGridView rexRail(boolean spotlight) {
        RecyclerView.ViewHolder holder = rexGrid.findViewHolderForAdapterPosition(spotlight ? rexSpotlightPosition : rexMixedPosition);
        if (!(holder instanceof ItemBridgeAdapter.ViewHolder bridge)) return null;
        if (!(bridge.getPresenter() instanceof ListRowPresenter presenter)) return null;
        return ((ListRowPresenter.ViewHolder) presenter.getRowViewHolder(bridge.getViewHolder())).getGridView();
    }

    /** Verify the production navigation intents while keeping the fixture offline. */
    @Override public void startActivity(Intent intent) { recordNavigation(intent); }
    @Override public void startActivity(Intent intent, Bundle options) { recordNavigation(intent); }

    private void recordNavigation(Intent intent) {
        lastNavigation = intent;
        navigationCount++;
    }

    @Override protected void onDestroy() {
        if (posterHome != null) posterHome.close();
        super.onDestroy();
    }

    private void setupNavigation() {
        nav.setItems(Arrays.asList(
            new JetStreamHomeNavView.NavItem("home", "首页", R.drawable.msr_home),
            new JetStreamHomeNavView.NavItem("discover", "发现", R.drawable.msr_movie),
            new JetStreamHomeNavView.NavItem("search", "搜索", R.drawable.msr_search),
            new JetStreamHomeNavView.NavItem("settings", "设置", R.drawable.msr_settings)));
        nav.setSelectedKey("home");
        nav.setListener(new JetStreamHomeNavView.Listener() {
            public void onNavClick(String key) { lastAction = key; }
            public void onNavLongClick(String key) { lastAction = "long-" + key; }
        });
    }

    private void addChips(LinearLayoutCompat layout) {
        chips = new JetStreamChipRow(this);
        chips.setItems(Arrays.asList("全部", "电影", "剧集", "动漫", "纪录片", "综艺", "科幻", "冒险", "喜剧", "家庭", "经典", "新片"), 0);
        chips.setOnChipClickListener((JetStreamChipRow.OnChipClickListener) position -> { lastChip = position; chips.setSelectedPosition(position); });
        chips.setOnChipLongClickListener((JetStreamChipRow.OnChipLongClickListener) position -> lastAction = "chip-long-" + position);
        layout.addView(chips, new LinearLayoutCompat.LayoutParams(-1, dp(48)));
    }

    public void showDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_content, null);
        ((TextView) content.findViewById(R.id.text)).setText("星际旅途\n\n这段简介用于验证浅色和深色对话框中的文字对比度、留白以及遥控器与触屏操作。");
        AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("影片简介").setView(content).setPositiveButton("关闭", (d, which) -> d.dismiss()).create();
        previewDialog = dialog;
        dialog.show();
        JetStreamDialogDecor.tintButtons(dialog);
    }

    @Override protected void onThemeChanged() {
        if (retainPageOnThemeChange) return;
        setTheme(RexTheme.isLight() ? R.style.Theme_App_Light : R.style.Theme_App);
        if (settings != null) settings.refreshThemeSelection();
        else showPage(page);
    }

    @Override public void onSettingAction(String key) {
        lastAction = key;
        if (key.equals(JetStreamSettingView.KEY_THEME_MODE)) RexTheme.setLight(!RexTheme.isLight());
        if (key.equals(JetStreamSettingView.KEY_THEME_LIGHT)) RexTheme.setLight(true);
        if (key.equals(JetStreamSettingView.KEY_THEME_DARK)) RexTheme.setLight(false);
    }
    @Override public void onSettingLongAction(String key) { lastAction = "long-" + key; }

    @Override public void onSeeking(long time) { seekEvents++; seekTime = time; }
    @Override public void onSeekEnd(long time) { seekTime = time; }
    @Override public boolean onSpeedUp() { return false; }
    @Override public void onSpeedEnd() { }
    @Override public void onKeyUp() { if (controls != null) controls.setControlsVisible(true); }
    @Override public void onKeyDown() { if (controls != null) controls.setControlsVisible(true); }
    @Override public void onKeyCenter() { }
    @Override public void onSingleTap() { singleTaps++; if (controls != null) controls.setControlsVisible(true); }
    @Override public void onDoubleTap() { doubleTaps++; }

    private class CardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final int count;
        CardAdapter(int count) { this.count = count; }
        @NonNull public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View card = getLayoutInflater().inflate(R.layout.adapter_vod, parent, false);
            card.setLayoutParams(new RecyclerView.LayoutParams(-1, dp(142)));
            return new RecyclerView.ViewHolder(card) {};
        }
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            String[] titles = {"星际旅途", "山海之间", "追光的人", "夏日来信", "城市之声"};
            View card = holder.itemView;
            ((TextView) card.findViewById(R.id.name)).setText(titles[position % 5] + " " + (position + 1));
            ((TextView) card.findViewById(R.id.remark)).setText("8." + (position % 9) + " · 4K");
            TextView year = card.findViewById(R.id.year);
            year.setText("2026"); year.setVisibility(View.VISIBLE);
            int[] colors = {0xFF334C80, 0xFF366757, 0xFF785459, 0xFF6A5C91, 0xFF9A723C};
            GradientDrawable poster = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {colors[position % 5], Color.rgb(22, 30, 46)});
            card.findViewById(R.id.image).setBackground(poster);
            card.setOnClickListener(view -> { cardClicks++; lastAction = "card-" + position; });
            card.setOnLongClickListener(view -> { cardLongClicks++; return true; });
        }
        public int getItemCount() { return count; }
    }

}
