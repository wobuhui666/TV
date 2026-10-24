package com.fongmi.android.tv.setting;

public enum ThemeMode {
    DARK(0), LIGHT(1), SYSTEM(2);

    private final int value;

    ThemeMode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public boolean isLight(boolean systemNight) {
        return this == LIGHT || (this == SYSTEM && !systemNight);
    }

    public static ThemeMode fromStored(int value, boolean legacyLight) {
        for (ThemeMode mode : values()) if (mode.value == value) return mode;
        return legacyLight ? LIGHT : DARK;
    }
}
