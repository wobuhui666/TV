package com.fongmi.quickjs.method;

import android.net.Uri;

import com.github.catvod.Proxy;
import com.github.catvod.net.Net;
import com.github.catvod.net.NetOptions;
import com.github.catvod.net.NetSocket;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.File;
import java.io.InterruptedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exchanges JSON metadata and binary buffers; only the JS executor may touch QuickJS values. */
public final class NetBridge implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final ExecutorService executor;
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "Spider network bridge");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, Net> owners = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> parents = new ConcurrentHashMap<>();
    private final Map<Integer, StreamEntry> streams = new ConcurrentHashMap<>();
    private final Map<Integer, SocketEntry> sockets = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<byte[]>> chunks = new ConcurrentHashMap<>();
    private final java.util.Set<SocketEntry> socketScopes = ConcurrentHashMap.newKeySet();
    private final Map<Integer, CompletableFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<String>> loading = new ConcurrentHashMap<>();
    private final com.github.catvod.utils.Local local;
    private final String siteKey;
    private JSFunction deliver;
    private int nextOwner;
    private final java.util.concurrent.atomic.AtomicInteger nextToken = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger nextChunk = new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean closed;

    public NetBridge(QuickJSContext context, ExecutorService executor, Net net,
                     com.github.catvod.utils.Local local, String siteKey) {
        this.executor = executor;
        this.local = local;
        this.siteKey = siteKey;
        owners.put(0, net);
        JSObject nativeObject = context.createNewJSObject();
        nativeObject.setProperty("call", args -> {
            try {
                if (closed) throw new IllegalStateException("Network bridge closed");
                String method = (String) args[0];
                int owner = ((Number) args[1]).intValue();
                JsonObject params = Json.strict((String) args[2]).getAsJsonObject();
                return success(invoke(method, owner, params));
            } catch (Exception error) {
                return failure(error);
            }
        });
        nativeObject.setProperty("reqBinary", args -> {
            try {
                if (closed) throw new IllegalStateException("Network bridge closed");
                Net owner = owners.get(((Number) args[0]).intValue());
                if (owner == null) throw new IllegalStateException("Network session closed");
                JsonObject params = Json.strict((String) args[1]).getAsJsonObject();
                Object[] parts = owner.reqParts(string(params, "url"), json(params, "options"));
                JSObject response = (JSObject) context.parse((String) parts[0]);
                try {
                    if (parts[1] instanceof byte[] bytes) response.setProperty("content", bytes);
                    else response.setProperty("content", (String) parts[1]);
                    // Returning a JSObject transfers this reference back to QuickJS.
                    return response;
                } catch (RuntimeException | Error error) {
                    response.release();
                    throw error;
                }
            } catch (Exception error) {
                return failure(error);
            } finally {
                for (Object arg : args) if (arg instanceof JSObject object) object.release();
            }
        });
        nativeObject.setProperty("chunk", args -> {
            try {
                int id = ((Number) args[0]).intValue();
                CompletableFuture<byte[]> result = chunks.remove(id);
                if (result != null) {
                    if (args.length > 2 && args[2] != null) result.completeExceptionally(new IOException(String.valueOf(args[2])));
                    else if (args.length > 1 && args[1] instanceof byte[] bytes) result.complete(bytes);
                    else if (args.length > 1 && args[1] != null) result.completeExceptionally(new IOException("Proxy stream requires bytes"));
                    else result.complete(null);
                }
                return null;
            } finally {
                for (Object arg : args) if (arg instanceof JSObject object) object.release();
            }
        });
        nativeObject.setProperty("socketSend", args -> {
            try {
                SocketEntry entry = sockets.get(((Number) args[0]).intValue());
                if (entry == null || entry.finished.get() || entry.socket == null) return false;
                if (args.length > 1 && args[1] instanceof String text) return entry.socket.send(text);
                if (args.length > 2 && args[2] instanceof byte[] bytes) return entry.socket.send(bytes);
                throw new IllegalArgumentException("Socket send requires text or bytes");
            } finally {
                for (Object arg : args) if (arg instanceof JSObject object) object.release();
            }
        });
        context.getGlobalObject().setProperty("__SPIDER_NET_NATIVE__", nativeObject);
        nativeObject.release();
        context.evaluate(Asset.read("js/lib/net.js"));
        // This getter owns one reference, retained until close on the same executor.
        deliver = context.getGlobalObject().getJSFunction("__SPIDER_NET_DELIVER__");
        JsonObject site = new JsonObject();
        site.addProperty("key", siteKey);
        JSObject siteObject = (JSObject) context.parse(site.toString());
        context.getGlobalObject().setProperty("__SPIDER_SITE__", siteObject);
        siteObject.release();
    }

    private Object invoke(String method, int ownerId, JsonObject params) throws Exception {
        Net owner = owners.get(ownerId);
        if (owner == null && !method.startsWith("local") && !method.equals("proxy")
                && !method.equals("loaded") && !method.equals("close") && !method.equals("danmakuUrl")) {
            throw new IllegalStateException("Network session closed");
        }
        return switch (method) {
            case "req" -> owner.req(string(params, "url"), json(params, "options"));
            case "cookieGet" -> owner.getCookie(string(params, "url"));
            case "cookieSet" -> owner.setCookie(string(params, "url"), string(params, "value"));
            case "userAgent" -> Util.CHROME;
            case "session" -> {
                int id = ++nextOwner;
                owners.put(id, owner.session());
                parents.put(id, ownerId);
                yield id;
            }
            case "close" -> {
                closeOwner(ownerId);
                yield null;
            }
            case "peek" -> owner.peek(string(params, "key"));
            case "invalidateCache" -> owner.invalidateCache(string(params, "key"), string(params, "expected"));
            case "streamBody" -> createBody(ownerId);
            case "streamClose" -> closeStream(params.get("token").getAsInt(), ownerId);
            case "connect" -> connect(owner, ownerId, params);
            case "socketClose" -> {
                SocketEntry entry = sockets.get(params.get("token").getAsInt());
                if (entry == null) for (SocketEntry candidate : socketScopes) if (candidate.token == params.get("token").getAsInt()) { entry = candidate; break; }
                if (entry != null && entry.ownerId == ownerId) {
                    if (params.has("cancel") && params.get("cancel").getAsBoolean()) {
                        sockets.remove(entry.token, entry);
                        socketScopes.remove(entry);
                        entry.close();
                    } else entry.socket.close();
                }
                yield null;
            }
            case "proxyUrl" -> proxy(params.getAsJsonObject("params"), params.get("local").getAsBoolean(), true);
            case "danmakuUrl" -> com.github.catvod.crawler.Spider.danmakuUrl(siteKey, string(params, "id"));
            case "clearCache" -> {
                if (params.has("key")) owner.clearCache(string(params, "key"));
                else owner.clearCache();
                yield null;
            }
            case "localGet" -> local.get(string(params, "key"));
            case "localSet" -> {
                local.set(string(params, "key"), string(params, "value"));
                yield null;
            }
            case "localDelete" -> {
                local.delete(string(params, "key"));
                yield null;
            }
            case "localClear" -> {
                local.clear();
                yield null;
            }
            case "proxy" -> proxy(params);
            case "start" -> {
                start(owner, ownerId, params);
                yield null;
            }
            case "loaded" -> {
                CompletableFuture<String> result = loading.remove(params.get("id").getAsInt());
                if (result != null) {
                    if (params.has("error")) result.completeExceptionally(new IllegalStateException(string(params, "error")));
                    else result.complete(string(params, "value"));
                }
                yield null;
            }
            default -> throw new IllegalArgumentException("Unknown network operation: " + method);
        };
    }

    private void start(Net owner, int ownerId, JsonObject params) throws Exception {
        int id = params.get("id").getAsInt();
        Invocation invocation = Invocation.current();
        CallTimeout scope = CallTimeout.current();
        String operation = string(params, "operation");
        String optionsJson = json(params, "options");
        CompletableFuture<String> future;
        if (operation.equals("openStream") || operation.equals("stream")) {
            CompletableFuture<Net.StreamResult> opened = owner.requestStreamAsync(string(params, "url"), NetOptions.from(optionsJson));
            future = opened.thenApply(stream -> {
                int token = nextToken.incrementAndGet();
                StreamEntry entry = new StreamEntry(ownerId, token, stream, null);
                if (closed || !owners.containsKey(ownerId)) {
                    stream.close();
                    throw new IllegalStateException("Stream owner closed");
                }
                streams.put(token, entry);
                trackStream(entry, scope);
                if (closed || !owners.containsKey(ownerId)) closeStream(token);
                JsonObject reply = new JsonObject();
                reply.addProperty("token", token);
                reply.addProperty("code", stream.code);
                reply.add("headers", GSON.toJsonTree(stream.headers));
                reply.addProperty("contentType", stream.contentType);
                return reply.toString();
            });
            CompletableFuture<String> reply = future;
            future.whenComplete((value, error) -> { if (reply.isCancelled()) opened.cancel(true); });
            opened.whenComplete((stream, error) -> { if (reply.isCancelled() && stream != null) stream.close(); });
        } else if (operation.equals("streamRead")) {
            int token = params.get("token").getAsInt();
            int size = params.get("size").getAsInt();
            if (size < 1 || size > 1048576) throw new IllegalArgumentException("Invalid stream read size");
            StreamEntry entry = streams.get(token);
            if (entry == null || entry.ownerId != ownerId || entry.taken.get()) throw new IOException("Stream reader closed");
            ByteBuffer buffer = ByteBuffer.allocate(size);
            if (!(entry.input instanceof Net.StreamResult stream)) throw new IOException("Stream is not a reader");
            CompletableFuture<Integer> read = stream.readAsync(buffer);
            pending.put(id, read);
            read.whenComplete((count, error) -> {
                pending.remove(id, read);
                if (error != null) {
                    closeStream(token);
                    emit("result", id, failure(error), null, invocation, scope);
                } else if (count == -1) {
                    closeStream(token);
                    emit("result", id, success(null), null, invocation, scope);
                } else emit("streamBytes", id, success(null), Arrays.copyOf(buffer.array(), count), invocation, scope);
            });
            return;
        } else if (operation.equals("cached") || operation.equals("refresh")) {
            Loader loader = new Loader(id, invocation, scope);
            try {
                if (operation.equals("refresh")) {
                    JsonObject options = Json.strict(optionsJson).getAsJsonObject();
                    options.addProperty("stale", false);
                    optionsJson = options.toString();
                }
                future = owner.cachedAsync(string(params, "key"), optionsJson, loader::start);
            } catch (Exception error) {
                emit("releaseLoader", id, "", invocation);
                throw error;
            }
            future.whenComplete((value, error) -> {
                if (!loader.started.get()) emit("releaseLoader", id, "", invocation);
            });
        } else {
            NetOptions options = NetOptions.from(optionsJson);
            if (operation.equals("http") && options.getBuffer() == 1) {
                startBinary(owner, id, string(params, "url"), options, invocation);
                return;
            }
            future = switch (operation) {
                case "http" -> owner.requestAsync(string(params, "url"), options).thenApply(result -> result.json(options));
                case "json" -> {
                    if (options.getBuffer() != 0) throw new IllegalArgumentException("JSON requests require text content");
                    yield owner.requestAsync(string(params, "url"), options).thenApply(result -> {
                        if (result.error != null) throw new IllegalStateException(result.error);
                        int code = ((Number) result.code).intValue();
                        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
                        String value = result.text();
                        Json.strict(value);
                        return value;
                    });
                }
                case "ws" -> owner.socketAsync(string(params, "url"), options).thenApply(result -> result.json(options));
                case "batch" -> work(() -> batch(owner, params));
                case "download" -> owner.downloadAsync(string(params, "url"), optionsJson, new File(string(params, "path")));
                case "sleep" -> work(() -> {
                    owner.sleep(params.get("ms").getAsLong());
                    return null;
                });
                default -> throw new IllegalArgumentException("Unknown async network operation: " + operation);
            };
        }
        pending.put(id, future);
        future.whenComplete((value, error) -> {
            pending.remove(id);
            emit(operation.equals("refresh") ? "refreshed" : "result", id, error == null ? success(value) : failure(error), null, invocation, scope);
        });
    }

    private void startBinary(Net owner, int id, String url, NetOptions options, Invocation invocation) {
        CallTimeout scope = CallTimeout.current();
        CompletableFuture<Net.Result> future = owner.requestAsync(url, options);
        pending.put(id, future);
        future.whenComplete((result, error) -> {
            pending.remove(id);
            if (error != null) {
                emit("result", id, failure(error), null, invocation, scope);
                return;
            }
            try {
                Object[] parts = result.parts(options);
                if (parts[1] instanceof byte[] bytes) {
                    // Keep the body out of Gson: JNI converts byte[] directly into ArrayBuffer.
                    emit("binary", id, success(parts[0]), bytes, invocation, scope);
                } else {
                    emit("result", id, success(result.json(options)), null, invocation, scope);
                }
            } catch (Exception failure) {
                emit("result", id, failure(failure), null, invocation, scope);
            }
        });
    }

    private String batch(Net owner, JsonObject params) throws Exception {
        JsonArray requests = params.getAsJsonArray("requests");
        Net.Result[] results = owner.batch(requests.toString(), params.get("limit").getAsInt());
        JsonArray values = new JsonArray();
        for (int i = 0; i < results.length; i++) {
            String options = json(requests.get(i).getAsJsonObject(), "options");
            values.add(Json.strict(results[i].json(NetOptions.from(options))));
        }
        return values.toString();
    }

    private CompletableFuture<String> work(Callable<String> callable) {
        CallTimeout scope = CallTimeout.current();
        CompletableFuture<String> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            Runnable restore = scope == null ? () -> {} : scope.enter();
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                result.completeExceptionally(error);
            } finally { restore.run(); }
            return null;
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) task.cancel(true); });
        CallTimeout.trackCurrent(result);
        workers.execute(task);
        return result;
    }

    private String proxy(JsonObject params) { return proxy(params, true, false); }

    private String proxy(JsonObject params, boolean localUrl, boolean strict) {
        Uri.Builder url = Uri.parse(Proxy.getUrl(localUrl)).buildUpon().appendQueryParameter("do", "js")
                .appendQueryParameter("siteKey", siteKey);
        for (Map.Entry<String, JsonElement> entry : params.entrySet()) {
            if (entry.getKey().equals("do") || entry.getKey().equals("siteKey")) throw new IllegalArgumentException("Reserved proxy parameter");
            if (strict && (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())) throw new IllegalArgumentException("Proxy parameters require strings");
            url.appendQueryParameter(entry.getKey(), entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString());
        }
        return url.build().toString();
    }

    private void closeOwner(int id) {
        BridgeCleanup cleanup = new BridgeCleanup();
        closeOwner(id, cleanup);
        cleanup.finish();
    }

    private void closeOwner(int id, BridgeCleanup cleanup) {
        for (int child : new ArrayList<>(parents.keySet())) {
            if (parents.get(child) != null && parents.get(child) == id) closeOwner(child, cleanup);
        }
        for (StreamEntry entry : new ArrayList<>(streams.values())) if (entry.ownerId == id) cleanup.run(() -> closeStream(entry.token));
        for (SocketEntry entry : new ArrayList<>(socketScopes)) if (entry.ownerId == id) {
            sockets.remove(entry.token, entry);
            socketScopes.remove(entry);
            cleanup.run(entry::close);
        }
        Net owner = owners.remove(id);
        parents.remove(id);
        if (owner != null) cleanup.run(owner::close);
        emit("ownerClosed", id, "", null);
    }

    private void emit(String kind, int id, String json, Invocation invocation) {
        emit(kind, id, json, null, invocation);
    }

    private void emit(String kind, int id, String json, byte[] bytes, Invocation invocation) {
        emit(kind, id, json, bytes, invocation, null);
    }

    private void emit(String kind, int id, String json, byte[] bytes, Invocation invocation, CallTimeout scope) {
        if (closed) return;
        try {
            executor.execute(() -> {
                Runnable restore = scope == null ? () -> {} : scope.enter();
                try { Invocation.wrap(invocation, () -> {
                if (!closed && deliver != null) {
                    if (bytes == null) deliver.callVoid(kind, id, json);
                    else deliver.callVoid(kind, id, json, bytes);
                }
                }).run(); } finally { restore.run(); }
            });
        } catch (RejectedExecutionException ignored) {
            // close releases the sole owned JS callback on the JS executor.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        BridgeCleanup cleanup = new BridgeCleanup();
        JSFunction callback = deliver;
        deliver = null;
        if (callback != null) {
            cleanup.run(() -> callback.callVoid("close", 0, ""));
            cleanup.run(callback::release);
        }
        for (StreamEntry entry : new ArrayList<>(streams.values())) cleanup.run(() -> closeStream(entry.token));
        for (SocketEntry entry : new ArrayList<>(socketScopes)) cleanup.run(entry::close);
        socketScopes.clear();
        sockets.clear();
        for (CompletableFuture<byte[]> chunk : new ArrayList<>(chunks.values())) cleanup.run(() -> chunk.cancel(true));
        chunks.clear();
        for (Net owner : new ArrayList<>(owners.values())) cleanup.run(owner::close);
        owners.clear();
        parents.clear();
        for (CompletableFuture<?> future : new ArrayList<>(pending.values())) cleanup.run(() -> future.cancel(true));
        pending.clear();
        for (CompletableFuture<?> future : new ArrayList<>(loading.values())) cleanup.run(() -> future.cancel(true));
        loading.clear();
        cleanup.run(workers::shutdownNow);
        cleanup.finish();
    }

    /** Takes a source-local handle exactly once; the returned stream remains source-owned. */
    public InputStream takeStream(int token) throws IOException {
        StreamEntry entry = streams.get(token);
        if (closed || entry == null || !entry.taken.compareAndSet(false, true)) throw new IOException("Proxy stream unavailable");
        return new java.io.FilterInputStream(entry.input) {
            @Override public int read() throws IOException {
                try { int value = super.read(); if (value == -1) close(); return value; }
                catch (IOException | RuntimeException error) { close(); throw error; }
            }
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                try { int value = in.read(bytes, offset, length); if (value == -1) close(); return value; }
                catch (IOException | RuntimeException error) { close(); throw error; }
            }
            @Override public void close() { closeStream(token); }
        };
    }

    public boolean closeStream(int token) {
        StreamEntry entry = streams.remove(token);
        if (entry == null) return false;
        entry.close();
        return true;
    }

    private boolean closeStream(int token, int owner) {
        StreamEntry entry = streams.get(token);
        return entry != null && entry.ownerId == owner && closeStream(token);
    }

    private int createBody(int ownerId) {
        int token = nextToken.incrementAndGet();
        CallTimeout caller = CallTimeout.current();
        CallTimeout scope = CallTimeout.resourceScope();
        JsProxyBody body = new JsProxyBody(new JsProxyBody.Source() {
            @Override public CompletableFuture<byte[]> pull(int size) {
                int id = nextChunk.incrementAndGet();
                CompletableFuture<byte[]> result = new CompletableFuture<>();
                chunks.put(id, result);
                result.whenComplete((value, error) -> chunks.remove(id, result));
                Runnable restore = scope.enter();
                try { CallTimeout.trackCurrent(result); } finally { restore.run(); }
                JsonObject request = new JsonObject();
                request.addProperty("token", token);
                request.addProperty("size", size);
                emit("pull", id, success(request), null, null, scope);
                return result;
            }
            @Override public void close() {
                BridgeCleanup cleanup = new BridgeCleanup();
                cleanup.run(scope::close);
                streams.remove(token);
                emit("releaseStream", token, "", null);
                cleanup.finish();
            }
        });
        StreamEntry entry = new StreamEntry(ownerId, token, body, scope);
        streams.put(token, entry);
        trackStream(entry, caller);
        return token;
    }

    private void trackStream(StreamEntry entry, CallTimeout caller) {
        if (caller == null || !caller.isResourceScope()) return;
        Runnable restore = caller.enter();
        try { CallTimeout.trackCurrent(entry.lifetime); } finally { restore.run(); }
        entry.lifetime.whenComplete((value, error) -> { if (entry.lifetime.isCancelled()) closeStream(entry.token); });
    }

    private int connect(Net owner, int ownerId, JsonObject params) throws IOException {
        int token = nextToken.incrementAndGet();
        CallTimeout callerScope = CallTimeout.current();
        CallTimeout callbackScope = callerScope != null && callerScope.isResourceScope() ? callerScope : null;
        SocketEntry entry = new SocketEntry(ownerId, token, callbackScope);
        if (callbackScope != null) CallTimeout.trackCurrent(entry.lifetime);
        entry.lifetime.whenComplete((value, error) -> {
            if (entry.lifetime.isCancelled()) {
                sockets.remove(token, entry);
                socketScopes.remove(entry);
                entry.close();
            }
        });
        sockets.put(token, entry);
        socketScopes.add(entry);
        try {
            if (entry.canceled.get()) throw new IOException("Socket owner closed");
            entry.socket = owner.connect(string(params, "url"), json(params, "options"), (socket, event) -> {
                if (closed || entry.finished.get()) return;
                Object[] parts = event.parts();
                JsonObject metadata = Json.strict((String) parts[0]).getAsJsonObject();
                if (parts[1] instanceof String text) metadata.addProperty("content", text);
                emitSocket(entry, metadata.toString(), parts[1] instanceof byte[] bytes ? bytes : null);
                if (event.type.equals("close")) {
                    sockets.remove(token, entry);
                    entry.finished.set(true);
                }
            });
            if (entry.canceled.get()) entry.socket.cancel();
            return token;
        } catch (IOException | RuntimeException error) {
            sockets.remove(token, entry);
            socketScopes.remove(entry);
            entry.close();
            throw error;
        }
    }

    private void emitSocket(SocketEntry entry, String metadata, byte[] bytes) {
        if (closed || entry.canceled.get()) return;
        try {
            executor.execute(() -> {
                if (closed || entry.canceled.get() || deliver == null) return;
                Runnable restore = entry.callbackScope.enter();
                try {
                    if (bytes == null) deliver.callVoid("socket", entry.token, success(Json.strict(metadata)));
                    else deliver.callVoid("socket", entry.token, success(Json.strict(metadata)), bytes);
                } finally { restore.run(); }
            });
        } catch (RejectedExecutionException ignored) {}
    }

    private static final class StreamEntry {
        final int ownerId, token;
        final InputStream input;
        final CompletableFuture<Void> lifetime = new CompletableFuture<>();
        final CallTimeout scope;
        final AtomicBoolean taken = new AtomicBoolean();
        StreamEntry(int ownerId, int token, InputStream input, CallTimeout scope) {
            this.ownerId = ownerId; this.token = token; this.input = input; this.scope = scope;
        }
        void close() {
            BridgeCleanup cleanup = new BridgeCleanup();
            cleanup.run(() -> lifetime.complete(null));
            if (scope != null) cleanup.run(scope::close);
            cleanup.run(() -> { try { input.close(); } catch (IOException error) { throw new IllegalStateException("Failed to close stream", error); } });
            cleanup.finish();
        }
    }

    private static final class SocketEntry {
        final int ownerId, token;
        final CallTimeout scope = CallTimeout.resourceScope();
        final CallTimeout callbackScope;
        final CompletableFuture<Void> lifetime = new CompletableFuture<>();
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean canceled = new AtomicBoolean();
        volatile NetSocket socket;
        SocketEntry(int ownerId, int token, CallTimeout callbackScope) {
            this.ownerId = ownerId; this.token = token;
            this.callbackScope = callbackScope == null ? scope : callbackScope;
        }
        void close() {
            canceled.set(true);
            BridgeCleanup cleanup = new BridgeCleanup();
            cleanup.run(() -> lifetime.complete(null));
            cleanup.run(scope::close);
            if (finished.compareAndSet(false, true) && socket != null) cleanup.run(socket::cancel);
            cleanup.finish();
        }
    }

    private final class Loader {
        final int id;
        final AtomicBoolean started = new AtomicBoolean();
        final Invocation invocation;
        final CallTimeout scope;

        Loader(int id, Invocation invocation, CallTimeout scope) { this.id = id; this.invocation = invocation; this.scope = scope; }

        CompletableFuture<String> start() {
            started.set(true);
            CompletableFuture<String> value = new CompletableFuture<>();
            loading.put(id, value);
            if (scope != null) {
                Runnable restore = scope.enter();
                try { CallTimeout.trackCurrent(value); } finally { restore.run(); }
            }
            value.whenComplete((result, error) -> {
                loading.remove(id, value);
                emit("releaseLoader", id, "", null, invocation, scope);
            });
            if (closed) value.completeExceptionally(new InterruptedIOException("Network bridge closed"));
            else emit("load", id, "", null, invocation, scope);
            return value;
        }
    }

    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) throw new IllegalArgumentException("Missing " + name);
        return value.getAsString();
    }

    private static String json(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "{}" : value.toString();
    }

    private static String success(Object value) {
        JsonObject result = new JsonObject();
        result.add("value", GSON.toJsonTree(value));
        return result.toString();
    }

    private static String failure(Throwable error) {
        while (error.getCause() != null && (error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException)) error = error.getCause();
        JsonObject result = new JsonObject();
        result.addProperty("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        return result.toString();
    }
}
