package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.SoundEffectConstants;

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
import com.fongmi.android.tv.calendar.AiringCalendarIndex;
import com.fongmi.android.tv.databinding.ActivityLibraryDetailBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.playback.vod.LibraryPlaybackPrefetch;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.source.EpisodeLabel;
import com.fongmi.android.tv.source.EpisodeTarget;
import com.fongmi.android.tv.source.LibraryIdentity;
import com.fongmi.android.tv.source.LibrarySourceMatcher;
import com.fongmi.android.tv.source.MediaIdentity;
import com.fongmi.android.tv.source.MediaMatcher;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.ContentDialog;
import com.fongmi.android.tv.ui.dialog.VodSelectionDialog;
import com.fongmi.android.tv.ui.dialog.PosterSourcePriorityDialog;
import com.fongmi.android.tv.ui.library.LibraryDetailBadge;
import com.fongmi.android.tv.ui.library.LibraryDetailChoice;
import com.fongmi.android.tv.ui.library.LibraryDetailItem;
import com.fongmi.android.tv.ui.library.LibraryDetailState;
import com.fongmi.android.tv.ui.library.LibraryDetailView;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Playback remains activity-owned; Compose receives immutable display data and explicit actions. */
public final class LibraryDetailActivity extends BaseActivity implements LibraryResourcesController.Listener {
    private static final int CATALOG_PAGE_SIZE = 30;
    private static final int ORIGINAL_PAGE_SIZE = 40;
    private static final Pattern QUALITY_4K = Pattern.compile("(?i)(?:\\b4k\\b|2160p?|超高清)");
    private static final Pattern SOURCE_DECORATION = Pattern.compile("[\\p{So}\uFE0E\uFE0F\u200D\u20E3]");
    private static final Pattern QUALITY_1080 = Pattern.compile("(?i)(?:1080p?|全高清)");
    private final Object tag = new Object();
    private final Object metadataTag = new Object();
    private final Object seasonTag = new Object();
    private final Object logoTag = new Object();
    private final LibraryPlaybackPrefetch linkPrefetch = new LibraryPlaybackPrefetch();
    private final Runnable warmSelection = this::preparePlayback;
    private final Runnable renderFrame = this::renderState;
    private ActivityLibraryDetailBinding binding;
    private LibraryResourcesController resources;
    private Vod work, originalSource, preparedSource, playingSource;
    private DiscoverMediaKey key;
    private LibraryMedia.Metadata metadata;
    private LibraryMedia.Extras extras;
    private List<LibraryMedia.Episode> episodes = List.of();
    private List<Vod> resourceValues = List.of();
    private List<String> logoCandidates = List.of();
    private History history;
    private String reference, uiIdentity, restoreFocusKey = "play", pendingUiFocus;
    private String originalFlag = "", logoRequest = "", resourceFilter = "all";
    private int selectedSeason = 1, originalPage, catalogPage, logoIndex, logoGeneration;
    private Integer selectedEpisode, focusedEpisode;
    private boolean episodesLoading, episodesError, resourcesError, renderQueued, cataloguePositioned, keptState;
    private boolean extrasRequested, extrasDemanded, showMore, resumeRequested, sourceSearching, pendingAutoplay, metadataError;
    private boolean identityLocked, playbackActive, manualPlayback, originalEpisodeSelection;
    private boolean focusRestorePending, restoreResourceFocus, fallbackPending, fallbackSeasonChanged, logoFallback;


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
        if (history != null) selectedEpisode = history.getMediaEpisode();
        if (previousSeason != selectedSeason) { cataloguePositioned = false; catalogPage = 0; episodes = List.of(); }
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
        if (result.getResultCode() == VideoActivity.RESULT_LIBRARY_FAILURE && previousSeason != selectedSeason) loadEpisodes();
        updateResourceSelection(); updateKeep();
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
    @Override protected void onThemeChanged() { if (binding != null) binding.libraryRoot.refreshTheme(); }

    @Override protected void initView(Bundle saved) {
        work = getIntent().getParcelableExtra("work");
        if (work == null) { finish(); return; }
        key = DiscoverMediaKey.parse(work.getId());
        originalSource = key == null && !work.getSiteKey().isEmpty() ? work : null;
        reference = LibraryIdentity.reference(work, VodConfig.getCid());
        uiIdentity = reference;
        history = History.findByMedia(reference);
        String received = getIntent().getStringExtra("resumeHistory");
        if (received != null) { history = History.objectFrom(received); resumeRequested = true; }
        if (history != null && history.getMediaSeason() != null) selectedSeason = history.getMediaSeason();
        if (history != null) selectedEpisode = history.getMediaEpisode();
        if (saved != null) {
            selectedSeason = saved.getInt("season", selectedSeason);
            selectedEpisode = saved.containsKey("episode") ? saved.getInt("episode") : selectedEpisode;
            originalPage = saved.getInt("originalPage"); catalogPage = saved.getInt("catalogPage");
            cataloguePositioned = saved.containsKey("catalogPage");
            restoreFocusKey = saved.getString("focus", "play");
        }
        resources = new LibraryResourcesController(this);
        List<Vod> seeded = new ArrayList<>(work.getSourceCandidates());
        if (key == null && !work.getSiteKey().isEmpty()) seeded.add(0, work);
        if (resumeRequested && history != null) {
            Vod previous = new Vod();
            previous.setName(work.getName()); previous.setPic(work.getPic()); previous.setId(history.getVodId());
            previous.setTypeName(work.getTypeName()); previous.setSite(VodConfig.get().getSite(history.getSiteKey()));
            seeded.add(0, previous);
        }
        resources.seed(seeded);
        binding.libraryRoot.setListener(new LibraryDetailView.Listener() {
            @Override public void onAction(String action, String itemKey) { handleAction(action, itemKey); }
            @Override public void onFocused(String itemKey) { handleFocus(itemKey); }
        });
        bindWork(); configureResources(); resources.start(); loadMetadata(); resources.browse(originalSource);
        focusAfterRender(restoreFocusKey);
    }

