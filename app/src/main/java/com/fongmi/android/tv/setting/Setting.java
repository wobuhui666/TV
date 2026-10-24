package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Prefers;

public class Setting {

    public static final int THEME_DEFAULT = -1;
    public static final int THEME_FOLLOW_WALLPAPER = 0;
    public static final int THEME_BILIBILI_PINK = 0xFFFF6699;
    public static final int THEME_EMERALD_GREEN = 0xFF00A870;
    public static final int THEME_AMBER_GOLD = 0xFFFFB020;
    public static final int THEME_OBSIDIAN_PURPLE = 0xFF8E5CFF;
    public static final int THEME_FLAME_RED = 0xFFFF5A3D;

    private static final int MIN_WALL = 0;
    private static final int MAX_WALL = 4;
    private static final int MIN_WALL_TYPE = 0;
    private static final int MAX_WALL_TYPE = 2;
    private static final int MIN_SITE_MODE = 0;
    private static final int MAX_SITE_MODE = 1;
    private static final int MIN_SYNC_MODE = 0;
    private static final int MAX_SYNC_MODE = 2;

    public static String getSwitch(boolean value) {
        return ResUtil.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    public static String getDoh() {
        String stored = Prefers.getString("doh");
        return stored.isEmpty() ? Doh.defaultItem(App.get()).toString() : stored;
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static String getTmdbProxyUrl() {
        return Prefers.getString("tmdb_proxy_url", "");
    }

    /** 豆瓣云桥地址（空 = 官方默认；可填自建实例），仅本机存储。 */
    public static String getBridgeBase() {
        return Prefers.getString("browse_bridge_base", "");
    }

    public static void putBridgeBase(String url) {
        Prefers.put("browse_bridge_base", url);
    }

    /** 豆瓣云桥密钥（sk_ 开头），仅存本机 SharedPreferences，不入库不分发。 */
    public static String getBridgeSk() {
        return Prefers.getString("browse_bridge_sk", "");
    }

    public static void putBridgeSk(String sk) {
        Prefers.put("browse_bridge_sk", sk);
    }

    public static void putTmdbProxyUrl(String url) {
        Prefers.put("tmdb_proxy_url", url == null ? "" : url.trim());
    }

    public static String getDetailFilter() {
        return Prefers.getString("detail_filter");
    }

    public static void putDetailFilter(String filter) {
        Prefers.put("detail_filter", filter);
    }

    public static String getFlagFilter() {
        return Prefers.getString("flag_filter");
    }

    public static void putFlagFilter(String filter) {
        Prefers.put("flag_filter", filter);
    }

    public static int getWall() {
        return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", Math.clamp(wall, MIN_WALL, MAX_WALL));
    }

    public static int getWallType() {
        return Math.clamp(Prefers.getInt("wall_type", 0), MIN_WALL_TYPE, MAX_WALL_TYPE);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", Math.clamp(type, MIN_WALL_TYPE, MAX_WALL_TYPE));
    }

    public static boolean isLightTheme() {
        return Prefers.getBoolean("tv_light_theme", false);
    }

    public static void putLightTheme(boolean light) {
        Prefers.put("tv_light_theme", light);
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", THEME_DEFAULT);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == THEME_DEFAULT) return 0;
        return color != THEME_FOLLOW_WALLPAPER ? color : getWallColor();
    }

    public static int getSiteMode() {
        return Math.clamp(Prefers.getInt("site_mode"), MIN_SITE_MODE, MAX_SITE_MODE);
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", Math.clamp(mode, MIN_SITE_MODE, MAX_SITE_MODE));
    }

    public static int getSyncMode() {
        return Math.clamp(Prefers.getInt("sync_mode"), MIN_SYNC_MODE, MAX_SYNC_MODE);
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", Math.clamp(mode, MIN_SYNC_MODE, MAX_SYNC_MODE));
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isSeekAccelerate() {
        return Prefers.getBoolean("seek_accelerate", true);
    }

    public static void putSeekAccelerate(boolean value) {
        Prefers.put("seek_accelerate", value);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    /** Master switch for toast keyword filter. Default off until user fills keywords. */
    public static boolean isToastFilter() {
        return Prefers.getBoolean("toast_filter", false);
    }

    public static void putToastFilter(boolean enable) {
        Prefers.put("toast_filter", enable);
        if (enable) {
            // Install Pine hooks when user turns filter on (no process restart required).
            try {
                com.fongmi.android.tv.utils.ToastFilter.onFilterEnabledChanged();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Keywords for toast filter, one per line or separated by {@code |} / {@code ,} / newline.
     * Empty by default — user fills in settings; nothing brand-specific is shipped in code.
     */
    public static String getToastFilterRaw() {
        return Prefers.getString("toast_filter_keys", "");
    }

    public static void putToastFilterRaw(String value) {
        Prefers.put("toast_filter_keys", value == null ? "" : value);
    }

    public static java.util.List<String> getToastFilterKeywords() {
        String raw = getToastFilterRaw();
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        if (raw == null || raw.isEmpty()) return list;
        for (String part : raw.split("[\\n\\r|,，、]+")) {
            String key = part.trim();
            if (!key.isEmpty()) list.add(key);
        }
        return list;
    }
}
