package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.media3.ui.SubtitleView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogSubtitleBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.bassaer.library.MDColor;

public final class SubtitleDialog {

    private SubtitleView subtitleView;
    private PlayerManager player;

    public static SubtitleDialog create() {
        return new SubtitleDialog();
    }

    public SubtitleDialog view(SubtitleView subtitleView) {
        this.subtitleView = subtitleView;
        return this;
    }

    public SubtitleDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public void show(FragmentActivity activity) {
        FragmentManager manager = activity.getSupportFragmentManager();
        for (Fragment fragment : manager.getFragments()) if (fragment instanceof BottomSheet || fragment instanceof SideSheet) return;
        if (Util.isLeanback()) new SideSheet(subtitleView, player).show(manager, null);
        else new BottomSheet(subtitleView, player).show(manager, null);
    }

    private static DialogSubtitleBinding inflate(LayoutInflater inflater, ViewGroup container) {
        return DialogSubtitleBinding.inflate(inflater, container, false);
    }

    private static void initEvent(DialogSubtitleBinding binding, SubtitleView subtitleView, PlayerManager player) {
        binding.up.setOnClickListener(view -> onUp(subtitleView, player));
        binding.down.setOnClickListener(view -> onDown(subtitleView, player));
        binding.large.setOnClickListener(view -> onLarge(subtitleView, player));
        binding.small.setOnClickListener(view -> onSmall(subtitleView, player));
        binding.reset.setOnClickListener(view -> onReset(subtitleView, player));
    }

    private static void onUp(SubtitleView subtitleView, PlayerManager player) {
        float position = PlayerSetting.getSubtitlePosition() + 0.005f;
        subtitleView.setBottomPosition(position);
        PlayerSetting.putSubtitlePosition(position);
        applySubtitleStyle(player);
    }

    private static void onDown(SubtitleView subtitleView, PlayerManager player) {
        float position = PlayerSetting.getSubtitlePosition() - 0.005f;
        subtitleView.setBottomPosition(position);
        PlayerSetting.putSubtitlePosition(position);
        applySubtitleStyle(player);
    }

    private static void onLarge(SubtitleView subtitleView, PlayerManager player) {
        float textSize = getSubtitleTextSize() + 0.002f;
        subtitleView.setFractionalTextSize(textSize);
        PlayerSetting.putSubtitleTextSize(textSize);
        applySubtitleStyle(player);
    }

    private static void onSmall(SubtitleView subtitleView, PlayerManager player) {
        float textSize = getSubtitleTextSize() - 0.002f;
        subtitleView.setFractionalTextSize(textSize);
        PlayerSetting.putSubtitleTextSize(textSize);
        applySubtitleStyle(player);
    }

    private static float getSubtitleTextSize() {
        float textSize = PlayerSetting.getSubtitleTextSize();
        return textSize == 0.0f ? SubtitleView.DEFAULT_TEXT_SIZE_FRACTION : textSize;
    }

    private static void onReset(SubtitleView subtitleView, PlayerManager player) {
        PlayerSetting.putSubtitleTextSize(0.0f);
        PlayerSetting.putSubtitlePosition(0.0f);
        subtitleView.reset();
        applySubtitleStyle(player);
    }

    private static void applySubtitleStyle(PlayerManager player) {
        if (player != null && !player.isReleased()) player.setSubtitleStyle();
    }

    private static void tintImages(View view) {
        if (view instanceof ImageView imageView && imageView.getDrawable() != null) imageView.getDrawable().setTint(MDColor.WHITE);
        if (!(view instanceof ViewGroup group)) return;
        for (int i = 0; i < group.getChildCount(); i++) tintImages(group.getChildAt(i));
    }

    public static final class BottomSheet extends BaseBottomSheetDialog {

        private final SubtitleView subtitleView;
        private final PlayerManager player;
        private DialogSubtitleBinding binding;

        BottomSheet(SubtitleView subtitleView, PlayerManager player) {
            this.subtitleView = subtitleView;
            this.player = player;
        }

        private boolean isFull() {
            return Util.isFullscreen(getActivity());
        }

        @Override
        protected boolean transparent() {
            return isFull();
        }

        @Override
        protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
            return binding = SubtitleDialog.inflate(inflater, container);
        }

        @Override
        protected void initView() {
            if (isFull()) tintImages(binding.getRoot());
        }

        @Override
        protected void initEvent() {
            SubtitleDialog.initEvent(binding, subtitleView, player);
        }

        private int getDialogWidth() {
            return isFull() ? 232 : 216;
        }

        @Override
        public void onResume() {
            super.onResume();
            if (getDialog() == null || getDialog().getWindow() == null) return;
            getDialog().getWindow().setLayout(ResUtil.dp2px(getDialogWidth()), -1);
        }
    }

    public static final class SideSheet extends BaseSideSheetDialog {

        private final SubtitleView subtitleView;
        private final PlayerManager player;
        private DialogSubtitleBinding binding;

        SideSheet(SubtitleView subtitleView, PlayerManager player) {
            this.subtitleView = subtitleView;
            this.player = player;
        }

        @Override
        protected int getWidth() {
            return Math.min(ResUtil.dp2px(420), ResUtil.getScreenWidth() / 2);
        }

        @Override
        protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
            return binding = SubtitleDialog.inflate(inflater, container);
        }

        @Override
        protected void initView() {
            binding.large.post(() -> {
                if (binding.large.isShown() && binding.large.isEnabled()) binding.large.requestFocus();
            });
        }

        @Override
        protected void initEvent() {
            SubtitleDialog.initEvent(binding, subtitleView, player);
        }
    }
}
