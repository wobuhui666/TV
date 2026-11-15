package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.core.app.ActivityOptionsCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.DiscoverDetail;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.bean.DoubanDetail;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.databinding.ActivityDiscoverDetailBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.DiscoverCreditAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class DiscoverDetailActivity extends BaseActivity {

    private static final String TRANSITION = "discover_poster";

    private final Object requestTag = new Object();
    private ActivityDiscoverDetailBinding mBinding;
    private DiscoverCreditAdapter castAdapter;
    private DiscoverMediaKey key;
    private DiscoverDetail detail;
    private String title;
    private String poster;
    private String backdrop;
    private String overview;
    private String year;
    private String rating;
    private Vod doubanItem;
    private PosterSourcesController sources;
    private boolean sourcePanelOpen;
    private boolean metadataError;

    public static void start(Activity activity, Vod item, View sharedPoster) {
        if (com.fongmi.android.tv.setting.ExperienceSettings.usesLibrary()) {
            LibraryDetailActivity.start(activity, item);
            return;
        }
        DiscoverMediaKey key = DiscoverMediaKey.parse(item.getId());
        if (key != null) {
            start(activity, key, item.getName(), item.getPic(), item.getBackdrop(), item.getContent(), item.getYear(), item.getRemarks(), sharedPoster);
            return;
        }
        if (!item.getId().startsWith("douban:")) return;
        Intent intent = new Intent(activity, DiscoverDetailActivity.class);
        intent.putExtra("doubanItem", item);
        intent.putExtra("title", item.getName());
        intent.putExtra("poster", item.getPic());
        intent.putExtra("backdrop", item.getBackdrop());
        intent.putExtra("overview", item.getContent());
        intent.putExtra("year", item.getYear());
        intent.putExtra("rating", item.getRemarks());
        activity.startActivity(intent);
    }

    public static void start(Activity activity, DiscoverMediaKey key, String title, String poster, String backdrop,
                             String overview, String year, String rating, View sharedPoster) {
        if (com.fongmi.android.tv.setting.ExperienceSettings.usesLibrary()) {
            Vod item = new Vod(); item.setId(key.toString()); item.setTypeName(key.getMediaType());
            item.setName(title); item.setPic(poster); item.setBackdrop(backdrop); item.setContent(overview); item.setYear(year); item.setRemarks(rating);
            LibraryDetailActivity.start(activity, item);
            return;
        }
        Intent intent = new Intent(activity, DiscoverDetailActivity.class);
        intent.putExtra("key", key.toString());
        intent.putExtra("title", title);
        intent.putExtra("poster", poster);
        intent.putExtra("backdrop", backdrop);
        intent.putExtra("overview", overview);
        intent.putExtra("year", year);
        intent.putExtra("rating", rating);
        if (sharedPoster == null) {
            activity.startActivity(intent);
            return;
        }
        sharedPoster.setTransitionName(TRANSITION);
        activity.startActivity(intent, ActivityOptionsCompat.makeSceneTransitionAnimation(activity, sharedPoster, TRANSITION).toBundle());
        sharedPoster.post(() -> sharedPoster.setTransitionName(null));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDiscoverDetailBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        key = DiscoverMediaKey.parse(getIntent().getStringExtra("key"));
        doubanItem = getIntent().getParcelableExtra("doubanItem");
        if (key == null && doubanItem == null) {
            finish();
            return;
        }
        title = value(getIntent().getStringExtra("title"));
        poster = value(getIntent().getStringExtra("poster"));
        backdrop = value(getIntent().getStringExtra("backdrop"));
        overview = value(getIntent().getStringExtra("overview"));
        year = value(getIntent().getStringExtra("year"));
        rating = value(getIntent().getStringExtra("rating"));
        setCast();
        if (doubanItem != null) mBinding.sourceLabel.setText(R.string.discover_source_douban);
        if (BrowseExperienceSettings.isDetailSourcesEnabled()) {
            sources = new PosterSourcesController(this, mBinding.sourcePanel, this::closeSources);
            mBinding.search.setText(BrowseExperienceSettings.isSmartSourceEnabled() ? R.string.poster_sources_smart : R.string.poster_sources_open);
        }
        bindFallback();
        updateSourceMetadata();
        updateKeep();
        load();
        mBinding.search.requestFocus();
    }

    private void setCast() {
        mBinding.cast.setAdapter(castAdapter = new DiscoverCreditAdapter());
        mBinding.cast.setItemAnimator(null);
        mBinding.cast.addItemDecoration(new androidx.recyclerview.widget.RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@androidx.annotation.NonNull android.graphics.Rect outRect, @androidx.annotation.NonNull View view,
                                       @androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView parent,
                                       @androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView.State state) {
                outRect.right = ResUtil.dp2px(8);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.search.setOnClickListener(view -> {
            if (sources == null) CollectActivity.start(this, title);
            else openSources();
        });
        mBinding.keep.setOnClickListener(view -> toggleKeep());
        mBinding.fullOverview.setOnClickListener(view -> ContentDialog.create().content(overview).show(this));
        mBinding.retry.setOnClickListener(view -> load());
    }

    private void bindFallback() {
        mBinding.title.setText(title);
        mBinding.originalTitle.setVisibility(View.GONE);
        bindMeta();
        mBinding.creator.setVisibility(View.GONE);
        mBinding.overview.setText(overview);
        mBinding.overview.setVisibility(overview.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.fullOverview.setVisibility(hasLongOverview() ? View.VISIBLE : View.GONE);
        ImgUtil.load(title, poster, mBinding.poster);
        if (!backdrop.isEmpty()) ImgUtil.load(title, backdrop, mBinding.backdrop);
    }

    private void load() {
        metadataError = false;
        mBinding.retry.setVisibility(View.GONE);
        if (key == null) {
            loadDouban();
            return;
        }
        DiscoverApi.fetchDetail(key, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.DetailListener() {
            @Override
            public void onSuccess(DiscoverDetail value) {
                if (isInactive()) return;
                detail = value;
                bindDetail(value);
            }

            @Override
            public void onError(Exception e) {
                if (!isInactive()) {
                    metadataError = true;
                    mBinding.retry.setVisibility(sourcePanelOpen ? View.GONE : View.VISIBLE);
                }
            }
        });
    }

    private void bindDetail(DiscoverDetail item) {
        if (!item.getTitle().isEmpty()) title = item.getTitle();
        if (!item.getPoster().isEmpty()) poster = item.getPoster();
        if (!item.getBackdrop().isEmpty()) backdrop = item.getBackdrop();
        if (!item.getOverview().isEmpty()) overview = item.getOverview();
        if (!item.getYear().isEmpty()) year = item.getYear();
        if (!item.getRating().isEmpty()) rating = item.getRating();
        mBinding.title.setText(title);
        mBinding.originalTitle.setText(item.getOriginalTitle());
        mBinding.originalTitle.setVisibility(item.getOriginalTitle().isEmpty() || item.getOriginalTitle().equals(title) ? View.GONE : View.VISIBLE);
        bindMeta();
        String creator = item.getCreators().isEmpty() ? "" : getString(R.string.discover_creators, item.getCreators());
        mBinding.creator.setText(creator);
        mBinding.creator.setVisibility(creator.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.overview.setText(overview);
        mBinding.overview.setVisibility(overview.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.fullOverview.setVisibility(hasLongOverview() ? View.VISIBLE : View.GONE);
        ImgUtil.load(title, poster, mBinding.poster);
        if (!backdrop.isEmpty()) ImgUtil.load(title, backdrop, mBinding.backdrop);
        castAdapter.setItems(item.getCast());
        int castVisibility = item.getCast().isEmpty() ? View.GONE : View.VISIBLE;
        mBinding.castTitle.setVisibility(castVisibility);
        mBinding.cast.setVisibility(castVisibility);
        updateKeep();
        updateSourceMetadata();
        if (sourcePanelOpen) mBinding.fullOverview.setVisibility(View.GONE);
    }

    private void loadDouban() {
        if (doubanItem == null) return;
        DiscoverApi.fetchDoubanDetail(doubanItem, requestTag, new DiscoverApi.DoubanDetailListener() {
            @Override public void onSuccess(DoubanDetail item) {
                if (isInactive()) return;
                if (!item.getTitle().isEmpty()) title = item.getTitle();
                if (!item.getPoster().isEmpty()) poster = item.getPoster();
                if (!item.getComment().isEmpty()) overview = item.getComment();
                if (!item.getYear().isEmpty()) year = item.getYear();
                if (!item.getRating().isEmpty()) rating = item.getRating();
                bindFallback();
                String creator = item.getDirectors();
                mBinding.creator.setText(creator.isEmpty() ? "" : getString(R.string.discover_creators, creator));
                mBinding.creator.setVisibility(creator.isEmpty() ? View.GONE : View.VISIBLE);
                updateSourceMetadata();
                if (sourcePanelOpen) mBinding.fullOverview.setVisibility(View.GONE);
                // Enrichment may fail independently; source selection already works with Douban fields.
                DiscoverApi.matchDoubanToTmdb(item, BuildConfig.TMDB_API_KEY, requestTag, new DiscoverApi.MatchListener() {
                    @Override public void onMatch(DiscoverMediaKey matched) {
                        if (isInactive()) return;
                        key = matched;
                        updateKeep();
                        load();
                    }
                    @Override public void onNoMatch() { }
                    @Override public void onError(Exception error) { }
                });
            }
            @Override public void onError(Exception error) {
                if (isInactive()) return;
                metadataError = true;
                mBinding.retry.setVisibility(sourcePanelOpen ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void updateSourceMetadata() {
        if (sources == null) return;
        boolean movie = key != null ? key.isMovie() : doubanItem != null && "movie".equals(doubanItem.getTypeName());
        sources.metadata(title, detail == null ? "" : detail.getOriginalTitle(), year, movie, detail == null ? 0 : detail.getSeasons());
    }

    private void openSources() {
        sourcePanelOpen = true;
        android.view.ViewGroup.LayoutParams params = mBinding.artworkColumn.getLayoutParams();
        int available = getResources().getDisplayMetrics().widthPixels;
        params.width = Math.min(ResUtil.dp2px(360), Math.max(ResUtil.dp2px(280), (int) (available * 0.40f)));
        mBinding.artworkColumn.setLayoutParams(params);
        mBinding.poster.setVisibility(View.GONE);
        mBinding.sourcePanel.getRoot().setVisibility(View.VISIBLE);
        mBinding.fullOverview.setVisibility(View.GONE);
        mBinding.retry.setVisibility(View.GONE);
        sources.open();
    }

    private void closeSources() {
        sourcePanelOpen = false;
        sources.stop();
        mBinding.sourcePanel.getRoot().setVisibility(View.GONE);
        mBinding.poster.setVisibility(View.VISIBLE);
        android.view.ViewGroup.LayoutParams params = mBinding.artworkColumn.getLayoutParams();
        params.width = ResUtil.dp2px(216);
        mBinding.artworkColumn.setLayoutParams(params);
        mBinding.fullOverview.setVisibility(hasLongOverview() ? View.VISIBLE : View.GONE);
        mBinding.retry.setVisibility(metadataError ? View.VISIBLE : View.GONE);
        mBinding.search.setText(BrowseExperienceSettings.isSmartSourceEnabled() ? R.string.poster_sources_smart : R.string.poster_sources_open);
        mBinding.search.requestFocus();
    }

    @Override protected void onBackInvoked() {
        if (sourcePanelOpen) closeSources();
        else super.onBackInvoked();
    }

    @Override protected void onPause() {
        if (sources != null) sources.stop();
        super.onPause();
    }

    private void bindMeta() {
        List<String> values = new ArrayList<>();
        add(values, rating);
        add(values, year);
        if (detail != null) {
            add(values, detail.getGenres());
            add(values, detail.getCountries());
            if (detail.getRuntimeMinutes() > 0) add(values, getString(R.string.discover_runtime_minutes, detail.getRuntimeMinutes()));
            if (detail.getSeasons() > 0) add(values, getString(R.string.discover_seasons, detail.getSeasons()));
            if (detail.getEpisodes() > 0) add(values, getString(R.string.discover_episodes, detail.getEpisodes()));
            add(values, detail.getStatus());
        }
        mBinding.meta.setText(TextUtils.join(" · ", values));
        mBinding.meta.setVisibility(values.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void toggleKeep() {
        if (key == null) return;
        Keep saved = Keep.findDiscover(key.toString());
        if (saved != null) {
            saved.delete();
            Notify.show(getString(R.string.keep_del));
        } else {
            Keep keep = new Keep();
            keep.setKey(key.toString());
            keep.setType(Keep.TYPE_DISCOVER);
            keep.setCid(0);
            keep.setSiteName(getString(key.isMovie() ? R.string.discover_tmdb_movie : R.string.discover_tmdb_tv));
            keep.setVodName(title);
            keep.setVodPic(poster);
            keep.setCreateTime(System.currentTimeMillis());
            keep.save();
            Notify.show(getString(R.string.keep_add));
        }
        updateKeep();
        RefreshEvent.keep();
    }

    private void updateKeep() {
        mBinding.keep.setVisibility(key == null ? View.GONE : View.VISIBLE);
        if (key == null) return;
        boolean saved = Keep.findDiscover(key.toString()) != null;
        mBinding.keep.setText(saved ? R.string.discover_remove_keep : R.string.discover_add_keep);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private boolean hasLongOverview() {
        return overview.length() > 180;
    }

    private static void add(List<String> values, String value) {
        if (!TextUtils.isEmpty(value)) values.add(value);
    }

    @Override
    protected void onDestroy() {
        DiscoverApi.cancel(requestTag);
        if (sources != null) sources.stop();
        super.onDestroy();
    }

    private boolean isInactive() {
        return isFinishing() || isDestroyed();
    }
}
