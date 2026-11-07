package com.fongmi.android.tv.test;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.crawler.Spider;
import com.fongmi.quickjs.WasmRuntime;
import com.fongmi.quickjs.utils.QuickLog;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.crawler.SpiderRuntime;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;

/** Android/JNI verification using original generated WASM and an HTTP server bound to loopback. */
@RunWith(AndroidJUnit4.class)
public final class QuickJsWasmApiTest {

    private static final JSONObject RESULTS = new JSONObject();
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Spider> spiders = new ArrayList<>();
    private Fixture server;
    private String base;
    private String stage = "start";
    private boolean logging;

    @Rule public final TestWatcher evidence = new TestWatcher() {
        @Override protected void succeeded(Description description) { record(description, "PASS", null); }
        @Override protected void failed(Throwable error, Description description) { record(description, "FAIL", error); }
    };

    @Before
    public void startLoopbackFixture() throws Exception {
        assertTrue("Use the isolated test application", target.getPackageName().equals("com.fongmi.android.tv.preview")
                || target.getPackageName().equals("com.fongmi.android.tv.sourceprobe"));
        logging = QuickLog.isEnabled();
        QuickLog.putEnabled(false);
        server = new Fixture();
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        base = "http://127.0.0.1:" + server.getListeningPort();
    }

    @After
    public void closeEveryOwner() throws Exception {
        String lastStage = stage;
        try {
            for (Spider spider : spiders) step("owner-destroy", () -> { spider.destroy(); return true; });
            stage = lastStage;
        } finally {
            worker.shutdownNow();
            if (server != null) server.stop();
            QuickLog.putEnabled(logging);
        }
    }

    @Test
    public void asyncInitFetchesBinaryAndScopedModuleComputesHome() throws Exception {
        Spider spider = create();
        JSONObject home = new JSONObject(step("wasm-home", () -> spider.homeContent(true)));
        assertEquals("42", home.getJSONArray("list").getJSONObject(0).getString("vod_id"));
        assertEquals("WASM 42", home.getJSONArray("list").getJSONObject(0).getString("vod_name"));
        assertTrue("Binary fetch must use the real HTTP bridge", server.binaryRequests > 0);
        assertTrue("Scoped ES module must be loaded by the real module loader", server.moduleRequests > 0);
    }

    @Test
    public void arrayBufferTypedArrayAndDataViewRespectTheirSlices() throws Exception {
        assertAction(create(), "buffers");
        assertAction(create("/custom.js"), "custom-helper-preserved");
    }

    @Test
    public void scalarResultsPreserveBigIntNaNInfinityAndNegativeZero() throws Exception {
        assertAction(create(), "scalars");
    }

    @Test
    public void linearMemoryReadsWritesGrowthAndCallbacksUseTheSameBytes() throws Exception {
        assertAction(create(), "memory");
    }

    @Test
    public void standardWebAssemblyInstanceAndImportedMemoryWork() throws Exception {
        assertAction(create(), "standard");
    }

    @Test
    public void compileLinkTrapBoundsAndClosedHandlesHaveTypedErrors() throws Exception {
        assertAction(create(), "errors");
    }

    @Test
    public void twoSpidersKeepMemoryIsolatedAndDestroyReleasesOnlyItsOwner() throws Exception {
        Spider one = create();
        Spider two = create();
        assertEquals(0, action(one, "read").getInt("value"));
        assertEquals(0, action(two, "read").getInt("value"));
        assertAction(one, "write");
        assertEquals(91, action(one, "read").getInt("value"));
        assertEquals(0, action(two, "read").getInt("value"));
        step("destroy-first-spider", () -> { one.destroy(); return true; });
        spiders.remove(one);
        assertAction(two, "write");
        assertEquals(91, action(two, "read").getInt("value"));
        Spider replacement = create();
        assertEquals(0, action(replacement, "read").getInt("value"));
    }

