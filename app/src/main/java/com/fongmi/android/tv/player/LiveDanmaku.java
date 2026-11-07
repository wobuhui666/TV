package com.fongmi.android.tv.player;

import android.net.Uri;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.media3.ui.danmaku.Danmaku;

import com.fongmi.android.tv.api.SiteApi;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Owns one player's live comment subscription and rejects events from a previous source. */
public final class LiveDanmaku implements AutoCloseable {

    interface Subscriber {
        AutoCloseable subscribe(String source, Consumer<JSONObject> emit) throws Exception;
    }

    private final Consumer<Danmaku> emit;
    private final BiConsumer<String, String> status;
    private final Subscriber subscriber;
    private final Executor delivery;
    private volatile long generation;
    private volatile boolean closed;
    private boolean enabled = true;
    private boolean attached = true;
    private String source;
    private ExecutorService executor;
    private Future<?> setup;
    private AutoCloseable subscription;
    private String state = "";
    private String message = "";

    public LiveDanmaku(Consumer<Danmaku> emit, BiConsumer<String, String> status, Executor delivery) {
        this(emit, status, (source, listener) -> SiteApi.danmaku(Uri.parse(source), listener), delivery);
    }

    LiveDanmaku(Consumer<Danmaku> emit, BiConsumer<String, String> status, Subscriber subscriber, Executor delivery) {
        this.emit = Objects.requireNonNull(emit);
        this.status = Objects.requireNonNull(status);
        this.subscriber = Objects.requireNonNull(subscriber);
        this.delivery = Objects.requireNonNull(delivery);
    }

    public static boolean isLive(@Nullable Uri uri) {
        return uri != null && "spider".equalsIgnoreCase(uri.getScheme());
    }

    @MainThread
    public void setDataSource(@Nullable Uri uri) {
        setSource(uri == null ? null : uri.toString());
    }

    @MainThread
    public void setDataSource(@Nullable Uri uri, boolean attached) {
        setSource(uri == null ? null : uri.toString(), attached);
    }

    synchronized void setSource(@Nullable String source) {
        setSource(source, attached);
    }

    synchronized void setSource(@Nullable String source, boolean attached) {
        if (closed || (Objects.equals(this.source, source) && this.attached == attached)) return;
        this.source = source;
        this.attached = attached;
        restart();
    }

    @MainThread
    public synchronized void setAttached(boolean attached) {
        if (closed || this.attached == attached) return;
        this.attached = attached;
        if (source != null) restart();
    }

    @MainThread
    public synchronized void setEnabled(boolean enabled) {
        if (closed || this.enabled == enabled) return;
        this.enabled = enabled;
        if (source != null) restart();
    }

    private void restart() {
        long token = ++generation;
        String next = enabled && attached ? source : null;
        updateState(next == null ? "" : "connecting", "");
        if (setup != null && !setup.isDone()) setup.cancel(true);
        if (executor == null) executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Live danmaku"));
        setup = executor.submit(() -> subscribe(token, next));
    }

    private boolean isCurrent(long token) {
        return !closed && generation == token;
    }

    private void subscribe(long token, @Nullable String source) {
        if (!isCurrent(token)) return;
        closeSubscription();
        if (source == null) return;
        try {
            AutoCloseable created = subscriber.subscribe(source, item -> receive(token, item));
            boolean accepted;
            synchronized (this) {
                accepted = isCurrent(token);
                if (accepted) subscription = created;
            }
            if (!accepted) close(created);
        } catch (Exception error) {
            fail(token, error);
        }
    }

    private void receive(long token, JSONObject item) {
        if (!isCurrent(token)) return;
        try {
            Objects.requireNonNull(item, "Danmaku item");
            if (item.has("state")) {
                String nextState = parseState(item);
                String nextMessage = parseMessage(item);
                delivery.execute(() -> {
                    synchronized (this) {
                        if (!isCurrent(token)) return;
                        if (nextState.equals("error")) finish(nextMessage);
                        else updateState(nextState, nextMessage);
                    }
                });
            } else {
                Danmaku parsed = parseItem(item);
                delivery.execute(() -> {
                    synchronized (this) {
                        if (!isCurrent(token)) return;
                        try {
                            emit.accept(parsed);
                        } catch (RuntimeException error) {
                            fail(token, error);
                        }
                    }
                });
            }
        } catch (RuntimeException error) {
            fail(token, error);
        }
    }

