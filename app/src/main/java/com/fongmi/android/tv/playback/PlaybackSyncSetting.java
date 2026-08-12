package com.fongmi.android.tv.playback;

import com.github.catvod.utils.Prefers;

public final class PlaybackSyncSetting {
    private static final String ENABLED = "playback_sync_enabled";
    private static final String LOCAL_WRITE = "playback_sync_local_write";

    private PlaybackSyncSetting() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(ENABLED, false);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(ENABLED, enabled);
        if (enabled) PlaybackSyncScheduler.start();
        else PlaybackSyncScheduler.stop();
    }

    public static boolean isLocalWriteEnabled() {
        return Prefers.getBoolean(LOCAL_WRITE, false);
    }

    public static void setLocalWriteEnabled(boolean enabled) {
        Prefers.put(LOCAL_WRITE, enabled);
    }
}
