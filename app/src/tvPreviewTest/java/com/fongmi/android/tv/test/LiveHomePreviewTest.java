package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.HorizontalGridView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.ThemeMode;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.activity.LiveHomePreviewActivity;
import com.fongmi.android.tv.ui.home.PosterHomeCatalog;
import com.fongmi.android.tv.ui.home.PosterHomeController;
import com.fongmi.android.tv.ui.rex.RexFeedLoader;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.*;

/** Live official TMDB responses and decoded artwork on the real compact home. */
@RunWith(AndroidJUnit4.class)
public final class LiveHomePreviewTest {
    private static final String[] PREFERENCES = {"tmdb_connection_mode", "tmdb_proxy_url", "browse_home_layout_v1",
            "browse_poster_home", "browse_hero_rotation", "tv_theme_mode", "tv_light_theme"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final AtomicInteger officialImages = new AtomicInteger();
    private final List<String> successfulApiPaths = new CopyOnWriteArrayList<>();
    private LiveHomePreviewActivity home;
    private OkHttpClient liveClient;
    private Object previousTmdbClient;

    @Test(timeout = 150_000)
    public void capturesLiveOfficialCatalogAndDecodedArtwork() throws Exception {
        assertEquals("Isolated preview package only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        File credential = new File(instrumentation.getTargetContext().getFilesDir(), "tmdb-preview.key");
        assertTrue("A runtime-only TMDB credential is required", credential.isFile());
        String secret = new String(Files.readAllBytes(credential.toPath()), StandardCharsets.UTF_8).trim();
        assertTrue("Remove the runtime credential before loading UI", credential.delete());
        assertTrue("A valid TMDB API key is required", secret.matches("[a-fA-F0-9]{32}"));
        Map<String, ?> previousPreferences = Prefers.getPrefers().getAll();
        try {
            main(() -> {
                Setting.putTmdbConnectionMode(Setting.TMDB_DIRECT);
                BrowseExperienceSettings.putHomeLayout(BrowseExperienceSettings.HOME_COMPACT);
                BrowseExperienceSettings.putHeroRotationMode(BrowseExperienceSettings.HERO_ROTATION_MANUAL);
                Setting.putThemeMode(ThemeMode.DARK);
                RexFeedLoader.clearCache();
                DiscoverApi.clearTmdbCache();
                previousTmdbClient = field(TmdbNetwork.class, "client");
                liveClient = productionTmdbClient().newBuilder().addInterceptor(chain -> {
                    Request original = chain.request();
                    HttpUrl url = original.url();
                    Request request = original;
                    boolean officialApi = "api.themoviedb.org".equals(url.host()) && url.encodedPath().startsWith("/3/");
                    if (officialApi) request = original.newBuilder().url(url.newBuilder()
                            .setQueryParameter("api_key", secret).build()).build();
                    try {
                        Response response = chain.proceed(request);
                        if (response.isSuccessful()) {
                            if (officialApi) successfulApiPaths.add(url.encodedPath());
                            if ("image.tmdb.org".equals(url.host())) officialImages.incrementAndGet();
                        }
                        // The real credential must never appear in a callback's request or error text.
                        return response.newBuilder().request(original).build();
                    } catch (IOException error) {
                        throw new IOException("Live TMDB request failed");
                    }
                }).build();
                setField(TmdbNetwork.class, "client", liveClient);
            });
            assertEquals(TmdbEndpoint.DIRECT_API_BASE, TmdbEndpoint.getApiBase());
            assertEquals(TmdbEndpoint.DIRECT_IMAGE_BASE, TmdbEndpoint.getImageBase());
            home = (LiveHomePreviewActivity) instrumentation.startActivitySync(new Intent(
                    instrumentation.getTargetContext(), LiveHomePreviewActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await("Real TMDB trend and movie/series feeds load", 40_000, () -> home.hasWindowFocus()
                    && items("trending_day").size() >= 5 && items("now_playing").size() >= 5 && items("popular_tv").size() >= 5);
            main(() -> {
                for (String key : List.of("trending_day", "now_playing", "popular_tv")) {
                    for (Vod item : items(key)) {
                        assertTrue("Live catalog has a TMDB identity", item.getId().matches("tmdb:(movie|tv):[1-9][0-9]*"));
                        assertFalse("Live catalog has a title", item.getName().isEmpty());
                        assertEquals("Artwork uses the official image host", "image.tmdb.org", HttpUrl.get(item.getPic()).host());
                    }
                }
            });
            await("A real TMDB hero backdrop is decoded", 30_000, this::heroArtworkReady);
            assertTrue("Official API traffic was completed", successfulApiPaths.stream().anyMatch(path -> path.endsWith("/trending/all/day")));
            assertTrue("Official images were fetched", officialImages.get() > 0);
            NativeUiEvidence.capture("live-home-direct");

            selectCategory(PosterHomeCatalog.MOVIES);
            focusShelf("now_playing");
            await("Real movie posters decode", 25_000, () -> shelfArtworkReady("now_playing"));
            NativeUiEvidence.capture("live-home-movies");

            selectCategory(PosterHomeCatalog.TV);
            focusShelf("popular_tv");
            await("Real series posters decode", 25_000, () -> shelfArtworkReady("popular_tv"));
            NativeUiEvidence.capture("live-home-series");

            selectCategory(PosterHomeCatalog.ALL);
            main(() -> invoke(home, "requestRecyclerFocus", new Class<?>[]{int.class}, categoriesPosition()));
            await("Category controls are visible", 10_000, () -> home.findViewById(R.id.filter) != null
                    && home.findViewById(R.id.filter).isShown());
            NativeUiEvidence.capture("live-home-categories");
            saveReport();
        } finally {
            if (home != null) main(home::finish);
            if (home != null) await("Home closes before restoring settings", 10_000, home::isDestroyed);
            main(() -> {
                if (liveClient != null) liveClient.dispatcher().cancelAll();
                setField(TmdbNetwork.class, "client", previousTmdbClient);
                SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                for (String key : PREFERENCES) {
                    Object value = previousPreferences.get(key);
                    if (value instanceof String text) editor.putString(key, text);
                    else if (value instanceof Integer mode) editor.putInt(key, mode);
                    else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                    else editor.remove(key);
                }
                assertTrue("Restore preview settings", editor.commit());
                RexFeedLoader.clearCache();
                DiscoverApi.clearTmdbCache();
            });
            if (liveClient != null) liveClient.connectionPool().evictAll();
            if (credential.exists()) assertTrue(credential.delete());
        }
    }

    private void saveReport() throws Exception {
        JSONObject report = new JSONObject().put("liveMetadata", true).put("connection", "direct")
                .put("apiHost", "api.themoviedb.org").put("imageHost", "image.tmdb.org")
                .put("successfulApiPaths", new JSONArray(successfulApiPaths.stream().distinct().toList()))
                .put("successfulImageResponses", officialImages.get()).put("decodedHeroArtwork", true)
                .put("decodedMoviePosters", true).put("decodedSeriesPosters", true);
        JSONObject catalogs = new JSONObject();
        for (String key : List.of("trending_day", "now_playing", "popular_tv")) {
            JSONArray preview = new JSONArray();
            for (Vod item : value(() -> items(key)).stream().limit(5).toList()) {
                preview.put(new JSONObject().put("id", item.getId()).put("title", item.getName()).put("year", item.getYear()));
            }
            catalogs.put(key, new JSONObject().put("count", value(() -> items(key).size())).put("preview", preview));
        }
        report.put("catalogs", catalogs);
        File directory = instrumentation.getTargetContext().getExternalFilesDir("live-home-verification");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Files.write(new File(directory, "live-home.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void selectCategory(int category) {
        main(() -> invoke(controller(), "selectCategory", new Class<?>[]{int.class}, category));
    }

    private void focusShelf(String key) {
        main(() -> invoke(home, "requestRecyclerFocus", new Class<?>[]{int.class}, shelfPosition(key)));
    }

    private int categoriesPosition() {
        return page().indexOf(field(controller(), "categories"));
    }

    private int shelfPosition(String key) {
        for (Object shelf : (List<?>) field(controller(), "shelves")) {
            if (key.equals(field(shelf, "key"))) return page().indexOf(shelf);
        }
        throw new AssertionError("Missing live shelf " + key);
    }

    private boolean heroArtworkReady() {
        RecyclerView.ViewHolder holder = grid().findViewHolderForAdapterPosition(0);
        if (holder == null) return false;
        ImageView first = holder.itemView.findViewById(R.id.imageA);
        ImageView second = holder.itemView.findViewById(R.id.imageB);
        return isRealArtwork(first) || isRealArtwork(second);
    }

    private boolean shelfArtworkReady(String key) {
        int position = shelfPosition(key);
        if (position < 0) return false;
        RecyclerView.ViewHolder holder = grid().findViewHolderForAdapterPosition(position);
        if (holder == null) return false;
        HorizontalGridView posters = holder.itemView.findViewById(R.id.posters);
        if (posters == null) return false;
        RecyclerView.ViewHolder first = posters.findViewHolderForAdapterPosition(0);
        RecyclerView.ViewHolder second = posters.findViewHolderForAdapterPosition(1);
        return first != null && second != null && isRealArtwork(first.itemView.findViewById(R.id.image))
                && isRealArtwork(second.itemView.findViewById(R.id.image));
    }

    private boolean isRealArtwork(ImageView view) {
        if (view == null || !view.isShown() || view.getDrawable() == null) return false;
        Drawable current = view.getDrawable();
        if (current.getIntrinsicWidth() < 100 || current.getIntrinsicHeight() < 100) return false;
        Bitmap actual = render(current);
        Bitmap placeholder = render(home.getDrawable(R.drawable.artwork));
        boolean real = !actual.sameAs(placeholder);
        actual.recycle();
        placeholder.recycle();
        return real;
    }

    private static Bitmap render(Drawable drawable) {
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        android.graphics.Rect bounds = drawable.copyBounds();
        drawable.setBounds(0, 0, 32, 32);
        drawable.draw(new Canvas(bitmap));
        drawable.setBounds(bounds);
        return bitmap;
    }

    @SuppressWarnings("unchecked") private List<Vod> items(String key) {
        Map<String, RexFeedResult> feeds = (Map<String, RexFeedResult>) field(controller(), "compactFeeds");
        RexFeedResult result = feeds.get(key);
        return result == null ? List.of() : result.getItems();
    }

    private PosterHomeController controller() { return (PosterHomeController) field(home, "mPosterHome"); }
    private ArrayObjectAdapter page() { return (ArrayObjectAdapter) field(home, "mAdapter"); }
    private RecyclerView grid() { return home.findViewById(R.id.recycler); }

    private static Object field(Object target, String name) {
        Class<?> type = target instanceof Class<?> cls ? cls : target.getClass();
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target instanceof Class<?> ? null : target);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
        throw new AssertionError("Missing preview field " + name);
    }

    private static void setField(Class<?> owner, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static OkHttpClient productionTmdbClient() {
        try {
            Method method = TmdbNetwork.class.getDeclaredMethod("client");
            method.setAccessible(true);
            return (OkHttpClient) method.invoke(null);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void invoke(Object target, String name, Class<?>[] arguments, Object... values) {
        Class<?> type = target instanceof HomeActivity ? HomeActivity.class : target.getClass();
        try {
            Method method = type.getDeclaredMethod(name, arguments);
            method.setAccessible(true);
            method.invoke(target, values);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void main(Runnable task) {
        instrumentation.runOnMainSync(task);
        instrumentation.waitForIdleSync();
    }

    private <T> T value(Supplier<T> task) {
        AtomicReference<T> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(task.get()));
        return result.get();
    }

    private void await(String message, long timeout, BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            if (value(condition::getAsBoolean)) return;
            SystemClock.sleep(100);
        }
        fail(message);
    }
}
