package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.res.Configuration;

/** Production home with a fixed locale for live metadata screenshots; no catalog fixtures. */
public final class LiveHomePreviewActivity extends HomeActivity {
    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(base.createConfigurationContext(config));
    }
}