    @Test
    public void deadlineStopsPureWasmLoopAndNextRequestStillWorks() throws Exception {
        Spider spider = create();
        stage = "pure-wasm-deadline";
        long started = System.nanoTime();
        Future<String> running = worker.submit(() -> {
            Thread caller = Thread.currentThread();
            try (CallTimeout timeout = new CallTimeout(250)) {
                Runnable restore = timeout.enter();
                timeout.start(caller::interrupt);
                try { return spider.action("spin"); }
                finally { restore.run(); }
            } finally {
                Thread.interrupted();
            }
        });
        assertTrue("The loop request must enter the real spider", server.entered.await(3, TimeUnit.SECONDS));
        try {
            running.get(4, TimeUnit.SECONDS);
            fail("A pure WASM infinite loop must respect the request deadline");
        } catch (ExecutionException expected) {
            assertNotNull(expected.getCause());
        } finally {
            if (!running.isDone()) running.cancel(true);
        }
        assertTrue("Deadline should return promptly", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
        assertAction(spider, "healthy");
    }

    @Test
    public void requestCancellationStopsPureWasmLoopAndDestroyDoesNotHang() throws Exception {
        Spider spider = create();
        stage = "pure-wasm-request-cancel";
        Future<String> running = worker.submit(() -> {
            try { return spider.action("spin"); }
            finally { Thread.interrupted(); }
        });
        assertTrue("The loop request must enter the real spider", server.entered.await(3, TimeUnit.SECONDS));
        // Let the real interpreter enter its branch-only loop after the marker HTTP call returns.
        Thread.sleep(100);
        assertFalse("The fixture must actually be running", running.isDone());
        assertTrue(running.cancel(true));
        assertAction(spider, "healthy");
        step("destroy-after-loop-cancellation", () -> { spider.destroy(); return true; });
        spiders.remove(spider);
    }

    @Test
    public void javaRuntimeUsesScalarValuesAndByteBufferMemoryOnAndroid() throws Exception {
        stage = "direct-java-runtime";
        try (WasmRuntime runtime = new WasmRuntime();
             WasmRuntime.Module module = runtime.compile(WasmFixture.module(true, true));
             WasmRuntime.Memory memory = runtime.memory(1, 2)) {
            WasmRuntime.Function callback = runtime.function(new WasmRuntime.Signature(
                    List.of(WasmRuntime.Type.I32), List.of(WasmRuntime.Type.I32)), (owner, arguments) -> {
                assertArrayEquals(new byte[]{73}, owner.memory().read(0, 1));
                return new Object[]{(Integer) arguments[0] * 2};
            });
            try (WasmRuntime.Instance instance = runtime.instantiate(module,
                    Map.of("env", Map.of("double", callback, "memory", memory)))) {
                assertArrayEquals(new Object[]{42}, instance.call("add", 19, 23));
                for (long value : new long[]{1L, 9007199254740993L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                    assertArrayEquals(new Object[]{value}, instance.call("i64_id", value));
                }
                assertEquals(Float.floatToRawIntBits(-0f), Float.floatToRawIntBits((Float) instance.call("f32_id", -0f)[0]));
                assertEquals(Double.doubleToRawLongBits(-0d), Double.doubleToRawLongBits((Double) instance.call("f64_id", -0d)[0]));
                assertTrue(Double.isNaN((Double) instance.call("f64_id", Double.NaN)[0]));
                assertArrayEquals(new Object[]{Float.POSITIVE_INFINITY}, instance.call("f32_id", Float.POSITIVE_INFINITY));
                instance.call("poke", 0, 73);
                assertArrayEquals(new Object[]{42}, instance.call("callback", 21));
                memory.write(8, new byte[]{0, 127, -128, -1});
                assertArrayEquals(new byte[]{0, 127, -128, -1}, memory.read(8, 4));
                assertEquals(1, memory.grow(1));
                assertEquals(2, memory.pages());
                assertArrayEquals(new byte[]{0, 127, -128, -1}, memory.read(8, 4));
                assertArrayEquals(new byte[]{0}, memory.read(65536, 1));
                assertEquals(-1, memory.grow(1));
            }
        }
    }

    @Test
    public void javaSpiderLazilyOwnsOneRuntimeAndHostCleanupClosesIt() throws Exception {
        stage = "java-spider-runtime-ownership";
        com.github.catvod.crawler.Spider one = new com.github.catvod.crawler.Spider() { };
        com.github.catvod.crawler.Spider two = new com.github.catvod.crawler.Spider() { };
        try {
            WasmRuntime runtime = one.wasm();
            WasmRuntime other = two.wasm();
            assertSame("The owner reuses its runtime", runtime, one.wasm());
            assertNotSame("Each Java spider owns its runtime", runtime, other);
            try (WasmRuntime.Module module = runtime.compile(WasmFixture.module(true, false));
                 WasmRuntime.Module otherModule = other.compile(WasmFixture.module(false, false))) {
                WasmRuntime.Function callback = runtime.function(new WasmRuntime.Signature(
                        List.of(WasmRuntime.Type.I32), List.of(WasmRuntime.Type.I32)), (owner, arguments) -> {
                    assertArrayEquals(new byte[]{73}, owner.memory().read(0, 1));
                    return new Object[]{(Integer) arguments[0] * 2};
                });
                try (WasmRuntime.Instance instance = runtime.instantiate(module, Map.of("env", Map.of("double", callback)));
                     WasmRuntime.Instance survivor = other.instantiate(otherModule)) {
                    instance.call("poke", 0, 73);
                    assertArrayEquals(new Object[]{42}, instance.call("callback", 21));
                    SpiderRuntime.close(one);
                    try {
                        one.wasm();
                        fail("Host cleanup must prevent recreating the owner's runtime");
                    } catch (IllegalStateException expected) { }
                    try {
                        instance.call("add", 19, 23);
                        fail("Previously exported runtime handles must close with their owner");
                    } catch (IllegalStateException expected) { }
                    assertArrayEquals(new Object[]{42}, survivor.call("add", 19, 23));
                    one.closeWasm();
                    two.closeWasm();
                    try {
                        two.wasm();
                        fail("Explicit closeWasm must close the second owner");
                    } catch (IllegalStateException expected) { }
                }
            }
        } finally {
            SpiderRuntime.close(one);
            SpiderRuntime.close(two);
        }
    }

    private Spider create() throws Exception {
        return create("/spider.js");
    }

    private Spider create(String path) throws Exception {
        return step("async-wasm-init", () -> {
            Spider spider = new Loader().spider(base + path, null);
            spiders.add(spider);
            spider.siteKey = "wasm-android-" + UUID.randomUUID();
            spider.init(target, new JSONObject().put("base", base).toString());
            return spider;
        });
    }

    private JSONObject action(Spider spider, String mode) throws Exception {
        return new JSONObject(step("action-" + mode, () -> spider.action(mode)));
    }

    private void assertAction(Spider spider, String mode) throws Exception {
        assertTrue(mode, action(spider, mode).getBoolean("ok"));
    }

    private <T> T step(String name, Callable<T> operation) throws Exception {
        stage = name;
        Future<T> future = worker.submit(operation);
        try { return future.get(8, TimeUnit.SECONDS); }
        finally { if (!future.isDone()) future.cancel(true); }
    }

    private void record(Description description, String status, Throwable failure) {
        try {
            JSONObject item = new JSONObject().put("status", status).put("stage", stage);
            if (failure != null) item.put("failureType", failure.getClass().getSimpleName());
            RESULTS.put(description.getMethodName(), item);
            JSONObject report = new JSONObject().put("applicationId", target.getPackageName())
                    .put("expectedTests", 11).put("tests", RESULTS);
            try (FileOutputStream output = new FileOutputStream(new File(target.getFilesDir(), "quickjs-wasm-result.json"))) {
                output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception error) { throw new AssertionError("Cannot write WASM verification evidence", error); }
    }

    private static final String MODULE = """
            export async function load(base, imports) {
                const response = await net.http(base + '/fixture.wasm', {buffer:1});
                if (response.code !== 200 || !(response.content instanceof ArrayBuffer)) throw new Error('real binary HTTP ArrayBuffer');
                return {bytes:response.content, instance:await wasm.instantiate(response.content, imports)};
            }
            """;

    private static final String SCRIPT = """
            import {load} from './compute.js';
            export default function(site) {
                let base, bytes, instance;
                const ensure = (ok,label) => {if (!ok) throw new Error(label);};
                const expect = (name,fn) => {
                    let error; try { fn(); } catch (caught) {error=caught;}
                    ensure(error && error.name===name,'expected '+name+', got '+(error && error.name));
                };
                const imports = {env:{double:(owner,x)=>{ensure(!!owner,'callback instance');return x*2;}}};
                return {
                    async init(ext) {
                        base=ext.base;
                        ensure(this.wasm===wasm,'spider WASM helper injection');
                        const loaded=await load(base,imports);bytes=loaded.bytes;instance=loaded.instance;
                        ensure(!!site.key && instance.exports.add(19,23)===42,'init computation');
                    },
                    home() {return {class:[],list:[{vod_id:String(instance.exports.add(19,23)),vod_name:'WASM '+instance.exports.callback(21)}]};},
                    homeVod() {return {list:[]};},
                    async action(mode) {
                        if (mode==='healthy') ensure(instance.exports.add(20,22)===42,'next request after cancellation');
                        if (mode==='read') return {value:instance.exports.peek(0)};
                        if (mode==='write') instance.exports.poke(0,91);
                        if (mode==='spin') {net.req(base+'/entered');instance.exports.spin();throw new Error('loop returned');}
                        if (mode==='buffers') {
                            const raw=new Uint8Array(bytes), padded=new Uint8Array(raw.length+5);
                            padded.set([9,8],0);padded.set(raw,2);padded.set([7,6,5],raw.length+2);
                            const sources=[bytes,padded.subarray(2,2+raw.length),new DataView(padded.buffer,2,raw.length),Array.from(raw)];
                            for (const source of sources) {
                                const value=await wasm.instantiate(source,imports);
                                ensure(value.call('add',20,22)===42 && wasm.call(value,'add',40,2)===42,'BufferSource exports');
                                const payload=new Uint8Array([10,0,127,128,255,11]).subarray(1,5);
                                wasm.write(value,4,payload);
                                ensure(JSON.stringify(Array.from(new Uint8Array(wasm.read(value,4,4))))==='[0,127,128,255]','write view bounds');
                                ensure(wasm.byteLength(value)===65536 && value.byteLength===65536,'byteLength delegates');
                                wasm.close(value);
                            }
                        }
                        if (mode==='scalars') {
                            for (const value of [1n,9007199254740993n,9223372036854775807n,-9223372036854775808n]) {
                                ensure(instance.exports.i64_id(value)===value,'i64 BigInt '+String(value));
                            }
                            expect('TypeError',()=>instance.exports.i64_id(1));
                            for (const name of ['f32_id','f64_id']) {
                                const fn=instance.exports[name];
                                ensure(Number.isNaN(fn(NaN)),'NaN '+name);
                                ensure(fn(Infinity)===Infinity && fn(-Infinity)===-Infinity,'infinity '+name);
                                ensure(Object.is(fn(-0),-0),'negative zero '+name);
                            }
                            ensure(instance.exports.f32_id(1.1)===Math.fround(1.1),'f32 rounding');
                        }
                        if (mode==='memory') {
                            instance.write(12,new Uint8Array([6,0,255,9]).subarray(1,3));
                            ensure(instance.exports.peek(12)===0 && instance.exports.peek(13)===255,'linear write');
                            instance.exports.poke(12,128);
                            ensure(new Uint8Array(instance.read(12,2))[0]===128,'linear read');
                            ensure(instance.exports.size()===1 && instance.exports.grow(1)===1,'WASM memory grow');
                            ensure(instance.byteLength===131072 && instance.exports.peek(13)===255,'grown memory retains data');
                            ensure(instance.exports.peek(65536)===0,'grown memory is zeroed');
                            ensure(instance.exports.grow(1)===-1 && instance.exports.size()===2,'WASM maximum');
                            expect('RangeError',()=>instance.grow(1));
                            const imported=(await net.http(base+'/imported.wasm',{buffer:1})).content;
                            const memory=wasm.memory(1,2);
                            const other=await wasm.instantiate(imported,{env:{memory,double:(owner,x)=>{
                                ensure(new Uint8Array(owner.read(0,1))[0]===73,'callback sees WASM store');return x*3;
                            }}});
                            other.exports.poke(0,73);
                            ensure(other.exports.callback(14)===42,'import callback result');
                            ensure(new Uint8Array(memory.read(0,1))[0]===73,'shared imported memory');
                            other.close();
                            ensure(new Uint8Array(memory.read(0,1))[0]===73,'standalone memory survives instance close');
                            memory.write(0,new Uint8Array([74]));
                            ensure(new Uint8Array(memory.read(0,1))[0]===74,'standalone owner retains memory');
                            memory.close();
                            expect('RuntimeError',()=>memory.read(0,1));
                            const defining=wasm.instantiate(bytes,imports), origin=defining.memory;
                            const consuming=wasm.instantiate(imported,{env:{memory:origin,double:(owner,x)=>x*2}});
                            defining.exports.poke(0,37);
                            defining.close();
                            ensure(consuming.exports.add(20,22)===42,'consumer survives defining instance close');
                            ensure(new Uint8Array(consuming.read(0,1))[0]===37,'consumer retains defined memory');
                            ensure(new Uint8Array(origin.read(0,1))[0]===37,'shared origin remains usable');
                            consuming.close();
                            expect('RuntimeError',()=>origin.read(0,1));
                        }
                        if (mode==='standard') {
                            const imported=(await net.http(base+'/imported.wasm',{buffer:1})).content;
                            const module=new WebAssembly.Module(imported);
                            const memory=new WebAssembly.Memory({initial:1,maximum:2});
                            new Uint8Array(memory.buffer)[0]=44;
                            const value=new WebAssembly.Instance(module,{env:{memory,double:x=>x*2}});
                            ensure(value.exports.peek(0)===44,'standard memory import');
                            ensure(value.exports.callback(21)===42,'standard callback scalar signature');
                            value.exports.poke(0,55);
                            ensure(new Uint8Array(memory.buffer)[0]===55,'standard memory mirror after call');
                            ensure(memory.grow(1)===1 && memory.buffer.byteLength===131072,'standard grow');
                            expect('RangeError',()=>memory.grow(1));
                            const asynchronous=await WebAssembly.instantiate(bytes,{env:{double:x=>x*2}});
                            ensure(asynchronous.module instanceof WebAssembly.Module && asynchronous.instance.exports.add(2,40)===42,'standard instantiate result');
                            ensure(WebAssembly.validate(bytes) && !WebAssembly.validate(new Uint8Array([0,1,2,3])),'standard validation');
                        }
                        if (mode==='errors') {
                            expect('CompileError',()=>wasm.instantiate(new Uint8Array([0,1,2,3])));
                            expect('LinkError',()=>wasm.instantiate(bytes));
                            expect('RuntimeError',()=>instance.exports.trap());
                            expect('RangeError',()=>instance.read(65535,2));
                            expect('RangeError',()=>instance.write(-1,new Uint8Array([1])));
                            ensure(instance.exports.add(1,41)===42,'instance remains usable after trap');
                            const closing=wasm.instantiate(bytes,imports), fn=closing.exports.add;
                            closing.close();closing.close();
                            expect('RuntimeError',()=>fn(1,2));
                            expect('RuntimeError',()=>closing.read(0,1));
                        }
                        return {ok:true};
                    },
                    destroy() {if(instance) instance.close();}
                };
            }
            """;

    private static final String CUSTOM_SCRIPT = """
            export default {
                wasm:{marker:'source-defined'},
                init() {
                    if(this.wasm.marker!=='source-defined' || typeof globalThis.wasm.instantiate!=='function') throw new Error('custom WASM helper overwritten');
                },
                action() {return {ok:this.wasm.marker==='source-defined'};},
                destroy() {}
            };
            """;

    private static final class Fixture extends NanoHTTPD {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile int binaryRequests;
        volatile int moduleRequests;
        Fixture() { super("127.0.0.1", 0); }
        @Override public Response serve(IHTTPSession session) {
            switch (session.getUri()) {
                case "/spider.js": return newFixedLengthResponse(Response.Status.OK, "application/javascript", SCRIPT);
                case "/custom.js": return newFixedLengthResponse(Response.Status.OK, "application/javascript", CUSTOM_SCRIPT);
                case "/compute.js":
                    moduleRequests++;
                    return newFixedLengthResponse(Response.Status.OK, "application/javascript", MODULE);
                case "/fixture.wasm": case "/imported.wasm":
                    binaryRequests++;
                    byte[] bytes = WasmFixture.module(true, session.getUri().equals("/imported.wasm"));
                    return newFixedLengthResponse(Response.Status.OK, "application/wasm", new ByteArrayInputStream(bytes), bytes.length);
                case "/entered":
                    entered.countDown();
                    return newFixedLengthResponse("entered");
                default: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "missing");
            }
        }
    }
}
