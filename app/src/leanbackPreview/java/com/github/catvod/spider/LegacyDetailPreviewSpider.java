package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import com.github.catvod.crawler.Spider;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Old sources open native configuration UI from detailContent and cache the home Activity. */
public final class LegacyDetailPreviewSpider extends Spider {
    public final AtomicInteger details = new AtomicInteger();
    public final Handler handler = new Handler(Looper.getMainLooper());
    public Activity cachedActivity;
    public volatile AlertDialog shown;
    public volatile long delay;

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "{\"list\":[{\"vod_id\":\"legacy-settings\",\"vod_name\":\"Legacy proxy settings\"},"
                + "{\"vod_id\":\"legacy-movie\",\"vod_name\":\"A video in the same list\"}]}";
    }

    @Override
    public String detailContent(List<String> ids) {
        details.incrementAndGet();
        if (ids.contains("legacy-movie")) {
            return "{\"list\":[{\"vod_id\":\"legacy-movie\",\"vod_name\":\"A video in the same list\","
                    + "\"vod_play_from\":\"fixture\",\"vod_play_url\":\"Episode 1$https://tv-home-fixture.invalid/video.mp4\"}]}";
        }
        handler.postDelayed(() -> {
            shown = new AlertDialog.Builder(cachedActivity)
                    .setTitle("Legacy proxy engine")
                    .setItems(new String[]{"Go", "Java"}, (dialog, which) -> { })
                    .setNegativeButton(android.R.string.cancel, null).create();
            shown.show();
        }, delay);
        return "{\"list\":[{\"vod_id\":\"legacy-settings\",\"vod_name\":\"Refresh after configuring\"}]}";
    }
}
