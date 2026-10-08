package com.fongmi.android.tv.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.VerticalGridView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.PosterSourcePrioritySetting;
import com.fongmi.android.tv.source.PosterSourceResults;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.fongmi.android.tv.ui.activity.DiscoverDetailActivity;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.custom.JetStreamPageProgressLayout;
import com.fongmi.android.tv.ui.home.PosterHomeController;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

/** Real native activities and D-pad events, using in-memory posters and a deliberately offline client. */
@RunWith(AndroidJUnit4.class)
public final class NativeBrowseIntegrationTest {
    private static final String[] PREFERENCES = {"browse_poster_home", "browse_search_filter", "browse_detail_sources", "browse_smart_sources", "browse_poster_source_priority_v1"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<Activity> launched = new ArrayList<>();
    private final List<Activity> previousActivities = new ArrayList<>();
    private final Map<String, Object> preferences = new HashMap<>();
    private final AtomicInteger rejectedRequests = new AtomicInteger();
    private Map<DiscoverApi.Row, Object> cache;
    private Map<DiscoverApi.Row, Object> savedCache;
    private OkHttpClient originalClient;
    private OkHttpClient originalTmdbClient;
    private OkHttpClient offlineClient;
    private Object savedSites;
    private Config savedConfig;
    private boolean replacedSites;
    private boolean replacedConfig;
    private boolean prepared;

    @Before public void setup() {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Run only in an isolated validation application", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        assertEquals("Native browsing is a TV feature", "leanback", BuildConfig.FLAVOR_mode);
        main(() -> {
            previousActivities.addAll(validationActivities());
            Map<String, ?> existing = Prefers.getPrefers().getAll();
            for (String key : PREFERENCES) if (existing.containsKey(key)) preferences.put(key, existing.get(key));
            cache = discoverCache();
            savedCache = new EnumMap<>(DiscoverApi.Row.class);
            savedCache.putAll(cache);
            cache.clear();
            originalClient = OkHttp.client();
            originalTmdbClient = (OkHttpClient) field(TmdbNetwork.class, "client");
            offlineClient = new OkHttpClient.Builder().addInterceptor(chain -> {
                rejectedRequests.incrementAndGet();
                throw new IOException("Intentional offline native browsing fixture");
            }).build();
            setField(OkHttp.get(), "client", offlineClient);
            setField(TmdbNetwork.class, "client", offlineClient);
            BrowseExperienceSettings.restoreOriginal();
            prepared = true;
        });
    }

    @After public void cleanup() {
        if (!prepared) return;
        List<Activity> closing = new ArrayList<>(launched);
        main(() -> {
            // Include an activity opened by a tap even if the following assertion timed out
            // before awaitActivity could record it. Never close pre-existing or other-app UI.
            for (Activity activity : validationActivities()) {
                if (!previousActivities.contains(activity) && !closing.contains(activity)) closing.add(activity);
            }
            for (int i = closing.size() - 1; i >= 0; i--) {
                Activity activity = closing.get(i);
                if (!activity.isDestroyed()) activity.finish();
            }
        });
        try {
            await(() -> closing.stream().allMatch(Activity::isDestroyed), "test activities finish before restoring fixture state");
        } finally {
            main(() -> {
                offlineClient.dispatcher().cancelAll();
                offlineClient.connectionPool().evictAll();
                setField(OkHttp.get(), "client", originalClient);
                setField(TmdbNetwork.class, "client", originalTmdbClient);
                cache.clear();
                cache.putAll(savedCache);
                if (replacedSites) setField(VodConfig.get(), "sites", savedSites);
                if (replacedConfig) VodConfig.get().config(savedConfig);
                SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                for (String key : PREFERENCES) {
                    Object value = preferences.get(key);
                    if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                    else if (value instanceof Integer mode) editor.putInt(key, mode);
                    else if (value instanceof String text) editor.putString(key, text);
                    else if (value instanceof Long number) editor.putLong(key, number);
                    else if (value instanceof Float number) editor.putFloat(key, number);
                    else editor.remove(key);
                }
                assertTrue("Restore the exact pre-test settings", editor.commit());
            });
        }
    }

    @Test(timeout = 60000)
    public void homeModeOptInAndRestoreUseTheRealResumePath() {
        HomeActivity original = launch(HomeActivity.class);
        await(() -> original.hasWindowFocus(), "original home window");
        main(() -> {
            assertNull(field(original, "mPosterHome"));
            assertFalse(containsWebView(original.getWindow().getDecorView()));
        });

        HomeActivity wall = changeHomeMode(original, () -> BrowseExperienceSettings.putPosterHomeEnabled(true), true);
        assertNotSame("Changing the mode rebuilds the home surface", original, wall);
        main(() -> {
            assertNotNull(field(wall, "mPosterHome"));
            assertFalse(((JetStreamPageProgressLayout) wall.findViewById(R.id.progressLayout)).isProgress());
            assertFalse(containsWebView(wall.getWindow().getDecorView()));
        });

        HomeActivity restored = changeHomeMode(wall, BrowseExperienceSettings::restoreOriginal, false);
        main(() -> {
            assertNull(field(restored, "mPosterHome"));
            assertFalse(BrowseExperienceSettings.isDetailSourcesEnabled());
            assertFalse(BrowseExperienceSettings.isSmartSourceEnabled());
            assertEquals(0, BrowseExperienceSettings.getSearchFilterMode());
            assertFalse(containsWebView(restored.getWindow().getDecorView()));
        });
    }

    @Test(timeout = 45000)
    public void offlineWallKeepsCategoriesRetryAndNavigationReachable() {
        main(() -> BrowseExperienceSettings.putPosterHomeEnabled(true));
        HomeActivity home = launch(HomeActivity.class);
        await(() -> home.hasWindowFocus() && (int) field(field(home, "mPosterHome"), "pending") == 0,
                "all discovery requests finish with offline errors");
        assertTrue("The unavailable APIs were actually attempted", rejectedRequests.get() > 0);
        main(() -> {
            assertFalse(((JetStreamPageProgressLayout) home.findViewById(R.id.progressLayout)).isProgress());
            assertFalse(containsWebView(home.getWindow().getDecorView()));
            invoke(home, "requestNavFocus");
        });
        View nav = value(() -> home.findViewById(R.id.nav));
        VerticalGridView recycler = value(() -> home.findViewById(R.id.recycler));
        await(nav::hasFocus, "top navigation");
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> recycler.hasFocus() && recycler.getSelectedPosition() == 1,
                "first actionable content is the category row when no poster is available");
        press(KeyEvent.KEYCODE_DPAD_UP);
        await(nav::hasFocus, "UP from categories returns to navigation");

        main(() -> focusRow(home, ((ArrayObjectAdapter) field(home, "mAdapter")).size() - 1));
        await(() -> visibleView(home, R.id.retry) != null && visibleView(home, R.id.retry).hasFocus(), "offline retry button");
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> visibleView(home, R.id.more) != null && visibleView(home, R.id.more).hasFocus(), "more recommendations button");
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> visibleView(home, R.id.settings) != null && visibleView(home, R.id.settings).hasFocus(), "home settings button");
        press(KeyEvent.KEYCODE_BACK);
        await(nav::hasFocus, "BACK from an offline wall returns to the navigation");
    }

    @Test(timeout = 60000)
    public void fixtureHeroCategoriesAndPostersOpenNativeDetailsWithDpad() {
        main(() -> {
            seedPosters();
            BrowseExperienceSettings.putPosterHomeEnabled(true);
            BrowseExperienceSettings.putDetailSourcesEnabled(true);
        });
        HomeActivity home = launch(HomeActivity.class);
        VerticalGridView recycler = value(() -> home.findViewById(R.id.recycler));
        await(() -> home.hasWindowFocus() && recycler.findViewHolderForAdapterPosition(0) != null,
                "native hero is attached");
        main(() -> invoke(home, "requestNavFocus"));
        await(() -> home.findViewById(R.id.nav).hasFocus(), "navigation before entering the hero");
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> recycler.hasFocus() && recycler.getSelectedPosition() == 0, "hero focus");
        String firstTitle = value(() -> rowText(recycler, 0, R.id.name));
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> !firstTitle.equals(rowText(recycler, 0, R.id.name)), "manual hero advance");
        String selectedTitle = value(() -> rowText(recycler, 0, R.id.name));
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        DiscoverDetailActivity detail = awaitActivity(DiscoverDetailActivity.class);
        main(() -> {
            assertEquals(selectedTitle, ((TextView) detail.findViewById(R.id.title)).getText().toString());
            assertFalse(containsWebView(detail.getWindow().getDecorView()));
        });
        press(KeyEvent.KEYCODE_BACK);
        await(() -> home.hasWindowFocus() && recycler.hasFocus(), "return from native detail retains content focus");
        press(KeyEvent.KEYCODE_DPAD_DOWN);
        await(() -> recycler.getSelectedPosition() == 1 && recycler.hasFocus(), "category row below the hero");
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> (int) field(field(home, "mPosterHome"), "category") == 1, "movie category selected with the remote");
        int shelfPosition = value(() -> firstShelfPosition(home));
        main(() -> focusRow(home, shelfPosition));
        // Home first selects the shelf, then transfers focus to a card in a delayed callback.
        // The category still owns focus in between; sending CENTER then cancels that callback.
        await(() -> focusedPosterCard(home, shelfPosition) != null, "the actual clickable poster card receives focus");
        String posterTitle = value(() -> ((TextView) focusedPosterCard(home, shelfPosition).findViewById(R.id.name)).getText().toString());
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        DiscoverDetailActivity fromPoster = awaitActivity(DiscoverDetailActivity.class);
        main(() -> {
            assertEquals(posterTitle, ((TextView) fromPoster.findViewById(R.id.title)).getText().toString());
            assertFalse(containsWebView(fromPoster.getWindow().getDecorView()));
            assertNotNull(field(fromPoster, "sources"));
        });
    }

    @Test(timeout = 60000)
    public void populatedShelvesContainWholeCardsAndNeverOverlapFollowingContent() {
        main(() -> {
            seedPosters();
            BrowseExperienceSettings.putPosterHomeEnabled(true);
        });
        HomeActivity home = launch(HomeActivity.class);
        VerticalGridView page = value(() -> home.findViewById(R.id.recycler));
        await(() -> home.hasWindowFocus() && (int) field(field(home, "mPosterHome"), "pending") == 0,
                "all poster fixture shelves finish loading");
        List<Integer> positions = value(() -> shelfPositions(home));
        assertTrue("Exercise multiple populated shelves", positions.size() >= 3);

        for (int position : positions) {
            main(() -> focusRow(home, position));
            await(() -> focusedPosterCard(home, position) != null && page.getScrollState() == RecyclerView.SCROLL_STATE_IDLE
                    && !page.isLayoutRequested() && !page.isComputingLayout() && !page.isAnimating(),
                    "poster shelf " + position + " finishes layout with a real focused card");
            main(() -> assertAttachedShelfGeometry(page));
        }

        int lastShelf = positions.get(positions.size() - 1);
        int footerPosition = value(() -> ((ArrayObjectAdapter) field(home, "mAdapter")).size() - 1);
        await(() -> page.findViewHolderForAdapterPosition(lastShelf) != null
                && page.findViewHolderForAdapterPosition(footerPosition) != null
                && !page.isLayoutRequested(), "last shelf and footer are both laid out");
        main(() -> {
            RectF shelf = screenBounds(page.findViewHolderForAdapterPosition(lastShelf).itemView);
            RectF footer = screenBounds(page.findViewHolderForAdapterPosition(footerPosition).itemView);
            assertTrue("The footer must follow the complete last shelf: " + shelf + " / " + footer,
                    shelf.bottom <= footer.top + 1.5f);
        });
    }

    @Test(timeout = 60000)
    public void firstTouchActivatesCategoriesHeroPostersFooterAndSourceClose() {
        main(() -> {
            seedPosters();
            BrowseExperienceSettings.putPosterHomeEnabled(true);
            BrowseExperienceSettings.putDetailSourcesEnabled(true);
        });
        HomeActivity home = launch(HomeActivity.class);
        VerticalGridView page = value(() -> home.findViewById(R.id.recycler));
        await(() -> home.hasWindowFocus() && (int) field(field(home, "mPosterHome"), "pending") == 0,
                "touch fixture home is ready");
        main(() -> invoke(home, "requestNavFocus"));
        await(() -> home.findViewById(R.id.nav).hasFocus(), "remote focus starts outside the touched categories");
        Rect category = awaitTextBounds(home.getString(R.string.home_wall_top));
        tap(category.exactCenterX(), category.exactCenterY());
        await(() -> (int) field(field(home, "mPosterHome"), "category") == 3,
                "a single actual pointer tap selects the high-rated category");

        main(() -> invoke(home, "requestNavFocus"));
        await(() -> home.findViewById(R.id.nav).hasFocus(), "focus is outside the hero before its first tap");
        View action = value(() -> page.findViewHolderForAdapterPosition(0).itemView.findViewById(R.id.action));
        String title = value(() -> rowText(page, 0, R.id.name));
        tapView(action);
        DiscoverDetailActivity detail = awaitActivity(DiscoverDetailActivity.class);
        main(() -> {
            assertEquals(title, ((TextView) detail.findViewById(R.id.title)).getText().toString());
            assertFalse(containsWebView(detail.getWindow().getDecorView()));
            savedSites = field(VodConfig.get(), "sites");
            replacedSites = true;
            setField(VodConfig.get(), "sites", new ArrayList<Site>());
        });
        tapView(value(() -> detail.findViewById(R.id.search)));
        View panel = value(() -> detail.findViewById(R.id.sourcePanel));
        await(() -> panel.isShown() && panel.findViewById(R.id.smart).hasFocus(), "the source rail opens with focus on its first control");
        View close = value(() -> panel.findViewById(R.id.close));
        main(() -> assertFalse("Close has not been focused before the pointer tap", close.hasFocus()));
        tapView(close);
        await(() -> !panel.isShown() && detail.findViewById(R.id.search).hasFocus(),
                "one tap on an unfocused close control closes the native rail");

        press(KeyEvent.KEYCODE_BACK);
        await(home::hasWindowFocus, "return to the home wall for poster touch");
        int shelfPosition = value(() -> firstShelfPosition(home));
        main(() -> focusRow(home, shelfPosition));
        await(() -> focusedPosterCard(home, shelfPosition) != null, "first card is focused before touching another card");
        View secondCard = value(() -> {
            HorizontalGridView posters = page.findViewHolderForAdapterPosition(shelfPosition).itemView.findViewById(R.id.posters);
            RecyclerView.ViewHolder holder = posters.findViewHolderForAdapterPosition(1);
            assertNotNull("Fixture contains a second visible poster", holder);
            assertFalse("The touched poster has not first been focused", holder.itemView.hasFocus());
            return holder.itemView;
        });
        String secondTitle = value(() -> ((TextView) secondCard.findViewById(R.id.name)).getText().toString());
        tapView(secondCard);
        DiscoverDetailActivity fromPoster = awaitActivity(DiscoverDetailActivity.class);
        main(() -> assertEquals(secondTitle, ((TextView) fromPoster.findViewById(R.id.title)).getText().toString()));

        press(KeyEvent.KEYCODE_BACK);
        await(home::hasWindowFocus, "return to the home wall for footer touch");
        main(() -> focusRow(home, ((ArrayObjectAdapter) field(home, "mAdapter")).size() - 1));
        await(() -> visibleView(home, R.id.retry) != null && visibleView(home, R.id.retry).hasFocus(), "footer retry is attached");
        main(() -> assertTrue(home.findViewById(R.id.more).requestFocus()));
        int generation = value(() -> (int) field(field(home, "mPosterHome"), "generation"));
        main(cache::clear);
        tapView(value(() -> home.findViewById(R.id.retry)));
        await(() -> (int) field(field(home, "mPosterHome"), "generation") > generation,
                "one tap on an unfocused footer retry starts a fresh discovery request");
    }

    @Test(timeout = 60000)
    public void scrollingSourceCardsStayInsideTheirViewportAndLeaveControlsUsable() {
        main(() -> {
            BrowseExperienceSettings.putDetailSourcesEnabled(true);
            savedSites = field(VodConfig.get(), "sites");
            replacedSites = true;
            setField(VodConfig.get(), "sites", new ArrayList<Site>());
        });
        DiscoverDetailActivity detail = launchDetail(true);
        await(detail::hasWindowFocus, "detail for a scrollable source fixture");
        tapView(value(() -> detail.findViewById(R.id.search)));
        View panel = value(() -> detail.findViewById(R.id.sourcePanel));
        await(() -> panel.isShown() && panel.findViewById(R.id.smart).hasFocus(), "source controls are ready");
        RecyclerView results = value(() -> panel.findViewById(R.id.results));
        main(() -> {
            List<Vod> candidates = new ArrayList<>();
            for (int index = 0; index < 30; index++) {
                Vod candidate = poster("scroll-source-item-" + index, "星河旅人", "movie");
                candidate.setSite(Site.get("scroll-source-" + index, "示例播放源 " + (index + 1)));
                candidates.add(candidate);
            }
            Object controller = field(detail, "sources");
            ((PosterSourceResults) field(controller, "results")).add(candidates);
            invoke(controller, "render");
        });
        await(() -> results.getAdapter() != null && results.getAdapter().getItemCount() == 30
                && results.findViewHolderForAdapterPosition(0) != null && results.canScrollVertically(1),
                "more source cards than can fit in the viewport");
        main(() -> {
            View first = results.findViewHolderForAdapterPosition(0).itemView;
            assertTrue(first.getHeight() > 0);
            // A non-integral row scroll exposes the first/last partially clipped cards.
            results.scrollBy(0, first.getHeight() / 2 + results.getPaddingTop());
        });
        await(() -> results.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && !results.isLayoutRequested()
                && !results.isComputingLayout() && !results.isAnimating(), "partial source row scroll settles");
        main(() -> {
            RectF viewport = paddedScreenBounds(results);
            int clippedVisibleCards = 0;
            for (int index = 0; index < results.getChildCount(); index++) {
                View card = results.getChildAt(index);
                RectF full = screenBounds(card);
                boolean crossesEdge = full.top < viewport.top || full.bottom > viewport.bottom;
                if (!crossesEdge || !RectF.intersects(viewport, full)) continue;
                Rect visible = new Rect();
                assertTrue("A card straddling the viewport still has a visible portion", card.getGlobalVisibleRect(visible));
                clippedVisibleCards++;
                assertContains("Overflowing source card pixels must be clipped before the controls/footer", viewport, new RectF(visible));
            }
            assertTrue("The fixture must exercise actual partial-row clipping, not only complete cards", clippedVisibleCards > 0);
            for (int id : new int[]{R.id.heading, R.id.smart, R.id.retry, R.id.priority}) {
                RectF control = screenBounds(panel.findViewById(id));
                assertTrue("The source header controls remain above the scrolling viewport", control.bottom <= viewport.top + 1.5f);
            }
            for (int id : new int[]{R.id.fullSearch, R.id.close}) {
                RectF control = screenBounds(panel.findViewById(id));
                assertTrue("The source footer remains below the scrolling viewport", control.top + 1.5f >= viewport.bottom);
            }
            assertTrue(panel.findViewById(R.id.smart).requestFocus());
        });
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> panel.findViewById(R.id.retry).hasFocus(), "header controls remain reachable after scrolling many cards");
        tapView(value(() -> panel.findViewById(R.id.close)));
        await(() -> !panel.isShown() && detail.findViewById(R.id.search).hasFocus(),
                "the footer close button remains actionable after partially scrolling source cards");
    }

    @Test(timeout = 60000)
    public void detailSourcePanelIsOptionalAndWorksWithoutMetadataOrConfiguredSources() {
        DiscoverDetailActivity original = launchDetail(false);
        await(() -> original.hasWindowFocus(), "original detail window");
        main(() -> {
            assertNull(field(original, "sources"));
            assertEquals(original.getString(R.string.discover_search_play), ((TextView) original.findViewById(R.id.search)).getText().toString());
            assertEquals(View.GONE, original.findViewById(R.id.sourcePanel).getVisibility());
            original.finish();
            BrowseExperienceSettings.putDetailSourcesEnabled(true);
            savedSites = field(VodConfig.get(), "sites");
            replacedSites = true;
            setField(VodConfig.get(), "sites", new ArrayList<Site>());
        });
        await(original::isDestroyed, "the original detail finishes before testing the optional panel");

        DiscoverDetailActivity detail = launchDetail(true);
        await(() -> detail.hasWindowFocus() && (boolean) field(detail, "metadataError"), "unavailable metadata uses poster fallback");
        View panel = value(() -> detail.findViewById(R.id.sourcePanel));
        main(() -> {
            assertEquals("星河旅人", ((TextView) detail.findViewById(R.id.title)).getText().toString());
            assertTrue(detail.findViewById(R.id.search).requestFocus());
        });
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        await(() -> panel.isShown() && panel.findViewById(R.id.smart).hasFocus(), "native source panel initial focus");
        main(() -> {
            assertEquals(View.GONE, detail.findViewById(R.id.poster).getVisibility());
            assertEquals(detail.getString(R.string.poster_sources_no_sites), ((TextView) panel.findViewById(R.id.status)).getText().toString());
            assertFalse(containsWebView(detail.getWindow().getDecorView()));
        });
        press(KeyEvent.KEYCODE_DPAD_RIGHT);
        await(() -> panel.findViewById(R.id.retry).hasFocus(), "source retry reachable by D-pad");
        press(KeyEvent.KEYCODE_DPAD_LEFT);
        await(() -> panel.findViewById(R.id.smart).hasFocus(), "return to smart source option");
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        await(BrowseExperienceSettings::isSmartSourceEnabled, "smart sorting is separately optional");

        // Feed only transient search results; never install a source or launch its playback code.
        main(() -> {
            Object controller = field(detail, "sources");
            Vod candidate = poster("fixture-source-item", "星河旅人", "movie");
            candidate.setSite(Site.get("native-browse-fixture", "示例播放源"));
            ((PosterSourceResults) field(controller, "results")).add(List.of(candidate));
            invoke(controller, "render");
        });
        RecyclerView candidates = value(() -> panel.findViewById(R.id.results));
        await(() -> candidates.findViewHolderForAdapterPosition(0) != null, "a matched source renders as a native row");
        main(() -> assertTrue(candidates.findViewHolderForAdapterPosition(0).itemView.requestFocus()));
        await(candidates::hasFocus, "source row focus");
        press(KeyEvent.KEYCODE_BACK);
        await(() -> !panel.isShown() && detail.findViewById(R.id.search).hasFocus(), "BACK closes only the panel and restores the primary action");
        main(() -> assertFalse(detail.isFinishing()));

        tapView(value(() -> detail.findViewById(R.id.search)));
        await(panel::isShown, "source panel reopens for the complete-search fallback");
        tapView(value(() -> panel.findViewById(R.id.fullSearch)));
        SearchActivity search = awaitActivity(SearchActivity.class);
        main(() -> {
            View keyword = search.findViewById(R.id.keyword);
            assertTrue("The fallback opens an editable input, not a results-only page", keyword instanceof EditText);
            EditText input = (EditText) keyword;
            assertEquals("星河旅人", input.getText().toString());
            assertTrue(input.isEnabled() && input.isFocusable() && input.onCheckIsTextEditor());
            assertNotNull("The prefilled keyword accepts edits", input.getKeyListener());
        });
        press(KeyEvent.KEYCODE_BACK);
        await(() -> detail.hasWindowFocus() && !detail.isFinishing(), "BACK from editable search returns to the poster detail");
    }

    @Test(timeout = 60000)
    public void posterPriorityEntryRestartsSearchAndKeepsFocusStableUntilLeavingResults() {
        Site fallback = Site.get("native-priority-fallback", "示例补充来源");
        Site preferred = Site.get("native-priority-preferred", "示例常用来源");
        fallback.setSearchable(1);
        preferred.setSearchable(1);
        main(() -> {
            savedConfig = VodConfig.get().getConfig();
            replacedConfig = true;
            VodConfig.get().config(new Config().url("https://native-priority-fixture.invalid/" + UUID.randomUUID()));
            savedSites = field(VodConfig.get(), "sites");
            replacedSites = true;
            setField(VodConfig.get(), "sites", new ArrayList<>(List.of(fallback, preferred)));
            PosterSourcePrioritySetting.putOrderedKeys(List.of(preferred.getKey()));
            BrowseExperienceSettings.putDetailSourcesEnabled(true);
            BrowseExperienceSettings.putSmartSourceEnabled(true);
        });
        DiscoverDetailActivity detail = launchDetail(true);
        await(detail::hasWindowFocus, "poster detail for the source-priority fixture");
        tapView(value(() -> detail.findViewById(R.id.search)));
        View panel = value(() -> detail.findViewById(R.id.sourcePanel));
        Object controller = value(() -> field(detail, "sources"));
        View priority = value(() -> panel.findViewById(R.id.priority));
        RecyclerView list = value(() -> panel.findViewById(R.id.results));
        await(() -> panel.isShown() && !(boolean) field(controller, "running"), "offline queries finish before feeding transient results");
        main(() -> {
            assertEquals(detail.getString(R.string.poster_source_priority_button_count, 1), ((TextView) priority).getText().toString());
            Vod early = poster("shared", "星河旅人", "movie");
            early.setSite(fallback);
            ((PosterSourceResults) field(controller, "results")).add(List.of(early));
            invoke(controller, "render");
        });
        await(() -> list.findViewHolderForAdapterPosition(0) != null, "early fallback source is displayed");
        press(KeyEvent.KEYCODE_DPAD_UP);
        main(() -> assertTrue(list.findViewHolderForAdapterPosition(0).itemView.requestFocus()));
        await(list::hasFocus, "remote focus stays on the fallback source");
        main(() -> {
            Vod late = poster("shared", "星河旅人", "movie");
            late.setYear(""); // Less metadata must not override the user's explicit source preference.
            late.setSite(preferred);
            Vod wrongYear = poster("wrong-year", "星河旅人", "movie");
            wrongYear.setYear("1984");
            wrongYear.setSite(preferred);
            Vod wrongTitle = poster("wrong-title", "山海之间", "movie");
            wrongTitle.setSite(preferred);
            Vod wrongKind = poster("wrong-kind", "星河旅人", "tv");
            wrongKind.setSite(preferred);
            ((PosterSourceResults) field(controller, "results")).add(List.of(late, wrongYear, wrongTitle, wrongKind));
            invoke(controller, "render");
        });
        await(() -> list.getAdapter().getItemCount() == 2, "only the matching preferred and fallback sources remain");
        main(() -> {
            assertEquals(fallback.getName(), rowText(list, 0, R.id.site));
            assertTrue("A later preferred answer must not move the card under the remote", list.findViewHolderForAdapterPosition(0).itemView.hasFocus());
            assertTrue(priority.requestFocus());
        });
        await(() -> detail.getString(R.string.poster_source_priority_site, 1, preferred.getName()).equals(rowText(list, 0, R.id.site)),
                "leaving the result list applies the preferred order");
        Object previousRound = value(() -> field(controller, "results"));

        tapView(priority);
        awaitTextBounds(detail.getString(R.string.poster_source_priority_title));
        Rect reset = awaitTextBounds(detail.getString(R.string.poster_source_priority_reset));
        tap(reset.exactCenterX(), reset.exactCenterY());
        assertEquals("Reset is still a draft before Save", List.of(preferred.getKey()), value(PosterSourcePrioritySetting::getOrderedKeys));
        Rect save = awaitTextBounds(detail.getString(R.string.poster_source_priority_save));
        tap(save.exactCenterX(), save.exactCenterY());
        await(() -> detail.hasWindowFocus() && priority.hasFocus() && field(controller, "results") != previousRound,
                "saving from the real rail entry starts a fresh round and restores control focus");
        main(() -> {
            assertTrue(PosterSourcePrioritySetting.getOrderedKeys().isEmpty());
            assertEquals(List.of(), field(controller, "priorities"));
            assertEquals(detail.getString(R.string.poster_source_priority_button_default), ((TextView) priority).getText().toString());
            assertEquals(List.of(fallback, preferred), PosterSourcePrioritySetting.orderSites(VodConfig.get().getSites()));
            PosterSourcePrioritySetting.putOrderedKeys(List.of(preferred.getKey()));
            BrowseExperienceSettings.restoreOriginal();
            assertTrue("Restore original browsing also clears this configuration's optional source priority",
                    PosterSourcePrioritySetting.getOrderedKeys().isEmpty());
        });
    }

    private HomeActivity changeHomeMode(HomeActivity previous, Runnable change, boolean enabled) {
        CorePlaybackActivity cover = launch(CorePlaybackActivity.class);
        await(cover::hasWindowFocus, "temporary activity pauses home");
        main(() -> { change.run(); cover.finish(); });
        AtomicReference<HomeActivity> replacement = new AtomicReference<>();
        await(() -> {
            for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (candidate instanceof HomeActivity home && home != previous && home.hasWindowFocus()
                        && (field(home, "mPosterHome") != null) == enabled) {
                    replacement.set(home);
                    return true;
                }
            }
            return false;
        }, "home recreates after the mode setting changes");
        launched.add(replacement.get());
        return replacement.get();
    }

    private DiscoverDetailActivity launchDetail(boolean douban) {
        Intent intent = new Intent(instrumentation.getTargetContext(), DiscoverDetailActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (douban) intent.putExtra("doubanItem", poster("douban:900001", "星河旅人", "movie"));
        else intent.putExtra("key", "tmdb:movie:900001");
        intent.putExtra("title", "星河旅人");
        intent.putExtra("year", "2026");
        intent.putExtra("poster", artwork());
        intent.putExtra("backdrop", artwork());
        intent.putExtra("overview", "用于验证遥控器操作的本地影片卡片，元数据网络不可用时仍能选择播放源。");
        DiscoverDetailActivity activity = (DiscoverDetailActivity) instrumentation.startActivitySync(intent);
        launched.add(activity);
        return activity;
    }

    private void seedPosters() {
        List<Vod> movies = List.of(poster("tmdb:movie:900001", "星河旅人", "movie"), poster("tmdb:movie:900003", "山海之间", "movie"));
        List<Vod> series = List.of(poster("tmdb:tv:900002", "长夜微光", "tv"));
        try {
            Constructor<?> constructor = Class.forName(DiscoverApi.class.getName() + "$CacheEntry").getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            for (DiscoverApi.Row row : DiscoverApi.Row.values()) {
                List<Vod> items = row.name().contains("TV") ? series : movies;
                cache.put(row, constructor.newInstance(items));
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private Vod poster(String id, String title, String type) {
        Vod item = new Vod();
        item.setId(id);
        item.setName(title);
        item.setTypeName(type);
        item.setYear("2026");
        item.setPic(artwork());
        item.setBackdrop(artwork());
        item.setContent("用于验证原生海报墙与遥控器操作的本地卡片。");
        return item;
    }

    private String artwork() {
        return "android.resource://" + instrumentation.getTargetContext().getPackageName() + "/" + R.drawable.wallpaper_1;
    }

    private int firstShelfPosition(HomeActivity home) {
        List<Integer> positions = shelfPositions(home);
        if (!positions.isEmpty()) return positions.get(0);
        throw new AssertionError("Fixture has no focusable poster shelf");
    }

    private List<Integer> shelfPositions(HomeActivity home) {
        ArrayObjectAdapter adapter = (ArrayObjectAdapter) field(home, "mAdapter");
        PosterHomeController controller = (PosterHomeController) field(home, "mPosterHome");
        List<Integer> result = new ArrayList<>();
        for (int i = adapter.indexOf(R.string.home_recommend) + 1; i < adapter.size() - 1; i++) {
            if (controller.isFocusable(adapter.get(i))) result.add(i);
        }
        return result;
    }

    private static void assertAttachedShelfGeometry(VerticalGridView page) {
        List<RecyclerView.ViewHolder> shelves = new ArrayList<>();
        for (int index = 0; index < page.getChildCount(); index++) {
            RecyclerView.ViewHolder holder = page.getChildViewHolder(page.getChildAt(index));
            HorizontalGridView posters = holder.itemView.findViewById(R.id.posters);
            if (posters == null || !holder.itemView.isShown() || holder.itemView.getHeight() == 0
                    || posters.getAdapter() == null || posters.getAdapter().getItemCount() == 0) continue;
            shelves.add(holder);
            RectF shelfBounds = screenBounds(holder.itemView);
            RectF gridBounds = screenBounds(posters);
            View heading = holder.itemView.findViewById(R.id.title);
            RectF headingBounds = screenBounds(heading);
            for (int cardIndex = 0; cardIndex < posters.getChildCount(); cardIndex++) {
                View card = posters.getChildAt(cardIndex);
                if (!card.isShown() || card.getHeight() == 0) continue;
                RectF cardBounds = screenBounds(card);
                assertContains("A complete poster card must fit its shelf", shelfBounds, cardBounds);
                assertContains("The poster viewport must reserve space for artwork, title and metadata", gridBounds, cardBounds);
                assertTrue("Shelf heading must remain above its cards: " + headingBounds + " / " + cardBounds,
                        headingBounds.bottom <= cardBounds.top + 1.5f);
            }
        }
        assertFalse("At least one populated shelf must be attached", shelves.isEmpty());
        shelves.sort(java.util.Comparator.comparingInt(RecyclerView.ViewHolder::getBindingAdapterPosition));
        for (int index = 1; index < shelves.size(); index++) {
            RectF previous = screenBounds(shelves.get(index - 1).itemView);
            RectF next = screenBounds(shelves.get(index).itemView);
            assertTrue("Adjacent shelves must not overlap: " + previous + " / " + next,
                    previous.bottom <= next.top + 1.5f);
        }
    }

    private static void assertContains(String reason, RectF outer, RectF inner) {
        // Locations are rounded to physical pixels; allow only that rounding, not clipped cards.
        assertTrue(reason + ": " + outer + " / " + inner,
                outer.left <= inner.left + 1.5f && outer.top <= inner.top + 1.5f
                        && outer.right + 1.5f >= inner.right && outer.bottom + 1.5f >= inner.bottom);
    }

    private static RectF screenBounds(View view) {
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        // Card focus is a scale transform. VisibleRect would conceal the very overflow being tested.
        return new RectF(origin[0], origin[1], origin[0] + view.getWidth() * view.getScaleX(),
                origin[1] + view.getHeight() * view.getScaleY());
    }

    private static RectF paddedScreenBounds(View view) {
        RectF result = screenBounds(view);
        result.left += view.getPaddingLeft();
        result.top += view.getPaddingTop();
        result.right -= view.getPaddingRight();
        result.bottom -= view.getPaddingBottom();
        return result;
    }

    private static String rowText(RecyclerView recycler, int position, int viewId) {
        RecyclerView.ViewHolder holder = recycler.findViewHolderForAdapterPosition(position);
        if (holder == null) return "";
        TextView view = holder.itemView.findViewById(viewId);
        return view == null ? "" : view.getText().toString();
    }

    private static View focusedPosterCard(HomeActivity home, int shelfPosition) {
        VerticalGridView page = home.findViewById(R.id.recycler);
        if (!home.hasWindowFocus() || page.getSelectedPosition() != shelfPosition) return null;
        RecyclerView.ViewHolder shelf = page.findViewHolderForAdapterPosition(shelfPosition);
        if (shelf == null) return null;
        HorizontalGridView posters = shelf.itemView.findViewById(R.id.posters);
        if (posters == null || !posters.hasFocus()) return null;
        RecyclerView.ViewHolder card = posters.findViewHolderForAdapterPosition(posters.getSelectedPosition());
        if (card == null || !card.itemView.isShown() || !card.itemView.hasFocus()
                || !card.itemView.isClickable() || !card.itemView.hasOnClickListeners()) return null;
        return card.itemView;
    }

    private static View visibleView(Activity activity, int id) {
        View view = activity.findViewById(id);
        return view != null && view.isShown() ? view : null;
    }

    private static boolean containsWebView(View view) {
        if (view instanceof WebView) return true;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            if (containsWebView(group.getChildAt(i))) return true;
        }
        return false;
    }

    private <T extends Activity> T launch(Class<T> type) {
        int flags = Intent.FLAG_ACTIVITY_NEW_TASK;
        if (type == HomeActivity.class) flags |= Intent.FLAG_ACTIVITY_CLEAR_TASK;
        T activity = type.cast(instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), type)
                .addFlags(flags)));
        launched.add(activity);
        return activity;
    }

    private List<Activity> validationActivities() {
        List<Activity> result = new ArrayList<>();
        String targetPackage = instrumentation.getTargetContext().getPackageName();
        for (Stage stage : new Stage[]{Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED}) {
            for (Activity activity : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)) {
                if (targetPackage.equals(activity.getPackageName())) result.add(activity);
            }
        }
        return result;
    }

    private <T extends Activity> T awaitActivity(Class<T> type) {
        AtomicReference<T> result = new AtomicReference<>();
        await(() -> {
            for (Activity candidate : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                if (type.isInstance(candidate) && candidate.hasWindowFocus()) {
                    result.set(type.cast(candidate));
                    return true;
                }
            }
            return false;
        }, type.getSimpleName() + " becomes visible");
        launched.add(result.get());
        return result.get();
    }

    @SuppressWarnings("unchecked")
    private static Map<DiscoverApi.Row, Object> discoverCache() {
        try {
            Field field = DiscoverApi.class.getDeclaredField("CACHE");
            field.setAccessible(true);
            return (Map<DiscoverApi.Row, Object>) field.get(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static Object field(Object owner, String name) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner instanceof Class<?> ? null : owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void setField(Object owner, String name, Object value) {
        try {
            Field field = (owner instanceof Class<?> type ? type : owner.getClass()).getDeclaredField(name);
            field.setAccessible(true);
            field.set(owner instanceof Class<?> ? null : owner, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(Object owner, String name) {
        try {
            Method method = owner.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(owner);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void focusRow(HomeActivity home, int position) {
        try {
            Method method = HomeActivity.class.getDeclaredMethod("requestRecyclerFocus", int.class);
            method.setAccessible(true);
            method.invoke(home, position);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void press(int keyCode) {
        instrumentation.sendKeyDownUpSync(keyCode);
        // Each caller awaits its concrete focus/activity state; animated pages need not go idle.
    }

    private Rect awaitTextBounds(String text) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                Rect found;
                // Compose exposes virtual children but does not implement the provider text-search API.
                // Traverse the actual accessible nodes, as a user-facing automation selector does.
                try { found = findVisibleTextBounds(root, text, 0); }
                finally { root.recycle(); }
                if (found != null) return found;
            }
            SystemClock.sleep(50);
        }
        throw new AssertionError("No visible touch target for " + text);
    }

    private Rect findVisibleTextBounds(AccessibilityNodeInfo node, String text, int depth) {
        if (depth > 50) return null;
        if (text.contentEquals(node.getText() == null ? "" : node.getText()) && node.isVisibleToUser()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return bounds;
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) continue;
            Rect found;
            try { found = findVisibleTextBounds(child, text, depth + 1); }
            finally { child.recycle(); }
            if (found != null) return found;
        }
        return null;
    }

    private void tapView(View view) {
        Rect bounds = value(() -> {
            Rect result = new Rect();
            assertTrue("Touch target is actually visible", view.isShown() && view.getGlobalVisibleRect(result) && !result.isEmpty());
            return result;
        });
        tap(bounds.exactCenterX(), bounds.exactCenterY());
    }

    private void tap(float x, float y) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { instrumentation.sendPointerSync(down); }
        finally { down.recycle(); }
        SystemClock.sleep(40);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { instrumentation.sendPointerSync(up); }
        finally { up.recycle(); }
        // A looping input cursor animation can prevent global idle indefinitely. Callers use
        // bounded state assertions to observe the actual tap result instead.
    }

    private void await(BooleanSupplier predicate, String reason) {
        long deadline = SystemClock.elapsedRealtime() + 8000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (value(predicate::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for " + reason);
    }

    private void main(Runnable action) { value(() -> { action.run(); return null; }); }

    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); }
            catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("UI operation failed", failure.get());
        return result.get();
    }
}
