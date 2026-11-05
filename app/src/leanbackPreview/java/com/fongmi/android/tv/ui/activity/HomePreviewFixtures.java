package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.rex.RexFeedLoader;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.ui.rex.RexSections;
import com.fongmi.android.tv.utils.TmdbNetwork;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Seeds the existing production caches and database, as the native browse tests do. */
public final class HomePreviewFixtures {
    private static final String HOME_SOURCE_URL = "https://tv-home-fixture.invalid/home";
    private static final String HOME_SOURCE_CONTENT = "<rss><class><ty id=\"movie\">电影</ty><ty id=\"tv\">剧集</ty></class><list/></rss>";
    private static final String ACTION_URL = "https://tv-home-fixture.invalid/action";
    static Session active;

    private HomePreviewFixtures() { }

    public static Session open(Context context) {
        return open(context, false);
    }

    public static Session openCompact(Context context) {
        return open(context, true);
    }

    private static Session open(Context context, boolean compact) {
        if (!BuildConfig.DEBUG || !context.getPackageName().endsWith(".preview")) {
            throw new IllegalStateException("Home fixtures require the isolated preview application");
        }
        if (active != null) throw new IllegalStateException("A home fixture session is already active");
        active = new Session(compact);
        return active;
    }

    public static Object field(Object target, String name) {
        return access(target, name, null, false);
    }

    static void setField(Object target, String name, Object value) {
        access(target, name, value, true);
    }

