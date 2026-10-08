package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogHistoryActionsBinding;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.ui.activity.CollectActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.custom.TouchFocus;
import com.fongmi.android.tv.utils.HistoryProgressText;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** A menu for one captured record. Opening, cancelling and finding sources never change history. */
public final class HistoryActionsDialog {
    public interface Listener {
        void onDelete(int cid, String key);

        void onClosed(boolean restoreFocus);
    }

    private final FragmentActivity activity;
    private final DialogHistoryActionsBinding binding;
    private final AlertDialog dialog;
    private final int cid;
    private final String key;
    private final String title;
    private final String picture;
    private final String siteKey;
    private final String vodId;
    private boolean restoreFocus = true;
    private boolean actionSelected;
    private int openingKeyCode;
    private final long openingKeyDownTime;

    public static HistoryActionsDialog show(FragmentActivity activity, History item, KeyEvent openingKey, Listener listener) {
        if (activity.isFinishing() || activity.isDestroyed() || item.getCid() != VodConfig.getCid()
                || !BrowseExperienceSettings.isHistoryActionsEnabled()) return null;
        HistoryActionsDialog menu = new HistoryActionsDialog(activity, item, openingKey, listener);
        menu.show();
        return menu;
    }

    private HistoryActionsDialog(FragmentActivity activity, History item, KeyEvent openingKey, Listener listener) {
        this.activity = activity;
        openingKeyCode = openingKey == null ? KeyEvent.KEYCODE_UNKNOWN : openingKey.getKeyCode();
        openingKeyDownTime = openingKey == null ? 0 : openingKey.getDownTime();
        // Do not retain a mutable adapter item: refreshes and configuration switches can replace it.
        cid = item.getCid();
        key = item.getKey();
        title = item.getVodName();
        picture = item.getVodPic();
        siteKey = item.getSiteKey();
        vodId = item.getVodId();
        binding = DialogHistoryActionsBinding.inflate(LayoutInflater.from(activity));
        dialog = new MaterialAlertDialogBuilder(activity).setView(binding.getRoot()).create();
        binding.historyActionsTitle.setText(TextUtils.isEmpty(title) ? activity.getString(R.string.history_actions_title) : title);
        String episode = TextUtils.isEmpty(item.getVodRemarks())
                ? activity.getString(R.string.history_actions_episode_unknown) : item.getVodRemarks();
        String source = TextUtils.isEmpty(item.getSiteName()) ? siteKey : item.getSiteName();
        binding.historyActionsEpisode.setText(activity.getString(R.string.history_actions_episode, episode));
        binding.historyActionsProgress.setText(activity.getString(R.string.history_played_time,
                HistoryProgressText.elapsed(item.getPosition(), item.getDuration())));
        binding.historyActionsSource.setText(activity.getString(R.string.history_actions_source, source));
        TouchFocus.bind(binding.historyActionsContinue, binding.historyActionsSearch,
                binding.historyActionsDelete, binding.historyActionsCancel);
        binding.historyActionsContinue.setOnClickListener(view -> select(() ->
                VideoActivity.start(activity, siteKey, vodId, title, picture), false));
        binding.historyActionsSearch.setOnClickListener(view -> select(() ->
                CollectActivity.start(activity, title), false));
        binding.historyActionsDelete.setOnClickListener(view -> select(() -> listener.onDelete(cid, key), true));
        binding.historyActionsCancel.setOnClickListener(view -> dialog.cancel());
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            // A held OK/Enter key belongs to the card. Its tail must not activate the newly focused Continue button.
            if (openingKeyCode != KeyEvent.KEYCODE_UNKNOWN && keyCode == openingKeyCode
                    && event.getDownTime() == openingKeyDownTime) {
                if (event.getAction() == KeyEvent.ACTION_UP) openingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
                return true;
            }
            // A fresh press of the same key is a new action, even if its original release was lost.
            if (keyCode == openingKeyCode && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                openingKeyCode = KeyEvent.KEYCODE_UNKNOWN;
            }
            return keyCode == KeyEvent.KEYCODE_MENU;
        });
        DefaultLifecycleObserver lifecycle = new DefaultLifecycleObserver() {
            @Override public void onPause(@NonNull LifecycleOwner owner) {
                dismiss();
            }

            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                dismiss();
            }
        };
        activity.getLifecycle().addObserver(lifecycle);
        dialog.setOnDismissListener(ignored -> {
            activity.getLifecycle().removeObserver(lifecycle);
            listener.onClosed(restoreFocus && canAct());
        });
    }

    private void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(R.style.JetStreamDialogAnim);
            window.setLayout(Math.min(ResUtil.dp2px(480), (int) (ResUtil.getScreenWidth(activity) * 0.86f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        binding.historyActionsContinue.requestFocus();
    }

    private boolean canAct() {
        return !activity.isFinishing() && !activity.isDestroyed()
                && activity.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                && cid == VodConfig.getCid() && BrowseExperienceSettings.isHistoryActionsEnabled();
    }

    private void select(Runnable action, boolean returnToHistory) {
        if (actionSelected) return;
        if (!canAct()) {
            dismiss();
            return;
        }
        actionSelected = true;
        restoreFocus = returnToHistory;
        // Remove the captured item before dismissal restores focus to its nearest remaining neighbour.
        if (returnToHistory) action.run();
        dialog.dismiss();
        if (!returnToHistory) action.run();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public void dismiss() {
        restoreFocus = false;
        dialog.dismiss();
    }
}
