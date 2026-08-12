package com.fongmi.android.tv.ui.dialog;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSiteHomeBinding;
import com.fongmi.android.tv.databinding.DialogSiteBinding;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.setting.SiteSortMode;
import com.fongmi.android.tv.ui.adapter.SiteAdapter;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    private static final int GRID_COUNT = 10;

    private RecyclerView.ItemDecoration decoration;
    private ViewBinding binding;
    private RecyclerView recycler;
    private SiteListener listener;
    private SiteAdapter adapter;
    private ImageView mode;
    private View search;
    private View change;
    private View select;
    private View cancel;
    private View sortUp;
    private View sortDown;
    private View sortSave;
    private View sortSmart;
    private View sortRestore;
    private View actionView;
    private boolean action;
    private boolean classic;
    private int type;

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        type = 1;
        return this;
    }

    public SiteDialog classic() {
        classic = true;
        return this;
    }

    public SiteDialog action() {
        action = true;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
        if (activity instanceof SiteListener) listener = (SiteListener) activity;
    }

    private boolean list() {
        return Setting.getSiteMode() == 0 || adapter.getItemCount() < GRID_COUNT;
    }

    private int getCount() {
        return list() ? 1 : Math.clamp((int) Math.ceil((double) adapter.getItemCount() / GRID_COUNT), 2, 3);
    }

    private int getIcon() {
        return list() ? com.fongmi.android.tv.R.drawable.ic_site_grid : com.fongmi.android.tv.R.drawable.ic_site_list;
    }

    private float getWidth() {
        return 0.4f + (getCount() - 1) * 0.2f;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = classic ? bindClassic() : bindDefault();
    }

    private ViewBinding bindDefault() {
        DialogSiteBinding binding = DialogSiteBinding.inflate(getLayoutInflater());
        recycler = binding.recycler;
        actionView = binding.action;
        search = binding.search;
        change = binding.change;
        select = binding.select;
        cancel = binding.cancel;
        mode = binding.mode;
        sortUp = binding.sortUp;
        sortDown = binding.sortDown;
        sortSave = binding.sortSave;
        sortSmart = binding.sortSmart;
        sortRestore = binding.sortRestore;
        return binding;
    }

    private ViewBinding bindClassic() {
        DialogSiteHomeBinding binding = DialogSiteHomeBinding.inflate(getLayoutInflater());
        recycler = binding.recycler;
        actionView = binding.action;
        search = binding.search;
        change = binding.change;
        select = binding.select;
        cancel = binding.cancel;
        mode = binding.mode;
        sortUp = binding.sortUp;
        sortDown = binding.sortDown;
        sortSave = binding.sortSave;
        sortSmart = binding.sortSmart;
        sortRestore = binding.sortRestore;
        return binding;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this, classic);
        if (action || type == 1) actionView.setVisibility(View.VISIBLE);
        setType(type);
        setRecyclerView();
        rememberSitePosition();
        setMode();
        setAnimation();
    }

    @Override
    protected void initEvent() {
        mode.setOnClickListener(this::onMode);
        select.setOnClickListener(v -> adapter.selectAll());
        cancel.setOnClickListener(v -> adapter.cancelAll());
        search.setOnClickListener(v -> setType(v.isSelected() ? 0 : 1));
        change.setOnClickListener(v -> setType(v.isSelected() ? 0 : 2));
        sortUp.setOnClickListener(v -> moveFocused(-1));
        sortDown.setOnClickListener(v -> moveFocused(1));
        sortSave.setOnClickListener(v -> {
            SiteHealthStore.saveManualOrder(adapter.getItems());
            dismiss();
        });
        sortSmart.setOnClickListener(v -> {
            SiteHealthStore.setMode(VodConfig.getCid(), SiteSortMode.SMART);
            dismiss();
        });
        sortRestore.setOnClickListener(v -> {
            SiteHealthStore.resetManualOrder();
            dismiss();
        });
    }

    private void setRecyclerView() {
        recycler.setAdapter(adapter);
        recycler.setHasFixedSize(true);
        recycler.setItemAnimator(null);
        if (decoration != null) recycler.removeItemDecoration(decoration);
        recycler.addItemDecoration(decoration = new SpaceItemDecoration(getCount(), 16));
        recycler.setLayoutManager(new GridLayoutManager(requireContext(), getCount()));
        if (!mode.hasFocus()) focusRecycler(VodConfig.getHomeIndex());
    }

    private void focusRecycler(int position) {
        DialogFocus.requestRecyclerFocus(recycler, position, adapter.getItemCount());
    }

    private void setType(int type) {
        boolean enableBatch = type > 0;
        boolean restoreFocus = !enableBatch && (select.hasFocus() || cancel.hasFocus());
        search.setSelected(type == 1);
        change.setSelected(type == 2);
        setActionEnabled(select, enableBatch);
        setActionEnabled(cancel, enableBatch);
        adapter.setType(this.type = type);
        updateActionFocusChain();
        if (restoreFocus) focusRecycler(VodConfig.getHomeIndex());
    }

    private void setActionEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setFocusable(enabled);
        view.setFocusableInTouchMode(enabled);
    }

    private void setMode() {
        boolean enabled = adapter.getItemCount() >= GRID_COUNT;
        boolean restoreFocus = mode.hasFocus() && !enabled;
        if (!enabled) Setting.putSiteMode(0);
        setActionEnabled(mode, enabled);
        mode.setImageResource(getIcon());
        updateActionFocusChain();
        if (restoreFocus) focusRecycler(VodConfig.getHomeIndex());
    }

    private void updateActionFocusChain() {
        boolean enableBatch = type > 0;
        boolean enableMode = mode.isEnabled() && mode.isFocusable();
        int afterChange = enableBatch ? select.getId() : enableMode ? mode.getId() : sortUp.getId();
        int afterCancel = enableMode ? mode.getId() : sortUp.getId();
        search.setNextFocusUpId(search.getId());
        search.setNextFocusDownId(change.getId());
        change.setNextFocusUpId(search.getId());
        change.setNextFocusDownId(afterChange);
        select.setNextFocusUpId(change.getId());
        select.setNextFocusDownId(cancel.getId());
        cancel.setNextFocusUpId(select.getId());
        cancel.setNextFocusDownId(afterCancel);
        mode.setNextFocusUpId(enableBatch ? cancel.getId() : change.getId());
        mode.setNextFocusDownId(sortUp.getId());
        sortUp.setNextFocusUpId(enableMode ? mode.getId() : enableBatch ? cancel.getId() : change.getId());
        sortUp.setNextFocusDownId(sortDown.getId());
        sortDown.setNextFocusUpId(sortUp.getId());
        sortDown.setNextFocusDownId(sortSave.getId());
        sortSave.setNextFocusUpId(sortDown.getId());
        sortSave.setNextFocusDownId(sortSmart.getId());
        sortSmart.setNextFocusUpId(sortSave.getId());
        sortSmart.setNextFocusDownId(sortRestore.getId());
        sortRestore.setNextFocusUpId(sortSmart.getId());
        sortRestore.setNextFocusDownId(sortRestore.getId());
    }

    private void setAnimation() {
        if (actionView instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) actionView;
            viewGroup.setClipChildren(false);
            viewGroup.setClipToPadding(false);
        }
        bindFocus(search);
        bindFocus(change);
        bindFocus(select);
        bindFocus(cancel);
        bindFocus(mode);
        bindFocus(sortUp);
        bindFocus(sortDown);
        bindFocus(sortSave);
        bindFocus(sortSmart);
        bindFocus(sortRestore);
    }

    private void moveFocused(int offset) {
        int from = recycler.getTag() instanceof Integer position ? position : VodConfig.getHomeIndex();
        from = Math.clamp(from, 0, adapter.getItemCount() - 1);
        int to = Math.clamp(from + offset, 0, adapter.getItemCount() - 1);
        adapter.move(from, to);
        focusRecycler(to);
    }

    private void bindFocus(View view) {
        view.setOnFocusChangeListener((target, focused) -> {
            boolean selected = target.isSelected();
            JetStreamAnimator.animateFocus(target, focused, JetStreamAnimator.FOCUS_SCALE_LIST, 8);
            target.setSelected(selected);
        });
    }

    private void rememberSitePosition() {
        recycler.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus == null) return;
            RecyclerView.ViewHolder holder = recycler.findContainingViewHolder(newFocus);
            if (holder != null) recycler.setTag(holder.getBindingAdapterPosition());
        });
    }

    private void setWidth() {
        setWidth(getWidth());
    }

    private void onMode(View view) {
        Setting.putSiteMode(Math.abs(Setting.getSiteMode() - 1));
        setRecyclerView();
        setMode();
        setWidth();
    }

    @Override
    public void onItemClick(Site item) {
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth();
    }
}
