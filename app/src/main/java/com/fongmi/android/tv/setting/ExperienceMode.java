package com.fongmi.android.tv.setting;

public enum ExperienceMode {
    LIBRARY,
    SIMPLE,
    CLASSIC;

    public boolean usesLibrary() { return this != CLASSIC; }
}
