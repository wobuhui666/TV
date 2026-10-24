package com.fongmi.android.tv.ui.rex;

import androidx.annotation.ColorInt;

import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.ThemeMode;

/**
 * Rex 行的主题单一挂钩。
 *
 * <p>{@link #isLight()} 读取与 Compose / View 共用的全局外观偏好。
 * 叠加在剧照上的大字恒用白字 + 阴影，两套主题一致（由底部 scrim 保证可读性）。
 */
public final class RexTheme {

    private RexTheme() {
    }

    public static boolean isLight() {
        return Setting.isLightTheme();
    }

    public static void setLight(boolean light) {
        setMode(light ? ThemeMode.LIGHT : ThemeMode.DARK);
    }

    public static void setMode(ThemeMode mode) {
        if (Setting.getThemeMode() == mode) return;
        Setting.putThemeMode(mode);
        RefreshEvent.theme();
    }

    /** 精选大卡片名：恒定白。 */
    @ColorInt
    public static int spotlightTitleColor() {
        return 0xFFFFFFFF;
    }

    /** 精选大卡栏目名（小一号）：恒定白（95%）。 */
    @ColorInt
    public static int spotlightLabelColor() {
        return 0xF2FFFFFF;
    }

    /** 精选大卡装饰引号：恒定白（50%）。 */
    @ColorInt
    public static int spotlightQuoteColor() {
        return 0x80FFFFFF;
    }

    /** 叠加文案阴影色（黑 70%）。 */
    @ColorInt
    public static int spotlightShadowColor() {
        return 0xB3000000;
    }

    /** 图片上的白字保持深色底部渐变，与页面外观模式无关。 */
    @ColorInt
    public static int spotlightScrimColor() {
        return 0xF017181C;
    }
}
