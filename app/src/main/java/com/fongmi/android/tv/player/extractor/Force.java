package com.fongmi.android.tv.player.extractor;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.UrlUtil;
import com.forcetech.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class Force implements Source.Extractor, ServiceConnection {

    private static final Pattern PATTERN = Pattern.compile("(?i)(p[2-9]p|mitv)");
    private static final long CONNECT_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private final Set<String> set = ConcurrentHashMap.newKeySet();
    private final AtomicLong stopGeneration = new AtomicLong();

    @Override
    public boolean match(Uri uri) {
        return PATTERN.matcher(UrlUtil.scheme(uri)).find();
    }

    private void init(String scheme) {
        App.get().bindService(Util.intent(App.get(), scheme), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public String fetch(String url) throws Exception {
        long generation = stopGeneration.get();
        String scheme = Util.scheme(url);
        if (!set.contains(scheme)) init(scheme);
        long deadline = SystemClock.elapsedRealtime() + CONNECT_TIMEOUT_MS;
        while (!set.contains(scheme)) {
            if (generation != stopGeneration.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (SystemClock.elapsedRealtime() >= deadline) throw new TimeoutException("Force service connection timeout: " + scheme);
            Thread.sleep(10);
        }
        Uri uri = UrlUtil.uri(url);
        int port = Util.port(scheme);
        String id = uri.getLastPathSegment();
        String cmd = "http://127.0.0.1:" + port + "/cmd.xml?cmd=switch_chan&server=" + uri.getHost() + ":" + uri.getPort() + "&id=" + id;
        OkHttp.string(cmd, Map.of(HttpHeaders.USER_AGENT, "MTV"));
        return "http://127.0.0.1:" + port + "/" + id;
    }

    @Override
    public void stop() {
        stopGeneration.incrementAndGet();
    }

    @Override
    public void exit() {
        try {
            if (!set.isEmpty()) App.get().unbindService(this);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            set.clear();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        set.add(Util.trans(name));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        set.remove(Util.trans(name));
    }
}
