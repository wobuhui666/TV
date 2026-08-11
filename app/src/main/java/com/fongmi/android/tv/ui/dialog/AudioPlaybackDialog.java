package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class AudioPlaybackDialog {

    private AudioPlaybackDialog() {
    }

    public static void show(FragmentActivity activity, PlayerManager player) {
        String[] modes = activity.getResources().getStringArray(R.array.select_audio_channel_mode);
        String[] items = new String[modes.length + 1];
        items[0] = activity.getString(R.string.player_loudness_normalization);
        System.arraycopy(modes, 0, items, 1, modes.length);
        boolean[] checked = new boolean[items.length];
        checked[0] = PlayerSetting.isLoudnessNormalization();
        checked[PlayerSetting.getAudioChannelMode() + 1] = true;
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.player_audio_settings)
                .setMultiChoiceItems(items, checked, (dialog, which, value) -> {
                    if (which == 0) {
                        checked[0] = value;
                    } else {
                        for (int i = 1; i < checked.length; i++) checked[i] = i == which;
                        ((androidx.appcompat.app.AlertDialog) dialog).getListView().setItemChecked(which, true);
                    }
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    PlayerSetting.putLoudnessNormalization(checked[0]);
                    int mode = 0;
                    for (int i = 1; i < checked.length; i++) if (checked[i]) {
                        mode = i - 1;
                        break;
                    }
                    PlayerSetting.putAudioChannelMode(mode);
                    if (player != null) player.applyAudioSettings();
                })
                .show();
    }
}
