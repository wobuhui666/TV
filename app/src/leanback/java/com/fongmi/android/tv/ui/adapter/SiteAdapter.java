package com.fongmi.android.tv.ui.adapter;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterSiteHomeBinding;
import com.fongmi.android.tv.databinding.AdapterSiteBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;

import java.util.ArrayList;
import java.util.List;

public class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Site> mItems;
    private final boolean home;
    private int type;

    public SiteAdapter(OnClickListener listener) {
        this(listener, false);
    }

    public SiteAdapter(OnClickListener listener, boolean home) {
        this.listener = listener;
        this.home = home;
        this.mItems = new ArrayList<>();
        this.addAll();
    }

    public interface OnClickListener {

        void onItemClick(Site item);
    }

    public void setType(int type) {
        this.type = type;
        notifyDataSetChanged();
    }

    public void selectAll() {
        setEnable(type != 3);
    }

    public void cancelAll() {
        setEnable(type == 3);
    }

    private void addAll() {
        List<Site> sites = VodConfig.get().getSites().stream().filter(site -> !site.isHide()).toList();
        mItems.addAll(SiteHealthStore.getDisplayOrder(sites));
    }

    public List<Site> getItems() {
        return mItems;
    }

    public void move(int from, int to) {
        if (from == to || from < 0 || to < 0 || from >= mItems.size() || to >= mItems.size()) return;
        Site item = mItems.remove(from);
        mItems.add(to, item);
        notifyItemMoved(from, to);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (home) {
            AdapterSiteHomeBinding binding = AdapterSiteHomeBinding.inflate(inflater, parent, false);
            return new ViewHolder(binding.getRoot(), binding.text, binding.check);
        }
        AdapterSiteBinding binding = AdapterSiteBinding.inflate(inflater, parent, false);
        return new ViewHolder(binding.getRoot(), binding.text, binding.check);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mItems.get(position);
        holder.text.setText(item.getName() + " · " + healthLabel(item));
        holder.check.setChecked(getChecked(item));
        holder.text.setSelected(item.isSelected());
        holder.check.setVisibility(type == 0 ? View.GONE : View.VISIBLE);
        holder.itemView.setOnLongClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            return isValidPosition(adapterPosition) && setLongListener(mItems.get(adapterPosition));
        });
        holder.itemView.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (isValidPosition(adapterPosition)) setListener(mItems.get(adapterPosition), adapterPosition);
        });
        holder.text.setGravity(Setting.getSiteMode() == 0 ? Gravity.CENTER : Gravity.START);
    }

    private boolean isValidPosition(int position) {
        return position >= 0 && position < mItems.size();
    }

    private String healthLabel(Site item) {
        return switch (SiteHealthStore.status(VodConfig.getCid(), item.getKey())) {
            case GOOD -> "良好";
            case WARNING -> "警告";
            case BAD -> "较差";
            default -> "未知";
        };
    }

    private boolean getChecked(Site item) {
        if (type == 1) return item.isSearchable();
        if (type == 2) return item.isChangeable();
        return false;
    }

    private void setListener(Site item, int position) {
        if (type == 0) listener.onItemClick(item);
        if (type == 1) item.setSearchable(!item.isSearchable()).save();
        if (type == 2) item.setChangeable(!item.isChangeable()).save();
        if (type != 0) notifyItemChanged(position);
    }

    private boolean setLongListener(Site item) {
        if (type == 1) setEnable(!item.isSearchable());
        if (type == 2) setEnable(!item.isChangeable());
        return true;
    }

    private void setEnable(boolean enable) {
        if (type == 1) for (Site site : mItems) site.setSearchable(enable).save();
        if (type == 2) for (Site site : mItems) site.setChangeable(enable).save();
        notifyItemRangeChanged(0, getItemCount());
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final CompoundButton check;
        private final TextView text;

        ViewHolder(@NonNull View itemView, TextView text, CompoundButton check) {
            super(itemView);
            this.text = text;
            this.check = check;
            JetStreamAnimator.bindFocus(itemView, JetStreamAnimator.FOCUS_SCALE_LIST, 8);
        }
    }
}