    private synchronized void fail(long token, Exception error) {
        if (!isCurrent(token)) return;
        String text = error.getMessage();
        if (text == null || text.isBlank()) text = error.getClass().getSimpleName();
        finish(text.substring(0, Math.min(text.length(), 512)));
    }

    private void finish(String message) {
        generation++;
        if (setup != null && !setup.isDone()) setup.cancel(true);
        if (executor != null) executor.execute(this::closeSubscription);
        updateState("error", message);
    }

    private void updateState(String state, String message) {
        if (this.state.equals(state) && this.message.equals(message)) return;
        this.state = state;
        this.message = message;
        status.accept(state, message);
    }

    private static String parseState(JSONObject item) {
        Object value = item.opt("state");
        if (item.has("text") || !(value instanceof String state)
                || !(state.equals("ready") || state.equals("retrying") || state.equals("error")))
            throw new IllegalArgumentException("Invalid danmaku state");
        return state;
    }

    private static String parseMessage(JSONObject item) {
        if (!item.has("msg")) return "";
        if (!(item.opt("msg") instanceof String text) || text.length() > 512)
            throw new IllegalArgumentException("Invalid danmaku message");
        return text;
    }

    static Danmaku parseItem(JSONObject item) {
        if (!(item.opt("text") instanceof String text) || text.isBlank() || text.length() > 4096)
            throw new IllegalArgumentException("Danmaku requires text up to 4096 characters");
        double typeValue = number(item, "type", Danmaku.TYPE_SCROLL);
        int type = (int) typeValue;
        if (typeValue != type || !(type == Danmaku.TYPE_SCROLL || type == Danmaku.TYPE_BOTTOM
                || type == Danmaku.TYPE_TOP || type == Danmaku.TYPE_REVERSE))
            throw new IllegalArgumentException("Invalid danmaku type");
        double size = number(item, "size", 0);
        if (size < 0 || size > 96) throw new IllegalArgumentException("Invalid danmaku size");
        return new Danmaku(text, 0, type, color(number(item, "color", 0xffffff)), (float) size, colors(item));
    }

    private static double number(JSONObject item, String key, double fallback) {
        if (!item.has(key)) return fallback;
        if (!(item.opt(key) instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Invalid danmaku " + key);
        return value.doubleValue();
    }

    private static int color(double value) {
        if (value < 0 || value > 0xffffff || value != (int) value)
            throw new IllegalArgumentException("Invalid danmaku color");
        return 0xff000000 | (int) value;
    }

    @Nullable
    private static int[] colors(JSONObject item) {
        if (!item.has("colors")) return null;
        if (!(item.opt("colors") instanceof JSONArray values) || values.length() < 2 || values.length() > 8)
            throw new IllegalArgumentException("Danmaku colors requires 2 to 8 RGB values");
        int[] colors = new int[values.length()];
        for (int index = 0; index < colors.length; index++) {
            if (!(values.opt(index) instanceof Number value)) throw new IllegalArgumentException("Invalid danmaku color");
            colors[index] = color(value.doubleValue());
        }
        return colors;
    }

    private void closeSubscription() {
        AutoCloseable previous;
        synchronized (this) {
            previous = subscription;
            subscription = null;
        }
        close(previous);
    }

    private static void close(@Nullable AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception error) {
            SpiderDebug.log(error);
        }
    }

    @Override
    @MainThread
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation++;
        if (setup != null && !setup.isDone()) setup.cancel(true);
        updateState("", "");
        if (executor == null) return;
        executor.execute(this::closeSubscription);
        executor.shutdown();
    }
}
