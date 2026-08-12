package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentSettingBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.playback.PlaybackSyncSetting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.dialog.PlaybackSyncDialog;
import com.fongmi.android.tv.ui.dialog.ThemeDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SettingFragment extends BaseFragment implements ConfigListener, SiteListener, LiveListener, ThemeDialog.Listener {

    private FragmentSettingBinding mBinding;
    private String[] size;

    public static SettingFragment newInstance() {
        return new SettingFragment();
    }

    private String getThemeText() {
        int color = Setting.getThemeColor();
        if (color == -1) return getString(R.string.setting_off);
        return getString(color == 0 ? R.string.setting_auto : R.string.setting_custom);
    }

    private String getFilterStatus(String value) {
        return getString(TextUtils.isEmpty(value) ? R.string.none : R.string.yes);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
        mBinding.versionText.setText(BuildConfig.VERSION_NAME);
        setOtherText();
        setCacheText();
    }

    private void setOtherText() {
        mBinding.themeColorText.setText(getThemeText());
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.flagFilterText.setText(getFilterStatus(Setting.getFlagFilter()));
        mBinding.detailFilterText.setText(getFilterStatus(Setting.getDetailFilter()));
        mBinding.tmdbProxyText.setText(getTmdbProxyStatus());
        mBinding.toastFilterText.setText(Setting.getSwitch(Setting.isToastFilter()));
        mBinding.toastFilterKeysText.setText(getFilterStatus(Setting.getToastFilterRaw()));
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
        mBinding.playbackSyncText.setText(Setting.getSwitch(PlaybackSyncSetting.isEnabled()));
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.tmdbProxy.setOnClickListener(this::setTmdbProxy);
        mBinding.flagFilter.setOnClickListener(this::setFlagFilter);
        mBinding.detailFilter.setOnClickListener(this::setDetailFilter);
        mBinding.toastFilter.setOnClickListener(this::setToastFilter);
        mBinding.toastFilterKeys.setOnClickListener(this::setToastFilterKeys);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.themeColor.setOnClickListener(this::onThemeColor);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);
        mBinding.playbackSync.setOnClickListener(v -> PlaybackSyncDialog.create().show(this));
        mBinding.siteHealthClear.setOnClickListener(v -> {
            SiteHealthStore.clear(VodConfig.getCid());
            Notify.show("站点健康统计已清空");
        });
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(requireActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    @Override
    public void setTheme(int color) {
        Setting.putThemeColor(color);
        RefreshEvent.theme();
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().search().change().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        getRoot().change(2);
    }

    private void onDanmaku(View view) {
        getRoot().change(3);
    }

    private void onThemeColor(View view) {
        ThemeDialog.show(this);
    }

    private void setDetailFilter(View view) {
        setTextFilter(R.string.setting_detail_filter, Setting.getDetailFilter(), value -> {
            Setting.putDetailFilter(value);
            mBinding.detailFilterText.setText(getFilterStatus(value));
        });
    }

    private void setFlagFilter(View view) {
        setTextFilter(R.string.setting_flag_filter, Setting.getFlagFilter(), value -> {
            Setting.putFlagFilter(value);
            mBinding.flagFilterText.setText(getFilterStatus(value));
        });
    }

    private void setToastFilter(View view) {
        Setting.putToastFilter(!Setting.isToastFilter());
        mBinding.toastFilterText.setText(Setting.getSwitch(Setting.isToastFilter()));
    }

    private void setToastFilterKeys(View view) {
        setTextFilter(R.string.setting_toast_filter_keys, Setting.getToastFilterRaw(), value -> {
            Setting.putToastFilterRaw(value);
            if (!value.isEmpty() && !Setting.isToastFilter()) {
                Setting.putToastFilter(true);
                mBinding.toastFilterText.setText(Setting.getSwitch(true));
            }
            mBinding.toastFilterKeysText.setText(getFilterStatus(value));
        });
    }

    private void setTmdbProxy(View view) {
        setApiUrl(R.string.setting_tmdb_proxy, TmdbEndpoint.getCustomRoot(), value -> {
            String root = TmdbEndpoint.normalizeRoot(value);
            if (!TextUtils.equals(root, TmdbEndpoint.getCustomRoot())) DiscoverApi.clearTmdbCache();
            Setting.putTmdbProxyUrl(root);
            mBinding.tmdbProxyText.setText(getTmdbProxyStatus());
        });
    }

    private String getTmdbProxyStatus() {
        String root = TmdbEndpoint.getCustomRoot();
        return TextUtils.isEmpty(root) ? getString(R.string.setting_default) : root;
    }

    private void setTextFilter(int title, String value, Consumer<String> callback) {
        EditText input = new EditText(requireActivity());
        int padding = ResUtil.dp2px(24);
        input.setHint(R.string.setting_text_filter_hint);
        input.setMinLines(5);
        input.setText(value);
        input.setPadding(padding, 0, padding, 0);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSelection(TextUtils.isEmpty(value) ? 0 : value.length());
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(title).setView(input).setPositiveButton(R.string.dialog_positive, (dialog, which) -> callback.accept(input.getText().toString().trim())).setNegativeButton(R.string.dialog_negative, null).show();
    }

    private void setApiUrl(int title, String value, Consumer<String> callback) {
        EditText input = new EditText(requireActivity());
        FrameLayout container = new FrameLayout(requireActivity());
        int horizontalPadding = ResUtil.dp2px(24);
        int verticalPadding = ResUtil.dp2px(12);
        input.setHint(title);
        input.setSingleLine(true);
        input.setText(value);
        input.setMinHeight(ResUtil.dp2px(56));
        input.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSelection(TextUtils.isEmpty(value) ? 0 : value.length());
        container.setPadding(0, ResUtil.dp2px(8), 0, 0);
        container.addView(input, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(title).setView(container).setPositiveButton(R.string.dialog_positive, (dialog, which) -> callback.accept(input.getText().toString().trim())).setNegativeButton(R.string.dialog_negative, null).show();
    }

    private void onVersion(View view) {
        Updater.create().force().start(requireActivity());
    }

    private void setWallDefault(View view) {
        Setting.putWall(Setting.getWall() == 4 ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(Setting.getSwitch(Setting.isIncognito()));
    }

    private void setSize(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_size).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(size, PlayerSetting.getSize(), (dialog, which) -> {
            mBinding.sizeText.setText(size[which]);
            PlayerSetting.putSize(which);
            RefreshEvent.size();
            dialog.dismiss();
        }).show();
    }

    private void setDoh(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_doh).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(getDohList(), getDohIndex(), (dialog, which) -> {
            setDoh(VodConfig.get().getDoh().get(which));
            dialog.dismiss();
        }).show();
    }

    private void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> AppDatabase.backup(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        }));
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().show(requireActivity(), new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }));
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) return;
        setCacheText();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }
}
