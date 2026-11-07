package com.fongmi.quickjs.method;

import android.net.Uri;

import com.github.catvod.Proxy;
import com.github.catvod.net.Net;
import com.github.catvod.net.NetOptions;
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
    private final Map<Integer, Net> owners = new HashMap<>();
    private final Map<Integer, Integer> parents = new HashMap<>();
    private final Map<Integer, CompletableFuture<?>> pending = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<String>> loading = new ConcurrentHashMap<>();
    private final com.github.catvod.utils.Local local;
    private final String siteKey;
    private JSFunction deliver;
    private int nextOwner;
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
                && !method.equals("loaded") && !method.equals("close")) {
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
                start(owner, params);
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

    private void start(Net owner, JsonObject params) throws Exception {
        int id = params.get("id").getAsInt();
        Invocation invocation = Invocation.current();
        String operation = string(params, "operation");
        String optionsJson = json(params, "options");
        CompletableFuture<String> future;
        if (operation.equals("cached") || operation.equals("refresh")) {
            Loader loader = new Loader(id, invocation);
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
            emit(operation.equals("refresh") ? "refreshed" : "result", id, error == null ? success(value) : failure(error), invocation);
        });
    }

    private void startBinary(Net owner, int id, String url, NetOptions options, Invocation invocation) {
        CompletableFuture<Net.Result> future = owner.requestAsync(url, options);
        pending.put(id, future);
        future.whenComplete((result, error) -> {
            pending.remove(id);
            if (error != null) {
                emit("result", id, failure(error), invocation);
                return;
            }
            try {
                Object[] parts = result.parts(options);
                if (parts[1] instanceof byte[] bytes) {
                    // Keep the body out of Gson: JNI converts byte[] directly into ArrayBuffer.
                    emit("binary", id, success(parts[0]), bytes, invocation);
                } else {
                    emit("result", id, success(result.json(options)), invocation);
                }
            } catch (Exception failure) {
                emit("result", id, failure(failure), invocation);
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
        CompletableFuture<String> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                result.complete(callable.call());
            } catch (Throwable error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                result.completeExceptionally(error);
            }
            return null;
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) task.cancel(true); });
        workers.execute(task);
        return result;
    }

    private String proxy(JsonObject params) {
        Uri.Builder url = Uri.parse(Proxy.getUrl(true)).buildUpon().appendQueryParameter("do", "js")
                .appendQueryParameter("siteKey", siteKey);
        for (Map.Entry<String, JsonElement> entry : params.entrySet()) {
            if (entry.getKey().equals("do") || entry.getKey().equals("siteKey")) throw new IllegalArgumentException("Reserved proxy parameter");
            url.appendQueryParameter(entry.getKey(), entry.getValue().isJsonNull() ? "" : entry.getValue().getAsString());
        }
        return url.build().toString();
    }

    private void closeOwner(int id) {
        for (int child : new ArrayList<>(parents.keySet())) {
            if (parents.get(child) != null && parents.get(child) == id) closeOwner(child);
        }
        Net owner = owners.remove(id);
        parents.remove(id);
        if (owner != null) owner.close();
    }

    private void emit(String kind, int id, String json, Invocation invocation) {
        emit(kind, id, json, null, invocation);
    }

    private void emit(String kind, int id, String json, byte[] bytes, Invocation invocation) {
        if (closed) return;
        try {
            executor.execute(Invocation.wrap(invocation, () -> {
                if (!closed && deliver != null) {
                    if (bytes == null) deliver.callVoid(kind, id, json);
                    else deliver.callVoid(kind, id, json, bytes);
                }
            }));
        } catch (RejectedExecutionException ignored) {
            // close releases the sole owned JS callback on the JS executor.
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (deliver != null) {
            try {
                deliver.callVoid("close", 0, "");
            } catch (RuntimeException ignored) {
                // A throwing script callback must not prevent owner cleanup.
            } finally {
                deliver.release();
                deliver = null;
            }
        }
        for (Net owner : owners.values()) owner.close();
        owners.clear();
        parents.clear();
        for (CompletableFuture<?> future : pending.values()) future.cancel(true);
        pending.clear();
        for (CompletableFuture<?> future : loading.values()) future.cancel(true);
        loading.clear();
        workers.shutdownNow();
    }

    private final class Loader {
        final int id;
        final AtomicBoolean started = new AtomicBoolean();
        final Invocation invocation;

        Loader(int id, Invocation invocation) { this.id = id; this.invocation = invocation; }

        CompletableFuture<String> start() {
            started.set(true);
            CompletableFuture<String> value = new CompletableFuture<>();
            loading.put(id, value);
            value.whenComplete((result, error) -> {
                loading.remove(id, value);
                emit("releaseLoader", id, "", invocation);
            });
            if (closed) value.completeExceptionally(new InterruptedIOException("Network bridge closed"));
            else emit("load", id, "", invocation);
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
