package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Tracks;
import androidx.media3.ui.DefaultTrackNameProvider;
import androidx.media3.ui.TrackNameProvider;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.databinding.DialogTrackBinding;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.ui.adapter.TrackAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class TrackDialog extends BasePlaybackSelectionDialog implements TrackAdapter.OnClickListener {

    private final TrackNameProvider provider;
    private final TrackAdapter adapter;
    private DialogTrackBinding binding;
    private PlayerManager player;
    private boolean selectionHandled;
    private int type;
    private boolean secondary;

    public static TrackDialog create() {
        return new TrackDialog();
    }

    public TrackDialog() {
        this.adapter = new TrackAdapter(this);
        this.provider = new DefaultTrackNameProvider(App.get().getResources());
    }

    public TrackDialog player(PlayerManager player) {
        this.player = player;
        return this;
    }

    public TrackDialog type(int type) {
        this.type = type;
        return this;
    }

    public TrackDialog secondary(boolean secondary) { this.secondary = secondary; return this; }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof TrackDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    private boolean hasChoose() {
        return !secondary && type == C.TRACK_TYPE_TEXT && player.isVod();
    }

    private boolean hasText() {
        return type == C.TRACK_TYPE_TEXT && player.haveTrack(type);
    }

    private boolean hasAudio() {
        return type == C.TRACK_TYPE_AUDIO && player.haveTrack(type);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogTrackBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setAdapter(adapter.addAll(getTrack()));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.title.setText(ResUtil.getStringArray(R.array.select_track)[type - 1]);
        if (secondary) binding.title.setText(R.string.subtitle_secondary);
        focusRecycler(adapter.getSelected());
        binding.recycler.setVisibility(adapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
        binding.offset.setVisibility(hasText() || hasAudio() ? View.VISIBLE : View.GONE);
        binding.choose.setVisibility(hasChoose() ? View.VISIBLE : View.GONE);
        binding.subtitle.setVisibility(hasText() ? View.VISIBLE : View.GONE);
        binding.effects.setVisibility(!secondary && (type == C.TRACK_TYPE_AUDIO || type == C.TRACK_TYPE_TEXT) ? View.VISIBLE : View.GONE);
        binding.effects.setText(type == C.TRACK_TYPE_AUDIO ? R.string.audio_effect_title : R.string.subtitle_advanced);
        focusInitialView();
    }

    @Override
    protected void initEvent() {
        binding.effects.setOnClickListener(view -> {
            if (type == C.TRACK_TYPE_AUDIO) AudioEffectDialog.show(requireActivity(), player);
            else AdvancedSubtitleDialog.show(requireActivity(), player);
            dismiss();
        });
        binding.offset.setOnClickListener(this::onOffset);
        binding.choose.setOnClickListener(this::onChoose);
        binding.subtitle.setOnClickListener(this::onSubtitle);
    }

    private void onOffset(View view) {
        OffsetDialog.create().player(player).type(type).show(requireActivity());
        dismiss();
    }

    private void onChoose(View view) {
        FileChooser.from(launcher).show(new String[]{MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_TTML, MimeTypes.APPLICATION_PGS, "audio/*", "text/*", "application/octet-stream"});
        player.pause();
    }

    private void onSubtitle(View view) {
        Listener listener = (Listener) requireActivity();
        listener.onSubtitleClick();
        dismiss();
    }

    private void focusRecycler(int position) {
        DialogFocus.requestRecyclerFocus(binding.recycler, position, adapter.getItemCount());
    }

    private void focusInitialView() {
        if (adapter.getItemCount() > 0) return;
        View target = binding.choose;
        if (target.getVisibility() != View.VISIBLE) target = binding.offset;
        if (target.getVisibility() != View.VISIBLE) target = binding.subtitle;
        if (target.getVisibility() != View.VISIBLE) target = binding.effects;
        if (target.getVisibility() != View.VISIBLE) return;
        View focusTarget = target;
        focusTarget.post(() -> {
            if (focusTarget.isShown() && focusTarget.isEnabled()) focusTarget.requestFocus();
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0 && !hasVisibleAction()) dismiss();
    }

    private boolean hasVisibleAction() {
        return binding.effects.getVisibility() == View.VISIBLE
                || binding.choose.getVisibility() == View.VISIBLE
                || binding.offset.getVisibility() == View.VISIBLE
                || binding.subtitle.getVisibility() == View.VISIBLE;
    }

    private List<Track> getTrack() {
        List<Track> items = new ArrayList<>();
        addTrack(items);
        return items;
    }

    private void addTrack(List<Track> items) {
        List<Tracks.Group> groups = player.getCurrentTracks().getGroups();
        for (int i = 0; i < groups.size(); i++) {
            Tracks.Group trackGroup = groups.get(i);
            if (trackGroup.getType() != type) continue;
            for (int j = 0; j < trackGroup.length; j++) {
                Format format = trackGroup.getTrackFormat(j);
                String name = provider.getTrackName(format);
                Track item = new Track(type, name, PlayerHelper.describeFormat(format));
                item.setLocalSubtitle(type == C.TRACK_TYPE_TEXT
                        && com.fongmi.android.tv.player.subtitle.LocalSubtitleOrigin.isLocal(format));
                item.setSelected(trackGroup.isTrackSelected(j));
                if (secondary) item.setSelected(item.getFormat().equals(player.getSecondaryTrack()));
                items.add(item);
            }
        }
    }

    @Override
    public void onItemClick(Track item) {
        if (secondary) {
            player.setSecondaryTrack(item.toggle());
            dismiss();
            return;
        }
        // Selecting an already active audio or video track must be idempotent. Toggling it off
        // creates an empty TrackSelectionOverride, tears down the decoder/renderer and can race
        // with a second remote click while the bottom sheet is closing. Text tracks intentionally
        // keep their toggle-off behavior so users can disable subtitles.
        if (selectionHandled) return;
        selectionHandled = true;
        boolean isAudioOrVideo = item.getType() == C.TRACK_TYPE_AUDIO || item.getType() == C.TRACK_TYPE_VIDEO;
        if (!(isAudioOrVideo && item.isSelected())) {
            player.setTrack(Arrays.asList(item.toggle().key(player.getKey()).save()));
        }
        dismiss();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        player.setSub(Sub.from(FileChooser.getPathFromUri(result.getData().getData())));
        dismiss();
    });

    public interface Listener {

        void onSubtitleClick();
    }
}
