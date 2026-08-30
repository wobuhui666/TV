package com.fongmi.android.tv.ui.dialog;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSpeedBinding;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class PreloadDialog extends BaseAlertDialog {

    public static final int SIZE = 1;
    public static final int TIME = 2;

    public interface Listener {
        void setPreload(int type, int value);
    }

    private DialogSpeedBinding binding;
    private int type;

    public static void show(FragmentActivity activity, int type) {
        PreloadDialog dialog = new PreloadDialog();
        Bundle args = new Bundle();
        args.putInt("type", type);
        dialog.setArguments(args);
        dialog.show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSpeedBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        type = requireArguments().getInt("type");
        binding.title.setText(getTitleRes());
        binding.slider.setValueTo(getMax());
        binding.slider.setValueFrom(getMin());
        binding.slider.setStepSize(getStep());
        binding.slider.setValue(getValue());
        binding.slider.setLabelFormatter(value -> format(Math.round(value)));
        binding.slider.post(() -> {
            if (binding.slider.isShown() && binding.slider.isEnabled()) binding.slider.requestFocus();
        });
    }

    @Override
    protected void initEvent() {
        binding.slider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && requireActivity() instanceof Listener listener) listener.setPreload(type, Math.round(value));
        });
        binding.slider.setOnKeyListener((view, keyCode, event) -> {
            boolean enter = KeyUtil.isEnterKey(event);
            if (enter) dismiss();
            return enter;
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
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

    private int getTitleRes() {
        if (type == SIZE) return R.string.player_preload_size;
        return R.string.player_preload_time;
    }

    private String format(int value) {
        if (type == SIZE) return FileUtil.byteCountToDisplaySize(value * 1024L * 1024L);
        return getString(R.string.player_preload_time_value, value);
    }
}
