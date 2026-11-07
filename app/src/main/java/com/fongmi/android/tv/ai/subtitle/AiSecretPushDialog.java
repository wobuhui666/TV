package com.fongmi.android.tv.ai.subtitle;

import android.os.Bundle;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogAiSecretPushBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.dialog.BaseAlertDialog;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public final class AiSecretPushDialog extends BaseAlertDialog {
    public static final String TARGET_MTRAN_TOKEN = "mtran_token";
    public static final String TARGET_API_KEY = "api_key";
    private static final String ARG_TARGET = "target";

    private DialogAiSecretPushBinding binding;

    public static AiSecretPushDialog create(String target) {
        AiSecretPushDialog dialog = new AiSecretPushDialog();
        Bundle args = new Bundle();
        args.putString(ARG_TARGET, target);
        dialog.setArguments(args);
        return dialog;
    }

    public void show(FragmentActivity activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof AiSecretPushDialog) return;
        }
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogAiSecretPushBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        boolean token = TARGET_MTRAN_TOKEN.equals(target());
        Server.get().start();
        binding.title.setText(token ? "手机推送 MTran Token" : "手机推送 API Key");
        binding.textLayout.setHint(token ? "MTran Token" : "API Key");
        binding.code.setImageBitmap(QRCode.getThemedBitmap(requireContext(), Server.get().getAddress(4), 220, 4));
        binding.info.setText(ResUtil.getString(R.string.ai_secret_push_info, Server.get().getAddress()));
        binding.text.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(v -> dismiss());
    }

    private String target() {
        return requireArguments().getString(ARG_TARGET, TARGET_API_KEY);
    }

    private void onPositive(View view) {
        String value = binding.text.getText() == null ? "" : binding.text.getText().toString().trim();
        if (value.isEmpty()) return;
        ((Listener) requireActivity()).onAiSecretInput(target(), value);
        dismiss();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.SETTING || event.text() == null || event.text().isBlank()) return;
        binding.text.setText(event.text());
        binding.text.setSelection(binding.text.length());
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.60f);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    public interface Listener {
        void onAiSecretInput(String target, String value);
    }
}
