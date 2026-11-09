package com.fongmi.quickjs.method;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.fongmi.quickjs.utils.Crypto;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Js;
import com.fongmi.quickjs.utils.HtmlQuery;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.utils.Trans;
import com.github.catvod.utils.UriUtil;
import com.orhanobut.logger.Logger;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSMethod;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class Global {

    private final Map<Integer, Timeout> timers;
    private final ExecutorService executor;
    private final AtomicInteger timerId;
    private final QuickJSContext ctx;
    private final Timer timer;
    private final boolean strict;
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    private final Set<JSFunction> callbacks = ConcurrentHashMap.newKeySet();

    private volatile boolean destroyed;
    private final Js scripts = new Js();

    private Global(QuickJSContext ctx, ExecutorService executor, boolean strict) {
        this.executor = executor;
        this.timerId = new AtomicInteger();
        this.timers = new ConcurrentHashMap<>();
        this.timer = new Timer("quickjs-timer", true);
        this.ctx = ctx;
        this.strict = strict;
        try { setProperty(); setModernHelpers(); }
        catch (RuntimeException | Error error) { timer.cancel(); throw error; }
    }

    public static Global create(QuickJSContext ctx, ExecutorService executor) {
        return new Global(ctx, executor, false);
    }

    /** Evaluation errors must reach the caller; legacy crawlers retain their null fallback. */
    public static Global createStrict(QuickJSContext ctx, ExecutorService executor) {
        return new Global(ctx, executor, true);
    }

    public void destroy() {
        destroyed = true;
        scripts.close();
        timer.cancel();
        for (Call call : calls) call.cancel();
        calls.clear();
        for (JSFunction callback : callbacks) releaseCallback(callback);
        for (Timeout timeout : timers.values()) timeout.cancelAndRelease();
        timers.clear();
    }

    private void setProperty() {
        for (Method method : getClass().getMethods()) {
            if (!method.isAnnotationPresent(JSMethod.class)) continue;
            ctx.getGlobalObject().setProperty(method.getName(), args -> {
                try {
                    return method.invoke(this, args);
                } catch (Exception e) {
                    if (strict) {
                        Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
                        if (cause instanceof RuntimeException error) throw error;
                        if (cause instanceof Error error) throw error;
                        throw new IllegalStateException("JavaScript helper failed: " + method.getName(), cause);
                    }
                    return null;
                }
            });
        }
    }

    private void setModernHelpers() {
        ctx.getGlobalObject().setProperty("__SPIDER_JS_START__", args -> {
            JSFunction callback = (JSFunction) args[2];
            callback.hold();
            callbacks.add(callback);
            try {
                if (destroyed) throw new IllegalStateException("Spider is closed");
                CompletableFuture<String> future = scripts.evalAsync((String) args[0], (String) args[1]);
                Invocation invocation = Invocation.current();
                future.whenComplete((value, error) -> postCallback(callback, Invocation.wrap(invocation,
                        () -> callback.callVoid(value, error == null ? null : "JavaScript evaluation failed"))));
            } catch (Throwable error) {
                postCallback(callback, () -> callback.callVoid(null, "JavaScript evaluation failed"));
            }
            return null;
        });
        ctx.getGlobalObject().setProperty("__SPIDER_OPEN_WEB__", args -> {
            if (args.length != 1 || !(args[0] instanceof JSObject options)) throw new IllegalArgumentException("openWeb requires options");
            try {
                JSONObject value = new JSONObject(options.stringify());
                String url = value.optString("url");
                Uri uri = Uri.parse(url);
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) throw new IllegalArgumentException("openWeb requires an HTTP URL");
                android.content.Context context = com.github.catvod.Runtime.activity();
                if (context == null) context = com.github.catvod.Runtime.context();
                if (context == null) return false;
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                if (!(context instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (org.json.JSONException error) { throw new IllegalArgumentException("Invalid browser options", error); }
            finally { options.release(); }
        });
        ctx.getGlobalObject().setProperty("__SPIDER_HTML_QUERY__", args -> {
            JSObject plan = args.length > 1 && args[1] instanceof JSObject object ? object : null;
            try { return ctx.parse(HtmlQuery.query((String) args[0], plan == null ? null : plan.stringify())); }
            catch (IOException error) { throw new IllegalArgumentException("HTML query failed", error); }
            finally { if (plan != null) plan.release(); }
        });
        ctx.evaluate("globalThis.__SPIDER_JS_EVAL__ = (source, options = {}) => {"
                + "if (typeof source !== 'string' || !options || typeof options !== 'object' || Array.isArray(options)) return Promise.reject(new TypeError('Script requires source and options'));"
                + "return new Promise((resolve,reject) => __SPIDER_JS_START__(source,JSON.stringify(options),(value,error) => {"
                + "if(error) reject(new Error(error)); else {try {resolve(JSON.parse(value));} catch(e) {reject(e);}}}));};");
    }

    private boolean submit(Runnable runnable) {
        try {
            if (destroyed) return false;
            if (executor.isShutdown()) return false;
            executor.submit(runnable);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Keep
    @JSMethod
    public String s2t(String text) {
        return Trans.s2t(false, text);
    }

    @Keep
    @JSMethod
    public String t2s(String text) {
        return Trans.t2s(false, text);
    }

    @Keep
    @JSMethod
    public Integer getPort() {
        return Proxy.getPort();
    }

    @Keep
    @JSMethod
    public String getProxy(Boolean local) {
        return Proxy.getUrl(local) + "?do=js";
    }

    @Keep
    @JSMethod
    public String js2Proxy(Boolean dynamic, Integer siteType, String siteKey, String url, JSObject headers) {
        return getProxy(!dynamic) + String.format("&from=catvod&siteType=%s&siteKey=%s&header=%s&url=%s", siteType, siteKey, URLEncoder.encode(headers.stringify()), URLEncoder.encode(url));
    }

    @Keep
    @JSMethod
    public Integer setTimeout(JSFunction func, Integer delay) {
        Timeout timeout = createTimeout(func);
        if (timeout == null) return 0;
        return schedule(timeout, delay) ? timeout.id : 0;
    }

    @Keep
    @JSMethod
    public Object clearTimeout(Integer id) {
        cancel(id);
        return null;
    }

    @Keep
    @JSMethod
    public JSObject _http(String url, JSObject options) {
        JSFunction complete = options.getJSFunction("complete");
        if (complete == null) return req(url, options);
        requestAsync(url, options, complete);
        return null;
    }

    @Keep
    @JSMethod
    public JSObject req(String url, JSObject options) {
        CompletableFuture<Void> done = null;
        Call call = null;
        try {
            Req req = Req.objectFrom(options.stringify());
            call = Connect.to(url, req);
            done = track(call);
            Response res = call.execute();
            return Connect.success(ctx, req, res);
        } catch (Exception e) {
            return Connect.error(ctx);
        } finally {
            if (call != null) calls.remove(call);
            if (done != null) done.complete(null);
        }
    }

    @Keep
    @JSMethod
    public String joinUrl(String parent, String child) {
        return UriUtil.resolve(parent, child);
    }

    @Keep
    @JSMethod
    public String drpyPdfh(String html, String parse) {
        return Drpy.pdfh(html, parse);
    }

    @Keep
    @JSMethod
    public String drpyPdfa(String html, String parse) {
        return Drpy.pdfa(html, parse);
    }

    @Keep
    @JSMethod
    public String drpyPd(String html, String parse, String baseUrl) {
        return Drpy.pd(html, parse, baseUrl);
    }

    @Keep
    @JSMethod
    public String drpyPdfl(String html, String listParse, String titleParse, String urlParse, String baseUrl) {
        return Drpy.pdfl(html, listParse, titleParse, urlParse, baseUrl);
    }

    @Keep
    @JSMethod
    public String md5X(String text) {
        String result = Crypto.md5(text);
        if (!strict) Logger.t("md5X").d("text:%s\nresult:\n%s", text, result);
        return result;
    }

    @Keep
    @JSMethod
    public String sha256X(String text) {
        return com.github.catvod.utils.Crypto.sha256(text);
    }

    @Keep
    @JSMethod
    public String randomX(Integer size) {
        return com.github.catvod.utils.Crypto.randomUrlSafe(size);
    }

    @Keep
    @JSMethod
    public String desX(String mode, boolean encrypt, String input, boolean inBase64, String key, String iv, boolean outBase64) {
        return com.github.catvod.utils.Crypto.des(mode, encrypt, input, inBase64, key, iv, outBase64);
    }

    @Keep
    @JSMethod
    public String aesX(String mode, boolean encrypt, String input, boolean inBase64, String key, String iv, boolean outBase64) {
        String result = Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64);
        if (!strict) Logger.t("aesX").d("mode:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:%s\niv:%s\ninput:\n%s\nresult:\n%s", mode, encrypt, inBase64, outBase64, key, iv, input, result);
        return result;
    }

    @Keep
    @JSMethod
    public String rsaX(String mode, boolean pub, boolean encrypt, String input, boolean inBase64, String key, boolean outBase64) {
        String result = Crypto.rsa(mode, pub, encrypt, input, inBase64, key, outBase64);
        if (!strict) Logger.t("rsaX").d("mode:%s\npub:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:\n%s\ninput:\n%s\nresult:\n%s", mode, pub, encrypt, inBase64, outBase64, key, input, result);
        return result;
    }

    private void requestAsync(String url, JSObject options, JSFunction complete) {
        complete.hold();
        callbacks.add(complete);
        try {
            Req req = Req.objectFrom(options.stringify());
            Call call = Connect.to(url, req);
            call.enqueue(getCallback(complete, req, track(call)));
        } catch (Throwable e) {
            completeError(complete);
        }
    }

    private CompletableFuture<Void> track(Call call) {
        calls.add(call);
        CompletableFuture<Void> done = new CompletableFuture<>();
        done.whenComplete((value, error) -> {
            if (done.isCancelled()) call.cancel();
            calls.remove(call);
        });
        CallTimeout.trackCurrent(done);
        if (destroyed) done.cancel(true);
        return done;
    }

    private Callback getCallback(JSFunction complete, Req req, CompletableFuture<Void> done) {
        Invocation invocation = Invocation.current();
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response res) {
                completeSuccess(complete, req, res, invocation, done);
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                try { completeError(complete, invocation); }
                finally { done.complete(null); }
            }
        };
    }

    private void completeSuccess(JSFunction complete, Req req, Response res, Invocation invocation, CompletableFuture<Void> done) {
        boolean posted = submit(Invocation.wrap(invocation, () -> {
            try {
                if (!destroyed) complete.call(Connect.success(ctx, req, res));
                else res.close();
            } finally {
                releaseCallback(complete);
                done.complete(null);
            }
        }));
        if (posted) return;
        res.close();
        done.complete(null);
    }

    private void completeError(JSFunction complete) {
        completeError(complete, Invocation.current());
    }

    private void completeError(JSFunction complete, Invocation invocation) {
        postCallback(complete, Invocation.wrap(invocation, () -> complete.call(Connect.error(ctx))));
    }

    private boolean postCallback(JSFunction callback, Runnable runnable) {
        boolean posted = submit(() -> callAndRelease(callback, runnable));
        // Rejected/late callbacks remain owned by destroy(), which runs on the JS thread.
        return posted;
    }

    private void callAndRelease(JSFunction callback, Runnable runnable) {
        try {
            if (!destroyed) runnable.run();
        } finally {
            releaseCallback(callback);
        }
    }

    private void releaseCallback(JSFunction callback) {
        if (callbacks.remove(callback)) callback.release();
    }

    private Timeout createTimeout(JSFunction func) {
        if (func == null || destroyed) return null;
        Timeout timeout = new Timeout(timerId.incrementAndGet(), func);
        timers.put(timeout.id, timeout);
        func.hold();
        if (timeout.scope != null) CallTimeout.trackCurrent(timeout.lifetime);
        timeout.lifetime.whenComplete((value, error) -> {
            if (timeout.lifetime.isCancelled()) submit(() -> cancel(timeout.id));
        });
        return timeout;
    }

    private boolean schedule(Timeout timeout, Integer delay) {
        try {
            timer.schedule(timeout, getDelay(delay));
            return true;
        } catch (Throwable e) {
            cancel(timeout.id);
            return false;
        }
    }

    private int getDelay(Integer delay) {
        return Math.max(0, delay == null ? 0 : delay);
    }

    private void cancel(Integer id) {
        if (id == null) return;
        Timeout timeout = timers.remove(id);
        if (timeout != null) timeout.cancelAndRelease();
    }

    private class Timeout extends TimerTask {

        private final JSFunction func;
        private final Invocation invocation = Invocation.current();
        private final CallTimeout scope = CallTimeout.current() != null && CallTimeout.current().isResourceScope() ? CallTimeout.current() : null;
        private final CompletableFuture<Void> lifetime = new CompletableFuture<>();
        private final int id;
        private volatile boolean canceled;
        private boolean released;

        private Timeout(int id, JSFunction func) {
            this.func = func;
            this.id = id;
        }

        @Override
        public void run() {
            // A timer thread cannot release native values; destroy() owns rejected callbacks.
            submit(() -> {
                Runnable restore = scope == null ? () -> {} : scope.enter();
                try { Invocation.wrap(invocation, this::fire).run(); }
                finally { restore.run(); }
            });
        }

        private void fire() {
            if (canceled || lifetime.isCancelled()) return;
            try {
                func.call();
            } finally {
                Global.this.cancel(id);
            }
        }

        private synchronized void cancelAndRelease() {
            canceled = true;
            cancel();
            release();
        }

        private synchronized void release() {
            if (released) return;
            released = true;
            lifetime.complete(null);
            func.release();
        }
    }
}
