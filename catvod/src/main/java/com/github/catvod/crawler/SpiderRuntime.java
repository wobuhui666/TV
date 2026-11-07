package com.github.catvod.crawler;

import android.webkit.CookieManager;

import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;

/** Host-owned resources, bound before a plugin's init even when it omits super.init. */
public final class SpiderRuntime {
    private static final Net.Cookies COOKIES = new Net.Cookies() {
        @Override public String getCookie(String url) {
            return CookieManager.getInstance().getCookie(url);
        }
        @Override public boolean setCookie(String url, String value) {
            CookieManager manager = CookieManager.getInstance();
            manager.setCookie(url, value);
            manager.flush();
            return true;
        }
    };

    private SpiderRuntime() { }

    public static void bind(Spider spider, String siteKey) {
        spider.siteKey = siteKey;
        String owner = siteKey == null || siteKey.isEmpty() ? "legacy:" + spider.getClass().getName() : siteKey;
        if (spider.net == null) spider.net = new Net(COOKIES, owner);
        if (spider.local == null) spider.local = new Local(owner);
    }

    public static void close(Spider spider) {
        Throwable failure = close(null, spider.js::close);
        failure = close(failure, spider::closeWasm);
        failure = close(failure, spider.dialog::close);
        if (spider.net != null) failure = close(failure, spider.net::close);
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }

    private static Throwable close(Throwable failure, Runnable release) {
        try {
            release.run();
        } catch (RuntimeException | Error error) {
            if (failure == null) return error;
            if (failure != error) failure.addSuppressed(error);
        }
        return failure;
    }
}
