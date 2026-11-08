package com.fongmi.quickjs.crawler;

import android.content.Context;

import com.fongmi.quickjs.bean.Res;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.method.Global;
import com.fongmi.quickjs.method.Local;
import com.fongmi.quickjs.method.NetBridge;
import com.fongmi.quickjs.method.DialogBridge;
import com.fongmi.quickjs.method.Invocation;
import com.fongmi.quickjs.method.WasmBridge;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.JSUtil;
import com.fongmi.quickjs.utils.Module;
import com.fongmi.quickjs.utils.QuickLog;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.crawler.SpiderRuntime;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.google.common.util.concurrent.Uninterruptibles;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import dalvik.system.DexClassLoader;

public class Spider extends com.github.catvod.crawler.Spider {

    private static final String TAG = Spider.class.getSimpleName();
    private static final int PREVIEW_LIMIT = 240;

    private final ExecutorService executor;
    private final DexClassLoader dex;
    private final String tmdbApiKey;
    private final String api;

    private QuickJSContext ctx;
    private JSObject jsObject;
    private Global global;
    private NetBridge network;
    private DialogBridge dialogs;
    private volatile WasmBridge wasmBridge;
    private final Set<Invocation> invocations = ConcurrentHashMap.newKeySet();
    private final Map<Invocation, CompletableFuture<?>> pendingCalls = new ConcurrentHashMap<>();
    private boolean cat;
    private boolean standard;
    private String sourceContent;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger danmakuId = new AtomicInteger();
    private final Map<Integer, Subscription> danmakus = new ConcurrentHashMap<>();
    private static final class Subscription {
        final Consumer<JSONObject> emit;
        final CallTimeout scope = CallTimeout.resourceScope();
        Subscription(Consumer<JSONObject> emit) { this.emit = emit; }
    }

    public Spider(String api, DexClassLoader dex) {
        this(api, dex, "");
    }

    public Spider(String api, DexClassLoader dex, String tmdbApiKey) {
        this.executor = Executors.newSingleThreadExecutor();
        this.tmdbApiKey = tmdbApiKey == null ? "" : tmdbApiKey;
        this.api = api;
        this.dex = dex;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
    }

    private Object call(String func, Object... args) throws Exception {
        return callResult(func, false, args);
    }

    private String callJson(String func, Object... args) throws Exception {
        return (String) callResult(func, true, args);
    }

