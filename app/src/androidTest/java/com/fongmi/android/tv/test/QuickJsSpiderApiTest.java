package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.crawler.Spider;
import com.fongmi.quickjs.utils.QuickLog;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/** Real QuickJS/JNI tests; fixture HTTP never leaves the disposable sourceprobe UID. */
@RunWith(AndroidJUnit4.class)
public final class QuickJsSpiderApiTest {

    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final JSONObject report = new JSONObject();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String stage = "start";

    @Test
    public void newAndLegacyInterfacesRunThroughRealQuickJs() throws Exception {
        assertTrue("Use the isolated test application", target.getPackageName().equals("com.fongmi.android.tv.sourceprobe")
                || target.getPackageName().equals("com.fongmi.android.tv.preview"));
        boolean logging = QuickLog.isEnabled();
        QuickLog.putEnabled(false);
        Fixture server = new Fixture();
        List<Spider> spiders = new ArrayList<>();
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            String base = "http://127.0.0.1:" + server.getListeningPort();
            String key = "quickjs-fixture-" + server.getListeningPort();
            Spider one = create(base, key, spiders);
            JSONObject home = json(step("home-object-and-legacy-http", () -> one.homeContent(true)));
            assertEquals(key, home.getJSONArray("list").getJSONObject(0).getString("vod_id"));
            assertEquals(1, home.getJSONArray("class").length());
            JSONObject category = json(step("category-object-page", () -> one.categoryContent("test", "2", false, new HashMap<>())));
            assertEquals(2, category.getInt("page"));
            assertEquals(3, category.getInt("pagecount"));
            assertEquals(5, category.getInt("total"));
            assertEquals("3", category.getJSONArray("list").getJSONObject(0).getString("vod_id"));
            assertEquals(1, json(step("promise-object-search", () -> one.searchContent("probe", false, "1"))).getJSONArray("list").length());
            assertEquals("legacy", json(step("legacy-json-string", () -> one.action("legacy"))).getString("msg"));
            assertEquals("promise-string", json(step("promise-json-string", () -> one.action("promise-string"))).getString("msg"));
            assertTrue(step("raw-sniffer-boolean", one::manualVideoCheck));
            assertTrue(step("raw-video-boolean", () -> one.isVideoFormat("x.mp4")));
            assertEquals("#EXTM3U\nfixture", step("legacy-live-text", () -> one.liveContent("text")));
            assertEquals(1, new JSONArray(step("live-native-array", () -> one.liveContent("array"))).length());
            for (String action : List.of("http", "cache", "stale", "refresh", "session", "download", "local", "helpers")) {
                assertTrue(json(step("action-" + action, () -> one.action(action))).getBoolean("ok"));
            }
            assertArrayEquals(new byte[]{0, 127, -128, -1}, read(new FileInputStream(new File(target.getCacheDir(), "quickjs-fixture.bin"))));
            Object[] binary = step("proxy-byte-array", () -> one.proxy(Map.of("mode", "bytes")));
            assertEquals(200, binary[0]);
            assertArrayEquals(new byte[]{0, 127, -128, -1}, read(binary[2]));
            assertEquals("yes", ((Map<?, ?>) binary[3]).get("X-Probe"));
            Object[] legacy = step("proxy-base64", () -> one.proxy(Map.of("mode", "base64")));
            assertArrayEquals(new byte[]{0, -1}, read(legacy[2]));
            Object[] text = step("proxy-string", () -> one.proxy(Map.of("mode", "text")));
            assertEquals("legacy-body", new String(read(text[2]), StandardCharsets.UTF_8));
            try {
                step("proxy-invalid-byte", () -> one.proxy(Map.of("mode", "invalid")));
                fail("Out-of-range proxy bytes must fail");
            } catch (Exception expected) {
                report.put("invalidProxyRejected", true);
            }
            Spider two = create(base, key + "-other", spiders);
            assertTrue(json(step("local-isolation", () -> two.action("local-empty"))).getBoolean("ok"));
            assertTrue(json(step("owner-local-kept", () -> one.action("local-value"))).getBoolean("ok"));
            step("first-owner-close", () -> { one.destroy(); return true; });
            spiders.remove(one);
            Spider again = create(base, key, spiders);
            assertTrue(json(step("local-persistence", () -> again.action("local-value"))).getBoolean("ok"));
            assertTrue(json(step("local-cleanup", () -> again.action("cleanup"))).getBoolean("ok"));
            report.put("status", "PASS");
        } catch (Throwable failure) {
            report.put("status", "FAIL");
            report.put("stage", stage);
            report.put("failureType", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            for (Spider spider : spiders) {
                Future<?> cleanup = worker.submit(spider::destroy);
                try { cleanup.get(10, TimeUnit.SECONDS); } catch (Exception ignored) { cleanup.cancel(true); }
            }
            worker.shutdownNow();
            server.stop();
            new File(target.getCacheDir(), "quickjs-fixture.bin").delete();
            QuickLog.putEnabled(logging);
            writeReport();
        }
    }

