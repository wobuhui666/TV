package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.DiscoverApi;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivitySettingBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.DanmakuListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.impl.UaListener;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SourceSelectionSetting;
import com.fongmi.android.tv.source.SourceSelectionMode;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.JetStreamDialogDecor;
import com.fongmi.android.tv.ui.custom.JetStreamSettingView;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleSettingsActivity;
import com.fongmi.android.tv.ai.skip.AiSkipApi;
import com.fongmi.android.tv.ai.skip.AiSkipSettings;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.DohDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.MpvConfDialog;
import com.fongmi.android.tv.ui.dialog.PreloadDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.dialog.SpeedDialog;
import com.fongmi.android.tv.ui.dialog.UaDialog;
import com.fongmi.android.tv.ui.theme.JetStreamPalette;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.MpvLogCollector;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.quickjs.utils.QuickLog;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class SettingActivity extends BaseActivity implements ConfigListener, SiteListener, LiveListener, DohDialog.Listener, UaListener, SpeedListener, DanmakuListener, PreloadDialog.Listener, JetStreamSettingView.Listener {

    private ActivitySettingBinding mBinding;
    private DecimalFormat format;
    private String[] caption;
    private String[] render;
    private String[] scale;
    private String[] engine;
    private String[] size;
    private String[] mpvAnime4K;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingActivity.class));
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        format = new DecimalFormat("0.#");
        initArrays();
        mBinding.settingView.setListener(this);
        refreshAll();
        mBinding.settingView.requestInitialFocus();
    }

    private void initArrays() {
        size = ResUtil.getStringArray(R.array.select_size);
        scale = ResUtil.getStringArray(R.array.select_scale);
        engine = ResUtil.getStringArray(R.array.select_engine);
        render = ResUtil.getStringArray(R.array.select_render);
        caption = ResUtil.getStringArray(R.array.select_caption);
        mpvAnime4K = ResUtil.getStringArray(R.array.select_mpv_anime4k);
    }

    private void refreshAll() {
        refreshSourceRows();
        refreshPlaybackRows();
        refreshDecodeRows();
        refreshPreloadRows();
        refreshDanmakuRows();
        refreshAppRows();
        setCacheText();
    }

    private void refreshSourceRows() {
        setRowValue(JetStreamSettingView.KEY_VOD, VodConfig.getDesc());
        setRowValue(JetStreamSettingView.KEY_LIVE, LiveConfig.getDesc());
        setRowValue(JetStreamSettingView.KEY_WALL, WallConfig.getDesc());
        setRowValue(JetStreamSettingView.KEY_TMDB_PROXY, getTmdbProxyStatus());
    }

    private void refreshPlaybackRows() {
        if (PlayerSetting.isBackgroundPiP()) PlayerSetting.putBackground(1);
        boolean exo = !PlayerSetting.isMpv();
        setRowValue(JetStreamSettingView.KEY_ENGINE, engine[PlayerSetting.getEngine()]);
        setRowValue(JetStreamSettingView.KEY_RENDER, render[PlayerSetting.getRender()]);
        setRowValue(JetStreamSettingView.KEY_SCALE, scale[PlayerSetting.getScale()]);
        setRowValue(JetStreamSettingView.KEY_SPEED, format.format(PlayerSetting.getSpeed()) + " " + getString(R.string.times));
        setRowValue(JetStreamSettingView.KEY_CAPTION, caption[PlayerSetting.isCaption() ? 1 : 0]);
        setRowValue(JetStreamSettingView.KEY_BACKGROUND, Setting.getSwitch(PlayerSetting.isBackgroundOn()));
        setRowValue(JetStreamSettingView.KEY_UA, getStatus(Setting.getUa()));
        setRowValue(JetStreamSettingView.KEY_MPV_ANIME4K, mpvAnime4K[PlayerSetting.getMpvAnime4K()]);
        setRowValue(JetStreamSettingView.KEY_MPV_GPU_NEXT, Setting.getSwitch(PlayerSetting.isMpvGpuNext()));
        setRowValue(JetStreamSettingView.KEY_MPV_VULKAN, Setting.getSwitch(PlayerSetting.isMpvVulkan()));
        setRowValue(JetStreamSettingView.KEY_MPV_HDR, ResUtil.getStringArray(R.array.select_mpv_hdr)[PlayerSetting.getMpvHdr()]);
        setRowValue(JetStreamSettingView.KEY_ADBLOCK, Setting.getSwitch(Setting.isAdblock()));
        setRowValue(JetStreamSettingView.KEY_SEEK_ACCELERATE, Setting.getSwitch(Setting.isSeekAccelerate()));
        setRowVisible(JetStreamSettingView.KEY_MPV_CONF, !exo);
        setRowVisible(JetStreamSettingView.KEY_MPV_ANIME4K, !exo);
        setRowVisible(JetStreamSettingView.KEY_MPV_GPU_NEXT, !exo);
        setRowVisible(JetStreamSettingView.KEY_MPV_VULKAN, !exo);
        setRowVisible(JetStreamSettingView.KEY_MPV_HDR, !exo);
        setRowVisible(JetStreamSettingView.KEY_ADBLOCK, exo);
        setRowVisible(JetStreamSettingView.KEY_CAPTION, PlayerSetting.hasCaption());
        refreshAiSkipRows();
    }

    private void refreshAiSkipRows() {
        boolean enabled = AiSkipSettings.isEnabled();
        setRowValue(JetStreamSettingView.KEY_AI_SKIP, Setting.getSwitch(enabled));
        setRowValue(JetStreamSettingView.KEY_AI_SKIP_URL, getStatus(AiSkipSettings.getBaseUrl()));
        setRowValue(JetStreamSettingView.KEY_AI_SKIP_TOKEN, getStatus(AiSkipSettings.getToken()));
        setRowValue(JetStreamSettingView.KEY_AI_SKIP_TEST, getString(R.string.ai_skip_test_idle));
        setRowVisible(JetStreamSettingView.KEY_AI_SKIP_URL, enabled);
        setRowVisible(JetStreamSettingView.KEY_AI_SKIP_TOKEN, enabled);
        setRowVisible(JetStreamSettingView.KEY_AI_SKIP_TEST, enabled);
    }

    private void refreshDecodeRows() {
        boolean exo = !PlayerSetting.isMpv();
        setRowValue(JetStreamSettingView.KEY_TUNNEL, Setting.getSwitch(PlayerSetting.isTunnel()));
        setRowValue(JetStreamSettingView.KEY_AUDIO_PASS_THROUGH, Setting.getSwitch(PlayerSetting.isAudioPassThrough()));
        setRowValue(JetStreamSettingView.KEY_AUDIO_PREFER, Setting.getSwitch(PlayerSetting.isAudioPrefer()));
        setRowValue(JetStreamSettingView.KEY_VIDEO_PREFER, Setting.getSwitch(PlayerSetting.isVideoPrefer()));
        setRowValue(JetStreamSettingView.KEY_AAC, Setting.getSwitch(PlayerSetting.isPreferAAC()));
        setRowValue(JetStreamSettingView.KEY_AV3A, Setting.getSwitch(PlayerSetting.isAv3a()));
        setRowValue(JetStreamSettingView.KEY_DOLBY, Setting.getSwitch(PlayerSetting.isMpvDolbyHwdecEnabled()));
        setRowValue(JetStreamSettingView.KEY_DV7, Setting.getSwitch(PlayerSetting.isDv7HevcFallback()));
        setRowVisible(JetStreamSettingView.KEY_TUNNEL, exo);
        setRowVisible(JetStreamSettingView.KEY_AUDIO_PASS_THROUGH, exo);
        setRowVisible(JetStreamSettingView.KEY_AUDIO_PREFER, exo);
        setRowVisible(JetStreamSettingView.KEY_VIDEO_PREFER, exo);
        setRowVisible(JetStreamSettingView.KEY_AAC, exo);
        setRowVisible(JetStreamSettingView.KEY_DOLBY, !exo);
        setRowVisible(JetStreamSettingView.KEY_DV7, exo);
        setRowVisible(JetStreamSettingView.KEY_AV3A, exo);
    }

    private void refreshPreloadRows() {
        boolean preload = PreloadSetting.isPreload();
        setRowValue(JetStreamSettingView.KEY_PRELOAD, Setting.getSwitch(preload));
        setPreloadSizeText();
        setPreloadTimeText();
        setRowVisible(JetStreamSettingView.KEY_PRELOAD_SIZE, preload);
        setRowVisible(JetStreamSettingView.KEY_PRELOAD_TIME, preload);
    }

    private void refreshDanmakuRows() {
        setRowValue(JetStreamSettingView.KEY_DANMAKU_LOAD, Setting.getSwitch(DanmakuSetting.isLoad()));
        setRowValue(JetStreamSettingView.KEY_DANMAKU_API, getApiStatus());
        setRowValue(JetStreamSettingView.KEY_DANMAKU_LOGVAR_API, getLogvarStatus());
        setRowValue(JetStreamSettingView.KEY_DANMAKU_AUTO, Setting.getSwitch(DanmakuSetting.isAuto()));
        setRowValue(JetStreamSettingView.KEY_DANMAKU_SPIDER, Setting.getSwitch(DanmakuSetting.isSpiderFirst()));
        updateDanmakuVisibility();
    }

    private void refreshAppRows() {
        initArrays();
        String[] doh = getDohList();
        setRowValue(JetStreamSettingView.KEY_INCOGNITO, Setting.getSwitch(Setting.isIncognito()));
        setSourceSelectionRows();
        setRowValue(JetStreamSettingView.KEY_DETAIL_FILTER, getStatus(Setting.getDetailFilter()));
        setRowValue(JetStreamSettingView.KEY_FLAG_FILTER, getStatus(Setting.getFlagFilter()));
        setRowValue(JetStreamSettingView.KEY_TOAST_FILTER, Setting.getSwitch(Setting.isToastFilter()));
        setRowValue(JetStreamSettingView.KEY_TOAST_FILTER_KEYS, getStatus(Setting.getToastFilterRaw()));
        setRowVisible(JetStreamSettingView.KEY_TOAST_FILTER_KEYS, Setting.isToastFilter());
        setRowValue(JetStreamSettingView.KEY_DOH, doh.length == 0 ? "" : doh[getDohIndex()]);
        setThemeText();
        mBinding.settingView.refreshThemeSelection();
        setRowValue(JetStreamSettingView.KEY_SIZE, size[PlayerSetting.getSize()]);
        setMpvLogText();
        setQuickJsLogText();
        setRowValue(JetStreamSettingView.KEY_VERSION, BuildConfig.VERSION_NAME);
    }

    private void setSourceSelectionRows() {
        SourceSelectionMode mode = SourceSelectionSetting.getMode();
        int label = mode == SourceSelectionMode.SMART ? R.string.setting_source_mode_smart : mode == SourceSelectionMode.GROUP_ONLY ? R.string.setting_source_mode_group : R.string.setting_source_mode_legacy;
        setRowValue(JetStreamSettingView.KEY_SOURCE_MODE, getString(label));
        setRowValue(JetStreamSettingView.KEY_SOURCE_CROSS_SITE, Setting.getSwitch(SourceSelectionSetting.isCrossSiteEnabled()));
        setRowVisible(JetStreamSettingView.KEY_SOURCE_CROSS_SITE, mode == SourceSelectionMode.SMART);
    }

    private void setRowValue(String key, CharSequence value) {
        mBinding.settingView.setRowValue(key, value);
    }

    private void setRowVisible(String key, boolean visible) {
        mBinding.settingView.setRowVisible(key, visible);
    }

    private String getStatus(String value) {
        return getString(TextUtils.isEmpty(value) ? R.string.none : R.string.yes);
    }

    private String getApiStatus() {
        return getStatus(DanmakuSetting.getEffectiveApiUrl());
    }

    private String getLogvarStatus() {
        return getStatus(DanmakuSetting.getEffectiveLogvarUrl());
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                setRowValue(JetStreamSettingView.KEY_CACHE, result);
            }
        });
    }

    @Override
    public void onSettingAction(String key) {
        Integer themeColor = JetStreamSettingView.parseThemeColorAction(key);
        if (themeColor != null) {
            setThemeColor(themeColor);
            return;
        }
        switch (key) {
            case JetStreamSettingView.KEY_VOD -> onVod();
            case JetStreamSettingView.KEY_LIVE -> onLive();
            case JetStreamSettingView.KEY_WALL -> onWall();
            case JetStreamSettingView.KEY_TMDB_PROXY -> onTmdbProxy();
            case JetStreamSettingView.KEY_VOD_HOME -> onVodHome();
            case JetStreamSettingView.KEY_VOD_HISTORY -> onVodHistory();
            case JetStreamSettingView.KEY_LIVE_HOME -> onLiveHome();
            case JetStreamSettingView.KEY_LIVE_HISTORY -> onLiveHistory();
            case JetStreamSettingView.KEY_WALL_DEFAULT -> setWallDefault();
            case JetStreamSettingView.KEY_WALL_REFRESH -> setWallRefresh();
            case JetStreamSettingView.KEY_ENGINE -> setEngine();
            case JetStreamSettingView.KEY_RENDER -> setRender();
            case JetStreamSettingView.KEY_SCALE -> setScale();
            case JetStreamSettingView.KEY_SPEED -> onSpeed();
            case JetStreamSettingView.KEY_CAPTION -> setCaption();
            case JetStreamSettingView.KEY_BACKGROUND -> onBackground();
            case JetStreamSettingView.KEY_UA -> onUa();
            case JetStreamSettingView.KEY_AI_SUBTITLE -> AiSubtitleSettingsActivity.start(this);
            case JetStreamSettingView.KEY_AI_SKIP -> setAiSkipEnabled();
            case JetStreamSettingView.KEY_AI_SKIP_URL -> setApiUrl(R.string.ai_skip_url, AiSkipSettings.getBaseUrl(), this::setAiSkipUrl);
            case JetStreamSettingView.KEY_AI_SKIP_TOKEN -> setAiSkipToken();
            case JetStreamSettingView.KEY_AI_SKIP_TEST -> testAiSkip();
            case JetStreamSettingView.KEY_MPV_CONF -> onMpvConf();
            case JetStreamSettingView.KEY_MPV_ANIME4K -> setMpvAnime4K();
            case JetStreamSettingView.KEY_MPV_GPU_NEXT -> setMpvGpuNext();
            case JetStreamSettingView.KEY_MPV_VULKAN -> setMpvVulkan();
            case JetStreamSettingView.KEY_MPV_HDR -> setMpvHdr();
            case JetStreamSettingView.KEY_ADBLOCK -> setAdblock();
            case JetStreamSettingView.KEY_SEEK_ACCELERATE -> setSeekAccelerate();
            case JetStreamSettingView.KEY_TUNNEL -> setTunnel();
            case JetStreamSettingView.KEY_AUDIO_PASS_THROUGH -> setAudioPassThrough();
            case JetStreamSettingView.KEY_AUDIO_PREFER -> setAudioPrefer();
            case JetStreamSettingView.KEY_VIDEO_PREFER -> setVideoPrefer();
            case JetStreamSettingView.KEY_AAC -> setAAC();
            case JetStreamSettingView.KEY_AV3A -> setAv3a();
            case JetStreamSettingView.KEY_DOLBY -> setDolby();
            case JetStreamSettingView.KEY_DV7 -> setDv7();
            case JetStreamSettingView.KEY_PRELOAD -> setPreload();
            case JetStreamSettingView.KEY_PRELOAD_SIZE -> PreloadDialog.show(this, PreloadDialog.SIZE);
            case JetStreamSettingView.KEY_PRELOAD_TIME -> PreloadDialog.show(this, PreloadDialog.TIME);
            case JetStreamSettingView.KEY_DANMAKU_LOAD -> setDanmakuLoad();
            case JetStreamSettingView.KEY_DANMAKU_API -> onDanmakuApi();
            case JetStreamSettingView.KEY_DANMAKU_LOGVAR_API -> onLogvarApi();
            case JetStreamSettingView.KEY_DANMAKU_AUTO -> setDanmakuAuto();
            case JetStreamSettingView.KEY_DANMAKU_SPIDER -> setDanmakuSpider();
            case JetStreamSettingView.KEY_INCOGNITO -> setIncognito();
            case JetStreamSettingView.KEY_SOURCE_MODE -> setSourceMode();
            case JetStreamSettingView.KEY_SOURCE_CROSS_SITE -> setSourceCrossSite();
            case JetStreamSettingView.KEY_SOURCE_CLEAR -> clearSourceLearning();
            case JetStreamSettingView.KEY_DETAIL_FILTER -> setDetailFilter();
            case JetStreamSettingView.KEY_FLAG_FILTER -> setFlagFilter();
            case JetStreamSettingView.KEY_TOAST_FILTER -> setToastFilter();
            case JetStreamSettingView.KEY_TOAST_FILTER_KEYS -> setToastFilterKeys();
            case JetStreamSettingView.KEY_DOH -> setDoh();
            case JetStreamSettingView.KEY_THEME_COLOR -> {
            }
            case JetStreamSettingView.KEY_SIZE -> setSize();
            case JetStreamSettingView.KEY_BACKUP -> onBackup();
            case JetStreamSettingView.KEY_RESTORE -> onRestore();
            case JetStreamSettingView.KEY_CACHE -> onCache();
            case JetStreamSettingView.KEY_MPV_LOG -> setMpvLog();
            case JetStreamSettingView.KEY_MPV_LOG_EXPORT -> onMpvLog();
            case JetStreamSettingView.KEY_QUICKJS_LOG -> setQuickJsLog();
            case JetStreamSettingView.KEY_QUICKJS_LOG_EXPORT -> onQuickJsLog();
            case JetStreamSettingView.KEY_VERSION -> onVersion();
        }
    }

    @Override
    public void onSettingLongAction(String key) {
        switch (key) {
            case JetStreamSettingView.KEY_VOD -> onVodEdit();
            case JetStreamSettingView.KEY_LIVE -> onLiveEdit();
            case JetStreamSettingView.KEY_WALL -> onWallEdit();
            case JetStreamSettingView.KEY_WALL_REFRESH -> onWallHistory();
            case JetStreamSettingView.KEY_CAPTION -> onCaption();
        }
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
            case 0 -> VodConfig.load(config, getCallback());
            case 1 -> LiveConfig.load(config, getCallback());
            case 2 -> {
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
            }
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(getActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                refreshSourceRows();
                refreshDanmakuRows();
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

    private void onVod() {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive() {
        ConfigDialog.create().live().show(this);
    }

    private void onWall() {
        ConfigDialog.create().wall().show(this);
    }

    private void onTmdbProxy() {
        setApiUrl(R.string.setting_tmdb_proxy, TmdbEndpoint.getCustomRoot(), this::setTmdbProxy);
    }

    private void setTmdbProxy(String url) {
        String root = TmdbEndpoint.normalizeRoot(url);
        if (!TextUtils.equals(root, TmdbEndpoint.getCustomRoot())) DiscoverApi.clearTmdbCache();
        Setting.putTmdbProxyUrl(root);
        setRowValue(JetStreamSettingView.KEY_TMDB_PROXY, getTmdbProxyStatus());
    }

    private String getTmdbProxyStatus() {
        String root = TmdbEndpoint.getCustomRoot();
        return TextUtils.isEmpty(root) ? getString(R.string.setting_default) : root;
    }

    private void onVodEdit() {
        ConfigDialog.create().vod().edit().show(this);
    }

    private void onLiveEdit() {
        ConfigDialog.create().live().edit().show(this);
    }

    private void onWallEdit() {
        ConfigDialog.create().wall().edit().show(this);
    }

    private void onVodHome() {
        SiteDialog.create().classic().action().show(this);
    }

    private void onLiveHome() {
        LiveDialog.create().action().show(this);
    }

    private void onVodHistory() {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory() {
        HistoryDialog.create().live().show(this);
    }

    private void onWallHistory() {
        HistoryDialog.create().wall().show(this);
    }

    private void setWallDefault() {
        Setting.putWall(Setting.getWall() == 4 ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh() {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private void setEngine() {
        int index = (PlayerSetting.getEngine() + 1) % engine.length;
        PlayerSetting.putEngine(index);
        refreshPlaybackRows();
        refreshDecodeRows();
        refreshPreloadRows();
    }

    private void setRender() {
        int index = (PlayerSetting.getRender() + 1) % render.length;
        PlayerSetting.putRender(index);
        refreshPlaybackRows();
        refreshDecodeRows();
    }

    private void setScale() {
        int index = (PlayerSetting.getScale() + 1) % scale.length;
        PlayerSetting.putScale(index);
        setRowValue(JetStreamSettingView.KEY_SCALE, scale[index]);
    }

    private void setCaption() {
        PlayerSetting.putCaption(!PlayerSetting.isCaption());
        setRowValue(JetStreamSettingView.KEY_CAPTION, caption[PlayerSetting.isCaption() ? 1 : 0]);
    }

    private void onCaption() {
        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
    }

    private void onSpeed() {
        SpeedDialog.show(this);
    }

    @Override
    public void setSpeed(float speed) {
        PlayerSetting.putSpeed(speed);
        setRowValue(JetStreamSettingView.KEY_SPEED, format.format(speed) + " " + getString(R.string.times));
    }

    private void onBackground() {
        PlayerSetting.putBackground(PlayerSetting.isBackgroundOn() ? 0 : 1);
        setRowValue(JetStreamSettingView.KEY_BACKGROUND, Setting.getSwitch(PlayerSetting.isBackgroundOn()));
    }

    private void onUa() {
        UaDialog.show(this);
    }

    @Override
    public void setUa(String ua) {
        Setting.putUa(ua);
        setRowValue(JetStreamSettingView.KEY_UA, getStatus(ua));
    }

    private void onMpvConf() {
        MpvConfDialog.show(this);
    }

    private void setMpvAnime4K() {
        int index = (PlayerSetting.getMpvAnime4K() + 1) % mpvAnime4K.length;
        PlayerSetting.putMpvAnime4K(index);
        setRowValue(JetStreamSettingView.KEY_MPV_ANIME4K, mpvAnime4K[index]);
    }

    private void setMpvGpuNext() {
        PlayerSetting.putMpvGpuNext(!PlayerSetting.isMpvGpuNext());
        setRowValue(JetStreamSettingView.KEY_MPV_GPU_NEXT, Setting.getSwitch(PlayerSetting.isMpvGpuNext()));
    }

    private void setMpvVulkan() {
        PlayerSetting.putMpvVulkan(!PlayerSetting.isMpvVulkan());
        setRowValue(JetStreamSettingView.KEY_MPV_VULKAN, Setting.getSwitch(PlayerSetting.isMpvVulkan()));
    }

    private void setMpvHdr() {
        String[] modes = ResUtil.getStringArray(R.array.select_mpv_hdr);
        int index = (PlayerSetting.getMpvHdr() + 1) % modes.length;
        PlayerSetting.putMpvHdr(index);
        setRowValue(JetStreamSettingView.KEY_MPV_HDR, modes[index]);
    }

    private void setAdblock() {
        Setting.putAdblock(!Setting.isAdblock());
        setRowValue(JetStreamSettingView.KEY_ADBLOCK, Setting.getSwitch(Setting.isAdblock()));
    }

    private void setSeekAccelerate() {
        Setting.putSeekAccelerate(!Setting.isSeekAccelerate());
        setRowValue(JetStreamSettingView.KEY_SEEK_ACCELERATE, Setting.getSwitch(Setting.isSeekAccelerate()));
    }

    private void setTunnel() {
        if (PlayerSetting.isMpv()) return;
        PlayerSetting.putTunnel(!PlayerSetting.isTunnel());
        refreshPlaybackRows();
        refreshDecodeRows();
    }

    private void setAudioPassThrough() {
        PlayerSetting.putAudioPassThrough(!PlayerSetting.isAudioPassThrough());
        setRowValue(JetStreamSettingView.KEY_AUDIO_PASS_THROUGH, Setting.getSwitch(PlayerSetting.isAudioPassThrough()));
    }

    private void setAudioPrefer() {
        PlayerSetting.putAudioPrefer(!PlayerSetting.isAudioPrefer());
        setRowValue(JetStreamSettingView.KEY_AUDIO_PREFER, Setting.getSwitch(PlayerSetting.isAudioPrefer()));
    }

    private void setVideoPrefer() {
        PlayerSetting.putVideoPrefer(!PlayerSetting.isVideoPrefer());
        setRowValue(JetStreamSettingView.KEY_VIDEO_PREFER, Setting.getSwitch(PlayerSetting.isVideoPrefer()));
    }

    private void setAAC() {
        PlayerSetting.putPreferAAC(!PlayerSetting.isPreferAAC());
        setRowValue(JetStreamSettingView.KEY_AAC, Setting.getSwitch(PlayerSetting.isPreferAAC()));
    }

    private void setAv3a() {
        PlayerSetting.putAv3a(!PlayerSetting.isAv3a());
        setRowValue(JetStreamSettingView.KEY_AV3A, Setting.getSwitch(PlayerSetting.isAv3a()));
    }

    private void setDolby() {
        PlayerSetting.putMpvDolbyHwdecEnabled(!PlayerSetting.isMpvDolbyHwdecEnabled());
        setRowValue(JetStreamSettingView.KEY_DOLBY, Setting.getSwitch(PlayerSetting.isMpvDolbyHwdecEnabled()));
    }

    private void setDv7() {
        PlayerSetting.putDv7HevcFallback(!PlayerSetting.isDv7HevcFallback());
        setRowValue(JetStreamSettingView.KEY_DV7, Setting.getSwitch(PlayerSetting.isDv7HevcFallback()));
    }

    private void setPreload() {
        PreloadSetting.putPreload(!PreloadSetting.isPreload());
        refreshPreloadRows();
    }

    @Override
    public void setPreload(int type, int value) {
        if (type == PreloadDialog.SIZE) {
            PreloadSetting.putPreloadSizeMb(value);
            setPreloadSizeText();
        } else if (type == PreloadDialog.TIME) {
            PreloadSetting.putPreloadTimeSeconds(value);
            setPreloadTimeText();
        }
    }

    private void setPreloadSizeText() {
        setRowValue(JetStreamSettingView.KEY_PRELOAD_SIZE, FileUtil.byteCountToDisplaySize(PreloadSetting.getPreloadSizeBytes()));
    }

    private void setPreloadTimeText() {
        setRowValue(JetStreamSettingView.KEY_PRELOAD_TIME, getString(R.string.player_preload_time_value, PreloadSetting.getPreloadTimeSeconds()));
    }

    private void setDanmakuLoad() {
        DanmakuSetting.putLoad(!DanmakuSetting.isLoad());
        refreshDanmakuRows();
    }

    private void onDanmakuApi() {
        setApiUrl(R.string.danmaku_api, DanmakuSetting.getApiUrl(), this::setDanmakuApi);
    }

    private void onLogvarApi() {
        setApiUrl(R.string.danmaku_logvar_api, DanmakuSetting.getLogvarUrl(), this::setLogvarApi);
    }

    @Override
    public void setDanmakuApi(String url) {
        DanmakuSetting.putApiUrl(url);
        refreshDanmakuRows();
    }

    @Override
    public void setLogvarApi(String url) {
        DanmakuSetting.putLogvarUrl(url);
        refreshDanmakuRows();
    }

    private void setDanmakuAuto() {
        DanmakuSetting.putAuto(!DanmakuSetting.isAuto());
        refreshDanmakuRows();
    }

    private void setDanmakuSpider() {
        DanmakuSetting.putSpiderFirst(!DanmakuSetting.isSpiderFirst());
        setRowValue(JetStreamSettingView.KEY_DANMAKU_SPIDER, Setting.getSwitch(DanmakuSetting.isSpiderFirst()));
    }

    private void updateDanmakuVisibility() {
        boolean load = DanmakuSetting.isLoad();
        boolean api = DanmakuSetting.hasSearchApi();
        setRowVisible(JetStreamSettingView.KEY_DANMAKU_API, load);
        setRowVisible(JetStreamSettingView.KEY_DANMAKU_LOGVAR_API, load);
        setRowVisible(JetStreamSettingView.KEY_DANMAKU_AUTO, load && api);
        setRowVisible(JetStreamSettingView.KEY_DANMAKU_SPIDER, load && api && DanmakuSetting.isAuto());
    }

    private void setIncognito() {
        Setting.putIncognito(!Setting.isIncognito());
        setRowValue(JetStreamSettingView.KEY_INCOGNITO, Setting.getSwitch(Setting.isIncognito()));
    }

    private void setSourceMode() {
        String[] modes = {getString(R.string.setting_source_mode_legacy), getString(R.string.setting_source_mode_group), getString(R.string.setting_source_mode_smart)};
        new MaterialAlertDialogBuilder(this).setTitle(R.string.setting_source_mode).setSingleChoiceItems(modes, SourceSelectionSetting.getMode().ordinal(), (dialog, which) -> {
            SourceSelectionSetting.putMode(SourceSelectionMode.values()[which]);
            setSourceSelectionRows();
            dialog.dismiss();
        }).setNegativeButton(R.string.dialog_negative, null).show();
    }

    private void setSourceCrossSite() {
        SourceSelectionSetting.putCrossSiteEnabled(!SourceSelectionSetting.isCrossSiteEnabled());
        setSourceSelectionRows();
    }

    private void clearSourceLearning() {
        SourceSelectionSetting.clearReliability();
        Notify.show(R.string.setting_source_clear);
    }

    private void setDetailFilter() {
        setTextFilter(R.string.setting_detail_filter, Setting.getDetailFilter(), value -> {
            Setting.putDetailFilter(value);
            setRowValue(JetStreamSettingView.KEY_DETAIL_FILTER, getStatus(value));
        });
    }

    private void setFlagFilter() {
        setTextFilter(R.string.setting_flag_filter, Setting.getFlagFilter(), value -> {
            Setting.putFlagFilter(value);
            setRowValue(JetStreamSettingView.KEY_FLAG_FILTER, getStatus(value));
        });
    }

    private void setToastFilter() {
        Setting.putToastFilter(!Setting.isToastFilter());
        setRowValue(JetStreamSettingView.KEY_TOAST_FILTER, Setting.getSwitch(Setting.isToastFilter()));
        setRowVisible(JetStreamSettingView.KEY_TOAST_FILTER_KEYS, Setting.isToastFilter());
    }

    private void setToastFilterKeys() {
        setTextFilter(R.string.setting_toast_filter_keys, Setting.getToastFilterRaw(), value -> {
            Setting.putToastFilterRaw(value);
            if (!value.isEmpty() && !Setting.isToastFilter()) {
                Setting.putToastFilter(true);
                setRowValue(JetStreamSettingView.KEY_TOAST_FILTER, Setting.getSwitch(true));
                setRowVisible(JetStreamSettingView.KEY_TOAST_FILTER_KEYS, true);
            }
            setRowValue(JetStreamSettingView.KEY_TOAST_FILTER_KEYS, getStatus(value));
        });
    }

    private void setThemeText() {
        setRowValue(JetStreamSettingView.KEY_THEME_COLOR, getString(JetStreamPalette.currentLabelRes()));
    }

    private void setThemeColor(int color) {
        if (Setting.getThemeColor() == color) return;
        Setting.putThemeColor(color);
        setThemeText();
        mBinding.settingView.refreshThemeSelection();
        RefreshEvent.theme();
    }

    private void setTextFilter(int title, String value, Consumer<String> callback) {
        EditText input = new EditText(this);
        int padding = ResUtil.dp2px(24);
        input.setHint(R.string.setting_text_filter_hint);
        input.setMinLines(5);
        input.setText(value);
        input.setPadding(padding, 0, padding, 0);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setSelection(TextUtils.isEmpty(value) ? 0 : value.length());
        JetStreamDialogDecor.tintButtons(new MaterialAlertDialogBuilder(this).setTitle(title).setView(input).setPositiveButton(R.string.dialog_positive, (dialog, which) -> callback.accept(input.getText().toString().trim())).setNegativeButton(R.string.dialog_negative, null).show());
    }

    private void setApiUrl(int title, String value, Consumer<String> callback) {
        EditText input = new EditText(this);
        FrameLayout container = new FrameLayout(this);
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
        JetStreamDialogDecor.tintButtons(new MaterialAlertDialogBuilder(this).setTitle(title).setView(container).setPositiveButton(R.string.dialog_positive, (dialog, which) -> callback.accept(input.getText().toString().trim())).setNegativeButton(R.string.dialog_negative, null).show());
    }

    private void setAiSkipEnabled() {
        AiSkipSettings.setEnabled(!AiSkipSettings.isEnabled());
        refreshAiSkipRows();
    }

    private void setAiSkipUrl(String url) {
        AiSkipSettings.setBaseUrl(url);
        refreshAiSkipRows();
    }

    private void setAiSkipToken() {
        EditText input = new EditText(this);
        FrameLayout container = new FrameLayout(this);
        int horizontalPadding = ResUtil.dp2px(24);
        int verticalPadding = ResUtil.dp2px(12);
        input.setHint(R.string.ai_skip_token);
        input.setSingleLine(true);
        input.setMinHeight(ResUtil.dp2px(56));
        input.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.setPadding(0, ResUtil.dp2px(8), 0, 0);
        container.addView(input, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        JetStreamDialogDecor.tintButtons(new MaterialAlertDialogBuilder(this).setTitle(R.string.ai_skip_token).setView(container)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    AiSkipSettings.setToken(input.getText().toString());
                    refreshAiSkipRows();
                }).setNegativeButton(R.string.dialog_negative, null).show());
    }

    private void testAiSkip() {
        setRowValue(JetStreamSettingView.KEY_AI_SKIP_TEST, getString(R.string.ai_skip_test_running));
        Task.execute(() -> {
            boolean success;
            try {
                success = new AiSkipApi().health();
            } catch (Exception ignored) {
                success = false;
            }
            boolean result = success;
            App.post(() -> setRowValue(JetStreamSettingView.KEY_AI_SKIP_TEST, getString(result ? R.string.ai_skip_test_ok : R.string.ai_skip_test_failed)));
        });
    }

    private void setSize() {
        int index = (PlayerSetting.getSize() + 1) % size.length;
        PlayerSetting.putSize(index);
        setRowValue(JetStreamSettingView.KEY_SIZE, size[index]);
        RefreshEvent.size();
    }

    private void setDoh() {
        DohDialog.create().index(getDohIndex()).show(this);
    }

    @Override
    public void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        setRowValue(JetStreamSettingView.KEY_DOH, doh.getName());
    }

    private void onCache() {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private String getLogText(boolean enabled, int count) {
        return Setting.getSwitch(enabled) + " / " + count + " 条";
    }

    private void setMpvLogText() {
        setRowValue(JetStreamSettingView.KEY_MPV_LOG, getLogText(MpvLogCollector.isEnabled(), MpvLogCollector.getLogCount()));
    }

    private void setQuickJsLogText() {
        setRowValue(JetStreamSettingView.KEY_QUICKJS_LOG, getLogText(QuickLog.isEnabled(), QuickLog.getLogCount()));
    }

    private void setMpvLog() {
        MpvLogCollector.putEnabled(!MpvLogCollector.isEnabled());
        setMpvLogText();
    }

    private void setQuickJsLog() {
        QuickLog.putEnabled(!QuickLog.isEnabled());
        setQuickJsLogText();
    }

    private void onMpvLog() {
        PermissionUtil.requestFile(this, allGranted -> {
            String path = MpvLogCollector.exportToFile(this);
            if (path != null) {
                Notify.show("日志已导出到: " + path);
                setMpvLogText();
            } else if (MpvLogCollector.getLogCount() == 0) {
                Notify.show("暂无MPV日志可导出");
            } else {
                Notify.show("导出日志失败，请检查存储权限");
            }
        });
    }

    private void onQuickJsLog() {
        PermissionUtil.requestFile(this, allGranted -> {
            String path = QuickLog.exportToFile(this);
            if (path != null) {
                Notify.show("JS日志已导出到: " + path);
                setQuickJsLogText();
            } else if (QuickLog.getLogCount() == 0) {
                Notify.show("暂无JS日志可导出");
            } else {
                Notify.show("导出JS日志失败，请检查存储权限");
            }
        });
    }

    private void onBackup() {
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

    private void onRestore() {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().callback(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                refreshAll();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }).show(this));
    }

    private void onVersion() {
        Updater.create().force().start(this);
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        refreshSourceRows();
        refreshDanmakuRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding == null) return;
        refreshPlaybackRows();
        refreshDecodeRows();
        refreshPreloadRows();
        refreshDanmakuRows();
        refreshAppRows();
    }

    @Override
    protected void onThemeChanged() {
        if (mBinding == null) return;
        setThemeText();
        mBinding.settingView.refreshThemeSelection();
    }
}
