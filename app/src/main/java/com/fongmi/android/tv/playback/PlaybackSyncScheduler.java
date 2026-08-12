package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;

import java.util.concurrent.TimeUnit;

public final class PlaybackSyncScheduler {

    private static final Runnable RUN = new Runnable() {
        @Override
        public void run() {
            if (!PlaybackSyncSetting.isEnabled()) return;
            PlaybackSyncStore.syncDue();
            App.post(this, nextDelay());
        }
    };

    private PlaybackSyncScheduler() {
    }

    public static void start() {
        Task.execute(PlaybackWebhookDispatcher::drain);
        App.removeCallbacks(RUN);
        if (!PlaybackSyncSetting.isEnabled()) return;
        PlaybackSyncStore.syncOnStart();
        App.post(RUN, nextDelay());
    }

    public static void stop() {
        App.removeCallbacks(RUN);
    }

    private static long nextDelay() {
        int minutes = PlaybackSyncStore.get().stream().filter(item -> item.enabled && "remote".equals(item.kind) && item.periodMinutes > 0)
                .mapToInt(item -> item.periodMinutes).min().orElse(60);
        return TimeUnit.MINUTES.toMillis(Math.max(1, minutes));
    }
}