    private Spider create(String base, String key, List<Spider> spiders) throws Exception {
        return step("init", () -> {
            Spider spider = new Loader().spider(base + "/spider.js", null);
            spiders.add(spider);
            spider.siteKey = key;
            spider.init(target, new JSONObject().put("base", base).toString());
            return spider;
        });
    }

    private <T> T step(String name, Callable<T> callable) throws Exception {
        stage = name;
        report.put("stage", stage);
        writeReport();
        Future<T> future = worker.submit(callable);
        try {
            return future.get(25, TimeUnit.SECONDS);
        } finally {
            if (!future.isDone()) future.cancel(true);
        }
    }

    private JSONObject json(String value) throws Exception { return new JSONObject(value); }

    private byte[] read(Object body) throws Exception {
        try (InputStream input = (InputStream) body; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1; ) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private void writeReport() throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "quickjs-api-result.json"))) {
            output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final String SCRIPT = """
            export default function(site) {
                let base;
                const ensure = (condition, label) => { if (!condition) throw new Error(label); };
                return {
                    init(ext) { base = ext.base; ensure(!!site.key, 'default site key'); },
                    async home() {
                        const data = await net.json(base + '/json');
                        const legacy = req(base + '/json', {async: false});
                        const oldHttp = await http(base + '/json');
                        ensure(data.value === 7 && legacy.code === 200 && oldHttp.code === 200, 'new/legacy HTTP');
                        return {class: [{type_id: 'test', type_name: 'Fixture'}], list: [{vod_id: site.key, vod_name: 'fixture'}]};
                    },
                    homeVod() { return {list: []}; },
                    category(tid, pg) { return page([1,2,3,4,5].map(x => ({vod_id:String(x)})), pg, 2); },
                    search() { return Promise.resolve({list:[{vod_id:'1',vod_name:'fixture'}],page:1,pagecount:1,limit:1}); },
                    detail() { return {list: [{vod_id:'1',vod_name:'fixture'}]}; },
                    play() { return {parse:0,url:base+'/binary'}; },
                    sniffer() { return true; }, isVideo(url) { return url.endsWith('.mp4'); },
                    live(mode) { return mode === 'array' ? [{name:'Fixture',channel:[]}] : '#EXTM3U\\nfixture'; },
                    async proxy(params) {
                        if (params.mode === 'bytes') return [200,'application/octet-stream',(await net.http(base+'/binary',{buffer:1})).content,{'X-Probe':'yes'}];
                        if (params.mode === 'base64') return [200,'application/octet-stream','AP8=',{},1];
                        if (params.mode === 'invalid') return [200,'application/octet-stream',[256]];
                        return [200,'text/plain','legacy-body'];
                    },
                    async action(mode) {
                        if (mode === 'legacy') return JSON.stringify({msg:'legacy'});
                        if (mode === 'promise-string') return Promise.resolve(JSON.stringify({msg:'promise-string'}));
                        if (mode === 'http') {
                            const value = await net.http(base+'/denied');
                            ensure(value.code===403 && value.content==='denied','HTTP status/body');
                            let rejected=false; try { await net.json(base+'/denied'); } catch (_) { rejected=true; }
                            ensure(rejected,'JSON HTTP rejection');
                            const batch=await net.batch([{url:base+'/json'},{url:base+'/denied'}],2);
                            ensure(batch[0].code===200 && batch[1].code===403,'batch ordering');
                            ensure(net.req(base+'/json').code===200,'new synchronous req');
                        }
                        if (mode === 'cache') {
                            net.clearCache(); let count=0;
                            const loader=async()=>{count++;await net.sleep(20);return {value:9};};
                            const values=await Promise.all([net.cached('same',{ttl:1000,stale:false},loader),net.cached('same',{ttl:1000,stale:false},loader)]);
                            ensure(count===1 && values[0].value===9 && values[1].value===9,'coalesced JS loader');
                        }
                        if (mode === 'stale') {
                            await net.cached('stale',{ttl:1,stale:false},()=>({value:'old'}));await net.sleep(10);
                            const old=await net.cached('stale',{ttl:1},async()=>{await net.sleep(30);return {value:'new'};});
                            ensure(old.value==='old','stale immediate');await net.sleep(80);ensure(net.peek('stale').value==='new','background loader retained');
                        }
                        if (mode === 'refresh') {
                            ensure(net.refresh('refresh',{ttl:1000},async()=>{await net.sleep(10);return {value:5};})===undefined,'refresh return');
                            await net.sleep(80);ensure(net.peek('refresh').value===5,'refresh result');
                        }
                        if (mode === 'session') {
                            const one=net.session(),two=net.session();
                            try {
                                await one.http(base+'/cookie-set');
                                ensure((await one.http(base+'/cookie-read')).content==='sid=one','session cookie');
                                ensure((await two.http(base+'/cookie-read')).content==='none','session isolation');
                                ensure((await net.http(base+'/cookie-read')).content==='none','owner isolation');
                            } finally {one.close();two.close();}
                        }
                        if (mode === 'download') ensure((await net.download(base+'/binary','quickjs-fixture.bin')).endsWith('quickjs-fixture.bin'),'download path');
                        if (mode === 'local') {
                            local.set('saved',{value:'retained',items:[1,false,null]});
                            ensure(local.get('saved').items[1]===false,'JSON local');
                            local.set(site.key,'legacy','yes');ensure(local.get(site.key,'legacy')==='yes','legacy local');
                            local.delete(site.key,'legacy');ensure(local.get(site.key,'legacy')==='','legacy delete');
                        }
                        if (mode === 'local-empty') ensure(local.get('saved')===null,'site isolation');
                        if (mode === 'local-value') ensure(local.get('saved').value==='retained','local survives owner close');
                        if (mode === 'helpers') {
                            ensure(link('n',{id:'1'}).includes('[a=cr:'),'link');
                            ensure(getProxyUrl({q:'a b&中'}).includes('siteKey='),'proxy helper');
                            ensure(typeof getProxy==='function' && typeof js2Proxy==='function','old proxy helpers');
                            ensure(net.getUserAgent().length>0,'user agent');
                        }
                        if (mode === 'cleanup') {local.clear();net.clearCache();}
                        return {ok:true};
                    },
                    destroy() {}
                };
            }
            """;

    private static final class Fixture extends NanoHTTPD {
        Fixture() { super("127.0.0.1", 0); }
        @Override public Response serve(IHTTPSession session) {
            String path = session.getUri();
            if (path.equals("/spider.js")) return newFixedLengthResponse(Response.Status.OK, "application/javascript", SCRIPT);
            if (path.equals("/json")) return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"value\":7}");
            if (path.equals("/denied")) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "denied");
            if (path.equals("/binary")) return newFixedLengthResponse(Response.Status.OK, "application/octet-stream", new ByteArrayInputStream(new byte[]{0,127,-128,-1}), 4);
            if (path.equals("/cookie-set")) {
                Response response = newFixedLengthResponse("saved");
                response.addHeader("Set-Cookie", "sid=one; Path=/");
                return response;
            }
            if (path.equals("/cookie-read")) return newFixedLengthResponse(session.getHeaders().getOrDefault("cookie", "none"));
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing");
        }
    }
}