    private void handleFocus(String itemKey) {
        if (isInactive()) return;
        Integer next = null;
        if (itemKey.startsWith("episode:")) {
            for (LibraryMedia.Episode item : episodes) if (episodeKey(item).equals(itemKey)) { next = item.number(); break; }
        } else if (!List.of("play", "source", "keep", "more", "summary").contains(itemKey)) return;
        if (!Objects.equals(focusedEpisode, next)) { linkPrefetch.clear(); preparedSource = null; }
        focusedEpisode = next;
        if (!playbackActive && !resources.isSelecting()) schedulePreparation();
    }

    private void handleAction(String action, String itemKey) {
        if (isInactive()) return;
        if (!action.equals("loadExtras") && !action.equals("logoFailed")) binding.libraryRoot.playSoundEffect(SoundEffectConstants.CLICK);
        switch (action) {
            case "play" -> play(null, "play");
            case "source" -> focusResources();
            case "keep" -> toggleKeep();
            case "summary" -> ContentDialog.create().content(work.getContent()).show(this);
            case "more" -> showMoreMenu();
            case "loadExtras" -> { extrasDemanded = true; loadExtras(); }
            case "season" -> { if (key == null) chooseOriginalLine(); else chooseSeason(); }
            case "episodePicker" -> chooseEpisode();
            case "episode" -> chooseEpisode(itemKey);
            case "range" -> chooseRange(itemKey);
            case "resourceFilter" -> { resourceFilter = itemKey.replace("filter:", ""); publishUi(); }
            case "resource" -> {
                Vod source = sourceForKey(itemKey);
                if (source != null) play(source, itemKey);
            }
            case "resourceDetails" -> {
                Vod source = sourceForKey(itemKey);
                if (source != null) ContentDialog.create().content(source.getName() + "\n" + source.getSiteName() + "\n" + source.getRemarks()).show(this);
            }
            case "cast" -> {
                if (metadata != null) for (DiscoverCredit credit : metadata.detail().getCast()) {
                    if (("person:" + credit.getId()).equals(itemKey) && credit.getId() > 0) { LibrarySearchActivity.credits(this, credit.getId(), credit.getName()); break; }
                }
            }
            case "genre" -> DiscoverActivity.startGenre(this, movie() ? "movie" : "tv", itemKey.replaceFirst("^genre:", ""));
            case "trailer" -> openTrailer(itemKey);
            case "still" -> {
                int index = itemIndex(itemKey, "image:");
                if (extras != null && index >= 0 && index < extras.images().size()) LibraryImagesActivity.start(this, extras.images(), index);
            }
            case "similar" -> {
                if (extras != null) for (Vod item : extras.recommendations()) if (("similar:" + item.getId()).equals(itemKey)) { LibraryNavigation.open(this, item, null); break; }
            }
            case "refresh" -> {
                if (extras == null) { loadExtras(); break; }
                List<Vod> recommendations = new ArrayList<>(extras.recommendations());
                Collections.shuffle(recommendations);
                extras = new LibraryMedia.Extras(extras.videos(), extras.images(), List.copyOf(recommendations));
                publishUi();
            }
            case "mediaInformation" -> ContentDialog.create().content(mediaInformation()).show(this);
            case "logoFailed" -> { if (itemKey.equals(currentLogo())) { logoIndex++; requestLogoFallback(); } }
        }
    }

    private void showMoreMenu() {
        new MaterialAlertDialogBuilder(this).setItems(new String[]{getString(R.string.detail_desc), getString(R.string.library_more), getString(R.string.library_source_priority)}, (dialog, which) -> {
            if (which == 0) ContentDialog.create().content(work.getContent()).show(this);
            else if (which == 1) { showMore = true; extrasDemanded = true; loadExtras(); publishUi(); }
            else PosterSourcePriorityDialog.show(this, () -> resources.start());
        }).show();
    }

    private void publishUi() {
        if (binding == null || work == null || isInactive() || renderQueued) return;
        renderQueued = true;
        binding.libraryRoot.postOnAnimation(renderFrame);
    }

