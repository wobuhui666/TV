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
        return binding;
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this, classic);
        if (action) actionView.setVisibility(View.VISIBLE);
        setType(type);
        setRecyclerView();
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
        search.setNextFocusUpId(search.getId());
        search.setNextFocusDownId(change.getId());
        change.setNextFocusUpId(search.getId());
        change.setNextFocusDownId(enableBatch ? select.getId() : enableMode ? mode.getId() : change.getId());
        select.setNextFocusUpId(change.getId());
        select.setNextFocusDownId(cancel.getId());
        cancel.setNextFocusUpId(select.getId());
        cancel.setNextFocusDownId(enableMode ? mode.getId() : cancel.getId());
        mode.setNextFocusUpId(enableBatch ? cancel.getId() : change.getId());
        mode.setNextFocusDownId(mode.getId());
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
    }

    private void bindFocus(View view) {
        view.setOnFocusChangeListener((target, focused) -> {
            boolean selected = target.isSelected();
            JetStreamAnimator.animateFocus(target, focused, JetStreamAnimator.FOCUS_SCALE_LIST, 8);
            target.setSelected(selected);
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
