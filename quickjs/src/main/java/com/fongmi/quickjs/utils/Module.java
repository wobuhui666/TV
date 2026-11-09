package com.fongmi.quickjs.utils;

import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.UriUtil;

public class Module {

    private static final String TAG = Module.class.getSimpleName();
    private static final int MAX_SIZE = 50;
    private final LruCache<String, String> cache;

    public Module() {
        cache = new LruCache<>(MAX_SIZE);
    }

    public static Module get() {
        return Loader.INSTANCE;
    }

    public static String normalize(String base, String name) {
        if ("catvod".equals(name)) return "assets://js/lib/catvod.js";
        if (name.startsWith("catvod:")) throw new IllegalArgumentException("Unknown catvod module: " + name);
        return UriUtil.resolve(base, name);
    }

    public String fetch(String name) {
        String content = cache.get(name);
        if (!TextUtils.isEmpty(content)) {
            QuickLog.d(TAG, "fetch cache name=%s length=%s", name, content.length());
            return content;
        }
        long start = System.currentTimeMillis();
        QuickLog.d(TAG, "fetch start name=%s", name);
        if (name.startsWith("http")) cache.put(name, content = OkHttp.string(name));
        else if (name.startsWith("assets")) cache.put(name, content = Asset.read(name));
        else if (name.startsWith("lib/")) cache.put(name, content = Asset.read("js/" + name));
        if (TextUtils.isEmpty(content)) {
            QuickLog.e(TAG, "fetch empty name=" + name + " elapsed=" + (System.currentTimeMillis() - start) + "ms");
            throw new IllegalStateException("Module fetch empty: " + name);
        }
        QuickLog.d(TAG, "fetch success name=%s length=%s elapsed=%sms", name, content.length(), System.currentTimeMillis() - start);
        return content;
    }

    public void clear() {
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}
