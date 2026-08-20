package com.fongmi.android.tv.source;

public final class SourceSelectionPolicy {

    private final SourceSelectionMode mode;

    public SourceSelectionPolicy(SourceSelectionMode mode) {
        this.mode = mode == null ? SourceSelectionMode.LEGACY : mode;
    }

    public SourceSelectionMode getMode() {
        return mode;
    }

    public boolean groupResults() {
        return mode.groupsResults();
    }

    public boolean smartFallback() {
        return mode.selectsAutomatically();
    }

    public boolean isLegacy() {
        return mode == SourceSelectionMode.LEGACY;
    }
}
