package com.fongmi.chaquo;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.github.catvod.crawler.SpiderRuntime;
import com.github.catvod.utils.Util;
import com.github.catvod.net.Net;
import com.google.gson.Gson;
import androidx.annotation.Keep;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Spider extends com.github.catvod.crawler.Spider {

    private final PyObject app;
    private final PyObject obj;
    private final Gson gson;
    private final Streams streams = new Streams();
    private final Set<Subscription> subscriptions = new HashSet<>();
    private final UserAgent userAgent;
    private boolean closed;
    private boolean destroyed;

    public Spider(PyObject app, PyObject obj, String api) {
        this(app, obj, api, null, null, null);
    }

    public Spider(PyObject app, PyObject obj, String api, Net.Cookies cookies) {
        this(app, obj, api, cookies, null, null);
    }

    public Spider(PyObject app, PyObject obj, String api, Net.Cookies cookies, Supplier<String> userAgent) {
        this(app, obj, api, cookies, userAgent, null);
    }

    public Spider(PyObject app, PyObject obj, String api, Net.Cookies cookies, Supplier<String> userAgent, Net net) {
        this.gson = new Gson();
        this.app = app;
        this.obj = obj;
        this.net = net;
        this.userAgent = new UserAgent(userAgent);
        if (this.net == null && cookies != null) this.net = new Net(cookies);
    }

    @Override
    public void init(Context context, String extend) {
        try {
            SpiderRuntime.bind(this, siteKey);
            obj.put("siteKey", siteKey);
            app.callAttr("bind", obj, net, local, dialog, streams, js, userAgent);
            app.callAttr("init", obj, extend);
        } catch (RuntimeException | Error error) {
            try { close(); } catch (RuntimeException | Error closing) { if (closing != error) error.addSuppressed(closing); }
            try { app.callAttr("release", obj); } catch (RuntimeException | Error closing) { if (closing != error) error.addSuppressed(closing); }
            throw error;
        }
    }

    @Override
    public String homeContent(boolean filter) {
        return app.callAttr("homeContent", obj, filter).toString();
    }

    @Override
    public String homeVideoContent() {
        return app.callAttr("homeVideoContent", obj).toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return app.callAttr("categoryContent", obj, tid, pg, filter, gson.toJson(extend)).toString();
    }

    @Override
    public String detailContent(List<String> ids) {
        return app.callAttr("detailContent", obj, gson.toJson(ids)).toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return app.callAttr("searchContent", obj, key, quick).toString();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return app.callAttr("searchContent", obj, key, quick, pg).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return app.callAttr("playerContent", obj, flag, id, gson.toJson(vipFlags)).toString();
    }

    @Override
    public String liveContent(String url) {
        return app.callAttr("liveContent", obj, url).toString();
    }

    @Override
    public boolean manualVideoCheck() {
        return app.callAttr("manualVideoCheck", obj).toBoolean();
    }

    @Override
    public boolean isVideoFormat(String url) {
        return app.callAttr("isVideoFormat", obj, url).toBoolean();
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        List<PyObject> list = app.callAttr("proxy", obj, gson.toJson(params)).asList();
        if (list.size() < 3) throw new IllegalArgumentException("Proxy response requires status, MIME and body");
        boolean base64 = list.size() > 4 && list.get(4).toInt() == 1;
        boolean header = list.size() > 3 && list.get(3) != null;
        InputStream stream = getStream(list.get(2), base64);
        try {
            int code = list.get(0).toInt();
            if (code < 100 || code > 599) throw new IllegalArgumentException("Invalid proxy response status");
            return new Object[]{code, list.get(1).toJava(String.class), stream, header ? getHeader(list.get(3)) : null};
        } catch (RuntimeException | Error error) {
            streams.release(stream);
            try { if (stream != null) stream.close(); }
            catch (IOException | RuntimeException | Error closing) { if (closing != error) error.addSuppressed(closing); }
            throw error;
        }
    }

    @Override
    public String action(String action) {
        return app.callAttr("action", obj, action).toString();
    }

    @Override
    public AutoCloseable danmaku(String id, Consumer<JSONObject> emit) {
        Subscription subscription = new Subscription(new Emitter(emit));
        synchronized (this) {
            if (closed) throw new IllegalStateException("Python spider closed");
            subscriptions.add(subscription);
        }
        try {
            subscription.attach(app.callAttr("danmaku", obj, id, subscription.emitter));
            return subscription;
        } catch (RuntimeException | Error error) {
            subscription.close();
            throw error;
        }
    }

    public void close() {
        Set<Subscription> pending;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending = new HashSet<>(subscriptions);
        }
        Throwable failure = null;
        for (Subscription subscription : pending) {
            try { subscription.close(); }
            catch (RuntimeException | Error error) { failure = merge(failure, error); }
        }
        try { streams.close(); } catch (RuntimeException | Error error) { failure = merge(failure, error); }
        try { SpiderRuntime.close(this); } catch (RuntimeException | Error error) { failure = merge(failure, error); }
        try { app.callAttr("closeDialog", obj); } catch (RuntimeException | Error error) { failure = merge(failure, error); }
        rethrow(failure);
    }

    @Override
    public void destroy() {
        synchronized (this) {
            if (destroyed) return;
            destroyed = true;
        }
        Throwable failure = null;
        try { close(); } catch (RuntimeException | Error error) { failure = error; }
        try {
            app.callAttr("destroy", obj);
        } catch (RuntimeException | Error error) { failure = merge(failure, error); }
        rethrow(failure);
    }

    private static Throwable merge(Throwable failure, Throwable error) {
        if (failure == null) return error;
        if (failure != error) failure.addSuppressed(error);
        return failure;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private Map<String, String> getHeader(PyObject obj) {
        Map<String, String> header = new HashMap<>();
        for (Map.Entry<PyObject, PyObject> entry : obj.asMap().entrySet()) {
            String name = entry.getKey().toJava(String.class);
            String value;
            try { value = entry.getValue().toJava(String.class); }
            catch (ClassCastException error) { value = String.join(", ", entry.getValue().toJava(String[].class)); }
            if (name == null || name.isEmpty() || value == null || name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0)
                throw new IllegalArgumentException("Invalid proxy response header");
            header.put(name, value);
        }
        return header;
    }

    private InputStream getStream(PyObject o, boolean base64) throws IOException {
        if (o == null) return null;
        String type = o.type().toString();
        if (type.contains("bytes") || type.contains("bytearray")) return new ByteArrayInputStream(o.toJava(byte[].class));
        Object value = o.toJava(Object.class);
        if (value instanceof InputStream stream) return streams.track(stream);
        if (value instanceof byte[] bytes) return new ByteArrayInputStream(bytes);
        if (!(value instanceof String content)) throw new IllegalArgumentException("Proxy body requires text, bytes or InputStream");
        if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
        return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes(StandardCharsets.UTF_8));
    }

    private final class Subscription implements AutoCloseable {
        private final Emitter emitter;
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicReference<PyObject> stop = new AtomicReference<>();
        Subscription(Emitter emitter) { this.emitter = emitter; }
        void attach(PyObject function) {
            if (function == null) throw new IllegalArgumentException("Danmaku requires a stop function");
            stop.set(function);
            if (stopped.get()) callStop();
        }
        private void callStop() {
            PyObject function = stop.getAndSet(null);
            if (function != null) function.call();
        }
        @Override public void close() {
            if (!stopped.compareAndSet(false, true)) return;
            emitter.close();
            synchronized (Spider.this) { subscriptions.remove(this); }
            callStop();
        }
    }

    @Keep
    public static final class Emitter {
        private final AtomicReference<Consumer<JSONObject>> listener;
        Emitter(Consumer<JSONObject> listener) { this.listener = new AtomicReference<>(listener); }
        public synchronized void emit(String json) throws JSONException {
            Consumer<JSONObject> current = listener.get();
            if (current != null) current.accept(new JSONObject(json));
        }
        synchronized void close() { listener.set(null); }
    }

    @Keep
    public static final class UserAgent {
        private final Supplier<String> supplier;
        UserAgent(Supplier<String> supplier) { this.supplier = supplier; }
        public String getUserAgent() { return supplier == null ? Util.CHROME : supplier.get(); }
    }
}
