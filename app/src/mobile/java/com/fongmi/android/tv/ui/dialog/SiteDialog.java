package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSiteBinding;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.ui.adapter.SiteAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.setting.SiteSortMode;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private boolean search;
    private boolean change;

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        search = true;
        return this;
    }

    public SiteDialog change() {
        change = true;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
        if (fragment instanceof SiteListener) listener = (SiteListener) fragment;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this);
        binding.recycler.setAdapter(adapter);
        adapter.search(search).change(change);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 8));
        binding.recycler.post(() -> binding.recycler.scrollToPosition(VodConfig.getHomeIndex()));
        setupSortControls();
        if (search && !change) attachReorder();
    }

    private void setupSortControls() {
        boolean visible = search && !change;
        binding.sortMode.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        binding.sortActions.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
        if (!visible) return;
        SiteSortMode mode = SiteHealthStore.getMode(VodConfig.getCid());
        binding.sortMode.check(mode == SiteSortMode.SMART ? binding.sortSmart.getId() : mode == SiteSortMode.MANUAL ? binding.sortManual.getId() : binding.sortConfig.getId());
        binding.sortMode.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            if (id == binding.sortSmart.getId()) SiteHealthStore.setMode(VodConfig.getCid(), SiteSortMode.SMART);
            if (id == binding.sortConfig.getId()) SiteHealthStore.setMode(VodConfig.getCid(), SiteSortMode.CONFIG);
            if (id == binding.sortManual.getId() && SiteHealthStore.getMode(VodConfig.getCid()) != SiteSortMode.MANUAL) {
                binding.sortMode.check(SiteHealthStore.getMode(VodConfig.getCid()) == SiteSortMode.SMART ? binding.sortSmart.getId() : binding.sortConfig.getId());
            }
        });
        binding.sortSave.setOnClickListener(v -> {
            SiteHealthStore.saveManualOrder(adapter.getItems());
            binding.sortMode.check(binding.sortManual.getId());
            dismiss();
        });
        binding.sortCancel.setOnClickListener(v -> dismiss());
        binding.sortRestore.setOnClickListener(v -> {
            SiteHealthStore.resetManualOrder();
            dismiss();
        });
    }

    private void attachReorder() {
        ItemTouchHelper helper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@androidx.annotation.NonNull RecyclerView recyclerView, @androidx.annotation.NonNull RecyclerView.ViewHolder source, @androidx.annotation.NonNull RecyclerView.ViewHolder target) {
                adapter.move(source.getBindingAdapterPosition(), target.getBindingAdapterPosition());
                return true;
            }

            @Override
            public void onSwiped(@androidx.annotation.NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public boolean isLongPressDragEnabled() {
                return true;
            }

        });
        helper.attachToRecyclerView(binding.recycler);
    }

    @Override
    public void onTextClick(Site item) {
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onSearchClick(int position, Site item) {
        item.setSearchable(!item.isSearchable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public void onChangeClick(int position, Site item) {
        item.setChangeable(!item.isChangeable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public boolean onSearchLongClick(Site item) {
        boolean result = !item.isSearchable();
        adapter.getItems().forEach(site -> site.setSearchable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public boolean onChangeLongClick(Site item) {
        boolean result = !item.isChangeable();
        adapter.getItems().forEach(site -> site.setChangeable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else if (ResUtil.isLand(requireContext())) setWidth(0.5f);
    }
}
