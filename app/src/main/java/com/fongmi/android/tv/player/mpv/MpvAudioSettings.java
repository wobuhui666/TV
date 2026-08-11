package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.setting.PlayerSetting;

final class MpvAudioSettings {

    private MpvAudioSettings() {
    }

    static String buildFilter() {
        return buildFilter(PlayerSetting.isLoudnessNormalization(), PlayerSetting.getAudioChannelMode());
    }

    static String buildFilter(boolean normalization, int mode) {
        String channel = switch (Math.clamp(mode, 0, 3)) {
            case 1 -> "lavfi=[aformat=channel_layouts=stereo]";
            case 2 -> "lavfi=[pan=stereo|FL=0.5*FL+0.5*FR|FR=0.5*FL+0.5*FR]";
            case 3 -> "lavfi=[pan=stereo|FL=FR|FR=FR]";
            default -> "";
        };
        String loudness = normalization ? "lavfi=[loudnorm=I=-18:LRA=11:TP=-1.5]" : "";
        if (channel.isEmpty()) return loudness;
        if (loudness.isEmpty()) return channel;
        return channel + "," + loudness;
    }
}
