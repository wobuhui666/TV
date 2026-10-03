package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivityMyBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.JetStreamDialogDecor;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class MyActivity extends BaseActivity {
    private ActivityMyBinding binding;
    private final ActivityResultLauncher<Intent> filePicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) {
            VideoActivity.start(this, result.getData().getData().toString());
        }
    });

    public static void start(Context context) {
        Intent intent = new Intent(context, MyActivity.class);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityMyBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        binding.keep.setContent(R.drawable.msr_bookmark_border, getString(R.string.home_keep), getString(R.string.my_keep_description));
        binding.history.setContent(R.drawable.msr_history, getString(R.string.my_history), getString(R.string.my_history_description));
        binding.push.setContent(R.drawable.msr_cloud_upload, getString(R.string.home_push), getString(R.string.my_push_description));
        binding.cast.setContent(R.drawable.msr_live_tv, getString(R.string.my_cast), getString(R.string.my_cast_description));
        binding.files.setContent(R.drawable.ic_folder, getString(R.string.my_files), getString(R.string.my_files_description));
        binding.settings.setContent(R.drawable.msr_settings, getString(R.string.home_setting), getString(R.string.my_settings_description));
        binding.keep.setOnClickListener(v -> KeepActivity.start(this));
        binding.history.setOnClickListener(v -> WatchHistoryActivity.start(this));
        binding.push.setOnClickListener(v -> PushActivity.start(this));
        binding.cast.setOnClickListener(v -> showCastInstructions());
        binding.files.setOnClickListener(v -> filePicker.launch(new Intent(this, FileActivity.class)));
        binding.settings.setOnClickListener(v -> SettingActivity.start(this));
        if (savedInstanceState == null) binding.keep.requestFocus();
    }

    private void showCastInstructions() {
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.my_cast)
                .setMessage(getString(R.string.my_cast_instructions, Util.getDeviceName()))
                .setPositiveButton(android.R.string.ok, null).create();
        dialog.setOnShowListener(ignored -> JetStreamDialogDecor.tintButtons(dialog));
        dialog.show();
    }
}
