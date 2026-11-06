package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.leanback.widget.ArrayObjectAdapter;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.ui.custom.JetStreamVerticalGridView;
import com.fongmi.android.tv.ui.home.PosterHomeController;

import java.lang.reflect.Method;
import java.util.List;

/** Real HomeActivity initialization, listeners, focus scheduling and routing with offline data. */
public final class TvHomePreviewActivity extends HomeActivity {
    public int navClicks;
    public int navLongClicks;
    public int sourceDialogs;
    public int navigationCount;
    public String lastNavKey;
    public String lastLongKey;
    public Intent lastNavigation;

    @Override protected void attachBaseContext(android.content.Context base) {
        android.content.res.Configuration config = new android.content.res.Configuration(base.getResources().getConfiguration());
        config.setLocale(java.util.Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override protected void initView(Bundle state) {
        super.initView(state);
        HomePreviewFixtures.Session fixture = HomePreviewFixtures.active;
        if (fixture == null) throw new IllegalStateException("Production home fixture session is missing");
        fixture.installSites();
        SiteViewModel model = (SiteViewModel) HomePreviewFixtures.field(this, "mViewModel");
        // Use the production request path so later configuration refreshes also retain offline categories.
        model.init();
        onRefresh();
        replaceHistory(fixture.histories);
    }

    @Override public void onNavClick(String key) { navClicks++; lastNavKey = key; super.onNavClick(key); }
    @Override public void onNavLongClick(String key) { navLongClicks++; lastLongKey = key; super.onNavLongClick(key); }
    @Override public void showDialog() { sourceDialogs++; super.showDialog(); }
    @Override public void startActivity(Intent intent) { record(intent); }
    @Override public void startActivity(Intent intent, Bundle options) { record(intent); }

    private void record(Intent intent) { navigationCount++; lastNavigation = intent; }
    public ArrayObjectAdapter pageAdapter() { return (ArrayObjectAdapter) HomePreviewFixtures.field(this, "mAdapter"); }
    public PosterHomeController controller() { return (PosterHomeController) HomePreviewFixtures.field(this, "mPosterHome"); }
    public JetStreamVerticalGridView pageGrid() { return findViewById(R.id.recycler); }
    public View toolbar() { return findViewById(R.id.toolbar); }
    public int historyPosition() { return pageAdapter().indexOf(R.string.home_history) + 1; }
    public int categoriesPosition() { return pageAdapter().indexOf(HomePreviewFixtures.field(controller(), "categories")); }
    public int popularPosition() { return pageAdapter().indexOf(((List<?>) HomePreviewFixtures.field(controller(), "shelves")).get(0)); }
    public int sourcePosition() { return pageAdapter().indexOf(HomePreviewFixtures.field(controller(), "source")); }
    public long focusGeneration() { return (long) HomePreviewFixtures.field(this, "mHistoryFocusGeneration"); }
    public boolean toolbarTouchActive() { return (boolean) HomePreviewFixtures.field(this, "mToolbarTouchActive"); }
    public void replaceHistory(List<History> values) { invoke("applyHistory", new Class<?>[] {List.class, boolean.class}, values, false); }
    public void focusRow(int position) { invoke("requestRecyclerFocus", new Class<?>[] {int.class}, position); }
    public void queueNavRestore() { invoke("requestNavFocus", new Class<?>[0]); }
    public void showToolbar(boolean visible) { invoke("setToolbarVisible", new Class<?>[] {boolean.class}, visible); }

    private void invoke(String name, Class<?>[] types, Object... values) {
        try {
            Method method = HomeActivity.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(this, values);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot invoke production home fixture method " + name, error);
        }
    }
}
