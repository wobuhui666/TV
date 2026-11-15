package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ImageView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.TmdbLibraryApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.DiscoverCredit;
import com.fongmi.android.tv.bean.DiscoverDetail;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.DoubanDetail;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.LibraryMedia;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityLibraryDetailBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.source.EpisodeTarget;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.source.LibraryIdentity;
import com.fongmi.android.tv.source.LibrarySourceMatcher;
import com.fongmi.android.tv.source.MediaIdentity;
import com.fongmi.android.tv.source.MediaMatcher;
import com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.LibraryViews;
import com.fongmi.android.tv.ui.custom.LibraryMotion;
import com.fongmi.android.tv.ui.custom.LibraryPalette;
import com.fongmi.android.tv.ui.custom.LibraryHeroEffects;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.ui.dialog.PosterSourcePriorityDialog;
import com.fongmi.android.tv.ui.rex.RexArtworkColors;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The shared work page owns browsing; VideoActivity receives only an explicit playback intent. */
public final class LibraryDetailActivity extends BaseActivity implements LibraryResourcesController.Listener {
    private final Object tag = new Object();
    private final Object metadataTag = new Object();
    private final Object seasonTag = new Object();
    private final Object logoTag = new Object();
    private final LibraryPlaybackPrefetch linkPrefetch = new LibraryPlaybackPrefetch();
    private ActivityLibraryDetailBinding binding;
    private LibraryResourcesController resources;
    private Vod work;
    private DiscoverMediaKey key;
    private LibraryMedia.Metadata metadata;
    private LibraryMedia.Metadata renderedPeople;
    private LibraryMedia.Extras extras;
    private List<LibraryMedia.Episode> episodes = List.of();
    private History history;
    private String reference;
    private int selectedSeason = 1;
    private Integer selectedEpisode, focusedEpisode;
    private View restoreFocus;
    private String restoreFocusKey;
    private LinearLayout episodeSection, resourceSection, resourceRail, castSection, genreSection, extraSection;
    private TextView resourceStatus;
    private TextView episodePicker;
    private boolean episodesLoading, episodesError;
    private String originalFlag = "";
    private int originalPage;
    private ImageView emptyResourceIcon;
    private boolean extrasRequested, showMore, resumeRequested, sourceSearching, pendingAutoplay, metadataError;
    private boolean identityLocked, playbackActive, manualPlayback, originalEpisodeSelection;
    private boolean focusRestorePending;
    private boolean restoreResourceFocus;
    private boolean fallbackPending, fallbackSeasonChanged;
    private String displayedBackdrop = "", logoRequest = "";
    private boolean backgroundReady, logoFallback;
    private LibraryHeroEffects heroEffects;
    private int logoGeneration;
    private Vod preparedSource;
    private final Runnable warmSelection = this::preparePlayback;
    private Vod playingSource;
    private Vod originalSource;
    private final java.util.Map<String, View> resourceCards = new java.util.LinkedHashMap<>();
    private final ActivityResultLauncher<Intent> player = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (isInactive()) return;
        playbackActive = false;
        History returned = result.getData() == null ? null : History.objectFrom(result.getData().getStringExtra("libraryHistory"));
        if (returned != null && reference.equals(returned.getMediaRef())) history = returned;
        else {
            History stored = History.findByMedia(reference);
            if (stored != null) history = stored;
        }
        int previousSeason = selectedSeason;
        if (history != null && history.getMediaSeason() != null) selectedSeason = history.getMediaSeason();
        if (history != null && history.getMediaEpisode() != null) selectedEpisode = history.getMediaEpisode();
        configureResources();
        if (result.getResultCode() == VideoActivity.RESULT_LIBRARY_FAILURE) {
            if (manualPlayback) {
                pendingAutoplay = false;
                Notify.show(R.string.library_playback_failed);
                updatePlay(); focusResources();
            } else {
                pendingAutoplay = true;
                fallbackPending = true;
                fallbackSeasonChanged = previousSeason != selectedSeason;
                binding.libraryRoot.post(this::retryPendingPlayback);
            }
        } else {
            pendingAutoplay = false;
            restoreResourceFocus = result.getData() != null && result.getData().getBooleanExtra("librarySelectResources", false);
            updatePlay();
            if (previousSeason != selectedSeason) { resources.start(); loadEpisodes(); }
            else if (!movie() && !episodes.isEmpty()) renderEpisodes();
            else if (key == null && originalSource != null) renderOriginalEpisodes(originalSource);
            restorePlaybackFocus();
        }
        updateResourceSelection();
        binding.libraryRoot.post(this::schedulePreparation);
    });

    public static void start(Activity activity, Vod work) {
        activity.startActivity(new Intent(activity, LibraryDetailActivity.class).putExtra("work", work));
    }

    public static void resume(Activity activity, History history) {
        Vod work = new Vod();
        work.setName(history.getVodName());
        work.setPic(history.getVodPic());
        work.setId(history.getVodId());
        work.setSite(VodConfig.get().getSite(history.getSiteKey()));
        DiscoverMediaKey key = DiscoverMediaKey.parse(history.getMediaRef());
        if (key != null) { work.setId(key.toString()); work.setTypeName(key.getMediaType()); }
        activity.startActivity(new Intent(activity, LibraryDetailActivity.class).putExtra("work", work).putExtra("resumeHistory", history.toString()));
    }

    @Override protected ViewBinding getBinding() { return binding = ActivityLibraryDetailBinding.inflate(getLayoutInflater()); }
    @Override protected boolean customWall() { return false; }

    @Override protected void onThemeChanged() {
        applyAppearance();
    }

    @Override protected void initView(Bundle saved) {
        work = getIntent().getParcelableExtra("work");
        if (work == null) { finish(); return; }
        key = DiscoverMediaKey.parse(work.getId());
        originalSource = key == null && !work.getSiteKey().isEmpty() ? work : null;
        reference = LibraryIdentity.reference(work, VodConfig.getCid());
        history = History.findByMedia(reference);
        String received = getIntent().getStringExtra("resumeHistory");
        if (received != null) { history = History.objectFrom(received); resumeRequested = true; }
        if (history != null && history.getMediaSeason() != null) selectedSeason = history.getMediaSeason();
        if (history != null) selectedEpisode = history.getMediaEpisode();
        if (saved != null) { selectedSeason = saved.getInt("season", selectedSeason); selectedEpisode = saved.containsKey("episode") ? saved.getInt("episode") : selectedEpisode; }
        resources = new LibraryResourcesController(this);
        List<Vod> seeded = new ArrayList<>(work.getSourceCandidates());
        if (key == null && !work.getSiteKey().isEmpty()) seeded.add(0, work);
        if (resumeRequested) {
            Vod previous = new Vod();
            previous.setName(work.getName()); previous.setPic(work.getPic()); previous.setId(history.getVodId());
            previous.setTypeName(work.getTypeName()); previous.setSite(VodConfig.get().getSite(history.getSiteKey()));
            seeded.add(0, previous);
        }
        resources.seed(seeded);
        heroEffects = new LibraryHeroEffects(binding.backdrop, binding.blurredBackdrop);
        binding.libraryScroll.setSmoothScrollingEnabled(true);
        for (TextView text : new TextView[]{binding.title, binding.meta, binding.overview, binding.seriesStatus, binding.ratings, binding.clock, binding.headerTitle, binding.source}) LibraryViews.applyTypeface(text);
        LibraryViews.playButton(binding.play);
        for (View action : new View[]{binding.source, binding.keep, binding.more}) LibraryViews.heroAction(action);
        LibraryViews.bindAppearance(binding.libraryRoot, this::applyAppearance);
        createSections();
        binding.hero.post(() -> {
            int width = Math.min(LibraryViews.dp(540), binding.hero.getWidth() - LibraryViews.dp(96));
            if (width <= 0) return;
            binding.heroInformation.getLayoutParams().width = width;
            binding.heroInformation.requestLayout();
            LibraryMotion.enter(binding.heroInformation);
            binding.heroInformation.post(this::updateChrome);
        });
        bindWork();
        configureResources();
        resources.start();
        loadMetadata();
        resources.browse(originalSource);
        binding.play.post(binding.play::requestFocus);
    }

    @Override protected void initEvent() {
        if (work == null) return;
        binding.heroInformation.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateChrome());
        for (View action : new View[]{binding.play, binding.source, binding.keep, binding.more}) {
            LibraryViews.bindFocusMotion(action);
            action.setOnFocusChangeListener((view, focused) -> {
                LibraryViews.animateFocus(view, focused);
                if (focused) {
                    focusedEpisode = null;
                    binding.libraryScroll.smoothScrollTo(0, 0);
                    schedulePreparation();
                }
            });
            action.setOnKeyListener((view, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) focusEpisodes();
                return true;
            });
        }
        binding.play.setOnClickListener(view -> play(null, binding.play));
        binding.source.setOnClickListener(view -> focusResources());
        binding.keep.setOnClickListener(view -> toggleKeep());
        binding.more.setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setItems(new String[]{getString(R.string.detail_desc), getString(R.string.library_more), getString(R.string.library_source_priority)}, (dialog, which) -> {
                    if (which == 0) ContentDialog.create().content(work.getContent()).show(this);
                    else if (which == 1) { showMore = true; loadExtras(); renderExtras(); if (castSection.isShown()) castSection.requestFocus(); }
                    else PosterSourcePriorityDialog.show(this, () -> resources.start());
                }).show());
        binding.libraryScroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            updateChrome();
            if (y > LibraryViews.dp(140)) loadExtras();
        });
    }

    private void updateChrome() {
        if (heroEffects == null) return;
        int y = binding.libraryScroll.getScrollY();
        int height = binding.hero.getHeight();
        heroEffects.onScroll(y, height);
        binding.scrim.setCollapse(height == 0 ? 0f : (float) y / height);
        float material = Math.max(0f, Math.min(1f, (y - LibraryViews.dp(40)) / (float) LibraryViews.dp(96)));
        binding.headerSurface.update(binding.libraryScroll, material);
        binding.headerDivider.setAlpha(material);
        View heading = binding.logo.getVisibility() == View.VISIBLE ? binding.logo : binding.title;
        int bottom = binding.heroInformation.getTop() + heading.getBottom() - y;
        int barHeight = binding.libraryHeader.getHeight() == 0 ? LibraryViews.dp(72) : binding.libraryHeader.getHeight();
        binding.headerTitle.setAlpha(Math.max(0f, Math.min(1f, (barHeight - bottom) / (float) LibraryViews.dp(24))));
    }

    private void applyAppearance() {
        binding.libraryRoot.setBackgroundColor(LibraryPalette.surface());
        binding.content.setBackgroundColor(LibraryPalette.surface());
        binding.scrim.setSurface(LibraryPalette.surface());
        binding.title.setTextColor(LibraryPalette.primary());
        binding.meta.setTextColor(LibraryPalette.secondary());
        binding.overview.setTextColor(LibraryPalette.secondary());
        binding.seriesStatus.setTextColor(LibraryPalette.secondary());
        binding.ratings.setTextColor(LibraryPalette.secondary());
        binding.headerTitle.setTextColor(LibraryPalette.primary());
        binding.clock.setTextColor(LibraryPalette.primary());
        if (emptyResourceIcon != null) emptyResourceIcon.setColorFilter(LibraryPalette.secondary());
        binding.headerDivider.setBackgroundColor(com.fongmi.android.tv.ui.theme.JetStreamPalette.resolveColor(this, R.color.jetstream_outline_variant));
        binding.source.setTextColor(LibraryPalette.primary());
        for (ImageView action : new ImageView[]{binding.keep, binding.more}) action.setImageTintList(ColorStateList.valueOf(LibraryPalette.primary()));
        updateChrome();
    }

    private boolean movie() {
        if (key != null) return key.isMovie();
        MediaIdentity.Kind kind = MediaMatcher.kind(work.getTypeName());
        if (kind != MediaIdentity.Kind.UNKNOWN) return kind == MediaIdentity.Kind.MOVIE;
        return work.getFlags().stream().flatMap(flag -> flag.getEpisodes().stream()).noneMatch(episode ->
                !episode.isGeneratedLabel() && episode.getLabel().isApiSafe());
    }

    private void createSections() {
        episodeSection = LibraryViews.section(this, binding.sections, getString(R.string.library_episodes));
        episodeSection.getChildAt(0).setVisibility(View.GONE);
        episodeSection.setVisibility(movie() ? View.GONE : View.VISIBLE);
        resourceSection = LibraryViews.section(this, binding.sections, getString(R.string.library_resources));
        emptyResourceIcon = new ImageView(this);
        emptyResourceIcon.setImageResource(R.drawable.msr_live_tv);
        emptyResourceIcon.setColorFilter(LibraryPalette.secondary());
        LinearLayout.LayoutParams emptyIconLayout = new LinearLayout.LayoutParams(LibraryViews.dp(56), LibraryViews.dp(56));
        emptyIconLayout.gravity = Gravity.CENTER_HORIZONTAL; emptyIconLayout.topMargin = LibraryViews.dp(12);
        resourceSection.addView(emptyResourceIcon, emptyIconLayout);
        emptyResourceIcon.setVisibility(View.GONE);
        resourceStatus = LibraryViews.text(this, getString(R.string.library_searching), 13, 0xFFBBB8C4);
        resourceStatus.setFocusable(true);
        resourceStatus.setFocusableInTouchMode(true);
        resourceStatus.setMinimumHeight(LibraryViews.dp(48));
        resourceStatus.setOnKeyListener((view, code, event) -> {
            if (code != KeyEvent.KEYCODE_DPAD_UP) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) binding.source.requestFocus();
            return true;
        });
        resourceSection.addView(resourceStatus);
        resourceRail = LibraryViews.rail(this, resourceSection);
        castSection = LibraryViews.section(this, binding.sections, getString(R.string.library_cast));
        genreSection = LibraryViews.section(this, binding.sections, getString(R.string.library_genres));
        extraSection = new LinearLayout(this); extraSection.setOrientation(LinearLayout.VERTICAL);
        binding.sections.addView(extraSection);
        castSection.setVisibility(View.GONE); genreSection.setVisibility(View.GONE);
        TextView attribution = LibraryViews.text(this, getString(R.string.library_tmdb_attribution), 10, 0xFF8B8894);
        attribution.setPadding(0, LibraryViews.dp(24), 0, 0);
        binding.sections.addView(attribution);
    }

    private void bindWork() {
        binding.title.setText(work.getName());
        binding.headerTitle.setText(work.getName());
        List<String> meta = new ArrayList<>();
        if (!work.getYear().isEmpty()) meta.add(work.getYear());
        if (!work.getArea().isEmpty()) meta.add(work.getArea());
        if (metadata != null && metadata.detail().getRuntimeMinutes() > 0) meta.add(getString(R.string.library_minutes, metadata.detail().getRuntimeMinutes()));
        binding.meta.setText(String.join(" · ", meta));
        binding.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        binding.ratings.setText(key != null && !work.getRemarks().isEmpty() ? "★ " + work.getRemarks() + "  TMDB" : work.getRemarks());
        binding.ratings.setVisibility(work.getRemarks().isEmpty() ? View.GONE : View.VISIBLE);
        binding.overview.setText(work.getContent().isEmpty() ? getString(R.string.library_no_overview) : work.getContent());
        bindSeriesStatus();
        binding.clock.setText(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
        String backdrop = work.getBackdrop().isEmpty() ? work.getPic() : work.getBackdrop();
        if (backdrop.equals(displayedBackdrop) && backgroundReady) { updateKeep(); updatePlay(); return; }
        displayedBackdrop = backdrop; backgroundReady = false;
        ImgUtil.loadArtwork(work.getName(), backdrop, binding.backdrop, success -> binding.backdrop.post(() -> {
            if (!success || isInactive()) return;
            Bitmap bitmap = LibraryViews.artworkBitmap(binding.backdrop);
            if (bitmap == null) return;
            backgroundReady = true;
            heroEffects.setBitmap(bitmap);
            updateChrome();
        }));
        updateKeep(); updatePlay();
    }

    private void loadMetadata() {
        TmdbLibraryApi.cancel(metadataTag); DiscoverApi.cancel(metadataTag);
        if (key != null) {
            DiscoverMediaKey requestedKey = key;
            TmdbLibraryApi.detail(key, BuildConfig.TMDB_API_KEY, metadataTag, new TmdbLibraryApi.Listener<>() {
                @Override public void onSuccess(LibraryMedia.Metadata value) {
                    if (isInactive() || !requestedKey.equals(key)) return;
                    metadataError = false;
                    metadata = value;
                    DiscoverDetail detail = value.detail();
                    work.setName(detail.getTitle()); work.setOriginalName(detail.getOriginalTitle());
                    if (!detail.getPoster().isEmpty()) work.setPic(detail.getPoster());
                    if (!detail.getBackdrop().isEmpty()) work.setBackdrop(detail.getBackdrop());
                    if (!detail.getOverview().isEmpty()) work.setContent(detail.getOverview());
                    work.setYear(detail.getYear()); work.setArea(detail.getCountries()); work.setRemarks(detail.getRating());
                    bindLogo(value);
                    if (!identityLocked && !movie() && value.seasons().stream().noneMatch(season -> season.number() == selectedSeason)) selectedSeason = value.seasons().stream().filter(season -> season.number() > 0).mapToInt(LibraryMedia.Season::number).min().orElse(1);
                    bindWork(); renderPeople();
                    if (!resources.isSelecting() && !playbackActive) { configureResources(); resources.start(); }
                    loadEpisodes();
                    if (resumeRequested) { resumeRequested = false; play(null, binding.play); }
                }
                @Override public void onError(Exception error) {
                    if (isInactive()) return;
                    metadataError = true;
                    if (resumeRequested) { resumeRequested = false; play(null, binding.play); }
                }
            });
        } else if (work.getId().startsWith("douban:")) {
            DiscoverApi.fetchDoubanDetail(work, metadataTag, new DiscoverApi.DoubanDetailListener() {
                @Override public void onSuccess(DoubanDetail detail) {
                    if (isInactive()) return;
                    work.setTypeName(detail.getMediaType()); work.setName(detail.getTitle()); work.setContent(detail.getComment()); work.setYear(detail.getYear());
                    bindWork();
                    DiscoverApi.matchDoubanToTmdb(detail, BuildConfig.TMDB_API_KEY, metadataTag, new DiscoverApi.MatchListener() {
                        @Override public void onMatch(DiscoverMediaKey value) { if (!isInactive() && !identityLocked) { key = value; work.setId(value.toString()); reference = value.toString(); loadMetadata(); } }
                        @Override public void onNoMatch() { }
                        @Override public void onError(Exception error) { }
                    });
                }
                @Override public void onError(Exception error) { }
            });
        } else {
            if (originalSource != null) {
                TmdbLibraryApi.search(work.getName(), "", 1, BuildConfig.TMDB_API_KEY, metadataTag, new TmdbLibraryApi.Listener<>() {
                    @Override public void onSuccess(TmdbLibraryApi.Page result) {
                        if (isInactive() || identityLocked || key != null) return;
                        Vod match = null;
                        for (Vod candidate : result.items()) {
                            if (!LibrarySourceMatcher.match(originalSource, candidate).automatic()) continue;
                            if (match != null) return;
                            match = candidate;
                        }
                        if (match != null) {
                            key = DiscoverMediaKey.parse(match.getId()); reference = match.getId();
                            History linked = History.findByMedia(reference);
                            if (linked != null) history = linked;
                            metadata = null; loadMetadata();
                        }
                    }
                    @Override public void onError(Exception error) { }
                });
                if (!originalSource.getFlags().isEmpty()) renderOriginalEpisodes(originalSource);
            }
            if (resumeRequested) { resumeRequested = false; play(work, binding.play); }
        }
    }

    private void configureResources() {
        linkPrefetch.clear(); preparedSource = null;
        resources.metadata(work.getName(), metadata == null ? (work.getOriginalName().isEmpty() ? List.of() : List.of(work.getOriginalName())) : metadata.aliases(),
                work.getYear(), movie(), movie() ? null : selectedSeason, history, key == null ? originalSource : null);
    }

    @Override public void onSourceDetail(Vod source) {
        originalSource = source;
        if (isInactive() || key != null) return;
        work = source;
        bindWork(); renderOriginalEpisodes(source);
        if (!resources.isSelecting() && !playbackActive) { configureResources(); resources.start(); }
        if (!identityLocked) loadMetadata();
    }

    private void loadEpisodes() {
        if (movie() || key == null) return;
        TmdbLibraryApi.cancel(seasonTag);
        int number = selectedSeason;
        episodesLoading = true;
        episodesError = false;
        renderEpisodes();
        TmdbLibraryApi.season(key, number, BuildConfig.TMDB_API_KEY, seasonTag, new TmdbLibraryApi.Listener<>() {
            @Override public void onSuccess(List<LibraryMedia.Episode> values) { if (!isInactive() && selectedSeason == number) { episodesLoading = false; episodes = values; renderEpisodes(); bindSeriesStatus(); schedulePreparation(); } }
            @Override public void onError(Exception error) { if (!isInactive() && selectedSeason == number) { episodesLoading = false; episodesError = true; episodes = List.of(); renderEpisodes(); bindSeriesStatus(); } }
        });
    }

    private void renderEpisodes() {
        String focused = episodeSection.hasFocus() && getCurrentFocus() != null && getCurrentFocus().getTag() instanceof String value ? value : null;
        episodeSection.removeViews(1, episodeSection.getChildCount() - 1);
        episodeSection.setVisibility(movie() ? View.GONE : View.VISIBLE);
        if (movie()) return;
        TextView season = LibraryViews.button(this, getString(selectedSeason == 0 ? R.string.library_specials : R.string.library_season, selectedSeason) + "  ▾");
        season.setTag("seasonSelector");
        season.setId(View.generateViewId());
        episodePicker = season;
        LibraryViews.chip(season);
        season.setNextFocusUpId(R.id.play);
        season.setOnClickListener(view -> chooseSeason());
        LinearLayout controls = new LinearLayout(this);
        controls.setClipChildren(false);
        episodeSection.addView(controls);
        controls.addView(season, new LinearLayout.LayoutParams(-2, -2));
        if (episodesLoading || episodes.isEmpty()) {
            TextView state = LibraryViews.text(this, getString(episodesLoading ? R.string.library_episodes_loading
                    : R.string.library_episodes_empty), 13, 0xFFBBB8C4);
            state.setPadding(0, LibraryViews.dp(16), 0, LibraryViews.dp(16));
            state.setTag(episodesLoading ? "episodesLoading" : "episodesEmpty");
            episodeSection.addView(state);
            if (focused != null) season.post(season::requestFocus);
            return;
        }
        TextView all = LibraryViews.button(this, getString(R.string.library_all_episodes));
        all.setTag("allEpisodes");
        all.setNextFocusUpId(R.id.play);
        LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(-2, -2);
        allParams.setMarginStart(LibraryViews.dp(12)); controls.addView(all, allParams);
        all.setOnClickListener(view -> new MaterialAlertDialogBuilder(this).setTitle(R.string.library_all_episodes)
                .setSingleChoiceItems(episodes.stream().map(item -> getString(R.string.library_episode_title, item.number(), item.name())).toArray(String[]::new),
                        currentEpisodeIndex(), (dialog, position) -> {
                            dialog.dismiss();
                            View target = binding.libraryRoot.findViewWithTag("episode:" + selectedSeason + ":" + episodes.get(position).number());
                            if (target != null) target.requestFocus();
                        }).show());
        LinearLayout row = LibraryViews.rail(this, episodeSection);
        for (LibraryMedia.Episode episode : episodes) {
            String focusKey = "episode:" + episode.season() + ":" + episode.number();
            List<String> info = new ArrayList<>();
            if (!episode.airDate().isEmpty()) info.add(episode.airDate());
            if (episode.runtime() > 0) info.add(getString(R.string.library_minutes, episode.runtime()));
            boolean future = !episode.airDate().isEmpty() && episode.airDate().compareTo(LocalDate.now().toString()) > 0;
            if (future) info.add(getString(R.string.library_airing));
            ViewGroup card = LibraryViews.card(this, row, 198, episode.still().isEmpty() ? work.getBackdrop() : episode.still(), 111,
                    getString(R.string.library_episode_title, episode.number(), episode.name()), String.join(" · ", info), "", () -> {
                        if (future) { Notify.show(R.string.library_airing); return; }
                        selectedEpisode = episode.number(); play(null, binding.libraryRoot.findViewWithTag(focusKey));
                    });
            card.setTag(focusKey);
            card.setId(View.generateViewId());
            card.setNextFocusUpId(season.getId());
            if (episode.number() == (selectedEpisode == null ? 1 : selectedEpisode)) {
                season.setNextFocusDownId(card.getId()); all.setNextFocusDownId(card.getId());
            }
            card.setOnFocusChangeListener((view, focusedCard) -> {
                LibraryViews.animateFocus(view, focusedCard);
                if (focusedCard && !playbackActive && !resources.isSelecting()) {
                    if (!Objects.equals(focusedEpisode, episode.number())) { linkPrefetch.clear(); preparedSource = null; }
                    focusedEpisode = episode.number();
                    schedulePreparation();
                }
            });
            card.setSelected(Objects.equals(selectedEpisode, episode.number()));
            if (history != null && Objects.equals(history.getMediaSeason(), episode.season()) && Objects.equals(history.getMediaEpisode(), episode.number()) && history.getDuration() > 0) {
                android.widget.ProgressBar progress = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(100); progress.setProgress((int) Math.min(100, 100 * history.getPosition() / history.getDuration()));
                card.addView(progress, new LinearLayout.LayoutParams(-1, LibraryViews.dp(3)));
            }
        }
        if (focused != null) {
            View restored = binding.libraryRoot.findViewWithTag(focused);
            if (restored == null) restored = binding.libraryRoot.findViewWithTag("episode:" + selectedSeason + ":" + episodes.get(currentEpisodeIndex()).number());
            if (restored != null) restored.post(restored::requestFocus);
        }
    }

    private void chooseSeason() {
        if (metadata == null || metadata.seasons().isEmpty()) return;
        String[] labels = metadata.seasons().stream().map(season -> season.number() == 0 ? getString(R.string.library_specials) : getString(R.string.library_season, season.number())).toArray(String[]::new);
        int current = 0;
        for (int i = 0; i < metadata.seasons().size(); i++) if (metadata.seasons().get(i).number() == selectedSeason) current = i;
        new MaterialAlertDialogBuilder(this).setSingleChoiceItems(labels, current, (dialog, index) -> {
            dialog.dismiss();
            selectedSeason = metadata.seasons().get(index).number(); selectedEpisode = focusedEpisode = null;
            episodes = List.of(); linkPrefetch.clear();
            resources.cancelSelection(); pendingAutoplay = false; configureResources(); bindSeriesStatus(); resources.start(); loadEpisodes();
        }).show();
    }

    private int currentEpisodeIndex() {
        int number = focusedEpisode != null ? focusedEpisode : selectedEpisode == null ? 1 : selectedEpisode;
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).number() == number) return i;
        return 0;
    }

    private void focusEpisodes() {
        if (episodeSection.isShown()) {
            View current = binding.libraryRoot.findViewWithTag("episode:" + selectedSeason + ":" + (selectedEpisode == null ? 1 : selectedEpisode));
            if (current != null && current.requestFocus()) return;
            if (episodePicker != null && episodePicker.isShown() && episodePicker.requestFocus()) return;
        }
        focusResources();
    }

    private void renderPeople() {
        boolean visible = ExperienceSettings.getMode() != ExperienceMode.SIMPLE || showMore;
        castSection.setVisibility(visible && metadata != null && !metadata.detail().getCast().isEmpty() ? View.VISIBLE : View.GONE);
        genreSection.setVisibility(visible && metadata != null && !metadata.detail().getGenres().isEmpty() ? View.VISIBLE : View.GONE);
        if (metadata == null || !visible || metadata == renderedPeople) return;
        String focused = (castSection.hasFocus() || genreSection.hasFocus()) && getCurrentFocus() != null
                && getCurrentFocus().getTag() instanceof String value ? value : null;
        renderedPeople = metadata;
        castSection.removeViews(1, castSection.getChildCount() - 1);
        genreSection.removeViews(1, genreSection.getChildCount() - 1);
        LinearLayout cast = LibraryViews.rail(this, castSection);
        for (DiscoverCredit credit : metadata.detail().getCast()) {
            ViewGroup card = LibraryViews.card(this, cast, 90, credit.getProfile(), 86, credit.getName(), credit.getCharacter(), "", () -> {
                if (credit.getId() > 0) LibrarySearchActivity.credits(this, credit.getId(), credit.getName());
            });
            LibraryViews.portrait(card);
            card.setTag("person:" + credit.getId());
        }
        LinearLayout genres = LibraryViews.rail(this, genreSection);
        for (String genre : metadata.detail().getGenres().split("、")) {
            TextView chip = LibraryViews.button(this, genre); LibraryViews.chip(chip); genres.addView(chip);
            chip.setTag("genre:" + genre);
            chip.setOnClickListener(view -> DiscoverActivity.startGenre(this, movie() ? "movie" : "tv", genre));
        }
        if (focused != null) {
            View restored = binding.libraryRoot.findViewWithTag(focused);
            if (restored != null) restored.post(restored::requestFocus);
        }
    }

    private void renderOriginalEpisodes(Vod source) {
        if (movie() && source.getFlags().stream().allMatch(flag -> flag.getEpisodes().size() <= 1)) return;
        String focused = episodeSection.hasFocus() && getCurrentFocus() != null && getCurrentFocus().getTag() instanceof String value ? value : null;
        episodeSection.setVisibility(View.VISIBLE);
        episodeSection.removeViews(1, episodeSection.getChildCount() - 1);
        List<Flag> flags = source.getFlags().stream().filter(flag -> !flag.getEpisodes().isEmpty()).toList();
        if (flags.isEmpty()) return;
        if (originalFlag.isEmpty() && history != null) originalFlag = history.getVodFlag();
        Flag selected = flags.stream().filter(flag -> flag.getFlag().equals(originalFlag)).findFirst().orElse(flags.get(0));
        originalFlag = selected.getFlag();
        LinearLayout controls = new LinearLayout(this); controls.setClipChildren(false);
        episodeSection.addView(controls);
        TextView line = LibraryViews.button(this, selected.getShow() + "  ▾");
        line.setTag("originalLine"); line.setId(View.generateViewId());
        line.setNextFocusUpId(R.id.play); episodePicker = line;
        controls.addView(line);
        line.setOnClickListener(view -> new MaterialAlertDialogBuilder(this).setTitle(R.string.library_select_source)
                .setSingleChoiceItems(flags.stream().map(Flag::getShow).toArray(String[]::new), flags.indexOf(selected), (dialog, index) -> {
                    dialog.dismiss(); originalFlag = flags.get(index).getFlag(); originalPage = 0;
                    renderOriginalEpisodes(source); episodePicker.requestFocus();
                }).show());
        int count = selected.getEpisodes().size();
        originalPage = Math.min(originalPage, (count - 1) / 40);
        if (count > 40) {
            int pages = (count + 39) / 40;
            String[] labels = new String[pages];
            for (int i = 0; i < pages; i++) labels[i] = (i * 40 + 1) + " – " + Math.min(count, (i + 1) * 40);
            TextView range = LibraryViews.button(this, labels[originalPage] + "  ▾"); range.setTag("originalRange");
            range.setNextFocusUpId(R.id.play);
            LinearLayout.LayoutParams rangeParams = new LinearLayout.LayoutParams(-2, -2); rangeParams.setMarginStart(LibraryViews.dp(12));
            controls.addView(range, rangeParams);
            range.setOnClickListener(view -> new MaterialAlertDialogBuilder(this).setTitle(R.string.library_all_episodes)
                    .setSingleChoiceItems(labels, originalPage, (dialog, index) -> {
                        dialog.dismiss(); originalPage = index; renderOriginalEpisodes(source);
                        binding.libraryRoot.findViewWithTag("originalRange").requestFocus();
                    }).show());
        }
        LinearLayout row = null;
        for (int i = originalPage * 40; i < Math.min(count, (originalPage + 1) * 40); i++) {
            Episode episode = selected.getEpisodes().get(i);
            if (i % 5 == 0) {
                row = new LinearLayout(this); row.setClipChildren(false); row.setClipToPadding(false);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2); rowParams.topMargin = LibraryViews.dp(12);
                episodeSection.addView(row, rowParams);
            }
            Flag flag = selected;
            String focusKey = "original:" + flag.getFlag() + ":" + episode.getUrl();
            TextView card = LibraryViews.button(this, episode.getName());
            card.setMaxLines(1); card.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LibraryViews.dp(48), 1f);
            params.setMarginEnd(LibraryViews.dp(12)); row.addView(card, params);
            card.setOnClickListener(view -> {
                storePlaybackFocus(binding.libraryRoot.findViewWithTag(focusKey));
                resources.cancelSelection();
                originalEpisodeSelection = manualPlayback = identityLocked = true;
                onSelected(source, flag, episode);
            });
            card.setTag(focusKey);
            if (i < originalPage * 40 + 5) card.setNextFocusUpId(line.getId());
            if (history != null && episode.getUrl().equals(history.getEpisodeUrl()) && flag.getFlag().equals(history.getVodFlag())) card.setSelected(true);
        }
        if (row != null) while (row.getChildCount() < 5) row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (focused != null) {
            View restored = binding.libraryRoot.findViewWithTag(focused);
            if (restored != null) restored.post(restored::requestFocus);
        }
    }

    private void loadExtras() {
        if (key == null || extrasRequested || ExperienceSettings.getMode() == ExperienceMode.SIMPLE && !showMore) return;
        extrasRequested = true;
        TmdbLibraryApi.extras(key, BuildConfig.TMDB_API_KEY, tag, new TmdbLibraryApi.Listener<>() {
            @Override public void onSuccess(LibraryMedia.Extras value) { if (!isInactive()) { extras = value; renderExtras(); } }
            @Override public void onError(Exception error) { extrasRequested = false; }
        });
    }

    private void renderExtras() {
        renderPeople();
        extraSection.removeAllViews();
        if (extras == null || ExperienceSettings.getMode() == ExperienceMode.SIMPLE && !showMore) return;
        if (!extras.videos().isEmpty()) {
            LinearLayout row = LibraryViews.rail(this, LibraryViews.section(this, extraSection, getString(R.string.library_trailers)));
            for (LibraryMedia.Video video : extras.videos()) LibraryViews.card(this, row, 240, video.thumbnail(), 135, video.name(), "", "", () -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(video.url()))); }
                catch (ActivityNotFoundException error) { Notify.show(R.string.library_external_unavailable); }
            });
        }
        if (!extras.images().isEmpty()) {
            LinearLayout row = LibraryViews.rail(this, LibraryViews.section(this, extraSection, getString(R.string.library_images)));
            for (int i = 0; i < extras.images().size(); i++) {
                int position = i;
                ViewGroup card = LibraryViews.card(this, row, 240, extras.images().get(i), 135, "", "", "", () -> LibraryImagesActivity.start(this, extras.images(), position));
                card.setTag("image:" + position);
                card.setContentDescription(getString(R.string.library_images) + " " + (position + 1));
            }
        }
        if (!extras.recommendations().isEmpty()) {
            LinearLayout row = LibraryViews.rail(this, LibraryViews.section(this, extraSection, getString(R.string.library_similar)));
            for (Vod item : extras.recommendations()) LibraryViews.related(this, row, item.getPic(), item.getName(), item.getYear() + " · " + item.getRemarks(), item.getContent(), () -> LibraryNavigation.open(this, item, null));
        }
    }

    @Override public void onResources(List<Vod> values, boolean running, boolean partialError) {
        if (isInactive()) return;
        boolean waitingFocus = resourceStatus.hasFocus();
        sourceSearching = running;
        resourceStatus.setText(values.isEmpty() ? getString(running ? R.string.library_searching : partialError ? R.string.library_resource_error : R.string.library_no_resources) : "");
        boolean empty = values.isEmpty();
        resourceStatus.setVisibility(empty ? View.VISIBLE : View.GONE);
        resourceSection.getChildAt(0).setVisibility(empty ? View.GONE : View.VISIBLE);
        resourceSection.setMinimumHeight(empty ? LibraryViews.dp(156) : 0);
        emptyResourceIcon.setVisibility(empty ? View.VISIBLE : View.GONE);
        resourceStatus.setGravity(empty ? Gravity.CENTER : Gravity.START);
        ((View) resourceRail.getParent()).setVisibility(empty ? View.GONE : View.VISIBLE);
        // Keep the focused resource rail intact when additional sites finish searching.
        {
            java.util.Set<String> active = new java.util.HashSet<>();
            for (Vod source : values) {
                String id = LibraryIdentity.sourceKey(source); active.add(id);
                if (resourceCards.containsKey(id)) continue;
                String sourceTitle = source.getName().equals(work.getName()) ? "" : source.getName();
                ViewGroup card = LibraryViews.card(this, resourceRail, 202, "", 0, source.getSiteName().isEmpty() ? source.getSiteKey() : source.getSiteName(), sourceTitle, source.getRemarks(), () -> play(source, resourceCards.get(id)));
                if (!sourceTitle.isEmpty()) {
                    View title = card.getChildAt(1);
                    card.removeView(title); card.addView(title, 0);
                }
                card.setTag("resource:" + id);
                card.setMinimumHeight(LibraryViews.dp(72));
                card.setNextFocusUpId(R.id.source);
                boolean selected = playingSource != null && source.getSiteKey().equals(playingSource.getSiteKey()) && source.getId().equals(playingSource.getId())
                        || playingSource == null && history != null && source.getSiteKey().equals(history.getSiteKey()) && source.getId().equals(history.getVodId());
                card.setSelected(selected);
                card.setOnLongClickListener(view -> {
                    ContentDialog.create().content(source.getName() + "\n" + source.getSiteName() + "\n" + source.getRemarks()).show(this);
                    return true;
                });
                resourceCards.put(id, card);
            }
            for (java.util.Map.Entry<String, View> entry : resourceCards.entrySet()) entry.getValue().setVisibility(active.contains(entry.getKey()) || entry.getValue().hasFocus() ? View.VISIBLE : View.GONE);
        }
        updateResourceSelection();
        updatePlay();
        if (waitingFocus && !empty) binding.libraryRoot.post(this::focusResources);
        if (!running) schedulePreparation();
    }

    private void play(Vod manual, View focused) {
        if (resources.isSelecting()) {
            resources.cancelSelection(); pendingAutoplay = false;
            if (focused == binding.play) { updatePlay(); return; }
        }
        storePlaybackFocus(focused);
        identityLocked = true;
        manualPlayback = manual != null;
        originalEpisodeSelection = false;
        pendingAutoplay = true;
        App.removeCallbacks(warmSelection);
        if (metadataError) loadMetadata();
        if (!resources.isRunning() && resources.snapshot().isEmpty()) resources.start();
        resources.choose(episodeTarget(), manual);
        updatePlay();
    }

    private EpisodeTarget episodeTarget() {
        if (movie()) return null;
        if (key == null && selectedEpisode == null && history != null && !history.getEpisodeUrl().isEmpty()) {
            originalEpisodeSelection = true;
            return EpisodeTarget.of(history.getEpisode(), originalSource == null ? null : MediaMatcher.identity(originalSource).getSeason());
        }
        int number = selectedEpisode == null ? 1 : selectedEpisode;
        selectedEpisode = number;
        return EpisodeTarget.coordinate(selectedSeason, number);
    }

    private void bindLogo(LibraryMedia.Metadata value) {
        String identity = key + ":" + value.logos();
        if (identity.equals(logoRequest)) return;
        logoRequest = identity; logoFallback = false;
        int ticket = ++logoGeneration;
        TmdbLibraryApi.cancel(logoTag);
        tryLogo(value.logos(), 0, ticket);
    }

    private void tryLogo(List<String> candidates, int index, int ticket) {
        if (isInactive() || ticket != logoGeneration) return;
        if (index >= candidates.size()) {
            if (!logoFallback && key != null) {
                logoFallback = true;
                TmdbLibraryApi.logos(key, metadata.originalLanguage(), BuildConfig.TMDB_API_KEY, logoTag, new TmdbLibraryApi.Listener<>() {
                    @Override public void onSuccess(List<String> logos) { tryLogo(logos.stream().filter(url -> !candidates.contains(url)).toList(), 0, ticket); }
                    @Override public void onError(Exception error) { logoUnavailable(ticket); }
                });
            } else logoUnavailable(ticket);
            return;
        }
        binding.logo.setVisibility(View.GONE);
        binding.logo.setAlpha(0f);
        ImgUtil.loadArtwork(work.getName(), candidates.get(index), binding.logo, success -> binding.logo.post(() -> {
            if (isInactive() || ticket != logoGeneration) return;
            if (success) { binding.logo.setVisibility(View.VISIBLE); binding.title.setVisibility(View.GONE); LibraryMotion.fadeIn(binding.logo); binding.heroInformation.post(this::updateChrome); }
            else tryLogo(candidates, index + 1, ticket);
        }));
    }

    private void logoUnavailable(int ticket) {
        if (isInactive() || ticket != logoGeneration) return;
        binding.logo.setVisibility(View.GONE); binding.title.setVisibility(View.VISIBLE);
    }

    private void bindSeriesStatus() {
        if (movie() || metadata == null) { binding.seriesStatus.setVisibility(View.GONE); return; }
        LibraryMedia.SeasonProgress progress = metadata.seasonProgress(selectedSeason, episodes, LocalDate.now());
        int updated = progress.updatedEpisode(), total = progress.totalEpisodes();
        if (progress.nextEpisode() != null && progress.nextEpisode().number() > total) total = 0;
        List<String> facts = new ArrayList<>();
        if (progress.complete() && total > 0 && progress.nextEpisode() == null) facts.add(getString(R.string.library_series_complete, total));
        else if (updated > 0 && total >= updated) facts.add(getString(R.string.library_series_progress, updated, total));
        else if (updated > 0) facts.add(getString(R.string.library_series_updated, updated));
        else if (total > 0) facts.add(getString(R.string.library_series_total, total));
        if (progress.nextEpisode() != null && !progress.nextEpisode().airDate().isEmpty()
                && progress.nextEpisode().airDate().compareTo(LocalDate.now().toString()) >= 0) facts.add(getString(R.string.library_series_next, progress.nextEpisode().airDate()));
        String text = String.join("   ·   ", facts);
        binding.seriesStatus.setText(text); binding.seriesStatus.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void schedulePreparation() {
        App.removeCallbacks(warmSelection);
        if (!isInactive() && !playbackActive && !pendingAutoplay && !resources.isSelecting()) App.post(warmSelection, 450);
    }

    private void preparePlayback() {
        if (isInactive() || !hasWindowFocus() || playbackActive || pendingAutoplay || resources.isSelecting() || resources.isRunning()) return;
        if (metadata == null && key != null || !movie() && key != null && episodes.isEmpty()) return;
        if (!movie() && key != null) {
            LibraryMedia.Episode current = episodes.stream().filter(episode -> episode.number() == (focusedEpisode != null ? focusedEpisode : selectedEpisode == null ? 1 : selectedEpisode)).findFirst().orElse(null);
            if (current == null || !current.airDate().isEmpty() && current.airDate().compareTo(LocalDate.now().toString()) > 0) return;
        }
        if (!movie() && key == null && selectedEpisode == null && (history == null || history.getEpisodeUrl().isEmpty())) return;
        EpisodeTarget target = preparationTarget();
        resources.prepare(target);
    }

    private EpisodeTarget preparationTarget() {
        return !movie() && key != null && focusedEpisode != null ? EpisodeTarget.coordinate(selectedSeason, focusedEpisode) : episodeTarget();
    }

    @Override public void onPrepared(Vod source, Flag flag, Episode episode) {
        if (isInactive() || !hasWindowFocus() || playbackActive || pendingAutoplay) return;
        EpisodeTarget current = preparationTarget();
        if (current != null && !current.matches(episode, com.fongmi.android.tv.source.SmartSourceSelector.implicitEpisodeSeason(source))) return;
        preparedSource = source;
        linkPrefetch.prepare(reference, source, flag, episode, this::updatePlay);
        updateResourceSelection();
    }

    private void storePlaybackFocus(View view) {
        restoreFocus = view;
        restoreFocusKey = view != null && view.getTag() instanceof String value ? value : null;
    }

    private void restorePlaybackFocus() {
        focusRestorePending = true;
        binding.libraryRoot.post(this::restoreFocusedCard);
    }

    private void restoreFocusedCard() {
        if (!focusRestorePending || !hasWindowFocus() || isInactive()) return;
        if (restoreResourceFocus) {
            focusResources();
            restoreResourceFocus = focusRestorePending = false;
            return;
        }
        View restored = restoreFocusKey == null ? restoreFocus : binding.libraryRoot.findViewWithTag(restoreFocusKey);
        if (restored == null || !restored.isShown()) restored = binding.play;
        if (restored.requestFocus()) focusRestorePending = false;
    }

    private void retryPendingPlayback() {
        if (!fallbackPending || !hasWindowFocus() || isInactive()) return;
        fallbackPending = false;
        if (fallbackSeasonChanged) resources.start();
        resources.retryAfterPlayback(playingSource, episodeTarget());
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && binding != null) {
            if (focusRestorePending) binding.libraryRoot.post(this::restoreFocusedCard);
            if (fallbackPending) binding.libraryRoot.post(this::retryPendingPlayback);
            if (!fallbackPending) schedulePreparation();
        }
    }

    private void updateResourceSelection() {
        String selected = playingSource != null ? LibraryIdentity.sourceKey(playingSource) : preparedSource != null ? LibraryIdentity.sourceKey(preparedSource) : history == null ? ""
                : history.getSiteKey().length() + ":" + history.getSiteKey() + ":" + history.getVodId();
        for (java.util.Map.Entry<String, View> entry : resourceCards.entrySet()) entry.getValue().setSelected(entry.getKey().equals(selected));
    }

    @Override public void onSelected(Vod source, Flag flag, Episode episode) {
        if (isInactive()) return;
        playingSource = source;
        pendingAutoplay = false;
        playbackActive = identityLocked = true;
        Integer playSeason = movie() ? null : selectedSeason;
        Integer playNumber = movie() ? null : selectedEpisode;
        if (originalEpisodeSelection) {
            EpisodeLabel label = episode.getLabel();
            Integer rawSeason = label.season() == null ? com.fongmi.android.tv.source.SmartSourceSelector.implicitEpisodeSeason(source) : label.season();
            boolean confirmed = key != null && episodes.stream().anyMatch(item -> item.season() == selectedSeason
                    && Objects.equals(item.number(), selectedEpisode) && EpisodeTarget.coordinate(item.season(), item.number()).matches(episode, rawSeason));
            playSeason = confirmed ? selectedSeason : null;
            playNumber = confirmed ? selectedEpisode : null;
        }
        History incoming = history;
        if (incoming != null && (!Objects.equals(incoming.getMediaSeason(), playSeason)
                || !Objects.equals(incoming.getMediaEpisode(), playNumber))) incoming = null;
        if (incoming != null && originalEpisodeSelection && (!source.getSiteKey().equals(incoming.getSiteKey())
                || !source.getId().equals(incoming.getVodId()) || !episode.getUrl().equals(incoming.getEpisodeUrl()))) incoming = null;
        Intent intent = VideoActivity.libraryIntent(this, source, reference, playSeason, playNumber, flag, episode, incoming);
        String token = linkPrefetch.handoff(reference, source, flag, episode);
        if (!token.isEmpty()) intent.putExtra("libraryPrefetch", token);
        linkPrefetch.clear();
        player.launch(intent);
        updateResourceSelection();
        updatePlay();
    }

    @Override public void onSelectionFailed(Vod source) {
        if (isInactive()) return;
        pendingAutoplay = false;
        updatePlay();
        if (source != null && !source.getFlags().isEmpty()) {
            List<Flag> flags = source.getFlags();
            String[] names = flags.stream().map(Flag::getShow).toArray(String[]::new);
            new MaterialAlertDialogBuilder(this).setTitle(movie() ? R.string.library_choose_version : R.string.library_original_episodes).setItems(names, (dialog, index) -> {
                Flag flag = flags.get(index);
                new MaterialAlertDialogBuilder(this).setTitle(R.string.library_original_episodes)
                        .setItems(flag.getEpisodes().stream().map(Episode::getName).toArray(String[]::new), (next, position) -> {
                            originalEpisodeSelection = manualPlayback = true;
                            onSelected(source, flag, flag.getEpisodes().get(position));
                        }).show();
            }).show();
        } else {
            if (source != null) Notify.show(R.string.library_playback_failed);
            else if (!resources.snapshot().isEmpty()) Notify.show(movie() ? R.string.library_choose_version : R.string.library_episode_missing);
            focusResources();
        }
    }

    private void focusResources() {
        View selected = playingSource == null ? null : resourceCards.get(LibraryIdentity.sourceKey(playingSource));
        if (selected != null && selected.isShown() && selected.requestFocus()) return;
        for (int i = 0; i < resourceRail.getChildCount(); i++) {
            View card = resourceRail.getChildAt(i);
            if (card.isShown()) { card.requestFocus(); return; }
        }
        resourceStatus.requestFocus();
        resourceSection.requestRectangleOnScreen(new android.graphics.Rect(0, 0, resourceSection.getWidth(), resourceSection.getHeight()), false);
    }

    private void updatePlay() {
        if (resources == null) return;
        boolean resume = history != null && history.getPosition() > 0 && (movie() || selectedEpisode == null
                || Objects.equals(history.getMediaSeason(), selectedSeason) && Objects.equals(history.getMediaEpisode(), selectedEpisode));
        int label = pendingAutoplay || resources.isSelecting() ? R.string.library_cancel_playback
                : sourceSearching && resources.snapshot().isEmpty() ? R.string.library_searching
                : resources.snapshot().isEmpty() ? R.string.library_no_resources
                : resume ? R.string.library_resume : R.string.library_play;
        String text = getString(label);
        if (label == R.string.library_resume) {
            String position = com.fongmi.android.tv.utils.Util.timeMs(history.getPosition());
            text = !movie() && history.getMediaEpisode() != null ? getString(R.string.library_resume_episode, history.getMediaEpisode(), position)
                    : getString(R.string.library_resume_position, position);
        } else if (label == R.string.library_play && !movie() && key != null) text = getString(R.string.library_play_episode, selectedEpisode == null ? 1 : selectedEpisode);
        binding.play.setText((label == R.string.library_play || label == R.string.library_resume ? "▶  " : "") + text);
        binding.play.setAlpha(label == R.string.library_no_resources ? .62f : 1f);
    }

    private void toggleKeep() {
        String keepKey = key == null ? work.getSiteKey() + com.fongmi.android.tv.db.AppDatabase.SYMBOL + work.getId() : key.toString();
        Keep saved = key == null ? Keep.find(keepKey) : Keep.findDiscover(keepKey);
        if (saved != null) saved.delete();
        else {
            Keep keep = new Keep(); keep.setKey(keepKey); keep.setMediaRef(reference);
            keep.setType(key == null ? Keep.TYPE_VOD : Keep.TYPE_DISCOVER); keep.setCid(key == null ? VodConfig.getCid() : 0);
            keep.setVodName(work.getName()); keep.setVodPic(work.getPic()); keep.setSiteName(key == null ? work.getSiteName() : "TMDB");
            keep.setCreateTime(System.currentTimeMillis()); keep.save();
        }
        RefreshEvent.keep(); updateKeep();
    }

    private void updateKeep() {
        if (work == null) return;
        Keep keep = key == null ? Keep.find(work.getSiteKey() + com.fongmi.android.tv.db.AppDatabase.SYMBOL + work.getId()) : Keep.findDiscover(key.toString());
        binding.keep.setImageResource(keep == null ? R.drawable.msr_bookmark_border : R.drawable.msr_bookmark);
    }

    private boolean isInactive() { return isFinishing() || isDestroyed(); }
    @Override protected void onPause() {
        App.removeCallbacks(warmSelection); linkPrefetch.clear();
        if (!playbackActive && resources != null) {
            resources.cancelSelection();
            pendingAutoplay = fallbackPending = false;
            updatePlay();
        }
        super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("season", selectedSeason); if (selectedEpisode != null) state.putInt("episode", selectedEpisode);
        super.onSaveInstanceState(state);
    }
    @Override protected void onBackInvoked() {
        if (resources != null && resources.isSelecting()) { resources.cancelSelection(); pendingAutoplay = false; updatePlay(); }
        else super.onBackInvoked();
    }
    @Override protected void onDestroy() {
        if (heroEffects != null) heroEffects.release();
        App.removeCallbacks(warmSelection); linkPrefetch.clear();
        TmdbLibraryApi.cancel(logoTag);
        TmdbLibraryApi.cancel(tag); TmdbLibraryApi.cancel(metadataTag); TmdbLibraryApi.cancel(seasonTag); DiscoverApi.cancel(metadataTag);
        if (resources != null) resources.close();
        super.onDestroy();
    }
}
