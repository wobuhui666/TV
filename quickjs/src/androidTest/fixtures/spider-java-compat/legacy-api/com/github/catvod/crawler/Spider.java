package com.github.catvod.crawler;

import android.app.Activity;
import com.github.catvod.utils.Dialog;
import java.util.Map;
import okhttp3.OkHttpClient;

/** Compile-only legacy declarations; no modern methods are available here. */
public abstract class Spider implements AutoCloseable {
    public String siteKey;
    public final Dialog dialog = null;
    public static OkHttpClient client() { return null; }
    public Activity getActivity() { return null; }
    public String getProxyUrl() { return null; }
    public String getProxyUrl(Map<String, String> params) { return null; }
    public void close() {}
}
