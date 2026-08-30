package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class Source {

    private final List<Extractor> extractors;
    private final AtomicLong resolveGeneration;
    private final Object resolveLock;
    private final Object fetchLock;

    public Source() {
        resolveGeneration = new AtomicLong();
        resolveLock = new Object();
        fetchLock = new Object();
        extractors = new ArrayList<>();
        extractors.add(new Force());
        extractors.add(new JianPian());
        extractors.add(new Push());
        extractors.add(new Strm());
        extractors.add(new Thunder());
        extractors.add(new TVBus());
        extractors.add(new Video());
        extractors.add(new Youtube());
    }

    public static Source get() {
        return Loader.INSTANCE;
    }

    private Extractor getExtractor(Uri uri) {
        return extractors.stream().filter(extractor -> extractor.match(uri)).findFirst().orElse(null);
    }

    private void addCallable(Iterator<Episode> iterator, List<Callable<List<Episode>>> items) {
        String url = iterator.next().getUrl();
        if (Thunder.Parser.match(url)) {
            items.add(Thunder.Parser.get(url));
            iterator.remove();
        } else if (Youtube.Parser.match(url)) {
            items.add(Youtube.Parser.get(url));
            iterator.remove();
        }
    }

    public void parse(Vod vod) throws Exception {
        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            for (Flag flag : vod.getFlags()) {
                List<Callable<List<Episode>>> items = new ArrayList<>();
                Iterator<Episode> iterator = flag.getEpisodes().iterator();
                while (iterator.hasNext()) addCallable(iterator, items);
                for (Future<List<Episode>> future : executor.invokeAll(items, 30, TimeUnit.SECONDS)) {
                    try {
                        flag.getEpisodes().addAll(future.get());
                    } catch (CancellationException ignored) {
                    }
                }
            }
        }
    }

    public ResolveRequest beginResolve() {
        synchronized (resolveLock) {
            ResolveRequest request = new ResolveRequest(resolveGeneration.incrementAndGet());
            stop();
            return request;
        }
    }

    public String fetch(ResolveRequest request, Result result) throws Exception {
        requireCurrent(request);
        synchronized (fetchLock) {
            requireCurrent(request);
            Uri uri = result.getUrl().uri();
            String url = result.getUrl().v();
            Extractor extractor = getExtractor(uri);
            if (extractor != null) result.setParse(0);
            if (extractor instanceof Video) result.setParse(1);
            String resolved = extractor == null ? url : extractor.fetch(result);
            requireCurrent(request);
            return resolved;
        }
    }

    private void requireCurrent(ResolveRequest request) {
        if (request == null || request.generation != resolveGeneration.get()) throw new CancellationException();
    }

    public void stop() {
        if (extractors == null) return;
        extractors.forEach(Extractor::stop);
    }

    public void exit() {
        if (extractors == null) return;
        Task.execute(() -> extractors.forEach(Extractor::exit));
    }

    public interface Extractor {

        default String fetch(Result result) throws Exception {
            return fetch(result.getUrl().v());
        }

        String fetch(String url) throws Exception;

        boolean match(Uri uri);

        void stop();

        void exit();
    }

    public record ResolveRequest(long generation) {
    }

    private static class Loader {
        static volatile Source INSTANCE = new Source();
    }
}
