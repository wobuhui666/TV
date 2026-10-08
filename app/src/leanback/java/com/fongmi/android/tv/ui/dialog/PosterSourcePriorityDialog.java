package com.fongmi.android.tv.ui.dialog;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterPosterSourcePriorityBinding;
import com.fongmi.android.tv.databinding.DialogPosterSourcePriorityBinding;
import com.fongmi.android.tv.setting.PosterSourcePrioritySetting;
import com.fongmi.android.tv.ui.custom.JetStreamDialogDecor;
import com.fongmi.android.tv.ui.custom.TouchFocus;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native draft editor. Only Save changes the current configuration's poster-source order. */
public final class PosterSourcePriorityDialog {
    private final FragmentActivity activity;
    private final Runnable onChanged;
    private final String configurationUrl;
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final List<String> draft;
    private final DialogPosterSourcePriorityBinding binding;
    private final PriorityAdapter adapter = new PriorityAdapter();
    private final AlertDialog dialog;
    private AlertDialog picker;
    private int focusRequest;

    public static void show(FragmentActivity activity, Runnable onChanged) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        new PosterSourcePriorityDialog(activity, onChanged).show();
    }

    private PosterSourcePriorityDialog(FragmentActivity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        configurationUrl = VodConfig.getUrl();
        for (Site site : VodConfig.get().getSites()) {
            if (site == null || !site.isSearchable() || site.getKey().isBlank()) continue;
            sources.putIfAbsent(site.getKey(), site.getName().isBlank() ? site.getKey() : site.getName());
        }
        draft = new ArrayList<>(PosterSourcePrioritySetting.getOrderedKeys());
        draft.retainAll(sources.keySet());
        binding = DialogPosterSourcePriorityBinding.inflate(LayoutInflater.from(activity));
        dialog = new MaterialAlertDialogBuilder(activity).setView(binding.getRoot()).create();
        binding.recycler.setLayoutManager(new LinearLayoutManager(activity));
        binding.recycler.setItemAnimator(null);
        binding.recycler.setAdapter(adapter);
        binding.getRoot().setClipChildren(true);
        binding.getRoot().setClipToPadding(true);
        TouchFocus.bind(binding.add, binding.reset, binding.cancel, binding.save);
        binding.add.setOnClickListener(view -> addSources());
        binding.reset.setOnClickListener(view -> {
            draft.clear();
            render();
            (binding.add.isEnabled() ? binding.add : binding.save).requestFocus();
        });
        binding.cancel.setOnClickListener(view -> dialog.dismiss());
        binding.save.setOnClickListener(view -> save());
        DefaultLifecycleObserver lifecycle = new DefaultLifecycleObserver() {
            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                dialog.dismiss();
            }
        };
        activity.getLifecycle().addObserver(lifecycle);
        dialog.setOnDismissListener(ignored -> {
            focusRequest++;
            if (picker != null) picker.dismiss();
            activity.getLifecycle().removeObserver(lifecycle);
            binding.recycler.setAdapter(null);
        });
        render();
    }

    private void show() {
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(R.style.JetStreamDialogAnim);
            window.setLayout((int) (ResUtil.getScreenWidth(activity) * 0.86f),
                    (int) (ResUtil.getScreenHeight(activity) * 0.90f));
        }
        (binding.add.isEnabled() ? binding.add : binding.save).requestFocus();
    }

    private void render() {
        focusRequest++;
        adapter.notifyDataSetChanged();
        binding.empty.setVisibility(draft.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(draft.isEmpty() ? View.GONE : View.VISIBLE);
        binding.empty.setText(sources.isEmpty() ? R.string.poster_source_priority_no_sites : R.string.poster_source_priority_empty);
        binding.status.setText(draft.isEmpty()
                ? activity.getString(R.string.poster_source_priority_default_status, sources.size())
                : activity.getString(R.string.poster_source_priority_status, draft.size(), sources.size() - draft.size()));
        enable(binding.add, draft.size() < Math.min(sources.size(), PosterSourcePrioritySetting.MAX_SOURCES));
        enable(binding.reset, !draft.isEmpty());
    }

    private static void enable(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setFocusable(enabled);
        view.setFocusableInTouchMode(enabled);
        view.setAlpha(enabled ? 1f : 0.4f);
    }

    private void addSources() {
        List<String> available = new ArrayList<>(sources.keySet());
        available.removeAll(draft);
        if (available.isEmpty() || draft.size() >= PosterSourcePrioritySetting.MAX_SOURCES) return;
        String[] labels = new String[available.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = sources.get(available.get(i));
        boolean[] selected = new boolean[labels.length];
        String[] firstAdded = {null};
        picker = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.poster_source_priority_add_title)
                .setMultiChoiceItems(labels, selected, (choiceDialog, which, checked) -> {
                    int count = draft.size();
                    for (boolean value : selected) if (value) count++;
                    if (checked && count > PosterSourcePrioritySetting.MAX_SOURCES) {
                        selected[which] = false;
                        ((AlertDialog) choiceDialog).getListView().setItemChecked(which, false);
                        Toast.makeText(activity, activity.getString(R.string.poster_source_priority_limit,
                                PosterSourcePrioritySetting.MAX_SOURCES), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.poster_source_priority_cancel, null)
                .setPositiveButton(R.string.poster_source_priority_add_selected, (choiceDialog, which) -> {
                    for (int i = 0; i < selected.length; i++) {
                        if (!selected[i] || draft.size() >= PosterSourcePrioritySetting.MAX_SOURCES) continue;
                        String key = available.get(i);
                        if (firstAdded[0] == null) firstAdded[0] = key;
                        draft.add(key);
                    }
                    render();
                }).create();
        picker.setOnDismissListener(ignored -> {
            picker = null;
            if (!dialog.isShowing()) return;
            if (firstAdded[0] != null) focusRow(firstAdded[0], R.id.priorityMoveUp);
            else (binding.add.isEnabled() ? binding.add : binding.save).requestFocus();
        });
        picker.show();
        JetStreamDialogDecor.tintButtons(picker);
        TouchFocus.bind(picker.getButton(AlertDialog.BUTTON_POSITIVE), picker.getButton(AlertDialog.BUTTON_NEGATIVE));
        picker.getListView().requestFocus();
        picker.getListView().setSelection(0);
    }

    private void move(String key, int offset) {
        int from = draft.indexOf(key);
        int to = from + offset;
        if (from < 0 || to < 0 || to >= draft.size()) return;
        Collections.swap(draft, from, to);
        render();
        focusRow(key, offset < 0 ? R.id.priorityMoveUp : R.id.priorityMoveDown);
    }

    private void remove(String key) {
        int index = draft.indexOf(key);
        if (index < 0) return;
        draft.remove(index);
        render();
        if (draft.isEmpty()) binding.add.requestFocus();
        else focusRow(draft.get(Math.min(index, draft.size() - 1)), R.id.priorityRemove);
    }

    private void focusRow(String key, int action) {
        int position = draft.indexOf(key);
        if (position < 0) return;
        int request = ++focusRequest;
        binding.recycler.scrollToPosition(position);
        binding.recycler.postOnAnimation(() -> focusRowAfterLayout(key, action, request, 2));
    }

    private void focusRowAfterLayout(String key, int action, int request, int retries) {
        if (!dialog.isShowing() || request != focusRequest) return;
        RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(draft.indexOf(key));
        if (holder instanceof PriorityHolder priorityHolder) {
            View target = priorityHolder.itemView.findViewById(action);
            if (target != null && target.isEnabled()) target.requestFocus();
            else priorityHolder.binding.priorityRemove.requestFocus();
        } else if (retries > 0) {
            binding.recycler.postOnAnimation(() -> focusRowAfterLayout(key, action, request, retries - 1));
        }
    }

    private void save() {
        if (!Objects.equals(configurationUrl, VodConfig.getUrl())) {
            Toast.makeText(activity, R.string.poster_source_priority_config_changed, Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }
        boolean changed = !draft.equals(PosterSourcePrioritySetting.getOrderedKeys());
        if (changed) {
            if (draft.isEmpty()) PosterSourcePrioritySetting.clear();
            else PosterSourcePrioritySetting.putOrderedKeys(draft);
        }
        dialog.dismiss();
        if (changed && onChanged != null) onChanged.run();
    }

    private final class PriorityAdapter extends RecyclerView.Adapter<PriorityHolder> {
        @NonNull
        @Override public PriorityHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PriorityHolder(AdapterPosterSourcePriorityBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override public void onBindViewHolder(@NonNull PriorityHolder holder, int position) {
            String key = draft.get(position);
            String name = sources.get(key);
            holder.binding.rank.setText(String.valueOf(position + 1));
            holder.binding.name.setText(name);
            holder.binding.name.setContentDescription(activity.getString(R.string.poster_source_priority_rank_description, position + 1, name));
            holder.binding.priorityMoveUp.setContentDescription(activity.getString(position == 0
                    ? R.string.poster_source_priority_first_description : R.string.poster_source_priority_up_description, name));
            holder.binding.priorityMoveDown.setContentDescription(activity.getString(position == draft.size() - 1
                    ? R.string.poster_source_priority_last_description : R.string.poster_source_priority_down_description, name));
            holder.binding.priorityRemove.setContentDescription(activity.getString(R.string.poster_source_priority_remove_description, name));
            // Keep the remote on the same action at an edge. Repeated OK must not jump to Remove.
            holder.binding.priorityMoveUp.setAlpha(position > 0 ? 1f : 0.4f);
            holder.binding.priorityMoveDown.setAlpha(position < draft.size() - 1 ? 1f : 0.4f);
            holder.binding.priorityMoveUp.setOnClickListener(view -> move(key, -1));
            holder.binding.priorityMoveDown.setOnClickListener(view -> move(key, 1));
            holder.binding.priorityRemove.setOnClickListener(view -> remove(key));
        }

        @Override public int getItemCount() {
            return draft.size();
        }
    }

    private static final class PriorityHolder extends RecyclerView.ViewHolder {
        private final AdapterPosterSourcePriorityBinding binding;

        private PriorityHolder(AdapterPosterSourcePriorityBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            TouchFocus.bind(binding.priorityMoveUp, binding.priorityMoveDown, binding.priorityRemove);
        }
    }
}
