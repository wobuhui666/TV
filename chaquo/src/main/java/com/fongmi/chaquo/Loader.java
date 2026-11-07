package com.fongmi.chaquo;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.github.catvod.utils.Path;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.Net;

import android.webkit.CookieManager;
import java.util.function.Supplier;

public class Loader {

    private final PyObject app;
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

    public Loader() {
        synchronized (Python.class) {
            if (!Python.isStarted()) Python.start(Platform.create());
        }
        try {
            Python.getInstance().getModule("tv_diagnostics").callAttr("install");
        } catch (Exception error) {
            SpiderDebug.log(error);
        }
        app = Python.getInstance().getModule("app");
    }

    public Spider spider(String api) {
        return spider(api, "", COOKIES, null);
    }

    public Spider spider(String api, String siteKey) {
        return spider(api, siteKey, COOKIES, null);
    }

    public Spider spider(String api, Net.Cookies cookies) {
        return spider(api, "", cookies, null);
    }

    public Spider spider(String api, Net.Cookies cookies, Supplier<String> userAgent) {
        return spider(api, "", cookies, userAgent);
    }

    public Spider spider(String api, String siteKey, Net.Cookies cookies, Supplier<String> userAgent) {
        String key = siteKey == null ? "" : siteKey;
        Net net = new Net(cookies, key.isEmpty() ? "legacy:python:" + api : key);
        PyObject obj = null;
        try {
            obj = app.callAttr("spider", Path.py().getAbsolutePath(), api, key, net);
            Spider spider = new Spider(app, obj, api, cookies, userAgent, net);
            spider.siteKey = key;
            return spider;
        } catch (RuntimeException | Error error) {
            try { net.close(); }
            catch (RuntimeException | Error closing) { if (closing != error) error.addSuppressed(closing); }
            if (obj != null) {
                try { app.callAttr("release", obj); }
                catch (RuntimeException | Error closing) { if (closing != error) error.addSuppressed(closing); }
            }
            throw error;
        }
    }
}
