package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSpeedBinding;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.ui.fragment.SettingPreloadFragment;
import com.fongmi.android.tv.utils.FileUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class PreloadDialog extends BaseAlertDialog {

    public static final int SIZE = 1;
    public static final int TIME = 2;

    private DialogSpeedBinding binding;
    private int type;
    private int value;

    public static void show(Fragment fragment, int type) {
        PreloadDialog dialog = new PreloadDialog();
        Bundle args = new Bundle();
        args.putInt("type", type);
        dialog.setArguments(args);
        dialog.show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSpeedBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        type = requireArguments().getInt("type");
        return builder().setTitle(getTitle()).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, this::onNegative);
    }

    @Override
    protected void initView() {
        binding.slider.setValueTo(getMax());
        binding.slider.setValueFrom(getMin());
        binding.slider.setStepSize(getStep());
        binding.slider.setValue(value = getValue());
        binding.slider.setLabelFormatter(value -> format(Math.round(value)));
    }

    private void onPositive(DialogInterface dialog, int which) {
        ((SettingPreloadFragment) requireParentFragment()).setPreload(type, Math.round(binding.slider.getValue()));
    }

    private void onNegative(DialogInterface dialog, int which) {
        ((SettingPreloadFragment) requireParentFragment()).setPreload(type, value);
    }

    private int getTitle() {
        if (type == SIZE) return R.string.player_preload_size;
        return R.string.player_preload_time;
    }

    private int getMin() {
        if (type == SIZE) return PreloadSetting.MIN_SIZE_MB;
        return PreloadSetting.MIN_TIME_SECONDS;
    }

    private int getMax() {
        if (type == SIZE) return PreloadSetting.MAX_SIZE_MB;
        return PreloadSetting.MAX_TIME_SECONDS;
    }

    private int getStep() {
        if (type == SIZE) return PreloadSetting.STEP_SIZE_MB;
        if (type == TIME) return PreloadSetting.STEP_TIME_SECONDS;
        return 1;
    }

    private int getValue() {
        if (type == SIZE) return PreloadSetting.getPreloadSizeMb();
        return PreloadSetting.getPreloadTimeSeconds();
    }

    private String format(int value) {
        if (type == SIZE) return FileUtil.byteCountToDisplaySize(value * 1024L * 1024L);
        return getString(R.string.player_preload_time_value, value);
    }
}
