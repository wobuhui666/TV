package com.github.catvod.net;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.github.catvod.Init;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.CompletableFutures;
import com.github.catvod.utils.CallTimeout;
import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

@Keep
public final class Net
implements AutoCloseable {
    private static final int TEXT_TIMEOUT = 15000;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    private static final Set<String> POST_TYPES = Set.of("json", "form", "form-data");
    static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Spider WebSocket timeout");
        thread.setDaemon(true);
        return thread;
    });
    private final Cookies cookies;
    private final OkHttpClient client;
    private final Net parent;
    private final NetTrace trace;
    private final Object lock = new Object();
    private final Set<Call> calls = new HashSet<>();
    private final Set<Exchange> sockets = new HashSet<>();
    private final Set<StreamResult> streams = new HashSet<>();
    private final Set<NetSocket> connections = new HashSet<>();
    private final Set<NetMap<?, ?>> maps = new HashSet<>();
    private final Set<Net> children = new HashSet<>();
    private final NetCache cache;
    private boolean closed;

    public Net() {
        this(null);
    }

    public Net(Cookies cookies) {
        this(OkHttp.client(), cookies);
    }

    public Net(Cookies cookies, String siteKey) {
        this(OkHttp.client(), cookies, null, new NetStore(siteKey), NetTrace.find(siteKey));
    }

    Net(OkHttpClient client, Cookies cookies) {
        this(client, cookies, null, null, null);
    }

    Net(OkHttpClient client, Cookies cookies, NetTrace trace) {
        this(client, cookies, null, null, trace);
    }

    private Net(OkHttpClient client, Cookies cookies, Net parent, NetStore store, NetTrace trace) {
        this.client = client;
        this.cookies = cookies;
        this.parent = parent;
        this.trace = trace;
        this.cache = store == null ? new NetCache() : new NetCache(store);
    }

    public Net session() {
        Object object = this.lock;
        synchronized (object) {
            this.checkOpen();
            Net child = new Net(this.client, new SessionCookies(), this, null, trace);
            this.children.add(child);
            return child;
        }
    }

    public String getCookie(String url) {
        HttpUrl target;
        this.checkOpen();
        HttpUrl httpUrl = target = url == null ? null : HttpUrl.parse((String)url);
        if (this.cookies == null || target == null) {
            return "";
        }
        String value = this.cookies.getCookie(target.toString());
        return value == null ? "" : value;
    }

    public boolean setCookie(String url, String value) {
        HttpUrl target;
        this.checkOpen();
        HttpUrl httpUrl = target = url == null ? null : HttpUrl.parse((String)url);
        if (this.cookies == null || target == null || value == null || Cookie.parse((HttpUrl)target, (String)value) == null) {
            return false;
        }
        return this.cookies.setCookie(target.toString(), value);
    }

    private void checkOpen() {
        Object object = this.lock;
        synchronized (object) {
            if (this.closed) {
                throw new IllegalStateException("Network owner closed");
            }
        }
    }

    public void sleep(long milliseconds) throws InterruptedIOException {
        if (milliseconds < 0L) {
            throw new IllegalArgumentException("Sleep duration must not be negative");
        }
        long started = System.nanoTime();
        Object object = this.lock;
        synchronized (object) {
            while (true) {
                if (this.closed) {
                    throw new InterruptedIOException("Network owner closed");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Thread interrupted");
                }
                long remaining = milliseconds - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                if (remaining <= 0L) {
                    return;
                }
                try {
                    this.lock.wait(remaining);
                }
                catch (InterruptedException e) {
                    throw Net.interrupted(e);
                }
            }
        }
    }

    public String cached(String key, String options, Callable<String> loader) throws Exception {
        if (loader == null) {
            throw new IllegalArgumentException("Cache loader is required");
        }
        CompletableFuture<String> result = this.cachedAsync(key, options, () -> this.load(loader));
        try {
            return result.get();
        }
        catch (InterruptedException e) {
            throw Net.interrupted(e);
        }
        catch (ExecutionException error) {
            Throwable throwable = error.getCause();
            if (throwable instanceof Exception) {
                Exception cause = (Exception)throwable;
                throw cause;
            }
            throwable = error.getCause();
            if (throwable instanceof Error) {
                Error cause = (Error)throwable;
                throw cause;
            }
            throw new IllegalStateException(error.getCause());
        }
    }

    private static InterruptedIOException interrupted(InterruptedException cause) {
        Thread.currentThread().interrupt();
        InterruptedIOException error = new InterruptedIOException("Thread interrupted");
        error.initCause(cause);
        return error;
    }

    private CompletableFuture<String> load(Callable<String> loader) {
        CompletableFuture<String> loading = new CompletableFuture<>();
        CallTimeout scope = CallTimeout.current();
        FutureTask<Void> task = new FutureTask<Void>(() -> {
            Runnable restore = scope == null ? () -> {} : scope.enter();
            try {
                loading.complete((String)loader.call());
            }
            catch (Throwable error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                loading.completeExceptionally(error);
            } finally {
                restore.run();
            }
            return null;
        });
        loading.whenComplete((value, error) -> {
            if (loading.isCancelled()) {
                task.cancel(true);
            }
        });
        this.client.dispatcher().executorService().execute(task);
        return loading;
    }

    public CompletableFuture<String> cachedAsync(String key, String options, Supplier<CompletableFuture<String>> loader) {
        Object object = this.lock;
        synchronized (object) {
            if (this.closed) {
                return CompletableFutures.failedFuture(new InterruptedIOException("Network owner closed"));
            }
        }
        CompletableFuture<String> result = this.cache.cached(key, CacheOptions.from(options), loader);
        CallTimeout.trackCurrent(result);
        return result;
    }

    public String peek(String key) {
        this.checkOpen();
        return this.cache.peek(key);
    }

    public String cachedValue(String key, String options) {
        this.checkOpen();
        return this.cache.value(key, CacheOptions.from((String)options));
    }

    public void refresh(String key, String options, Callable<String> loader) {
        if (loader == null) {
            throw new IllegalArgumentException("Cache loader is required");
        }
        CacheOptions config = CacheOptions.from((String)options);
        Object object = this.lock;
        synchronized (object) {
            if (this.closed) {
                SpiderDebug.log(new InterruptedIOException("Network owner closed"));
                return;
            }
        }
        this.cache.cached(key, new CacheOptions(config.ttl, false, config.persist), () -> this.load(loader)).whenComplete((value, error) -> {
            if (error != null) {
                SpiderDebug.log(error);
            }
        });
    }

    public void clearCache(String key) {
        this.cache.clear(key);
    }

    public boolean invalidateCache(String key, String expected) {
        return cache.invalidate(key, expected);
    }

    public void clearCache() {
        this.cache.clear(null);
    }

    public String json(String url, String options) {
        String text = this.jsonText(url, options);
        Json.strict(text);
        return text;
    }

    public String jsonText(String url, String options) {
        NetOptions request = NetOptions.from(options);
        if (request.getBuffer() != 0) {
            throw new IllegalArgumentException("JSON requests require text content");
        }
        Result result = this.request(url, request);
        if (result.error != null) {
            throw new IllegalStateException(result.error);
        }
        int code = (Integer)result.code;
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        return result.text(request);
    }

    public String req(String url, String json) {
        return this.json(url, json, false);
    }

    public String get(String url) {
        return this.get(url, null);
    }

    public String get(String url, Map<String, String> headers) {
        return this.text(url, NetOptions.text("GET", null, headers, 15000));
    }

    public String get(String url, int timeout) {
        return this.text(url, NetOptions.text("GET", null, null, timeout));
    }

    public String post(String url, String json) {
        return this.post(url, json, null);
    }

    public String post(String url, String json, Map<String, String> headers) {
        return this.text(url, NetOptions.text("POST", json, headers, 15000));
    }

    private String text(String url, NetOptions options) {
        Result result = this.request(url, options);
        if (result.error != null) {
            throw new IllegalStateException(result.error);
        }
        return result.text(options);
    }

    public String ws(String url, String json) {
        return this.json(url, json, true);
    }

    private String json(String url, String json, boolean webSocket) {
        NetOptions options = new NetOptions();
        try {
            options = NetOptions.from(json);
            return (webSocket ? this.socket(url, options) : this.request(url, options)).json(options);
        }
        catch (Exception e) {
            return Result.failure(e).json(options);
        }
    }

    public Object[] reqParts(String url, String json) {
        return this.parts(url, json, false);
    }

    public Object[] wsParts(String url, String json) {
        return this.parts(url, json, true);
    }

    private Object[] parts(String url, String json, boolean webSocket) {
        NetOptions options = new NetOptions();
        try {
            options = NetOptions.from(json);
            return (webSocket ? this.socket(url, options) : this.request(url, options)).parts(options);
        }
        catch (Exception e) {
            return Result.failure(e).parts(options);
        }
    }

    private Result socket(String url, NetOptions options) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Result> result = new AtomicReference<>();
        Exchange exchange = this.openSocket(url, options, value -> {
            result.set(value);
            latch.countDown();
        });
        try {
            latch.await();
        }
        catch (InterruptedException e) {
            if (exchange != null) {
                exchange.cancel();
            }
            Thread.currentThread().interrupt();
            return Result.failure(e);
        }
        return (Result)result.get();
    }

    public Result request(String url, NetOptions options) {
        CompletableFuture<Result> future = this.requestAsync(url, options);
        try {
            return future.get();
        }
        catch (InterruptedException e) {
            future.cancel(true);
            return Result.failure(Net.interrupted(e));
        }
        catch (ExecutionException e) {
            return Result.failure(e.getCause());
        }
    }

    public <T, R> List<R> map(List<T> items, Mapper<T, R> mapper) throws Exception {
        return map(items, mapper, 4);
    }

    public <T, R> List<R> map(List<T> items, Mapper<T, R> mapper, int limit) throws Exception {
        if (items == null || mapper == null || limit < 1) throw new IllegalArgumentException("Invalid map options");
        if (items.isEmpty()) {
            checkOpen();
            return new ArrayList<>();
        }
        NetMap<T, R> mapping = createMap(items, mapper, limit);
        try {
            return mapping.start().get();
        } catch (InterruptedException error) {
            mapping.cancel();
            throw interrupted(error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(error.getCause());
        }
    }

    private <T, R> NetMap<T, R> createMap(List<T> items, Mapper<T, R> mapper, int limit) {
        synchronized (lock) {
            checkOpen();
            NetMap<T, R> mapping = new NetMap<>(items, mapper, limit, this::removeMap);
            maps.add(mapping);
            return mapping;
        }
    }

    private void removeMap(NetMap<?, ?> mapping) {
        synchronized (lock) {
            maps.remove(mapping);
        }
    }

    public Result[] batch(String requests) throws IOException {
        return this.batch(requests, 4);
    }

    public Result[] batch(String requests, int limit) throws IOException {
        return this.batch(Json.strict(requests).getAsJsonArray(), limit);
    }

    private Result[] batch(JsonArray requests, int limit) throws IOException {
        if (limit < 1) {
            throw new IllegalArgumentException("Batch limit must be positive");
        }
        for (JsonElement value : requests) {
            if (!value.isJsonObject()) {
                throw new IllegalArgumentException("Invalid batch request");
            }
            JsonObject item = value.getAsJsonObject();
            JsonElement url = item.get("url");
            JsonElement options = item.get("options");
            if (url != null && url.isJsonPrimitive() && url.getAsJsonPrimitive().isString() && (options == null || options.isJsonNull() || options.isJsonObject())) continue;
            throw new IllegalArgumentException("Invalid batch request");
        }
        Result[] results = new Result[requests.size()];
        ArrayList<CompletableFuture<Result>> active = new ArrayList<>();
        try {
            for (int start = 0; start < requests.size(); start += active.size()) {
                int index;
                active.clear();
                for (index = start; index < requests.size() && active.size() < limit; ++index) {
                    JsonObject item = requests.get(index).getAsJsonObject();
                    String url = item.get("url").getAsString();
                    active.add(this.requestAsync(url, NetOptions.from(item.get("options") == null ? null : item.get("options").toString())));
                }
                for (index = 0; index < active.size(); ++index) {
                    results[start + index] = (Result)((CompletableFuture)active.get(index)).get();
                }
            }
            Result[] start = results;
            return start;
        }
        catch (InterruptedException e) {
            throw Net.interrupted(e);
        }
        catch (ExecutionException e) {
            throw new IOException(e.getCause());
        }
        finally {
            for (CompletableFuture completableFuture : active) {
                if (completableFuture.isDone()) continue;
                completableFuture.cancel(true);
            }
        }
    }

    public Object[][] batchParts(String requests, int limit) throws IOException {
        JsonArray items = Json.strict(requests).getAsJsonArray();
        Result[] results = this.batch(items, limit);
        Object[][] parts = new Object[results.length][];
        for (int i = 0; i < parts.length; ++i) {
            JsonElement options = items.get(i).getAsJsonObject().get("options");
            parts[i] = results[i].parts(NetOptions.from(options == null ? null : options.toString()));
        }
        return parts;
    }

    public void download(String url, String json, File file) throws IOException {
        CompletableFuture<String> future = this.downloadAsync(url, json, file);
        try {
            future.get();
        }
        catch (InterruptedException e) {
            future.cancel(true);
            throw Net.interrupted(e);
        }
        catch (ExecutionException e) {
            Throwable throwable = e.getCause();
            if (throwable instanceof IOException) {
                IOException error = (IOException)throwable;
                throw error;
            }
            throw new IOException(e.getCause());
        }
    }

    public CompletableFuture<String> downloadAsync(String url, String json, File file) {
        File target = file.isAbsolute() ? file : new File(Init.context().getCacheDir(), file.getPath());
        NetOptions options;
        Download download;
        Call call;
        try {
            options = NetOptions.from(json);
            download = createDownload(url, target, options);
        } catch (Exception e) {
            return CompletableFutures.failedFuture(e);
        }
        try {
            call = newDownloadCall(download, options);
        } catch (Exception e) {
            closeDownload(download, e);
            return CompletableFutures.failedFuture(e);
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) cancelDownload(call, download);
        });
        CallTimeout.trackCurrent(future);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                failDownload(call, e, download, future);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                finishDownload(call, response, download, target, future);
            }
        });
        return future;
    }

    private Download createDownload(String url, File target, NetOptions options) {
        Download download = new Download(buildRequest(url, options, false), target)
                .maxBytes(options.getMaxBytes())
                .threads(options.getThreads())
                .resume(!usesCookies(options))
                .strictStatus(false)
                .allowEmpty(true);
        if (options.getSize() > 0) download.size(options.getSize());
        return download;
    }

    private Call newDownloadCall(Download download, NetOptions options) throws IOException {
        return newCall(download.request(request -> {
            try {
                return newCall(request, options);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }), options);
    }

    private void failDownload(Call call, IOException e, Download download, CompletableFuture<String> future) {
        closeDownload(download, e);
        remove(call);
        future.completeExceptionally(e);
    }

    private void closeDownload(Download download, Throwable failure) {
        try {
            download.close();
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private void finishDownload(Call call, Response response, Download download, File target, CompletableFuture<String> future) {
        try (download) {
            download.write(call, response, null);
        } catch (Exception e) {
            future.completeExceptionally(response.isSuccessful() ? e : new IOException("HTTP " + response.code(), e));
            return;
        } finally {
            download.releaseCalls(this::remove);
            remove(call);
        }
        future.complete(target.getAbsolutePath());
    }

    private void cancelDownload(Call call, Download download) {
        Throwable failure = null;
        try {
            call.cancel();
        } catch (RuntimeException | Error closing) {
            failure = closing;
        }
        try {
            download.cancel();
        } catch (RuntimeException | Error closing) {
            if (failure == null) failure = closing;
            else if (failure != closing) failure.addSuppressed(closing);
        }
        if (failure instanceof RuntimeException closing) throw closing;
        if (failure instanceof Error closing) throw closing;
    }

    public void enqueue(String url, NetOptions options, Consumer<Result> complete) {
        this.requestAsync(url, options).whenComplete((result, error) -> complete.accept(error == null ? result : Result.failure(error)));
    }

    public CompletableFuture<Result> requestAsync(String url, NetOptions options) {
        Call call;
        final CompletableFuture<Result> future = new CompletableFuture<>();
        try {
            call = this.newCall(url, options);
        }
        catch (Exception e) {
            return CompletableFuture.completedFuture(Result.failure(e));
        }
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) {
                call.cancel();
            }
        });
        CallTimeout.trackCurrent(future);
        call.enqueue(new Callback(){

            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Net.this.remove(call);
                future.complete(Result.failure(call, e));
            }

            public void onResponse(@NonNull Call call, @NonNull Response response) {
                Result result;
                try (Response response2 = response;){
                    result = Result.read(response);
                }
                catch (Exception e) {
                    result = Result.failure(call, e);
                }
                finally {
                    Net.this.remove(call);
                }
                future.complete(result);
            }
        });
        return future;
    }

    public StreamResult openStream(String url, String json) throws IOException {
        CompletableFuture<StreamResult> future = requestStreamAsync(url, NetOptions.from(json));
        try {
            return future.get();
        } catch (InterruptedException error) {
            future.cancel(true);
            throw interrupted(error);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof IOException cause) throw cause;
            throw new IOException(error.getCause());
        }
    }

    public Object[] stream(String url, String json) throws IOException {
        StreamResult stream = openStream(url, json);
        Map<String, String> headers = new LinkedHashMap<>();
        stream.headers.forEach((key, value) -> headers.put(key, value instanceof List<?> values
                ? String.join(", ", values.stream().map(Object::toString).toList()) : value.toString()));
        return new Object[]{stream.code, stream.contentType, stream, headers};
    }

    public CompletableFuture<StreamResult> requestStreamAsync(String url, NetOptions options) {
        Call call;
        try {
            call = newCall(url, options);
        } catch (Exception error) {
            return CompletableFutures.failedFuture(error);
        }
        CompletableFuture<StreamResult> future = new CompletableFuture<>();
        future.whenComplete((result, error) -> {
            if (future.isCancelled()) call.cancel();
        });
        CallTimeout.trackCurrent(future);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                remove(call);
                future.completeExceptionally(error);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                acceptStream(call, response, future);
            }
        });
        return future;
    }

    private void acceptStream(Call call, Response response, CompletableFuture<StreamResult> future) {
        StreamResult result;
        try {
            result = new StreamResult(call, response);
        } catch (RuntimeException | Error error) {
            try { response.close(); }
            catch (RuntimeException | Error closing) { if (closing != error) error.addSuppressed(closing); }
            remove(call);
            future.completeExceptionally(error);
            return;
        }
        boolean accepted;
        synchronized (lock) {
            accepted = !closed && !call.isCanceled();
            if (accepted) streams.add(result);
        }
        if (!accepted) {
            InterruptedIOException error = new InterruptedIOException("Network request canceled");
            try {
                result.close();
            } catch (RuntimeException | Error closing) {
                error.addSuppressed(closing);
            }
            future.completeExceptionally(error);
        } else if (!future.complete(result)) {
            result.close();
        }
    }

    public void webSocket(String url, NetOptions options, Consumer<Result> complete) {
        this.openSocket(url, options, complete);
    }

    public CompletableFuture<Result> socketAsync(String url, NetOptions options) {
        CompletableFuture<Result> future = new CompletableFuture<>();
        Exchange exchange = this.openSocket(url, options, future::complete);
        future.whenComplete((result, error) -> {
            if (future.isCancelled() && exchange != null) {
                exchange.cancel();
            }
        });
        CallTimeout.trackCurrent(future);
        return future;
    }

    private Call newCall(String url, NetOptions options) throws IOException {
        return newCall(buildRequest(url, options, false), options);
    }

    private Call newCall(Request request, NetOptions options) throws IOException {
        OkHttpClient http = this.builder(options).addNetworkInterceptor(chain -> this.interceptCookies(chain, request, options)).build();
        Call call = http.newCall(request.newBuilder().tag(NetOptions.class, options).build());
        Object object = this.lock;
        synchronized (object) {
            if (this.closed) {
                throw new InterruptedIOException("Network owner closed");
            }
            this.calls.add(call);
        }
        return call;
    }

    private Response interceptCookies(Interceptor.Chain chain, Request request, NetOptions options) throws IOException {
        Request next = this.withCookies(chain.request(), request.url(), request.header("Cookie"), options);
        Response response = chain.proceed(next);
        try {
            this.saveCookies(response, options);
            return response;
        }
        catch (IOException | RuntimeException e) {
            response.close();
            throw e;
        }
    }

    private OkHttpClient.Builder builder(NetOptions options) {
        OkHttpClient.Builder builder = client.newBuilder()
                .connectTimeout(options.getTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(options.getTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(options.getTimeout(), TimeUnit.MILLISECONDS)
                .callTimeout(options.getCallTimeout(), TimeUnit.MILLISECONDS)
                .followSslRedirects(options.isRedirect());
        // The shared client chooses a proxy again on every redirect. Native redirects
        // would bypass that interceptor and retain the previous host's proxy policy.
        boolean routedRedirects = builder.interceptors().stream().anyMatch(item -> item instanceof ProxyRedirectInterceptor);
        if (!options.isRedirect()) builder.interceptors().removeIf(item -> item instanceof ProxyRedirectInterceptor);
        if (trace != null) builder.eventListenerFactory(trace::listener);
        return builder.followRedirects(options.isRedirect() && !routedRedirects);
    }

    private Request buildRequest(String url, NetOptions options, boolean socket) {
        options.getBuffer();
        Headers headers = Headers.of(options.getHeader());
        Request.Builder request = new Request.Builder().url(url).headers(headers);
        if (options.getData() != null && options.getRawBody() != null) {
            throw new IllegalArgumentException("data and body are mutually exclusive");
        }
        if (socket) {
            // WebSocket body remains text; reject HTTP byte arrays before opening a connection.
            options.getBody();
        } else {
            String method = this.requestMethod(options);
            request.method(method, this.requestBody(method, options, headers));
        }
        return this.withParams(request.build(), options.getParams());
    }

    private String requestMethod(NetOptions options) {
        String method = options.getMethod().toUpperCase(Locale.ROOT);
        if (method.equals("HEADER")) {
            method = "HEAD";
        }
        if (!METHODS.contains(method)) {
            throw new IllegalArgumentException("Unsupported method");
        }
        return method;
    }

    private RequestBody requestBody(String method, NetOptions options, Headers headers) {
        boolean accepts;
        JsonElement data = options.getData();
        JsonElement raw = options.getRawBody();
        String postType = options.getPostType();
        if (!POST_TYPES.contains(postType)) {
            throw new IllegalArgumentException("Unsupported postType");
        }
        boolean required = method.equals("POST") || method.equals("PUT") || method.equals("PATCH");
        boolean supplied = data != null || raw != null;
        boolean bl = accepts = !method.equals("GET") && !method.equals("HEAD");
        if (!accepts && supplied) {
            throw new IllegalArgumentException("Method does not accept a body");
        }
        if (data != null && !postType.equals("json") && !data.isJsonObject()) {
            throw new IllegalArgumentException("Form data must be an object");
        }
        if (raw != null && headers.get("Content-Type") == null) {
            throw new IllegalArgumentException("Raw body requires Content-Type");
        }
        if (!accepts || !required && !supplied) {
            return null;
        }
        return this.encodeBody(options, headers.get("Content-Type"));
    }

    private Request withParams(Request request, JsonElement params) {
        if (params == null || params.isJsonNull()) {
            return request;
        }
        if (!params.isJsonObject()) {
            throw new IllegalArgumentException("params must be an object");
        }
        HttpUrl.Builder url = request.url().newBuilder();
        for (Map.Entry<String, JsonElement> entry : params.getAsJsonObject().entrySet()) {
            JsonElement value = (JsonElement)entry.getValue();
            if (value.isJsonArray()) {
                for (JsonElement item : value.getAsJsonArray()) {
                    this.addParam(url, (String)entry.getKey(), item);
                }
                continue;
            }
            this.addParam(url, (String)entry.getKey(), value);
        }
        return request.newBuilder().url(url.build()).build();
    }

    private void addParam(HttpUrl.Builder url, String key, JsonElement value) {
        if (value.isJsonNull()) {
            return;
        }
        if (!value.isJsonPrimitive()) {
            throw new IllegalArgumentException("Query values must be scalar");
        }
        url.addQueryParameter(key, value.getAsString());
    }

    private RequestBody encodeBody(NetOptions options, String contentType) {
        JsonElement data = options.getData();
        JsonElement raw = options.getRawBody();
        if (data == null) {
            return raw == null ? RequestBody.create(new byte[0]) : rawBody(raw, MediaType.get(contentType));
        }
        return switch (options.getPostType()) {
            case "json" -> RequestBody.create((String)data.toString(), (MediaType)MediaType.get((String)"application/json; charset=utf-8"));
            case "form" -> {
                FormBody.Builder body = new FormBody.Builder();
                NetOptions.toMap(data).forEach((arg_0, arg_1) -> ((FormBody.Builder)body).add(arg_0, arg_1));
                yield body.build();
            }
            case "form-data" -> {
                MultipartBody.Builder body = new MultipartBody.Builder().setType(MultipartBody.FORM);
                NetOptions.toMap(data).forEach((arg_0, arg_1) -> ((MultipartBody.Builder)body).addFormDataPart(arg_0, arg_1));
                yield body.build();
            }
            default -> throw new IllegalArgumentException("Unsupported postType");
        };
    }

    private RequestBody rawBody(JsonElement raw, MediaType type) {
        if (raw.isJsonPrimitive()) return RequestBody.create(raw.getAsString(), type);
        if (!raw.isJsonArray()) throw new IllegalArgumentException("Raw body must be text or bytes");
        JsonArray values = raw.getAsJsonArray();
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) bytes[i] = rawByte(values.get(i));
        return RequestBody.create(bytes, type);
    }

    private byte rawByte(JsonElement item) {
        if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Raw body bytes must be integers from 0 to 255");
        }
        int value;
        try {
            value = item.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("Raw body bytes must be integers from 0 to 255", error);
        }
        if (value < 0 || value > 255) {
            throw new IllegalArgumentException("Raw body bytes must be integers from 0 to 255");
        }
        return (byte) value;
    }

    private Request withCookies(Request request, HttpUrl origin, String explicit, NetOptions options) throws IOException {
        if (explicit == null && !this.usesCookies(options)) {
            return request;
        }
        Request.Builder builder = request.newBuilder().removeHeader("Cookie");
        String value = null;
        if (explicit != null && this.sameOrigin(origin, request.url())) {
            value = explicit;
        } else if (this.usesCookies(options)) {
            try {
                value = this.getCookie(request.url().toString());
            }
            catch (RuntimeException e) {
                throw this.cookieFailure(e);
            }
        }
        if (value != null && !value.isEmpty()) {
            builder.header("Cookie", value);
        }
        return builder.build();
    }

    void saveCookies(Response response, NetOptions options) throws IOException {
        if (!this.usesCookies(options)) {
            return;
        }
        HttpUrl url = response.request().url();
        try {
            for (String value : response.headers("Set-Cookie")) {
                this.setCookie(url.toString(), value);
            }
        }
        catch (RuntimeException e) {
            throw this.cookieFailure(e);
        }
    }

    private IOException cookieFailure(RuntimeException cause) {
        Object object = this.lock;
        synchronized (object) {
            IOException error = this.closed ? new InterruptedIOException("Network owner closed") : new IOException("Cookie provider failed");
            error.initCause(cause);
            return error;
        }
    }

    private boolean usesCookies(NetOptions options) {
        return this.parent != null || options.isCookie();
    }

    private boolean sameOrigin(HttpUrl first, HttpUrl second) {
        return first.scheme().equals(second.scheme()) && first.host().equals(second.host()) && first.port() == second.port();
    }

    private Exchange openSocket(String url, NetOptions options, Consumer<Result> complete) {
        Exchange exchange = null;
        try {
            if (options.getTimeout() <= 0) {
                throw new IllegalArgumentException("WebSocket requires a deadline");
            }
            Request request = this.buildRequest(url, options, true);
            request = this.withCookies(request, request.url(), request.header("Cookie"), options);
            OkHttpClient socket = this.socketClient(options);
            exchange = new Exchange(options, complete);
            Object object = this.lock;
            synchronized (object) {
                if (this.closed) {
                    throw new InterruptedIOException("Network owner closed");
                }
                this.sockets.add(exchange);
            }
            exchange.start(socket, request);
            return exchange;
        }
        catch (Exception e) {
            if (exchange != null) {
                exchange.finish(Result.failure(e));
            } else {
                complete.accept(Result.failure(e));
            }
            return null;
        }
    }

    public NetSocket connect(String url, String json, NetSocket.Listener listener) throws IOException {
        NetOptions options = NetOptions.from(json);
        if (listener == null) throw new IllegalArgumentException("WebSocket requires a listener");
        if (options.getTimeout() <= 0) throw new IllegalArgumentException("WebSocket requires a connect deadline");
        Request request = socketRequest(url, options);
        OkHttpClient client = socketClient(options).newBuilder().pingInterval(options.getPing(), TimeUnit.MILLISECONDS).build();
        NetSocket socket = new NetSocket(this, url, options, listener);
        synchronized (lock) {
            checkOpen();
            connections.add(socket);
        }
        try {
            socket.start(client, request);
            return socket;
        } catch (RuntimeException e) {
            socket.cancel();
            throw e;
        }
    }

    Request socketRequest(String url, NetOptions options) throws IOException {
        Request request = buildRequest(url, options, true);
        return withCookies(request, request.url(), request.header("Cookie"), options);
    }

    void remove(NetSocket socket) {
        synchronized (lock) {
            connections.remove(socket);
        }
    }

    private OkHttpClient socketClient(NetOptions options) {
        // Keep the application's DNS, ECH socket factory and proxy authentication.
        OkHttpClient.Builder builder = builder(options).followRedirects(false)
                .followSslRedirects(false).retryOnConnectionFailure(false)
                .webSocketCloseTimeout(Math.min(options.getTimeout(), 1000), TimeUnit.MILLISECONDS);
        builder.interceptors().removeIf(item -> item instanceof ProxyRedirectInterceptor);
        return builder.build();
    }

    private void remove(Call call) {
        Object object = this.lock;
        synchronized (object) {
            this.calls.remove(call);
        }
    }

    @Override
    public void close() {
        List<Call> pending;
        List<StreamResult> streaming;
        List<Exchange> active;
        List<NetSocket> connected;
        List<Net> sessions;
        List<NetMap<?, ?>> mappings;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            lock.notifyAll();
            pending = new ArrayList<>(calls);
            streaming = new ArrayList<>(streams);
            active = new ArrayList<>(sockets);
            connected = new ArrayList<>(connections);
            sessions = new ArrayList<>(children);
            mappings = new ArrayList<>(maps);
            maps.clear();
            children.clear();
        }
        cache.close();
        for (NetMap<?, ?> mapping : mappings) mapping.cancel();
        if (cookies instanceof SessionCookies local) local.close();
        Throwable failure = null;
        failure = cancelCalls(pending, failure);
        failure = closeStreams(streaming, failure);
        failure = cancelExchanges(active, failure);
        failure = cancelSockets(connected, failure);
        failure = closeSessions(sessions, failure);
        if (parent != null) {
            synchronized (parent.lock) {
                parent.children.remove(this);
            }
        }
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private Throwable cancelCalls(List<Call> pending, Throwable failure) {
        for (Call call : pending) {
            try {
                call.cancel();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private Throwable closeStreams(List<StreamResult> streaming, Throwable failure) {
        for (StreamResult stream : streaming) {
            try {
                stream.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private Throwable cancelExchanges(List<Exchange> active, Throwable failure) {
        for (Exchange exchange : active) {
            try {
                exchange.cancel();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private Throwable cancelSockets(List<NetSocket> connected, Throwable failure) {
        for (NetSocket socket : connected) {
            try {
                socket.cancel();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        return failure;
    }

    private Throwable closeSessions(List<Net> sessions, Throwable failure) {
        for (Net session : sessions) {
            try {
                session.close();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        return failure;
    }

    @Keep
    public interface Mapper<T, R> {
        R apply(T item, int index) throws Exception;
    }

    @Keep
    public static interface Cookies {
        public String getCookie(String var1);

        public boolean setCookie(String var1, String var2);
    }

    private static final class SessionCookies
    implements Cookies {
        private final List<Cookie> values = new ArrayList<>();
        private boolean closed;

        private SessionCookies() {
        }

        @Override
        public synchronized String getCookie(String url) {
            this.checkOpen();
            HttpUrl target = HttpUrl.get((String)url);
            this.values.removeIf(cookie -> cookie.expiresAt() <= System.currentTimeMillis());
            ArrayList<Cookie> matching = new ArrayList<>();
            for (Cookie cookie2 : this.values) {
                if (!cookie2.matches(target)) continue;
                matching.add(cookie2);
            }
            matching.sort(Comparator.comparingInt((Cookie cookie) -> cookie.path().length()).reversed());
            ArrayList<String> result = new ArrayList<>();
            for (Cookie cookie3 : matching) {
                result.add(cookie3.name() + "=" + cookie3.value());
            }
            return String.join((CharSequence)"; ", result);
        }

        @Override
        public synchronized boolean setCookie(String url, String value) {
            this.checkOpen();
            Cookie cookie = Cookie.parse((HttpUrl)HttpUrl.get((String)url), (String)value);
            if (cookie == null) {
                return false;
            }
            for (int i = 0; i < this.values.size(); ++i) {
                Cookie previous = this.values.get(i);
                if (!previous.name().equals(cookie.name()) || !previous.domain().equals(cookie.domain()) || !previous.path().equals(cookie.path())) continue;
                if (cookie.expiresAt() <= System.currentTimeMillis()) {
                    this.values.remove(i);
                } else {
                    this.values.set(i, cookie);
                }
                return true;
            }
            if (cookie.expiresAt() > System.currentTimeMillis()) {
                this.values.add(cookie);
            }
            return true;
        }

        private synchronized void close() {
            this.closed = true;
            this.values.clear();
        }

        private void checkOpen() {
            if (this.closed) {
                throw new IllegalStateException("Network owner closed");
            }
        }
    }

    @Keep
    public final class StreamResult extends InputStream {
        public final int code;
        public final Map<String, Object> headers;
        public final String contentType;
        private final Call call;
        private final Response response;
        private final InputStream input;
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean reading = new AtomicBoolean();
        private final AtomicBoolean bodyClosed = new AtomicBoolean();

        private StreamResult(Call call, Response response) {
            this.call = call;
            this.response = response;
            this.input = response.body().byteStream();
            this.code = response.code();
            this.headers = Result.headers(response.headers());
            this.contentType = response.header("Content-Type", "application/octet-stream");
        }

        @Override
        public int read() throws IOException {
            beginRead();
            try {
                return finishRead(input.read());
            } catch (IOException | RuntimeException | Error error) {
                failRead(error);
                throw error;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int capacity = bytes.length;
            if (offset < 0 || length < 0 || length > capacity - offset) throw new IndexOutOfBoundsException();
            if (finished.get()) throw new IOException("Response stream closed");
            if (length == 0) return 0;
            beginRead();
            try {
                return finishRead(input.read(bytes, offset, length));
            } catch (IOException | RuntimeException | Error error) {
                failRead(error);
                throw error;
            }
        }

        @Override
        public void close() {
            finish(true);
        }

        public CompletableFuture<Integer> readAsync(ByteBuffer bytes) {
            if (bytes == null || bytes.isReadOnly() || !bytes.hasRemaining() || bytes.remaining() > 1024 * 1024) throw new IllegalArgumentException("Read requires a writable range of 1..1048576 bytes");
            if (!reading.compareAndSet(false, true)) return CompletableFutures.failedFuture(new IllegalStateException("Stream read already pending"));
            if (finished.get()) {
                endRead();
                return CompletableFutures.failedFuture(new IOException("Response stream closed"));
            }
            CompletableFuture<Integer> result = new CompletableFuture<>();
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) close();
            });
            CallTimeout.trackCurrent(result);
            try {
                client.dispatcher().executorService().execute(() -> readInto(bytes, result));
            } catch (RuntimeException error) {
                failRead(error);
                result.completeExceptionally(error);
            }
            return result;
        }

        private void readInto(ByteBuffer bytes, CompletableFuture<Integer> result) {
            try {
                if (finished.get()) throw new IOException("Response stream closed");
                int size = response.body().source().read(bytes);
                if (finished.get()) throw new IOException("Response stream closed");
                result.complete(finishRead(size));
            } catch (Throwable error) {
                failRead(error);
                result.completeExceptionally(error);
            }
        }

        private void beginRead() throws IOException {
            if (!reading.compareAndSet(false, true)) throw new IOException("Stream read already pending");
            if (finished.get()) {
                endRead();
                throw new IOException("Response stream closed");
            }
        }

        private void endRead() {
            reading.set(false);
            closeBody();
        }

        private int finishRead(int size) {
            if (size == -1) finish(false);
            endRead();
            return size;
        }

        private void failRead(Throwable error) {
            try {
                close();
            } catch (Throwable closing) {
                if (closing != error) error.addSuppressed(closing);
            } finally {
                endFailedRead(error);
            }
        }

        private void endFailedRead(Throwable error) {
            try {
                endRead();
            } catch (Throwable closing) {
                if (closing != error) error.addSuppressed(closing);
            }
        }

        private void closeBody() {
            if (finished.get() && !reading.get() && bodyClosed.compareAndSet(false, true)) response.close();
        }

        private void finish(boolean cancel) {
            if (!finished.compareAndSet(false, true)) return;
            Throwable failure = null;
            try {
                if (cancel) call.cancel();
            } catch (RuntimeException | Error error) {
                failure = error;
            }
            synchronized (lock) {
                streams.remove(this);
                calls.remove(call);
            }
            try {
                closeBody();
            } catch (RuntimeException | Error error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
            if (failure instanceof RuntimeException error) throw error;
            if (failure instanceof Error error) throw error;
        }
    }

    public static final class Result {
        private static final Gson GSON = new Gson();
        public final Object code;
        public final Map<String, Object> headers;
        public final byte[] body;
        public final String error;
        private final boolean http;

        private Result(Object code, Map<String, Object> headers, byte[] body, String error, boolean http) {
            this.code = code;
            this.headers = headers;
            this.body = body;
            this.error = error;
            this.http = http;
        }

        public static Result failure(Throwable error) {
            String message = "Network request failed";
            if (error instanceof SSLException) {
                message = "TLS verification failed";
            } else if (Result.isTimeout(error)) {
                message = "Timed out";
            } else if (error instanceof InterruptedIOException || error instanceof InterruptedException || error instanceof CancellationException) {
                message = "Canceled";
            } else if (error instanceof UnknownHostException) {
                message = "Host resolution failed";
            } else if (error instanceof IllegalArgumentException) {
                message = "Invalid request options";
            }
            return Result.failure(error.getClass().getSimpleName() + ": " + message);
        }

        private static Result failure(Call call, Throwable error) {
            return Result.failure(call != null && call.isCanceled() && !Result.isTimeout(error) ? new InterruptedIOException() : error);
        }

        private static boolean isTimeout(Throwable error) {
            return error instanceof SocketTimeoutException || error instanceof InterruptedIOException && "timeout".equals(error.getMessage());
        }

        private static Result failure(String error) {
            return new Result("", new LinkedHashMap<>(), new byte[0], error, false);
        }

        private static Result read(Response response) throws IOException {
            return Result.success(response.code(), response.headers(), response.body().bytes(), true);
        }

        private static Result success(int code, Headers headers, byte[] body, boolean http) {
            return new Result(code, headers(headers), body, null, http);
        }

        private static Map<String, Object> headers(Headers headers) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : headers.toMultimap().entrySet()) {
                values.put(entry.getKey(), entry.getValue().size() == 1 ? entry.getValue().get(0) : entry.getValue());
            }
            return values;
        }

        private static Result handshakeFailure(Response response) {
            Result result = Result.success(response.code(), response.headers(), new byte[0], true);
            return new Result(result.code, result.headers, result.body, "WebSocket handshake failed (HTTP " + response.code() + ")", false);
        }

        public String json(NetOptions options) {
            Map<String, Object> result = this.metadata();
            result.put("content", this.content(options));
            return GSON.toJson(result);
        }

        public Object[] parts(NetOptions options) {
            return new Object[]{GSON.toJson(this.metadata()), this.content(options)};
        }

        private Map<String, Object> metadata() {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            result.put("code", this.code);
            result.put("headers", this.headers);
            if (this.error != null) {
                result.put("error", this.error);
            }
            return result;
        }

        private Object content(NetOptions options) {
            if (this.error != null) {
                return "";
            }
            return switch (options.getBuffer()) {
                case 0 -> this.text(options);
                case 1, 3 -> this.body;
                case 2 -> ByteString.of((byte[])this.body).base64();
                default -> "";
            };
        }

        public String text() {
            return this.text(new NetOptions());
        }

        private String text(NetOptions options) {
            if (!http) return new String(body, Charset.forName(options.getCharset()));
            try (ResponseBody response = ResponseBody.create(body, contentType())) {
                return response.string();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private MediaType contentType() {
            for (Map.Entry<String, Object> entry : this.headers.entrySet()) {
                if (!entry.getKey().equalsIgnoreCase("Content-Type")) continue;
                Object value = entry.getValue();
                if (value instanceof List) {
                    List values = (List)value;
                    value = values.get(values.size() - 1);
                }
                return MediaType.parse((String)String.valueOf(value));
            }
            return null;
        }
    }

    private final class Exchange
    extends WebSocketListener {
        private final NetOptions options;
        private final Consumer<Result> complete;
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile WebSocket socket;
        private volatile ScheduledFuture<?> deadline;
        private volatile Headers headers = new Headers.Builder().build();
        private volatile boolean closing;

        private Exchange(NetOptions options, Consumer<Result> complete) {
            this.options = options;
            this.complete = complete;
        }

        private void start(OkHttpClient client, Request request) {
            WebSocket created;
            if (this.done.get()) {
                return;
            }
            this.deadline = DEADLINES.schedule(() -> {
                this.finish(Result.failure(new SocketTimeoutException()));
                this.cancel();
            }, (long)this.options.getTimeout(), TimeUnit.MILLISECONDS);
            if (this.done.get()) {
                this.deadline.cancel(false);
                return;
            }
            this.socket = created = client.newWebSocket(request, (WebSocketListener)this);
            if (this.done.get() && !this.closing) {
                created.cancel();
            }
        }

        public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
            String message;
            this.socket = webSocket;
            this.headers = response.headers();
            try {
                Net.this.saveCookies(response, this.options);
                if (this.done.get()) {
                    webSocket.cancel();
                    return;
                }
                message = this.message();
            }
            catch (Exception e) {
                this.finish(Result.failure(e));
                return;
            }
            if (message != null && !webSocket.send(message)) {
                this.finish(Result.failure("IOException: WebSocket send failed"));
            }
        }

        private String message() {
            JsonElement data = this.options.getData();
            if (data == null) {
                return this.options.getBody();
            }
            if (data.isJsonPrimitive() && data.getAsJsonPrimitive().isString()) {
                return data.getAsString();
            }
            return data.toString();
        }

        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            this.finish(Result.success(101, this.headers, text.getBytes(StandardCharsets.UTF_8), false));
        }

        public void onMessage(@NonNull WebSocket webSocket, @NonNull ByteString bytes) {
            this.finish(Result.success(101, this.headers, bytes.toByteArray(), false));
        }

        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            this.finish(Result.failure("IOException: WebSocket closed before a message"));
            webSocket.close(code, reason);
        }

        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            this.finish(Result.failure("IOException: WebSocket closed before a message"));
            this.release();
        }

        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable error, @Nullable Response response) {
            this.finish(this.failure(error, response));
            this.release();
        }

        private Result failure(Throwable error, Response response) {
            if (response == null) return Result.failure(error);
            try (response) {
                Net.this.saveCookies(response, options);
                return Result.handshakeFailure(response);
            } catch (Exception e) {
                return Result.failure(e);
            }
        }

        private void cancel() {
            this.finish(Result.failure(new InterruptedIOException()));
            WebSocket current = this.socket;
            if (current != null) {
                current.cancel();
            }
            this.release();
        }

        private void finish(Result result) {
            if (!this.done.compareAndSet(false, true)) {
                return;
            }
            this.closing = result.error == null;
            WebSocket current = this.socket;
            if (current != null) {
                if (this.closing) {
                    current.close(1000, null);
                } else {
                    current.cancel();
                }
            }
            if (!this.closing) {
                this.release();
            }
            this.complete.accept(result);
        }

        private void release() {
            ScheduledFuture<?> timer = this.deadline;
            if (timer != null) {
                timer.cancel(false);
            }
            Object object = Net.this.lock;
            synchronized (object) {
                Net.this.sockets.remove((Object)this);
            }
        }
    }

}