    private Object callResult(String func, boolean json, Object... args) throws Exception {
        long start = System.currentTimeMillis();
        boolean cleanup = "destroy".equals(func);
        if (closed.get() && !cleanup) throw new IllegalStateException("Spider is closed");
        Invocation invocation = new Invocation();
        invocations.add(invocation);
        Future<CompletableFuture<Object>> started = null;
        CompletableFuture<Object> pending = null;
        try {
            started = submit(() -> invocation.call(() -> json ? Async.runJson(jsObject, func, args) : Async.run(jsObject, func, args)));
            pending = started.get();
            pendingCalls.put(invocation, pending);
            CallTimeout.trackCurrent(pending);
            if (closed.get() && !cleanup) pending.cancel(false);
            invocation.bind(pending);
            Object result = cleanup ? pending.get(5, TimeUnit.SECONDS) : pending.get();
            QuickLog.d(TAG, "call success site=%s func=%s elapsed=%sms result=%s", siteKey, func, System.currentTimeMillis() - start, invocation.hadDialog() ? "[dialog result omitted]" : describe(result));
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (Exception error) {
            if (invocation.hadDialog()) {
                QuickLog.e(TAG, "call failed site=%s func=%s error=%s", siteKey, func, error.getClass().getSimpleName());
                throw new Exception("Source request failed after dialog");
            }
            QuickLog.e(TAG, "call failed site=" + siteKey + " func=" + func + " error=" + error.getClass().getSimpleName(), error);
            throw error;
        } finally {
            invocation.close();
            invocations.remove(invocation);
            pendingCalls.remove(invocation);
            if (pending != null && !pending.isDone()) pending.cancel(false);
            if (started != null && !started.isDone()) started.cancel(false);
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        long start = System.currentTimeMillis();
        QuickLog.d(TAG, "init start site=%s api=%s ext=%s", siteKey, api, preview(extend));
        SpiderRuntime.bind(this, siteKey);
        try {
            initializeJS();
            call("init", standard ? extend : submit(() -> getExt(extend)).get());
        } catch (Exception | LinkageError error) {
            try {
                releaseJS();
            } finally {
                SpiderRuntime.close(this);
                executor.shutdownNow();
            }
            throw error;
        }
        QuickLog.d(TAG, "init success site=%s elapsed=%sms", siteKey, System.currentTimeMillis() - start);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return callJson("home", filter);
    }

    @Override
    public String homeVideoContent() throws Exception {
        return callJson("homeVod");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSObject obj = submit(() -> JSUtil.toObject(ctx, extend)).get();
        return callJson("category", tid, pg, filter, obj);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (!standard) return callJson("detail", ids.get(0));
        JSArray array = submit(() -> JSUtil.toArray(ctx, ids)).get();
        try { return callJson("detail", array); }
        finally { submit(() -> { array.release(); return null; }).get(); }
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return callJson("search", key, quick);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return callJson("search", key, quick, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSArray array = submit(() -> JSUtil.toArray(ctx, vipFlags)).get();
        return callJson("play", flag, id, array);
    }

    @Override
    public String liveContent(String url) throws Exception {
        return callJson("live", url);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return (Boolean) call("sniffer");
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return (Boolean) call("isVideo", url);
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        return "catvod".equals(params.get("from")) ? proxy2(params) : proxy1(params);
    }

    @Override
    public String action(String action) throws Exception {
        return callJson("action", action);
    }

    @Override
    public AutoCloseable danmaku(String id, Consumer<JSONObject> emit) throws Exception {
        if (closed.get()) throw new IllegalStateException("Spider is closed");
        if (emit == null) throw new NullPointerException("Danmaku emitter is required");
        int token = danmakuId.incrementAndGet();
        Subscription subscription = new Subscription(emit);
        danmakus.put(token, subscription);
        try {
            Runnable restore = subscription.scope.enter();
            try { call("startDanmaku", id, token); }
            finally { restore.run(); }
            if (closed.get()) throw new IllegalStateException("Spider is closed");
        } catch (Exception | Error failure) {
            danmakus.remove(token);
            subscription.scope.close();
            stopDanmaku(token);
            throw failure;
        }
        AtomicBoolean stopped = new AtomicBoolean();
        return () -> {
            if (!stopped.compareAndSet(false, true)) return;
            danmakus.remove(token);
            subscription.scope.close();
            stopDanmaku(token);
        };
    }

    private void stopDanmaku(int token) {
        if (closed.get() || executor.isShutdown()) return;
        // Remove the Java emitter before queuing source cleanup, so already-queued events are ignored.
        try {
            executor.execute(() -> {
                if (closed.get() || jsObject == null) return;
                Async.run(jsObject, "stopDanmaku", token).whenComplete((value, error) -> {
                    if (error != null) QuickLog.e(TAG, "danmaku stop failed site=" + siteKey, error);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    @Override
    public void close() {
        destroy();
    }

    @Override
    public void destroy() {
        if (!closed.compareAndSet(false, true)) return;
        for (Subscription subscription : danmakus.values()) subscription.scope.close();
        danmakus.clear();
        if (wasmBridge != null) wasmBridge.cancel();
        for (Invocation invocation : invocations) invocation.close();
        for (CompletableFuture<?> pending : pendingCalls.values()) pending.cancel(false);
        dialog.close();
        try {
            call("destroy");
        } catch (Throwable e) {
            e.printStackTrace();
        }
        try {
            releaseJS();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            SpiderRuntime.close(this);
            executor.shutdownNow();
        }
    }

    private void releaseJS() throws Exception {
        // Caller cancellation must not skip the queued JS-thread cleanup before shutdownNow.
        // Guava waits for cleanup and then restores the caller's interrupt flag.
        Uninterruptibles.getUninterruptibly(submit(() -> {
            Throwable failure = null;
            if (dialogs != null) failure = release(failure, dialogs::close);
            if (network != null) failure = release(failure, network::close);
            if (wasmBridge != null) failure = release(failure, wasmBridge::close);
            if (global != null) failure = release(failure, global::destroy);
            if (jsObject != null) failure = release(failure, jsObject::release);
            if (ctx != null) failure = release(failure, ctx::destroy);
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
            return null;
        }));
    }

    private static Throwable release(Throwable failure, Runnable close) {
        try { close.run(); }
        catch (RuntimeException | Error error) {
            if (failure == null) return error;
            if (failure != error) failure.addSuppressed(error);
        }
        return failure;
    }

    private void initializeJS() throws Exception {
        submit(() -> {
            createCtx();
            createFun();
            network = new NetBridge(ctx, executor, net, local, siteKey);
            dialogs = new DialogBridge(ctx, executor, dialog);
            wasmBridge = new WasmBridge(ctx);
            createObj();
            ctx.evaluate("if (!('wasm' in globalThis.__JS_SPIDER__)) globalThis.__JS_SPIDER__.wasm = globalThis.wasm;");
            return null;
        }).get();
    }

    private void createCtx() {
        QuickLog.d(TAG, "createCtx site=%s", siteKey);
        ctx = QuickJSContext.create();
        ctx.setConsole(new Console());
        ctx.evaluate(Asset.read("js/lib/http.js"));
        ctx.getGlobalObject().setProperty("local", Local.class);
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public String moduleNormalizeName(String baseModuleName, String moduleName) {
                return Module.normalize(baseModuleName, moduleName);
            }

            @Override
            public byte[] getModuleBytecode(String moduleName) {
                return ctx.compileModule(api.equals(moduleName) && sourceContent != null ? sourceContent : Module.get().fetch(moduleName), moduleName);
            }
        });
    }

    private void createFun() {
        try {
            global = Global.create(ctx, executor);
            ctx.getGlobalObject().setProperty("__SPIDER_DANMAKU_EMIT__", args -> {
                if (closed.get()) return null;
                Subscription subscription = danmakus.get(((Number) args[0]).intValue());
                if (subscription != null) {
                    try { subscription.emit.accept(new JSONObject((String) args[1])); }
                    catch (Exception error) { throw new IllegalArgumentException("Invalid danmaku event", error); }
                }
                return null;
            });
            QuickLog.d(TAG, "createFun global ready site=%s", siteKey);
            if (dex == null) {
                QuickLog.d(TAG, "createFun skip jar function site=%s dex=null", siteKey);
                return;
            }
            Class<?> clz = dex.loadClass("com.github.catvod.js.Function");
            clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx);
            QuickLog.d(TAG, "createFun jar function loaded site=%s", siteKey);
        } catch (Throwable ignored) {
            QuickLog.d(TAG, "createFun jar function unavailable site=%s error=%s: %s", siteKey, ignored.getClass().getSimpleName(), ignored.getMessage());
        }
    }

    private void createObj() {
        String spider = "__JS_SPIDER__";
        String global = "globalThis." + spider;
        String content = patchDrpyCompat(Module.get().fetch(api));
        if (isForward(content)) {
            createForwardObj(spider, content);
            return;
        }
        cat = content.contains("__jsEvalReturn");
        QuickLog.d(TAG, "createObj module fetched site=%s api=%s length=%s cat=%s", siteKey, api, content.length(), cat);
        sourceContent = content.replace(spider, global);
        ctx.evaluateModule(String.format(Asset.read("js/lib/spider.js"), JSONObject.quote(api)));
        standard = Boolean.TRUE.equals(ctx.getProperty(ctx.getGlobalObject(), "__SPIDER_STANDARD__"));
        jsObject = (JSObject) ctx.getProperty(ctx.getGlobalObject(), spider);
        if (jsObject == null) throw new IllegalStateException("JS spider object missing: " + api);
        QuickLog.d(TAG, "createObj ready site=%s api=%s", siteKey, api);
    }

    private void createForwardObj(String spider, String content) {
        QuickLog.d(TAG, "createForwardObj start site=%s api=%s length=%s", siteKey, api, content.length());
        ctx.evaluateModule("import * as cheerio from 'lib/cheerio.min.js'; globalThis.__FORWARD_CHEERIO__ = cheerio;", "forward-cheerio.js");
        String script = Asset.read("js/lib/forward.js")
                .replace("__FORWARD_API_PLACEHOLDER__", JSONObject.quote(api))
                .replace("__FORWARD_TMDB_KEY_PLACEHOLDER__", JSONObject.quote(tmdbApiKey))
                .replace("__FORWARD_SOURCE_PLACEHOLDER__", JSONObject.quote(content));
        ctx.evaluate(script);
        jsObject = (JSObject) ctx.getProperty(ctx.getGlobalObject(), spider);
        if (jsObject == null) throw new IllegalStateException("Forward spider object missing: " + api);
        cat = false;
        QuickLog.d(TAG, "createForwardObj ready site=%s api=%s", siteKey, api);
    }

    private boolean isForward(String content) {
        String path = api.split("\\?", 2)[0].toLowerCase();
        if (path.endsWith(".fwd")) return true;
        return content.contains("WidgetMetadata") && content.contains("functionName") && content.contains("modules");
    }

    private String patchDrpyCompat(String content) {
        if (!needsDrpyCompat(content)) return content;
        String compat = Asset.read("js/lib/drpy-compat.js");
        QuickLog.d(TAG, "inject drpy compat site=%s api=%s", siteKey, api);
        return compat + "\n" + content;
    }

    private boolean needsDrpyCompat(String content) {
        return content.contains("defaultParser") && content.contains("pdfh") && content.contains("pdfa") && content.matches("(?s).*pd\\s*:\\s*pd.*") && !content.matches("(?s).*function\\s+pdfh\\s*\\(.*");
    }

    private Object getExt(String ext) {
        if (!cat) return Json.isObj(ext) ? ctx.parse(ext) : ext;
        JSObject obj = ctx.createNewJSObject();
        obj.setProperty("stype", 3);
        obj.setProperty("skey", siteKey);
        if (!Json.isObj(ext)) obj.setProperty("ext", ext);
        else obj.setProperty("ext", (JSObject) ctx.parse(ext));
        return obj;
    }

    private Object[] proxy1(Map<String, String> params) throws Exception {
        JSObject obj = submit(() -> JSUtil.toObject(ctx, params)).get();
        JSArray proxy = (JSArray) call("proxy", obj);
        String json = submit(() -> {
            JSFunction stringify = ctx.getGlobalObject().getJSFunction("__SPIDER_NET_PROXY_JSON__");
            try {
                return (String) stringify.call(proxy);
            } catch (Exception | Error failure) {
                closeProxyHandle(proxy);
                throw failure;
            } finally {
                if (stringify != null) stringify.release();
                proxy.release();
            }
        }).get();
        JSONArray array = null;
        InputStream body = null;
        try {
            array = new JSONArray(json);
            if (array.length() < 3) throw new IllegalArgumentException("Proxy requires status, content type and body");
            if (standard && (!(array.get(0) instanceof Number value)
                    || !Double.isFinite(value.doubleValue()) || value.doubleValue() != value.intValue())) {
                throw new IllegalArgumentException("Proxy status requires an integer");
            }
            int status = standard ? array.getInt(0) : array.optInt(0);
            if (standard && (status < 100 || status > 599)) throw new IllegalArgumentException("Invalid proxy status");
            if (standard && !(array.get(1) instanceof String)) throw new IllegalArgumentException("Proxy content type requires text");
            String contentType = standard ? array.getString(1) : array.optString(1);
            Map<String, String> headers = !standard && array.length() > 3 ? Json.toMap(array.optString(3)) : null;
            if (standard && array.length() > 3 && !array.isNull(3) && !"".equals(array.opt(3))) {
                Object value = array.get(3);
                JSONObject object = value instanceof JSONObject existing ? existing : new JSONObject((String) value);
                headers = new HashMap<>();
                java.util.Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object header = object.get(key);
                    if (standard && !(header instanceof String)) throw new IllegalArgumentException("Proxy headers require strings");
                    headers.put(key, String.valueOf(header));
                }
            }
            boolean base64 = array.length() > 4 && array.optInt(4) == 1;
            body = getStream(array.get(2), base64);
            return new Object[]{status, contentType, body, headers};
        } catch (Exception | Error failure) {
            if (body != null) try { body.close(); } catch (Exception close) { failure.addSuppressed(close); }
            if (array != null && array.opt(2) instanceof JSONObject handle && handle.has("$tvStream")) {
                network.closeStream(handle.optInt("$tvStream"));
            }
            throw failure;
        }
    }

    /** Runs on the JS executor before releasing a response which could not be serialized. */
    private void closeProxyHandle(JSArray proxy) {
        Object value = null;
        try {
            value = proxy.get(2);
            if (value instanceof JSObject object) {
                Object token = object.getProperty("$tvStream");
                if (token instanceof Number number) network.closeStream(number.intValue());
            }
        } catch (RuntimeException ignored) {
            // Source-wide close remains the final owner if a malformed response hides its handle.
        } finally {
            if (value instanceof JSObject object) object.release();
        }
    }

    private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        JSArray array = submit(() -> JSUtil.toArray(ctx, Arrays.asList(url.split("/")))).get();
        Object object = submit(() -> ctx.parse(header)).get();
        String proxy = (String) call("proxy", array, object);
        Res res = Res.objectFrom(proxy);
        Object[] result = new Object[3];
        result[0] = res.getCode();
        result[1] = res.getContentType();
        result[2] = res.getStream();
        return result;
    }

    private InputStream getStream(Object o, boolean base64) throws Exception {
        if (o instanceof JSONObject handle && handle.has("$tvStream")) return network.takeStream(handle.getInt("$tvStream"));
        if (o instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) o);
        } else if (o instanceof JSONArray array) {
            byte[] bytes = new byte[array.length()];
            for (int i = 0; i < bytes.length; i++) {
                Object value = array.opt(i);
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                        || number.doubleValue() != number.intValue() || number.intValue() < -128 || number.intValue() > 255) {
                    throw new IllegalArgumentException("Proxy body must contain byte values from -128 to 255");
                }
                bytes[i] = (byte) number.intValue();
            }
            return new ByteArrayInputStream(bytes);
        } else {
            String content = o == null || o == JSONObject.NULL ? "" : o.toString();
            if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
            return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
        }
    }

    private static String preview(String text) {
        if (text == null) return "null";
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() <= PREVIEW_LIMIT ? text : text.substring(0, PREVIEW_LIMIT) + "...";
    }

    private static String describe(Object result) {
        if (result == null) return "null";
        if (result instanceof String text) return "String(len=" + text.length() + ", preview=" + preview(text) + ")";
        if (result instanceof JSArray) return "JSArray";
        if (result instanceof JSObject) return "JSObject";
        if (result instanceof byte[] bytes) return "byte[" + bytes.length + "]";
        return result.getClass().getSimpleName();
    }
}