    private static Object access(Object target, String name, Object value, boolean write) {
        Class<?> type = target instanceof Class<?> cls ? cls : target.getClass();
        Object instance = target instanceof Class<?> ? null : target;
        for (Class<?> owner = type; owner != null; owner = owner.getSuperclass()) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                if (write) { field.set(instance, value); return null; }
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot access preview fixture field " + name, error);
            }
        }
        throw new IllegalStateException("Missing preview fixture field " + type.getName() + "." + name);
    }

    public static final class Session implements AutoCloseable {
        public final List<History> histories = new ArrayList<>();
        public final AtomicInteger actionCalls = new AtomicInteger();
        private final Object homeLayout = Prefers.getPrefers().getAll().get("browse_home_layout_v1");
        private final Object posterHome = Prefers.getPrefers().getAll().get("browse_poster_home");
        private final boolean compact;
        private final boolean keepShelf = BrowseExperienceSettings.isPosterKeepShelfEnabled();
        private final boolean historyActions = BrowseExperienceSettings.isHistoryActionsEnabled();
        private final int siteMode = Setting.getSiteMode();
        private final OkHttpClient client = OkHttp.client();
        private final Object tmdbClient = field(TmdbNetwork.class, "client");
        private final Object sites = field(VodConfig.get(), "sites");
        private final Object home = field(VodConfig.get(), "home");
        private final Object config = field(VodConfig.get(), "config");
        private final Map<Object, Object> discover = cache(DiscoverApi.class);
        private final Map<Object, Object> rex = cache(RexFeedLoader.class);
        private final Map<Object, Object> savedDiscover = new HashMap<>(discover);
        private final Map<Object, Object> savedRex = new HashMap<>(rex);
        private final int cid;
        private final List<History> savedHistory;

        private Session(boolean compact) {
            this.compact = compact;
            VodConfig.get().init();
            cid = VodConfig.getCid();
            savedHistory = new ArrayList<>(AppDatabase.get().getHistoryDao().findAll(cid));
            if (compact) {
                // Exercise both upgrade and install defaults, including a stale legacy flag.
                Prefers.put("browse_poster_home", false);
                Prefers.remove("browse_home_layout_v1");
            } else BrowseExperienceSettings.putPosterHomeEnabled(true);
            BrowseExperienceSettings.putPosterKeepShelfEnabled(false);
            BrowseExperienceSettings.putHistoryActionsEnabled(false);
            OkHttpClient offline = client.newBuilder().addInterceptor(chain -> {
                if (chain.request().url().toString().startsWith(HOME_SOURCE_URL)) {
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK")
                            .body(ResponseBody.create(compact ? compactSource() : HOME_SOURCE_CONTENT,
                                    MediaType.get(compact ? "application/json; charset=utf-8" : "application/xml; charset=utf-8")))
                            .build();
                }
                if (chain.request().url().toString().equals(ACTION_URL)) {
                    actionCalls.incrementAndGet();
                    return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                            .code(200).message("OK")
                            .body(ResponseBody.create("{\"msg\":\"CK 配置入口已调用\"}", MediaType.get("application/json"))).build();
                }
                throw new IOException("Intentional offline production home fixture");
            }).build();
            setField(OkHttp.get(), "client", offline);
            setField(TmdbNetwork.class, "client", offline);
            discover.clear();
            rex.clear();
            List<Vod> popular = RexPreviewFixtures.feeds().get(0).getItems();
            for (DiscoverApi.Row row : DiscoverApi.Row.values()) {
                discover.put(row, entry(DiscoverApi.class, row == DiscoverApi.Row.TMDB_DAY ? popular : List.of()));
            }
            Map<String, List<Vod>> feeds = new HashMap<>();
            for (RexFeedResult feed : RexPreviewFixtures.feeds()) feeds.put(feed.getSection().getKey(), feed.getItems());
            if (compact) {
                for (com.fongmi.android.tv.bean.RexSection section : RexSections.home()) {
                    String type = section.getKey().contains("tv") || section.isShelf() ? "tv" : "movie";
                    List<Vod> items = new ArrayList<>();
                    for (int i = 0; i < 12; i++) {
                        Vod item = sourcePoster(i, "tmdb:" + type + ":" + (30000 + Math.abs(section.getKey().hashCode() % 10000) + i));
                        item.setTypeName(type);
                        item.setName(("tv".equals(type) ? "精选剧集 " : "精选电影 ") + (i + 1));
                        items.add(item);
                    }
                    feeds.put(section.getKey(), items);
                }
            }
            for (com.fongmi.android.tv.bean.RexSection section : RexSections.home()) {
                rex.put(section.getKey(), entry(RexFeedLoader.class, feeds.getOrDefault(section.getKey(), List.of())));
            }
            for (int index = 0; index < 3; index++) {
                Vod vod = popular.get(index);
                History history = new History();
                history.setKey("preview-home" + AppDatabase.SYMBOL + vod.getId());
                history.setVodName(vod.getName() + " · 续看");
                history.setVodPic(vod.getBackdrop());
                history.setVodRemarks("第 " + (index + 2) + " 集");
                history.setPosition((index + 1) * 600_000L);
                history.setDuration(3_600_000L);
                history.setCreateTime(System.currentTimeMillis() - index * 1000L);
                history.setCid(cid);
                histories.add(history);
            }
            replaceHistory(true);
        }

        public void replaceHistory(boolean present) {
            AppDatabase.get().getHistoryDao().delete(cid);
            if (present) AppDatabase.get().getHistoryDao().insertOrUpdate(histories);
        }

        void installSites() {
            List<Site> fixtures = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                Site site = new Site();
                site.setKey("preview-source-" + index);
                site.setName("离线片库 " + (char) ('A' + index));
                site.setApi(HOME_SOURCE_URL);
                if (compact) setField(site, "type", 4);
                fixtures.add(site);
            }
            setField(VodConfig.get(), "sites", fixtures);
            setField(VodConfig.get(), "home", fixtures.get(0));
        }

        private String compactSource() {
            Result result = new Result();
            result.setTypes(List.of(com.fongmi.android.tv.bean.Class.objectFrom("{\"type_id\":\"movie\",\"type_name\":\"电影\"}"),
                    com.fongmi.android.tv.bean.Class.objectFrom("{\"type_id\":\"tv\",\"type_name\":\"剧集\"}")));
            List<Vod> items = new ArrayList<>();
            for (int i = 0; i < 25; i++) items.add(sourcePoster(i, "source-preview-" + i));
            // The 26th entry has no image or ID, just like a source's login/configuration action.
            items.add(Vod.objectFrom("{\"vod_name\":\"配置 CK\",\"action\":\"" + ACTION_URL + "\"}"));
            result.setList(items);
            return App.gson().toJson(result);
        }

        private Vod sourcePoster(int index, String id) {
            Vod template = RexPreviewFixtures.feeds().get(0).getItems().get(index % 12);
            Vod item = new Vod();
            item.setId(id);
            item.setName("来源影片 " + (index + 1));
            item.setTypeName("movie");
            item.setPic(template.getPic());
            item.setBackdrop(template.getBackdrop());
            item.setYear("2026");
            item.setRemarks("更新至第 5 集");
            return item;
        }

        @Override public void close() {
            AppDatabase.get().getHistoryDao().delete(cid);
            AppDatabase.get().getHistoryDao().insertOrUpdate(savedHistory);
            discover.clear(); discover.putAll(savedDiscover);
            rex.clear(); rex.putAll(savedRex);
            setField(OkHttp.get(), "client", client);
            setField(TmdbNetwork.class, "client", tmdbClient);
            setField(VodConfig.get(), "sites", sites);
            setField(VodConfig.get(), "home", home);
            setField(VodConfig.get(), "config", config);
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            if (homeLayout instanceof Integer value) editor.putInt("browse_home_layout_v1", value);
            else editor.remove("browse_home_layout_v1");
            if (posterHome instanceof Boolean value) editor.putBoolean("browse_poster_home", value);
            else editor.remove("browse_poster_home");
            editor.commit();
            BrowseExperienceSettings.putPosterKeepShelfEnabled(keepShelf);
            BrowseExperienceSettings.putHistoryActionsEnabled(historyActions);
            Setting.putSiteMode(siteMode);
            active = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> cache(Class<?> type) {
        return (Map<Object, Object>) field(type, "CACHE");
    }

    private static Object entry(Class<?> owner, List<Vod> items) {
        try {
            Constructor<?> constructor = Class.forName(owner.getName() + "$CacheEntry").getDeclaredConstructor(List.class);
            constructor.setAccessible(true);
            return constructor.newInstance(items);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot seed production cache " + owner.getName(), error);
        }
    }
}
