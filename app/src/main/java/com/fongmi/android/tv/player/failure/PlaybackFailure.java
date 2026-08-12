package com.fongmi.android.tv.player.failure;

public record PlaybackFailure(Category category, String userMessage, String technicalCode, String evidence,
                              boolean recoverable, boolean affectsSiteHealth) {

    public enum Category {
        SOURCE,
        LOCAL_PROXY,
        NETWORK,
        MEDIA,
        DECODER,
        OUTPUT,
        DRM,
        UNKNOWN
    }
}
