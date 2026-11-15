package com.fongmi.android.tv.source;

import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Vod;

public final class LibraryIdentity {
    private LibraryIdentity() { }

    public static String reference(Vod item, int cid) {
        DiscoverMediaKey key = DiscoverMediaKey.parse(item.getId());
        if (key != null) return key.toString();
        String site = item.getSiteKey();
        return "source:" + cid + ":" + site.length() + ":" + site + ":" + item.getId();
    }

    public static String sourceKey(Vod item) {
        return item.getSiteKey().length() + ":" + item.getSiteKey() + ":" + item.getId();
    }
}
