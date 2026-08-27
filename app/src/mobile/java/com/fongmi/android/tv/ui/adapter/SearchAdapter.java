package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterSearchBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class SearchAdapter extends BaseDiffAdapter<Vod, SearchAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Vod> items = new ArrayList<>();

    public SearchAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(Vod item);
    }

    public void replace(List<Vod> vods) {
        replace(vods, null);
    }

    public void replace(List<Vod> vods, Runnable runnable) {
        items.clear();
        if (vods != null) items.addAll(vods);
        setItems(new ArrayList<>(items), runnable);
    }

    public void append(List<Vod> vods) {
        if (vods == null || vods.isEmpty()) return;
        items.addAll(vods);
        setItems(new ArrayList<>(items));
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Vod item = getItem(position);
        holder.binding.name.setText(item.getName());
        holder.binding.site.setText(item.getSiteName());
        holder.binding.remark.setText(item.getSourceSummary());
        holder.binding.site.setVisibility(item.getSiteVisible());
        holder.binding.remark.setVisibility(item.getRemarkVisible());
        holder.binding.getRoot().setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition >= 0 && adapterPosition < getItemCount()) listener.onItemClick(getItem(adapterPosition));
        });
        ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
    }

    @Override
    public void onViewRecycled(@NonNull ViewHolder holder) {
        Glide.with(holder.binding.image).clear(holder.binding.image);
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSearchBinding binding;

        ViewHolder(@NonNull AdapterSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