    private void renderState() {
        renderQueued = false;
        if (isInactive() || work == null || resources == null) return;
        LibraryDetailState.Builder state = new LibraryDetailState.Builder();
        state.identity = uiIdentity;
        state.title = work.getName(); state.backdrop = work.getBackdrop().isEmpty() ? work.getPic() : work.getBackdrop();
        state.logo = currentLogo(); state.overview = work.getContent().isEmpty() ? getString(R.string.library_no_overview) : work.getContent();
        state.movie = movie(); state.simple = ExperienceSettings.getMode() == ExperienceMode.SIMPLE && !showMore;
        List<String> facts = new ArrayList<>();
        if (!work.getYear().isEmpty()) facts.add(work.getYear());
        if (!work.getArea().isEmpty()) facts.add(work.getArea());
        if (metadata != null && metadata.detail().getRuntimeMinutes() > 0) facts.add(getString(R.string.library_minutes, metadata.detail().getRuntimeMinutes()));
        state.metadata = String.join(" · ", facts); state.seriesStatus = seriesStatus();
        if (metadata != null && !metadata.detail().getRating().isEmpty()) state.ratings = List.of(new LibraryDetailBadge("TMDB", metadata.detail().getRating()));
        state.kept = keptState; state.followable = followable(); state.selecting = pendingAutoplay || resources.isSelecting();
        boolean resume = history != null && history.getPosition() > 0 && (state.movie || selectedEpisode == null
                || Objects.equals(history.getMediaSeason(), selectedSeason) && Objects.equals(history.getMediaEpisode(), selectedEpisode));
        state.playLabel = getString(state.selecting ? R.string.library_cancel_playback
                : sourceSearching && resourceValues.isEmpty() ? R.string.library_searching
                : resourceValues.isEmpty() ? R.string.library_no_resources : resume ? R.string.library_resume : R.string.library_play);
        state.playEnabled = state.selecting || sourceSearching || !resourceValues.isEmpty();
        if (resume) {
            String position = com.fongmi.android.tv.utils.Util.timeMs(history.getPosition());
            int season = history.getMediaSeason() == null ? selectedSeason : history.getMediaSeason();
            String seasonLabel = getString(season == 0 ? R.string.library_specials : R.string.library_season, season);
            state.playHint = !state.movie && history.getMediaEpisode() != null ? getString(R.string.library_detail_resume_episode_hint, seasonLabel, history.getMediaEpisode(), position) : position;
        } else if (!state.movie && key != null) state.playHint = getString(R.string.library_detail_episode_hint,
                getString(selectedSeason == 0 ? R.string.library_specials : R.string.library_season, selectedSeason), selectedEpisode == null ? 1 : selectedEpisode);
        bindEpisodeState(state); bindResourceState(state);
        if (!state.simple && metadata != null) {
            List<LibraryDetailItem> cast = new ArrayList<>();
            for (DiscoverCredit credit : metadata.detail().getCast()) cast.add(new LibraryDetailItem("person:" + credit.getId(), credit.getName(), credit.getCharacter(), "", credit.getProfile(), "", false, credit.getId() > 0, 0f));
            state.cast = cast;
            if (!metadata.detail().getGenres().isEmpty()) {
                List<LibraryDetailChoice> genres = new ArrayList<>();
                for (String genre : metadata.detail().getGenres().split("、")) genres.add(new LibraryDetailChoice("genre:" + genre, genre));
                state.genres = genres;
            }
        }
        if (!state.simple && extras != null) {
            List<LibraryDetailItem> trailers = new ArrayList<>(), stills = new ArrayList<>(), similar = new ArrayList<>();
            for (int i = 0; i < extras.videos().size(); i++) {
                LibraryMedia.Video video = extras.videos().get(i);
                trailers.add(new LibraryDetailItem("trailer:" + i, video.name(), "", "", video.thumbnail()));
            }
            for (int i = 0; i < extras.images().size(); i++) stills.add(new LibraryDetailItem("image:" + i, getString(R.string.library_images) + " " + (i + 1), "", "", extras.images().get(i)));
            for (Vod item : extras.recommendations()) {
                List<String> caption = new ArrayList<>();
                if (!item.getRemarks().isEmpty()) caption.add(item.getRemarks());
                if (!item.getYear().isEmpty()) caption.add(item.getYear());
                similar.add(new LibraryDetailItem("similar:" + item.getId(), item.getName(), String.join(" · ", caption), item.getContent(), item.getPic()));
            }
            state.trailers = trailers; state.stills = stills; state.similar = similar;
        }
        state.mediaInformation = mediaInformation();
        if (metadata != null) state.productionLogos = metadata.productionLogos();
        binding.libraryRoot.submit(state);
        if (pendingUiFocus != null) {
            String desired = pendingUiFocus; pendingUiFocus = null;
            binding.libraryRoot.focusKey(desired);
        }
    }

