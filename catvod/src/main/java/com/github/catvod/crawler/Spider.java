package com.github.catvod.crawler;

import android.content.Context;
import android.app.Activity;
import android.net.Uri;

import com.github.catvod.Init;
import com.github.catvod.Proxy;
import com.github.catvod.net.Net;
import com.github.catvod.utils.Local;
import com.github.catvod.utils.Dialog;

import com.github.catvod.net.OkHttp;
import com.fongmi.quickjs.WasmRuntime;
import com.github.catvod.utils.CallTimeout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

public abstract class Spider {

    public String siteKey;
    public Net net;
    public Local local;
    public final Dialog dialog = new Dialog();
    private volatile WasmRuntime wasmRuntime;
    private volatile boolean wasmClosed;

    /** Lazy ownership keeps ordinary crawlers free of WASM allocations. */
    public synchronized WasmRuntime wasm() {
        if (wasmClosed) throw new IllegalStateException("Crawler WASM runtime closed");
        if (wasmRuntime == null) {
            wasmRuntime = new WasmRuntime();
            wasmRuntime.setExecutionCheck(() -> {
                CallTimeout timeout = CallTimeout.current();
                if (timeout != null && timeout.isExpired()) throw new java.util.concurrent.CancellationException("Source request timed out");
            });
        }
        return wasmRuntime;
    }

    public void closeWasm() {
        WasmRuntime runtime;
        synchronized (this) {
            wasmClosed = true;
            runtime = wasmRuntime;
            wasmRuntime = null;
        }
        if (runtime != null) runtime.close();
    }

    public static Dns safeDns() {
        return OkHttp.dns();
    }

    public static OkHttpClient client() {
        return OkHttp.client();
    }

    public Activity getActivity() {
        return Init.activity();
    }

    public String getProxyUrl() {
        return getProxyUrl(java.util.Collections.emptyMap());
    }

    public String getProxyUrl(Map<String, String> params) {
        Uri.Builder uri = Uri.parse(Proxy.getUrl(true)).buildUpon()
                .appendQueryParameter("do", "jar").appendQueryParameter("siteKey", siteKey);
        for (Map.Entry<String, String> item : params.entrySet()) {
            if ("do".equals(item.getKey()) || "siteKey".equals(item.getKey()))
                throw new IllegalArgumentException("Reserved proxy parameter");
            uri.appendQueryParameter(item.getKey(), item.getValue());
        }
        return uri.build().toString();
    }

    public void init(Context context) throws Exception {
    }

    public void init(Context context, String extend) throws Exception {
        init(context);
    }

    public String homeContent(boolean filter) throws Exception {
        return "";
    }

    public String homeVideoContent() throws Exception {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "";
    }

    public String detailContent(List<String> ids) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick) throws Exception {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return "";
    }

    public String liveContent(String url) throws Exception {
        return "";
    }

    public boolean manualVideoCheck() throws Exception {
        return false;
    }

    public boolean isVideoFormat(String url) throws Exception {
        return false;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }

    public String action(String action) throws Exception {
        return null;
    }

    public void destroy() {
    }
}
