package com.fongmi.android.tv.source;

public final class SourceSelectionPolicies {

    private SourceSelectionPolicies() {
    }

    public static SourceSelectionPolicy forMode(SourceSelectionMode mode) {
        return new SourceSelectionPolicy(mode);
    }
}