    private void bindEpisodeState(LibraryDetailState.Builder state) {
        state.episodesLoading = episodesLoading;
        if (key == null && originalSource != null) { bindOriginalEpisodeState(state); return; }
        if (state.movie) return;
        state.seasonLabel = getString(selectedSeason == 0 ? R.string.library_specials : R.string.library_season, selectedSeason);
        state.episodeStatus = episodesLoading ? getString(R.string.library_episodes_loading) : episodes.isEmpty() ? getString(R.string.library_episodes_empty) : "";
        List<LibraryDetailItem> items = new ArrayList<>();
        catalogPage = Math.min(catalogPage, Math.max(0, (episodes.size() - 1) / CATALOG_PAGE_SIZE));
        int begin = catalogPage * CATALOG_PAGE_SIZE;
        if (!episodes.isEmpty() && !episodesLoading) state.episodeStatus = (begin + 1) + "–" + Math.min(episodes.size(), begin + CATALOG_PAGE_SIZE) + " / " + episodes.size();
        if (episodes.size() > CATALOG_PAGE_SIZE) {
            List<LibraryDetailChoice> ranges = new ArrayList<>();
            for (int page = 0; page * CATALOG_PAGE_SIZE < episodes.size(); page++) ranges.add(new LibraryDetailChoice("range:catalog:" + page,
                    (page * CATALOG_PAGE_SIZE + 1) + "–" + Math.min(episodes.size(), (page + 1) * CATALOG_PAGE_SIZE), page == catalogPage));
            state.ranges = ranges;
        }
        for (LibraryMedia.Episode item : episodes.subList(begin, Math.min(episodes.size(), begin + CATALOG_PAGE_SIZE))) {
            List<String> caption = new ArrayList<>();
            caption.add(getString(R.string.library_episode, item.number()));
            if (item.runtime() > 0) caption.add(getString(R.string.library_minutes, item.runtime()));
            boolean future = !item.airDate().isEmpty() && item.airDate().compareTo(LocalDate.now().toString()) > 0;
            boolean watched = history != null && Objects.equals(history.getMediaSeason(), item.season()) && Objects.equals(history.getMediaEpisode(), item.number());
            float progress = watched && history.getDuration() > 0 ? Math.min(1f, (float) history.getPosition() / history.getDuration()) : 0f;
            items.add(new LibraryDetailItem(episodeKey(item), item.name().trim(), String.join(" · ", caption), item.overview(),
                    item.still().isEmpty() ? work.getBackdrop() : item.still(), future ? getString(R.string.library_airing) : "", item.number() == (selectedEpisode == null ? 1 : selectedEpisode), true, progress, item.airDate()));
        }
        state.episodes = items;
    }

    private void bindOriginalEpisodeState(LibraryDetailState.Builder state) {
        List<Flag> flags = originalSource.getFlags().stream().filter(flag -> !flag.getEpisodes().isEmpty()).toList();
        if (flags.isEmpty()) return;
        if (state.movie && flags.stream().allMatch(flag -> flag.getEpisodes().size() <= 1)) return;
        boolean resumePage = originalFlag.isEmpty() && history != null && originalPage == 0;
        if (originalFlag.isEmpty() && history != null) originalFlag = history.getVodFlag();
        Flag flag = flags.stream().filter(item -> item.getFlag().equals(originalFlag)).findFirst().orElse(flags.get(0));
        if (resumePage) for (int i = 0; i < flag.getEpisodes().size(); i++) if (flag.getEpisodes().get(i).getUrl().equals(history.getEpisodeUrl())) { originalPage = i / ORIGINAL_PAGE_SIZE; break; }
        originalFlag = flag.getFlag(); state.seasonLabel = displaySourceName(flag.getShow(), flag.getFlag());
        int count = flag.getEpisodes().size(); originalPage = Math.min(originalPage, (count - 1) / ORIGINAL_PAGE_SIZE);
        state.episodeStatus = (originalPage * ORIGINAL_PAGE_SIZE + 1) + "–" + Math.min(count, (originalPage + 1) * ORIGINAL_PAGE_SIZE) + " / " + count;
        if (count > ORIGINAL_PAGE_SIZE) {
            List<LibraryDetailChoice> ranges = new ArrayList<>();
            for (int page = 0; page * ORIGINAL_PAGE_SIZE < count; page++) ranges.add(new LibraryDetailChoice("range:original:" + page,
                    (page * ORIGINAL_PAGE_SIZE + 1) + "–" + Math.min(count, (page + 1) * ORIGINAL_PAGE_SIZE), page == originalPage));
            state.ranges = ranges;
        }
        List<LibraryDetailItem> items = new ArrayList<>();
        for (Episode item : flag.getEpisodes().subList(originalPage * ORIGINAL_PAGE_SIZE, Math.min(count, (originalPage + 1) * ORIGINAL_PAGE_SIZE))) {
            boolean watched = history != null && item.getUrl().equals(history.getEpisodeUrl()) && flag.getFlag().equals(history.getVodFlag());
            float progress = watched && history.getDuration() > 0 ? Math.min(1f, (float) history.getPosition() / history.getDuration()) : 0f;
            items.add(new LibraryDetailItem(originalKey(flag, item), item.getName(), displaySourceName(flag.getShow(), flag.getFlag()), "", "", "", watched, true, progress));
        }
        state.episodes = items;
    }

