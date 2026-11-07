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
    private boolean cat;

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
        Invocation invocation = new Invocation();
        invocations.add(invocation);
        Future<CompletableFuture<Object>> started = null;
        CompletableFuture<Object> pending = null;
        try {
            started = submit(() -> invocation.call(() -> json ? Async.runJson(jsObject, func, args) : Async.run(jsObject, func, args)));
            pending = started.get();
            invocation.bind(pending);
            Object result = pending.get();
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
            call("init", submit(() -> getExt(extend)).get());
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
        return callJson("detail", ids.get(0));
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
    public void destroy() {
        if (wasmBridge != null) wasmBridge.cancel();
        for (Invocation invocation : invocations) invocation.close();
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
            if (dialogs != null) dialogs.close();
            if (network != null) network.close();
            if (wasmBridge != null) wasmBridge.close();
            if (global != null) global.destroy();
            if (jsObject != null) jsObject.release();
            if (ctx != null) ctx.destroy();
            return null;
        }));
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
                return UriUtil.resolve(baseModuleName, moduleName);
            }

            @Override
            public byte[] getModuleBytecode(String moduleName) {
                return ctx.compileModule(Module.get().fetch(moduleName), moduleName);
            }
        });
    }

    private void createFun() {
        try {
            global = Global.create(ctx, executor);
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
        ctx.evaluateModule(content.replace(spider, global), api);
        ctx.evaluateModule(String.format(Asset.read("js/lib/spider.js"), api));
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
            } finally {
                if (stringify != null) stringify.release();
                proxy.release();
            }
        }).get();
        JSONArray array = new JSONArray(json);
        Map<String, String> headers = array.length() > 3 ? Json.toMap(array.optString(3)) : null;
        boolean base64 = array.length() > 4 && array.optInt(4) == 1;
        Object[] result = new Object[4];
        result[0] = array.optInt(0);
        result[1] = array.optString(1);
        result[2] = getStream(array.opt(2), base64);
        result[3] = headers;
        return result;
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

    private ByteArrayInputStream getStream(Object o, boolean base64) {
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
            String content = o.toString();
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
