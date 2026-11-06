package com.fongmi.android.tv.playback.vod;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.*;

/** Uses a delayed loopback response to exercise the production asynchronous danmaku callback. */
@RunWith(AndroidJUnit4.class)
public class DanmakuPlaybackOwnershipAndroidTest {

    private static final String[] KEYS = {"danmaku_load", "danmaku_auto", "danmaku_show", "danmaku_api_url",
            "danmaku_logvar_url", "danmaku_logvr_url", "danmaku_spider_first"};
    private final Map<String, Object> saved = new HashMap<>();
    private final CountDownLatch releaseReply = new CountDownLatch(1);
    private MockWebServer server;
    private Config config;
    private String savedLogvar;

    @Before public void setup() throws Exception {
        String packageName = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        assertTrue(packageName.endsWith(".preview") || packageName.endsWith(".sourceprobe"));
        Map<String, ?> preferences = Prefers.getPrefers().getAll();
        for (String key : KEYS) if (preferences.containsKey(key)) saved.put(key, preferences.get(key));
        config = VodConfig.get().getConfig();
        savedLogvar = config.getLogvar();
        config.setLogvar("");
        DanmakuApi.cancel();
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (!releaseReply.await(10, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                return new MockResponse().setBody("[{\"name\":\"fixture\",\"url\":\"https://example.com/danmaku.xml\"}]");
            }
        });
        server.start(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0);
        DanmakuSetting.putApiUrl(server.url("/search").newBuilder().host("127.0.0.1").build().toString());
        Prefers.put("danmaku_logvar_url", "");
        Prefers.put("danmaku_logvr_url", "");
        DanmakuSetting.putLoad(true);
        DanmakuSetting.putAuto(true);
        DanmakuSetting.putSpiderFirst(false);
    }

    @After public void cleanup() throws Exception {
        releaseReply.countDown();
        DanmakuApi.cancel();
        if (server != null) server.shutdown();
        if (config != null) config.setLogvar(savedLogvar);
        for (String key : KEYS) {
            if (saved.containsKey(key)) Prefers.put(key, saved.get(key));
            else Prefers.getPrefers().edit().remove(key).apply();
        }
    }

    @Test public void shouldApplyTheReplyToCurrentPlayback() throws Exception {
        CountDownLatch applied = new CountDownLatch(1);
        AtomicInteger sets = new AtomicInteger();
        AtomicInteger adds = new AtomicInteger();
        search(new Result(), () -> true, danmaku -> { sets.incrementAndGet(); applied.countDown(); }, danmaku -> adds.incrementAndGet());
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        releaseReply.countDown();
        assertTrue(applied.await(10, TimeUnit.SECONDS));
        assertEquals(1, sets.get());
        assertEquals(0, adds.get());
    }

    @Test public void shouldDiscardTheReplyAfterPlaybackOwnershipChanges() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        CountDownLatch checked = new CountDownLatch(1);
        AtomicInteger mutations = new AtomicInteger();
        search(new Result(), () -> { checked.countDown(); return current.get(); },
                danmaku -> mutations.incrementAndGet(), danmaku -> mutations.incrementAndGet());
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        current.set(false);
        releaseReply.countDown();
        assertTrue(checked.await(10, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(0, mutations.get());
    }

    @Test public void shouldPreserveSpiderDanmakuWhenAddingCurrentSearchResults() throws Exception {
        DanmakuSetting.putSpiderFirst(true);
        Result result = new Result();
        result.getDanmaku().add(Danmaku.from("https://example.com/spider.xml"));
        CountDownLatch applied = new CountDownLatch(1);
        AtomicInteger sets = new AtomicInteger();
        AtomicInteger adds = new AtomicInteger();
        search(result, () -> true, danmaku -> sets.incrementAndGet(), danmaku -> { adds.incrementAndGet(); applied.countDown(); });
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
        releaseReply.countDown();
        assertTrue(applied.await(10, TimeUnit.SECONDS));
        assertEquals(0, sets.get());
        assertEquals(1, adds.get());
    }

    private void search(Result result, java.util.function.BooleanSupplier current,
                        java.util.function.Consumer<Danmaku> set, java.util.function.Consumer<Danmaku> add) {
        History history = new History();
        history.setVodName("Fixture show");
        Episode episode = Episode.create("第1集", "episode-1");
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> VodPlaybackMedia.searchDanmaku(result, history, episode, current, set, add));
    }
}