    private void bindResourceState(LibraryDetailState.Builder state) {
        state.resourcesLoading = sourceSearching;
        state.resourceStatus = resourceValues.isEmpty() ? getString(sourceSearching ? R.string.library_searching : resourcesError ? R.string.library_resource_error : R.string.library_no_resources) : "";
        String selected = selectedResourceKey();
        List<LibraryDetailItem> items = new ArrayList<>(); List<String> qualities = new ArrayList<>();
        for (Vod source : resourceValues) {
            String quality = sourceQuality(source); if (!qualities.contains(quality)) qualities.add(quality);
            String stable = LibraryIdentity.sourceKey(source);
            if (!resourceFilter.equals("all") && !resourceFilter.equals(quality) && !("resource:" + stable).equals(binding.libraryRoot.focusedKey())) continue;
            items.add(new LibraryDetailItem("resource:" + stable, displaySourceName(source.getSiteName(), source.getSiteKey()), source.getName(), "", "", source.getRemarks(), stable.equals(selected), true, 0f));
        }
        state.resources = items;
        if (qualities.size() > 1) {
            List<LibraryDetailChoice> filters = new ArrayList<>();
            filters.add(new LibraryDetailChoice("filter:all", getString(R.string.library_search_all), resourceFilter.equals("all")));
            for (String quality : List.of("4K", "1080P", "other")) if (qualities.contains(quality)) filters.add(new LibraryDetailChoice("filter:" + quality,
                    quality.equals("other") ? getString(R.string.library_detail_other_quality) : quality, resourceFilter.equals(quality)));
            state.resourceFilters = filters;
        }
    }

    private static String displaySourceName(String name, String fallback) {
        String clean = SOURCE_DECORATION.matcher(name).replaceAll("").trim();
        return clean.isEmpty() ? fallback : clean;
    }

    private String sourceQuality(Vod source) {
        String name = source.getName() + " " + source.getRemarks();
        return QUALITY_4K.matcher(name).find() ? "4K" : QUALITY_1080.matcher(name).find() ? "1080P" : "other";
    }

    private String mediaInformation() {
        List<String> lines = new ArrayList<>();
        if (!work.getOriginalName().isEmpty() && !work.getOriginalName().equals(work.getName())) lines.add(work.getOriginalName());
        if (!work.getYear().isEmpty()) lines.add(work.getYear());
        if (!work.getArea().isEmpty()) lines.add(work.getArea());
        if (metadata != null) {
            if (!metadata.detail().getCreators().isEmpty()) lines.add(metadata.detail().getCreators());
            if (!metadata.detail().getGenres().isEmpty()) lines.add(metadata.detail().getGenres());
            if (metadata.detail().getRuntimeMinutes() > 0) lines.add(getString(R.string.library_minutes, metadata.detail().getRuntimeMinutes()));
        }
        return String.join("\n", lines);
    }

