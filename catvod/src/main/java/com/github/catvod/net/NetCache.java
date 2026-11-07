package com.github.catvod.net;

import com.github.catvod.net.CacheOptions;
import com.github.catvod.net.NetStore;
import com.github.catvod.utils.CompletableFutures;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class NetCache
implements AutoCloseable {
    private static final int CAPACITY = 64;
    private static final long MAX_BYTES = 0x1000000L;
    private final LongSupplier clock;
    private final LongSupplier wallClock;
    private final Function<String, String> read;
    private final BiConsumer<String, String> write;
    private final Consumer<String> remove;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<Flight> pending = new HashSet<>();
    private boolean closed;

    NetCache() {
        this(System::nanoTime);
    }

    NetCache(LongSupplier clock) {
        this(clock, System::currentTimeMillis, null, null, null);
    }

    NetCache(NetStore store) {
        this(System::nanoTime, System::currentTimeMillis, store::get, store::set, store::remove);
    }

    NetCache(LongSupplier clock, LongSupplier wallClock, Function<String, String> read, BiConsumer<String, String> write, Consumer<String> remove) {
        this.clock = clock;
        this.wallClock = wallClock;
        this.read = read;
        this.write = write;
        this.remove = remove;
    }

    synchronized String peek(String key) {
        if (this.closed) {
            throw new IllegalStateException("Network owner closed");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Invalid cache key");
        }
        Entry entry = this.entries.get(key);
        if (entry == null && this.read != null) {
            entry = this.restore(key);
        }
        return entry == null ? null : entry.value;
    }

    synchronized String value(String key, CacheOptions options) {
        Entry entry;
        if (this.closed) {
            throw new IllegalStateException("Network owner closed");
        }
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Invalid cache key");
        }
        if (options.persist && this.read == null) {
            throw new IllegalArgumentException("Persistent cache requires a site owner");
        }
        Entry entry2 = entry = options.ttl == 0L ? null : this.entries.get(key);
        if (options.ttl > 0L && entry == null && options.persist) {
            entry = this.restore(key);
        }
        return this.fresh(entry, options.ttl) ? entry.value : null;
    }

    CompletableFuture<String> cached(String key, CacheOptions options, Supplier<CompletableFuture<String>> loader) {
        String stale;
        Flight flight;
        NetCache netCache = this;
        synchronized (netCache) {
            Entry entry;
            if (this.closed) {
                return CompletableFutures.failedFuture(NetCache.canceled());
            }
            if (key == null || key.isEmpty() || loader == null) {
                return CompletableFutures.failedFuture(new IllegalArgumentException("Invalid cache options"));
            }
            if (options.persist && this.read == null) {
                return CompletableFutures.failedFuture(new IllegalArgumentException("Persistent cache requires a site owner"));
            }
            Entry entry2 = entry = options.ttl == 0L ? null : this.entries.get(key);
            if (options.ttl > 0L && entry == null && options.persist) {
                entry = this.restore(key);
            }
            if (this.fresh(entry, options.ttl)) {
                return CompletableFuture.completedFuture(entry.value);
            }
            if (entry != null && entry.flight != null) {
                return entry.value == null || !options.stale ? NetCache.copy(entry.flight.result) : CompletableFuture.completedFuture(entry.value);
            }
            if (options.ttl > 0L && entry == null) {
                if (this.entries.size() == 64 && !this.evict()) {
                    return CompletableFutures.failedFuture(new IllegalStateException("Cache capacity exceeded"));
                }
                entry = new Entry();
                this.entries.put(key, entry);
            }
            flight = new Flight(key, entry, options.persist && options.ttl > 0L);
            if (entry != null) {
                entry.flight = flight;
            }
            this.pending.add(flight);
            stale = entry == null || !options.stale ? null : entry.value;
        }
        this.start(flight, loader);
        return stale == null ? NetCache.copy(flight.result) : CompletableFuture.completedFuture(stale);
    }

    private boolean fresh(Entry entry, long ttl) {
        return entry != null && entry.value != null && entry.age < ttl && TimeUnit.NANOSECONDS.toMillis(this.clock.getAsLong() - entry.loadedAt) < ttl - entry.age;
    }

    private Entry restore(String key) {
        long now;
        String saved = this.read.apply(key);
        if (saved == null) {
            return null;
        }
        JsonObject record = Json.strict(saved).getAsJsonObject();
        Entry entry = new Entry();
        entry.value = record.get("value").toString();
        long savedAt = record.get("time").getAsLong();
        entry.age = savedAt > (now = this.wallClock.getAsLong()) ? Long.MAX_VALUE : Math.max(0L, now - savedAt);
        entry.loadedAt = this.clock.getAsLong();
        if (this.entries.size() == 64 && !this.evict()) {
            throw new IllegalStateException("Cache capacity exceeded");
        }
        this.entries.put(key, entry);
        this.trim();
        return entry;
    }

    private boolean evict() {
        Iterator<Entry> values = this.entries.values().iterator();
        while (values.hasNext()) {
            if (values.next().flight != null) continue;
            values.remove();
            return true;
        }
        return false;
    }

    private static CompletableFuture<String> copy(CompletableFuture<String> result) {
        return result.thenApply(value -> value);
    }

    private void start(Flight flight, Supplier<CompletableFuture<String>> loader) {
        boolean canceled;
        CompletableFuture<String> loading;
        if (flight.result.isDone()) {
            return;
        }
        try {
            loading = loader.get();
            if (loading == null) {
                throw new IllegalArgumentException("Cache loader must return a future");
            }
        }
        catch (Throwable error2) {
            this.finish(flight, null, error2);
            return;
        }
        NetCache netCache = this;
        synchronized (netCache) {
            boolean bl = canceled = !this.pending.contains(flight);
            if (!canceled) {
                flight.loading = loading;
            }
        }
        if (canceled) {
            loading.cancel(true);
            return;
        }
        loading.whenComplete((value, error) -> this.finish(flight, (String)value, error == null ? this.validate((String)value) : error));
    }

    private Throwable validate(String value) {
        try {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Cache loader must return JSON");
            }
            Json.strict(value);
            return null;
        }
        catch (RuntimeException invalid) {
            return new IllegalArgumentException("Cache loader must return valid JSON");
        }
    }

    private void finish(Flight flight, String value, Throwable error) {
        NetCache netCache = this;
        synchronized (netCache) {
            if (!this.pending.remove(flight)) {
                return;
            }
            try {
                this.update(flight, value, error);
            }
            catch (RuntimeException failure) {
                error = failure;
            }
        }
        if (error == null) {
            flight.result.complete(value);
        } else {
            flight.result.completeExceptionally(error);
        }
    }

    private void update(Flight flight, String value, Throwable error) {
        if (flight.entry == null) {
            return;
        }
        flight.entry.flight = null;
        if (error != null) {
            if (flight.entry.value == null) {
                this.entries.remove(flight.key);
            }
            return;
        }
        if (flight.persist) {
            this.write.accept(flight.key, "{\"time\":" + this.wallClock.getAsLong() + ",\"value\":" + value + "}");
        }
        flight.entry.value = value;
        flight.entry.loadedAt = this.clock.getAsLong();
        flight.entry.age = 0L;
        this.trim();
    }

    private void trim() {
        long bytes = 0L;
        for (Entry entry : this.entries.values()) {
            if (entry.value == null) continue;
            bytes += (long)entry.value.length() * 2L;
        }
        Iterator<Entry> values = this.entries.values().iterator();
        while (bytes > 0x1000000L && values.hasNext()) {
            Entry entry;
            entry = values.next();
            if (entry.flight != null) continue;
            if (entry.value != null) {
                bytes -= (long)entry.value.length() * 2L;
            }
            values.remove();
        }
    }

    synchronized boolean invalidate(String key, String expected) {
        String current = peek(key);
        if (!Objects.equals(current, expected)) return false;
        for (Flight flight : pending) if (key.equals(flight.key)) return false;
        if (remove != null) remove.accept(key);
        entries.remove(key);
        return true;
    }

    void clear(String key) {
        ArrayList<Flight> active = new ArrayList<>();
        NetCache netCache = this;
        synchronized (netCache) {
            if (this.closed) {
                throw new IllegalStateException("Network owner closed");
            }
            if (key != null && key.isEmpty()) {
                throw new IllegalArgumentException("Invalid cache key");
            }
            if (this.remove != null) {
                this.remove.accept(key);
            }
            this.pending.removeIf(flight -> {
                if (key != null && !key.equals(flight.key)) {
                    return false;
                }
                active.add(flight);
                return true;
            });
            if (key == null) {
                this.entries.clear();
            } else {
                this.entries.remove(key);
            }
        }
        for (Flight flight2 : active) {
            this.cancel(flight2);
        }
    }

    @Override
    public void close() {
        ArrayList<Flight> active;
        NetCache netCache = this;
        synchronized (netCache) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            active = new ArrayList<>(this.pending);
            this.pending.clear();
            this.entries.clear();
        }
        for (Flight flight : active) {
            this.cancel(flight);
        }
    }

    private void cancel(Flight flight) {
        flight.result.completeExceptionally(NetCache.canceled());
        if (flight.loading != null) {
            flight.loading.cancel(true);
        }
    }

    private static InterruptedIOException canceled() {
        return new InterruptedIOException("Network owner closed");
    }

    private static final class Entry {
        String value;
        long loadedAt;
        long age;
        Flight flight;

        private Entry() {
        }
    }

    private static final class Flight {
        final String key;
        final Entry entry;
        final boolean persist;
        final CompletableFuture<String> result = new CompletableFuture<>();
        CompletableFuture<String> loading;

        Flight(String key, Entry entry, boolean persist) {
            this.key = key;
            this.entry = entry;
            this.persist = persist;
        }
    }
}
