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
        spider.closeWasm();
        spider.dialog.close();
        if (spider.net != null) spider.net.close();
    }
}