    private static String episodeKey(LibraryMedia.Episode episode) { return "episode:" + episode.season() + ":" + episode.number(); }
    private static String originalKey(Flag flag, Episode episode) { return "original:" + flag.getFlag() + ":" + episode.getUrl(); }
    private int itemIndex(String value, String prefix) {
        try { return value.startsWith(prefix) ? Integer.parseInt(value.substring(prefix.length())) : -1; }
        catch (NumberFormatException error) { return -1; }
    }
    private Vod sourceForKey(String itemKey) {
        return resourceValues.stream().filter(item -> ("resource:" + LibraryIdentity.sourceKey(item)).equals(itemKey)).findFirst().orElse(null);
    }
    private void openTrailer(String itemKey) {
        int index = itemIndex(itemKey, "trailer:");
        if (extras == null || index < 0 || index >= extras.videos().size()) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(extras.videos().get(index).url()))); }
        catch (ActivityNotFoundException error) { Notify.show(R.string.library_external_unavailable); }
    }

    private void bindWork() { updateKeep(); if (extrasDemanded) loadExtras(); }
    private void renderPeople() { publishUi(); }
    private void renderEpisodes() { publishUi(); }
    private void renderOriginalEpisodes(Vod source) { originalSource = source; publishUi(); }
    private void renderExtras() { renderPeople(); publishUi(); }
    private void bindSeriesStatus() { publishUi(); }
    private void updatePlay() { publishUi(); }
    private void updateKeep() { if (work != null) keptState = kept(); publishUi(); }
    private void updateResourceSelection() { publishUi(); }

    @Override public void onResources(List<Vod> values, boolean running, boolean partialError) {
        if (isInactive()) return;
        boolean waitingFocus = binding.libraryRoot.focusedKey().equals("resourceStatus");
        resourceValues = List.copyOf(values); sourceSearching = running; resourcesError = partialError;
        publishUi();
        if (waitingFocus && !values.isEmpty()) focusResources();
        if (!running) schedulePreparation();
    }

    private void play(Vod manual, String focused) {
        if (resources.isSelecting()) {
            resources.cancelSelection(); pendingAutoplay = false;
            if ("play".equals(focused)) { updatePlay(); return; }
        }
        storePlaybackFocus(focused); identityLocked = true; manualPlayback = manual != null;
        originalEpisodeSelection = false; pendingAutoplay = true; App.removeCallbacks(warmSelection);
        if (metadataError) loadMetadata();
        if (!resources.isRunning() && resources.snapshot().isEmpty()) resources.start();
        resources.choose(episodeTarget(), manual); updatePlay();
    }

    private void chooseSeason() {
        if (metadata == null || metadata.seasons().isEmpty()) return;
        String[] labels = metadata.seasons().stream().map(season -> season.number() == 0 ? getString(R.string.library_specials) : getString(R.string.library_season, season.number())).toArray(String[]::new);
        int current = 0;
        for (int i = 0; i < metadata.seasons().size(); i++) if (metadata.seasons().get(i).number() == selectedSeason) current = i;
        new MaterialAlertDialogBuilder(this).setSingleChoiceItems(labels, current, (dialog, index) -> {
            dialog.dismiss(); selectedSeason = metadata.seasons().get(index).number(); selectedEpisode = focusedEpisode = null;
            episodes = List.of(); catalogPage = 0; cataloguePositioned = false; linkPrefetch.clear(); resources.cancelSelection(); pendingAutoplay = false;
            configureResources(); bindSeriesStatus(); resources.start(); loadEpisodes(); focusAfterRender("season");
        }).show();
    }

    private void chooseOriginalLine() {
        if (originalSource == null) return;
        List<Flag> flags = originalSource.getFlags().stream().filter(flag -> !flag.getEpisodes().isEmpty()).toList();
        Flag selected = flags.stream().filter(flag -> flag.getFlag().equals(originalFlag)).findFirst().orElse(null);
        new MaterialAlertDialogBuilder(this).setTitle(R.string.library_select_source)
                .setSingleChoiceItems(flags.stream().map(Flag::getShow).toArray(String[]::new), flags.indexOf(selected), (dialog, index) -> {
                    dialog.dismiss(); originalFlag = flags.get(index).getFlag(); originalPage = 0; publishUi(); focusAfterRender("season");
                }).show();
    }

    private void chooseEpisode() {
        if (key == null) {
            if (originalSource == null) return;
            Flag flag = originalSource.getFlags().stream().filter(item -> item.getFlag().equals(originalFlag)).findFirst().orElse(null);
            if (flag == null) return;
            List<Episode> values = List.copyOf(flag.getEpisodes());
            int current = 0;
            if (history != null) for (int i = 0; i < values.size(); i++) if (values.get(i).getUrl().equals(history.getEpisodeUrl())) current = i;
            VodSelectionDialog.show(this, getString(R.string.library_all_episodes), values.size(), current,
                    position -> values.get(position).getName(), position -> {
                        if (isInactive()) return;
                        originalPage = position / ORIGINAL_PAGE_SIZE;
                        focusAfterRender(originalKey(flag, values.get(position)));
                    });
            return;
        }
        if (episodes.isEmpty()) return;
        List<LibraryMedia.Episode> values = List.copyOf(episodes);
        int season = selectedSeason;
        VodSelectionDialog.show(this, getString(R.string.library_all_episodes), values.size(), currentEpisodeIndex(),
                position -> getString(R.string.library_episode_title, values.get(position).number(), values.get(position).name()),
                position -> {
                    if (isInactive() || selectedSeason != season) return;
                    catalogPage = position / CATALOG_PAGE_SIZE;
                    cataloguePositioned = true;
                    focusAfterRender(episodeKey(values.get(position)));
                });
    }

    private void chooseEpisode(String itemKey) {
        if (itemKey.startsWith("original:") && originalSource != null) {
            for (Flag flag : originalSource.getFlags()) for (Episode episode : flag.getEpisodes()) if (originalKey(flag, episode).equals(itemKey)) {
                storePlaybackFocus(itemKey); resources.cancelSelection();
                originalEpisodeSelection = manualPlayback = identityLocked = true;
                onSelected(originalSource, flag, episode); return;
            }
        } else for (LibraryMedia.Episode item : episodes) if (episodeKey(item).equals(itemKey)) {
            if (!item.airDate().isEmpty() && item.airDate().compareTo(LocalDate.now().toString()) > 0) { Notify.show(R.string.library_airing); return; }
            selectedEpisode = item.number(); play(null, itemKey); return;
        }
    }

    private void chooseRange(String itemKey) {
        int original = itemIndex(itemKey, "range:original:"), catalog = itemIndex(itemKey, "range:catalog:");
        if (original >= 0) originalPage = original;
        else if (catalog >= 0) { catalogPage = catalog; cataloguePositioned = true; }
        else return;
        focusAfterRender(itemKey);
    }

    private void focusAfterRender(String desired) { pendingUiFocus = desired; publishUi(); }
    private void focusEpisodes() {
        if (key != null && !episodes.isEmpty()) {
            int index = currentEpisodeIndex(); catalogPage = index / CATALOG_PAGE_SIZE;
            focusAfterRender(episodeKey(episodes.get(index))); return;
        }
        if (originalSource != null && !originalSource.getFlags().isEmpty()) { focusAfterRender("season"); return; }
        focusResources();
    }
    private void focusResources() {
        String selected = selectedResourceKey();
        Vod source = resourceValues.stream().filter(item -> LibraryIdentity.sourceKey(item).equals(selected)).findFirst().orElse(resourceValues.isEmpty() ? null : resourceValues.get(0));
        resourceFilter = "all";
        focusAfterRender(source == null ? "resourceStatus" : "resource:" + LibraryIdentity.sourceKey(source));
    }
    private String selectedResourceKey() {
        return playingSource != null ? LibraryIdentity.sourceKey(playingSource) : preparedSource != null ? LibraryIdentity.sourceKey(preparedSource) : history == null ? ""
                : history.getSiteKey().length() + ":" + history.getSiteKey() + ":" + history.getVodId();
    }
    private void storePlaybackFocus(String desired) { restoreFocusKey = desired == null ? "play" : desired; }
    private void restorePlaybackFocus() { focusRestorePending = true; binding.libraryRoot.post(this::restoreFocusedCard); }
    private void restoreFocusedCard() {
        if (!focusRestorePending || !hasWindowFocus() || isInactive()) return;
        if (restoreResourceFocus) { focusResources(); restoreResourceFocus = focusRestorePending = false; return; }
        focusAfterRender(restoreFocusKey); focusRestorePending = false;
    }
    private boolean kept() {
        if (work == null) return false;
        if (followable()) return !followKeeps().isEmpty();
        return (key == null ? Keep.find(work.getSiteKey() + com.fongmi.android.tv.db.AppDatabase.SYMBOL + work.getId()) : Keep.findDiscover(key.toString())) != null;
    }

    private boolean followable() { return key != null && !key.isMovie(); }

    private List<Keep> followKeeps() {
        if (!followable()) return List.of();
        return Keep.getVodAndDiscover().stream().filter(keep -> key.equals(AiringCalendarIndex.mediaKey(keep))).toList();
    }

    /** Preserve a source bookmark when its existing detail is reliably linked to TMDB. */
    private void linkOriginalKeep() {
        if (!followable() || originalSource == null || originalSource.getSiteKey().isEmpty()) return;
        Keep saved = Keep.find(originalSource.getSiteKey() + com.fongmi.android.tv.db.AppDatabase.SYMBOL + originalSource.getId());
        if (saved == null || DiscoverMediaKey.parse(saved.getMediaRef()) != null) return;
        saved.setMediaRef(key.toString()); saved.save(); RefreshEvent.keep();
    }

    private void bindLogo(LibraryMedia.Metadata value) {
        String identity = key + ":" + value.logos();
        if (identity.equals(logoRequest)) return;
        logoRequest = identity; logoFallback = false; logoIndex = 0; logoGeneration++;
        TmdbLibraryApi.cancel(logoTag); logoCandidates = value.logos(); requestLogoFallback();
    }
    private String currentLogo() { return logoIndex < logoCandidates.size() ? logoCandidates.get(logoIndex) : ""; }
    private void requestLogoFallback() {
        publishUi();
        if (logoIndex < logoCandidates.size() || logoFallback || key == null || metadata == null) return;
        logoFallback = true; int ticket = logoGeneration; DiscoverMediaKey requested = key;
        TmdbLibraryApi.logos(key, metadata.originalLanguage(), BuildConfig.TMDB_API_KEY, logoTag, new TmdbLibraryApi.Listener<>() {
            @Override public void onSuccess(List<String> values) {
                if (isInactive() || ticket != logoGeneration || !requested.equals(key)) return;
                logoCandidates = values.stream().filter(url -> !logoCandidates.contains(url)).toList(); logoIndex = 0; publishUi();
            }
            @Override public void onError(Exception error) { publishUi(); }
        });
    }
    private String seriesStatus() {
        if (movie() || metadata == null) return "";
        LibraryMedia.SeasonProgress progress = metadata.seasonProgress(selectedSeason, episodes, LocalDate.now());
        int updated = progress.updatedEpisode(), total = progress.totalEpisodes();
        if (progress.nextEpisode() != null && progress.nextEpisode().number() > total) total = 0;
        List<String> facts = new ArrayList<>();
        if (progress.complete() && total > 0 && progress.nextEpisode() == null) facts.add(getString(R.string.library_series_complete, total));
        else if (updated > 0 && total >= updated) facts.add(getString(R.string.library_series_progress, updated, total));
        else if (updated > 0) facts.add(getString(R.string.library_series_updated, updated));
        else if (total > 0) facts.add(getString(R.string.library_series_total, total));
        if (progress.nextEpisode() != null && !progress.nextEpisode().airDate().isEmpty() && progress.nextEpisode().airDate().compareTo(LocalDate.now().toString()) >= 0) facts.add(getString(R.string.library_series_next, progress.nextEpisode().airDate()));
        return String.join(" · ", facts);
    }


    private boolean movie() {
        if (key != null) return key.isMovie();
        MediaIdentity.Kind kind = MediaMatcher.kind(work.getTypeName());
        if (kind != MediaIdentity.Kind.UNKNOWN) return kind == MediaIdentity.Kind.MOVIE;
        return work.getFlags().stream().flatMap(flag -> flag.getEpisodes().stream()).noneMatch(episode ->
                !episode.isGeneratedLabel() && episode.getLabel().isApiSafe());
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
                    if (resumeRequested) { resumeRequested = false; play(null, "play"); }
                }
                @Override public void onError(Exception error) {
                    if (isInactive()) return;
                    metadataError = true;
                    if (resumeRequested) { resumeRequested = false; play(null, "play"); }
                }
            });
        } else if (work.getId().startsWith("douban:")) {
            DiscoverApi.fetchDoubanDetail(work, metadataTag, new DiscoverApi.DoubanDetailListener() {
                @Override public void onSuccess(DoubanDetail detail) {
                    if (isInactive()) return;
                    work.setTypeName(detail.getMediaType()); work.setName(detail.getTitle()); work.setContent(detail.getComment()); work.setYear(detail.getYear());
                    bindWork();
                    DiscoverApi.matchDoubanToTmdb(detail, BuildConfig.TMDB_API_KEY, metadataTag, new DiscoverApi.MatchListener() {
                        @Override public void onMatch(DiscoverMediaKey value) { if (!isInactive() && !identityLocked) { key = value; work.setId(value.toString()); reference = value.toString(); updateKeep(); loadMetadata(); } }
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
                            linkOriginalKeep(); updateKeep();
                            History linked = History.findByMedia(reference);
                            if (linked != null) history = linked;
                            metadata = null; loadMetadata();
                        }
                    }
                    @Override public void onError(Exception error) { }
                });
                if (!originalSource.getFlags().isEmpty()) renderOriginalEpisodes(originalSource);
            }
            if (resumeRequested) { resumeRequested = false; play(work, "play"); }
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
            @Override public void onSuccess(List<LibraryMedia.Episode> values) { if (!isInactive() && selectedSeason == number) { episodesLoading = false; episodes = values; positionEpisodePage(); renderEpisodes(); bindSeriesStatus(); schedulePreparation(); } }
            @Override public void onError(Exception error) { if (!isInactive() && selectedSeason == number) { episodesLoading = false; episodesError = true; episodes = List.of(); renderEpisodes(); bindSeriesStatus(); } }
        });
    }

    private void positionEpisodePage() {
        if (cataloguePositioned) return;
        catalogPage = currentEpisodeIndex() / CATALOG_PAGE_SIZE;
        cataloguePositioned = true;
    }

    private int currentEpisodeIndex() {
        int number = focusedEpisode != null ? focusedEpisode : selectedEpisode == null ? 1 : selectedEpisode;
        for (int i = 0; i < episodes.size(); i++) if (episodes.get(i).number() == number) return i;
        return 0;
    }

    private void loadExtras() {
        if (key == null || extrasRequested || ExperienceSettings.getMode() == ExperienceMode.SIMPLE && !showMore) return;
        extrasRequested = true;
        TmdbLibraryApi.extras(key, BuildConfig.TMDB_API_KEY, tag, new TmdbLibraryApi.Listener<>() {
            @Override public void onSuccess(LibraryMedia.Extras value) { if (!isInactive()) { extras = value; renderExtras(); } }
            @Override public void onError(Exception error) { extrasRequested = false; }
        });
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

    private void toggleKeep() {
        if (followable()) {
            List<Keep> saved = followKeeps();
            if (!saved.isEmpty()) {
                for (Keep keep : saved) keep.delete();
                RefreshEvent.keep(); updateKeep(); return;
            }
        }
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

    private boolean isInactive() { return isFinishing() || isDestroyed(); }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onKeepEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.KEEP && work != null && !isInactive()) updateKeep();
    }

    @Override protected void onResume() {
        super.onResume();
        if (work != null && !isInactive()) updateKeep();
    }

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
        state.putInt("originalPage", originalPage); state.putInt("catalogPage", catalogPage);
        state.putString("focus", binding.libraryRoot.focusedKey());
        super.onSaveInstanceState(state);
    }

    @Override protected void onBackInvoked() {
        if (resources != null && resources.isSelecting()) { resources.cancelSelection(); pendingAutoplay = false; updatePlay(); }
        else super.onBackInvoked();
    }

    @Override protected void onDestroy() {
        binding.libraryRoot.removeCallbacks(renderFrame);
        binding.libraryRoot.setListener(null);
        App.removeCallbacks(warmSelection); linkPrefetch.clear();
        TmdbLibraryApi.cancel(logoTag);
        TmdbLibraryApi.cancel(tag); TmdbLibraryApi.cancel(metadataTag); TmdbLibraryApi.cancel(seasonTag); DiscoverApi.cancel(metadataTag);
        if (resources != null) resources.close();
        super.onDestroy();
    }
}
