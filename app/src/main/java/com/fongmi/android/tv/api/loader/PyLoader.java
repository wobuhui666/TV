package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.crawler.SpiderRuntime;
import com.github.catvod.utils.CallTimeout;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private volatile Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    /** Clearing configuration or using another source language must not start Python. */
    private Loader loader() {
        Loader current = loader;
        if (current != null) return current;
        synchronized (this) {
            if (loader == null) loader = new Loader();
            return loader;
        }
    }

    public void clear() {
        for (Spider spider : spiders.values()) destroySpider(spider, null);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        Spider created = spiders.computeIfAbsent(key, k -> {
            Spider spider = null;
            try {
                spider = loadSpider(api);
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                if (spider != null) destroySpider(spider, e);
                if (CallTimeout.currentDeniedDialog() || CallTimeout.currentHasDialog() || Thread.currentThread().isInterrupted()) return null;
                e.printStackTrace();
                return new SpiderNull();
            }
        });
        return created != null ? created : new SpiderNull();
    }

    Spider loadSpider(String api) {
        return loader().spider(api);
    }

    private void destroySpider(Spider spider, Throwable failure) {
        try {
            spider.destroy();
        } catch (Throwable cleanupError) {
            if (failure != null && cleanupError != failure) failure.addSuppressed(cleanupError);
            else cleanupError.printStackTrace();
        } finally {
            // A plugin may throw before reaching its own host-resource cleanup.
            try {
                SpiderRuntime.close(spider);
            } catch (Throwable cleanupError) {
                if (failure != null && cleanupError != failure) failure.addSuppressed(cleanupError);
                else cleanupError.printStackTrace();
            }
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
