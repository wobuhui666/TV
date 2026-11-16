package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.view.View;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.ExperienceSettings;

public final class LibraryNavigation {
    private LibraryNavigation() { }

    public static void open(Activity activity, Vod item, View poster) {
        if (item == null || item.isAction()) return;
        if (item.isFolder()) {
            if (!item.getSiteKey().isEmpty()) VodActivity.start(activity, item.getSiteKey(), Result.folder(item));
            return;
        }
        if (ExperienceSettings.usesLibrary()) LibraryDetailActivity.start(activity, item);
        else if (DiscoverMediaKey.parse(item.getId()) != null || item.getId().startsWith("douban:")) DiscoverDetailActivity.start(activity, item, poster);
        else VideoActivity.start(activity, item, poster);
    }

    public static void source(Activity activity, String site, Vod item, View poster) {
        item.setSite(VodConfig.get().getSite(site));
        open(activity, item, poster);
    }

    public static void history(Activity activity, History history) {
        if (!ExperienceSettings.usesLibrary()) {
            VideoActivity.start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic());
            return;
        }
        LibraryDetailActivity.resume(activity, history);
    }

    public static void keep(Activity activity, Keep keep, View poster) {
        Vod item = new Vod(); item.setName(keep.getVodName()); item.setPic(keep.getVodPic());
        DiscoverMediaKey key = DiscoverMediaKey.parse(keep.getMediaRef());
        if (key == null && keep.getType() == Keep.TYPE_DISCOVER) key = DiscoverMediaKey.parse(keep.getKey());
        if (key != null) { item.setId(key.toString()); item.setTypeName(key.getMediaType()); }
        else { item.setId(keep.getVodId()); item.setSite(VodConfig.get().getSite(keep.getSiteKey())); }
        open(activity, item, poster);
    }
}
