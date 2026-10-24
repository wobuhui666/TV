package com.fongmi.android.tv.ui.activity;

import android.os.Bundle;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.appcompat.widget.LinearLayoutCompat;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.*;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.ui.theme.RexTheme;
import java.util.Arrays;

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
    public JetStreamVodControlView controls;
    public CustomKeyDownVod gestures;
    public String page = "home";
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
        current = this;
        showPage("home");
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
        page = name;
        settings = null;
        if (name.equals("settings")) {
            root.removeAllViews();
            settings = new JetStreamSettingView(this);
            settings.setListener(this);
            root.addView(settings, new FrameLayout.LayoutParams(-1, -1));
            settings.showSection(JetStreamSettingView.SECTION_APP);
            settings.requestInitialFocus();
            return;
        }
        if (name.equals("home-layout")) {
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
            words.setLayoutManager(new GridLayoutManager(this, 2));
            words.setAdapter(new CardAdapter(8));
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
        dialog.show();
        dialog.getWindow().setLayout(dp(540), dp(340));
        JetStreamDialogDecor.tintButtons(dialog);
    }

    @Override protected void onThemeChanged() {
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
