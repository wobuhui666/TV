package com.fongmi.android.tv.source;

public enum SourceSelectionMode {
    LEGACY,
    GROUP_ONLY,
    SMART;

    public boolean groupsResults() {
        return this != LEGACY;
    }

    public boolean selectsAutomatically() {
        return this == SMART;
    }
}
