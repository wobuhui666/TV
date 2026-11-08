package com.fongmi.quickjs.crawler;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.github.catvod.Init;
import com.github.catvod.net.Net;
import com.fongmi.quickjs.utils.Module;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SpiderUpstreamNativeTest {
    private Context previous;
    private Context target;
    private final List<Spider> spiders = new ArrayList<>();
    @Before public void prepare() {
        previous=Init.context();
        target=InstrumentationRegistry.getInstrumentation().getTargetContext();
        Init.set(target);
        Module.get().clear();
    }
    @After public void cleanup() {
        Init.setDialog(null);
        for(Spider spider:spiders) spider.destroy();
        Module.get().clear();
        Init.set(previous);
    }
    private Spider load(String name) throws Exception {
        String content;
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("js/upstream/"+name)) {
            content=new String(read(input), java.nio.charset.StandardCharsets.UTF_8);
        }
        java.lang.reflect.Field cache=Module.class.getDeclaredField("cache");cache.setAccessible(true);
        @SuppressWarnings("unchecked") android.util.LruCache<String,String> values=(android.util.LruCache<String,String>)cache.get(Module.get());
        String api="https://example.invalid/upstream/"+name;
        values.put(api,content);
        Spider spider=new Loader().spider(api,null);
        spiders.add(spider);
        spider.siteKey="native-"+name;
        spider.net=new Net(null,spider.siteKey);
        spider.init(target,"{\"fixture\":true}");
        return spider;
    }
    private static byte[] read(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
        byte[] bytes=new byte[4096];
        for(int count;(count=input.read(bytes))!=-1;)output.write(bytes,0,count);
        return output.toByteArray();
    }
    @Test(timeout=20000) public void standardClassUsesItsOwnThisAndFullDetailIds() throws Exception {
        Spider spider=load("modern.js");
        JSONObject home=new JSONObject(spider.homeContent(true));
        assertEquals("native-modern.js",home.getJSONArray("list").getJSONObject(0).getString("vod_id"));
        assertEquals("7",home.getJSONArray("list").getJSONObject(0).getString("vod_name"));
        JSONObject detail=new JSONObject(spider.detailContent(List.of("one","two")));
        assertEquals(2,detail.getJSONArray("list").length());
        assertEquals("two",detail.getJSONArray("list").getJSONObject(1).getString("vod_id"));
        assertEquals("1",new JSONObject(spider.searchContent("key",false)).getString("page"));
        assertEquals("3",new JSONObject(spider.searchContent("key",true,"3")).getString("page"));
        assertTrue(spider.manualVideoCheck());assertTrue(spider.isVideoFormat("x.mp4"));
        assertEquals("#EXTM3U\nfixture",spider.liveContent("text"));
        assertEquals(1,new org.json.JSONArray(spider.liveContent("array")).length());
        JSONObject helpers=new JSONObject(spider.action("helpers"));
        assertEquals(9,helpers.getInt("evaluated"));
        assertFalse(helpers.getJSONObject("stored").getBoolean("value"));
        assertEquals("fallback",helpers.getString("fallback"));
        assertEquals("e80b5017098950fc58aad83c8c14978e",helpers.getString("md5"));
        assertEquals("fixture",helpers.getJSONObject("html").getJSONArray("rows").getJSONObject(0).getString("text"));
        assertTrue(helpers.getString("dm").startsWith("spider://native-modern.js/"));
        assertThrows(Exception.class,()->spider.action("bad"));
    }
    @Test(timeout=20000) public void oldFactoriesObjectsEvalReturnAndForwardRetainTheirShapes() throws Exception {
        Spider factory=load("factory.js");
        assertEquals("native-factory.js",new JSONObject(factory.homeContent(false)).getString("site"));
        assertEquals("one",new JSONObject(factory.detailContent(List.of("one","two"))).getJSONArray("list").getJSONObject(0).getString("vod_id"));
        assertEquals(3,new JSONObject(factory.searchContent("k",true,"3")).getJSONArray("args").length());
        assertTrue(new JSONObject(factory.action("legacy")).getBoolean("legacy"));
        Object[] proxy=factory.proxy(Map.of());
        try(InputStream input=(InputStream)proxy[2]){assertArrayEquals(new byte[]{0,127,-128,-1},read(input));}
        // The original Json.toMap parser rejects malformed non-JSON header text.
        assertThrows(Exception.class,()->factory.proxy(Map.of("mode","malformed")));
        assertTrue(new JSONObject(load("object.js").homeContent(false)).getBoolean("object"));
        Spider cat=load("cat.js");
        assertTrue(new JSONObject(cat.homeContent(false)).getBoolean("cat"));
        assertEquals("native-cat.js",new JSONObject(cat.action("state")).getString("skey"));
        Spider forward=load("widget.fwd");
        JSONObject home=new JSONObject(forward.homeContent(false));
        assertEquals(1,home.getJSONArray("class").length());
        String tid=home.getJSONArray("class").getJSONObject(0).getString("type_id");
        assertEquals(1,new JSONObject(forward.categoryContent(tid,"1",false,new HashMap<>())).getJSONArray("list").length());
    }
    @Test(timeout=20000) public void proxyStreamTransfersBytesAndMalformedMetadataReleasesItsHandle() throws Exception {
        Spider spider=load("modern.js");
        Object[] response=spider.proxy(Map.of("mode","stream"));
        assertEquals("stream",((Map<?,?>)response[3]).get("X-Probe"));
        try(InputStream input=(InputStream)response[2]){assertArrayEquals(new byte[]{0,127,-128,-1},read(input));}
        assertEquals(1,new JSONObject(spider.action("proxy-state")).getInt("stops"));
        assertThrows(Exception.class,()->spider.proxy(Map.of("mode","bad-header")));
        spider.action("proxy-state");
        assertThrows(Exception.class,()->spider.proxy(Map.of("mode","bad-serialize")));
        java.lang.reflect.Field field=Spider.class.getDeclaredField("network");field.setAccessible(true);
        Object network=field.get(spider);
        java.lang.reflect.Field streams=network.getClass().getDeclaredField("streams");streams.setAccessible(true);
        assertTrue(((Map<?,?>)streams.get(network)).isEmpty());
    }
    @Test(timeout=10000) public void standardDialogUsesSecondCallbackArgumentAndKeepsHostBridge() throws Exception {
        Spider spider=load("modern.js");
        java.util.concurrent.CompletableFuture<String> reply=new java.util.concurrent.CompletableFuture<>();
        java.util.concurrent.CountDownLatch opened=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<java.util.function.Function<String,java.util.concurrent.CompletableFuture<String>>> callback=new java.util.concurrent.atomic.AtomicReference<>();
        Init.setDialog((options,action)->{callback.set(action);opened.countDown();return reply;});
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<String> result=worker.submit(()->spider.action("dialog"));
            assertTrue(opened.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertNotNull(callback.get());
            String update=callback.get().apply("{\"action\":\"clicked\"}").get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("clicked",new JSONObject(update).getString("received"));
            reply.complete("{\"button\":\"ok\"}");
            assertEquals("ok",new JSONObject(result.get(2,java.util.concurrent.TimeUnit.SECONDS)).getString("button"));
        } finally {reply.cancel(true);worker.shutdownNow();}
    }
    @Test(timeout=10000) public void destroyHasABoundEvenIfSourceStopNeverSettles() throws Exception {
        Spider spider=load("hanging-stop.js");
        spider.danmaku("live",item->{});
        long start=System.nanoTime();
        spider.destroy();
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<8000);
        java.lang.reflect.Field executor=Spider.class.getDeclaredField("executor");executor.setAccessible(true);
        assertTrue(((java.util.concurrent.ExecutorService)executor.get(spider)).isShutdown());
        assertThrows(IllegalStateException.class,()->spider.homeContent(false));
    }
    @Test(timeout=10000) public void destroyCancelsForeverPendingSubscriptionStartupWithoutLateEvents() throws Exception {
        Spider spider=load("pending.js");
        List<JSONObject> events=new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<AutoCloseable> pending=worker.submit(()->spider.danmaku("pending",item->{events.add(item);started.countDown();}));
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            spider.destroy();
            assertThrows(java.util.concurrent.ExecutionException.class,()->pending.get(2,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,events.size());
        } finally {worker.shutdownNow();}
    }
    @Test(timeout=20000) public void liveCommentsStopOnceAndRejectLateCallbacksOnClose() throws Exception {
        Spider spider=load("modern.js");
        List<JSONObject> events=new CopyOnWriteArrayList<>();
        AutoCloseable first=spider.danmaku("first",events::add);
        assertEquals("first",events.get(0).getString("text"));
        first.close();first.close();
        assertEquals(1,new JSONObject(spider.action("state")).getInt("stops"));
        spider.action("late");
        assertEquals(1,events.size());
        AutoCloseable second=spider.danmaku("second",events::add);
        assertEquals(2,events.size());
        spider.destroy();second.close();spider.destroy();
        Thread.sleep(600);
        assertEquals(2,events.size());
        assertThrows(IllegalStateException.class,()->spider.danmaku("closed",events::add));
    }
}
