package com.fongmi.android.tv.ui.dialog;

import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.playback.PlaybackSyncEndpoint;
import com.fongmi.android.tv.playback.PlaybackSyncSetting;
import com.fongmi.android.tv.playback.PlaybackSyncStore;
import com.fongmi.android.tv.playback.PlaybackWebhookDispatcher;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class PlaybackSyncDialog extends BaseAlertDialog {

    private LinearLayout root;
    private LinearLayout endpoints;
    private TextView summary;

    public static PlaybackSyncDialog create() {
        return new PlaybackSyncDialog();
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(24);
        root.setPadding(padding, padding / 2, padding, padding);
        return () -> root;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle("观影记录同步").setView(getBinding().getRoot()).setNegativeButton("关闭", null);
    }

    @Override
    protected void initView() {
        SwitchMaterial enabled = toggle("启用公网跨设备同步", PlaybackSyncSetting.isEnabled());
        SwitchMaterial localWrite = toggle("允许本机 API 修改（默认关闭）", PlaybackSyncSetting.isLocalWriteEnabled());
        enabled.setOnCheckedChangeListener((button, checked) -> PlaybackSyncSetting.setEnabled(checked));
        localWrite.setOnCheckedChangeListener((button, checked) -> PlaybackSyncSetting.setLocalWriteEnabled(checked));
        summary = text("");
        endpoints = new LinearLayout(requireContext());
        endpoints.setOrientation(LinearLayout.VERTICAL);
        root.addView(enabled);
        root.addView(localWrite);
        root.addView(summary);
        root.addView(endpoints);
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("新增拉取", v -> edit(new PlaybackSyncEndpoint(), "remote")), weight());
        actions.addView(button("新增 Webhook", v -> edit(new PlaybackSyncEndpoint(), "webhook")), weight());
        actions.addView(button("Cloudflare 快配", v -> quickCloudflare()), weight());
        root.addView(actions);
        LinearLayout operations = new LinearLayout(requireContext());
        operations.setOrientation(LinearLayout.HORIZONTAL);
        operations.addView(button("立即同步", v -> PlaybackSyncStore.syncNow()), weight());
        operations.addView(button("重试失败投递", v -> PlaybackWebhookDispatcher.retryFailed()), weight());
        root.addView(operations);
        render();
    }

    private SwitchMaterial toggle(String label, boolean checked) {
        SwitchMaterial view = new SwitchMaterial(requireContext());
        view.setText(label);
        view.setChecked(checked);
        view.setPadding(0, ResUtil.dp2px(8), 0, ResUtil.dp2px(8));
        return view;
    }

    private TextView text(String value) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setPadding(0, ResUtil.dp2px(8), 0, ResUtil.dp2px(8));
        return view;
    }

    private MaterialButton button(String value, View.OnClickListener listener) {
        MaterialButton button = new MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(value);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private void render() {
        List<PlaybackSyncEndpoint> items = PlaybackSyncStore.get();
        long failures = items.stream().filter(item -> !TextUtils.isEmpty(item.lastError)).count();
        summary.setText("来源/端点：" + items.size() + " · 异常：" + failures + " · Token 默认隐藏");
        endpoints.removeAllViews();
        for (PlaybackSyncEndpoint endpoint : items) {
            String status = endpoint.lastError.isEmpty() ? (endpoint.lastSuccessAt > 0 ? "正常" : "未同步") : "失败";
            MaterialButton row = button(("webhook".equals(endpoint.kind) ? "推送" : "拉取") + " · " + endpoint.name + " · " + status + " · Token " + mask(endpoint.token), v -> edit(endpoint, endpoint.kind));
            row.setOnLongClickListener(v -> {
                confirmDelete(endpoint);
                return true;
            });
            endpoints.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void confirmDelete(PlaybackSyncEndpoint endpoint) {
        new MaterialAlertDialogBuilder(requireContext()).setTitle("删除同步配置").setMessage(endpoint.name)
                .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> {
                    List<PlaybackSyncEndpoint> items = PlaybackSyncStore.get();
                    items.removeIf(item -> item.id.equals(endpoint.id));
                    PlaybackSyncStore.save(items);
                    render();
                }).show();
    }

    private void edit(PlaybackSyncEndpoint endpoint, String kind) {
        boolean fresh = TextUtils.isEmpty(endpoint.id);
        LinearLayout form = form();
        EditText name = input(form, "名称", endpoint.name, InputType.TYPE_CLASS_TEXT);
        EditText url = input(form, "URL", endpoint.url, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText token = input(form, "Token", endpoint.token, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        SwitchMaterial reveal = toggle("显示 Token", false);
        reveal.setOnCheckedChangeListener((button, checked) -> token.setInputType(InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD)));
        form.addView(reveal);
        EditText sites = input(form, "站点过滤（逗号分隔，留空为全部）", String.join(",", endpoint.siteKeys), InputType.TYPE_CLASS_TEXT);
        EditText period = input(form, "周期分钟（0 为关闭）", String.valueOf(endpoint.periodMinutes), InputType.TYPE_CLASS_NUMBER);
        EditText limit = input(form, "单次上限（1～1000）", String.valueOf(endpoint.limit), InputType.TYPE_CLASS_NUMBER);
        EditText retries = input(form, "重试次数（0～3）", String.valueOf(endpoint.retries), InputType.TYPE_CLASS_NUMBER);
        SwitchMaterial syncOnStart = toggle("启动时同步", endpoint.syncOnStart);
        form.addView(syncOnStart);
        EditText preset = input(form, "字段预设（basic / standard / full / custom）", endpoint.fieldPreset, InputType.TYPE_CLASS_TEXT);
        EditText customFields = input(form, "自定义字段（逗号分隔）", String.join(",", endpoint.customFields), InputType.TYPE_CLASS_TEXT);
        EditText events = input(form, "事件（逗号分隔）", String.join(",", endpoint.events), InputType.TYPE_CLASS_TEXT);
        EditText interval = input(form, "进度推送间隔秒", String.valueOf(endpoint.progressIntervalSeconds), InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(requireContext()).setTitle("webhook".equals(kind) ? "Webhook 端点" : "远端拉取来源").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> {
                    endpoint.id = fresh ? UUID.randomUUID().toString() : endpoint.id;
                    endpoint.kind = kind;
                    endpoint.name = name.getText().toString().trim();
                    endpoint.url = url.getText().toString().trim();
                    endpoint.token = token.getText().toString();
                    endpoint.siteKeys = split(sites.getText().toString());
                    endpoint.periodMinutes = number(period, 0, 0, 1440);
                    endpoint.limit = number(limit, 100, 1, 1000);
                    endpoint.retries = number(retries, 3, 0, 3);
                    endpoint.syncOnStart = syncOnStart.isChecked();
                    endpoint.fieldPreset = preset(preset.getText().toString());
                    endpoint.customFields = split(customFields.getText().toString()).stream().filter(PlaybackSyncDialog::isAllowedField).toList();
                    endpoint.events = split(events.getText().toString()).stream().filter(PlaybackSyncDialog::isAllowedEvent).toList();
                    endpoint.progressIntervalSeconds = number(interval, 30, 0, 86400);
                    PlaybackSyncStore.save(endpoint);
                    render();
                }).show();
    }

    private void quickCloudflare() {
        LinearLayout form = form();
        EditText name = input(form, "名称", "Cloudflare", InputType.TYPE_CLASS_TEXT);
        EditText url = input(form, "Worker 同步 URL", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText token = input(form, "Token", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new MaterialAlertDialogBuilder(requireContext()).setTitle("Cloudflare 快速配置").setView(form).setNegativeButton("取消", null)
                .setPositiveButton("生成", (dialog, which) -> {
                    PlaybackSyncStore.quickCloudflare(name.getText().toString().trim(), url.getText().toString().trim(), token.getText().toString());
                    render();
                }).show();
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = ResUtil.dp2px(24);
        form.setPadding(padding, 0, padding, 0);
        return form;
    }

    private EditText input(LinearLayout parent, String hint, String value, int type) {
        EditText input = new EditText(requireContext());
        input.setHint(hint);
        input.setText(value == null ? "" : value);
        input.setInputType(type);
        input.setSingleLine(true);
        parent.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private int number(EditText input, int fallback, int min, int max) {
        try {
            return Math.clamp(Integer.parseInt(input.getText().toString()), min, max);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private List<String> split(String value) {
        if (TextUtils.isEmpty(value)) return new ArrayList<>();
        return Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).distinct().toList();
    }

    private String mask(String token) {
        if (TextUtils.isEmpty(token)) return "未设置";
        return token.length() <= 4 ? "••••" : token.substring(0, 2) + "••••" + token.substring(token.length() - 2);
    }

    private String preset(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return List.of("basic", "standard", "full", "custom").contains(normalized) ? normalized : "standard";
    }

    private static boolean isAllowedField(String value) {
        return List.of("siteKey", "vodId", "positionMs", "durationMs", "flag", "episodeName", "episodeUrl", "vodName", "vodPic", "speed").contains(value);
    }

    private static boolean isAllowedEvent(String value) {
        return List.of("playback.progress", "playback.ended", "playback.deleted").contains(value);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (com.fongmi.android.tv.utils.Util.isLeanback()) setWidth(0.72f);
    }
}
