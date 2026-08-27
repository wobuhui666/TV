package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterCollectBinding;

import java.util.ArrayList;
import java.util.List;

public class CollectAdapter extends BaseDiffAdapter<Collect, CollectAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Collect> items = new ArrayList<>();

    public CollectAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onItemClick(int position, Collect item);
    }

    public void reset(Runnable runnable) {
        items.clear();
        items.add(Collect.all());
        setItems(new ArrayList<>(items), runnable);
    }

    public void add(List<Vod> vods) {
        if (items.isEmpty() || vods == null || vods.isEmpty()) return;
        items.get(0).getList().addAll(vods);
        items.add(Collect.create(vods));
        setItems(new ArrayList<>(items));
    }

    public int getPosition() {
        for (int i = 0; i < getItemCount(); i++) if (getItem(i).isSelected()) return i;
        return 0;
    }

    public Collect getActivated() {
        return getItems().get(getPosition());
    }

    public void setSelected(int position) {
        for (int i = 0; i < getItemCount(); i++) getItem(i).setSelected(i == position);
        notifyItemRangeChanged(0, getItemCount());
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterCollectBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Collect item = getItem(position);
        holder.binding.text.setSelected(item.isSelected());
        holder.binding.text.setText(item.getSite().getName());
        holder.binding.text.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (adapterPosition >= 0 && adapterPosition < getItemCount()) listener.onItemClick(adapterPosition, getItem(adapterPosition));
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterCollectBinding binding;

        ViewHolder(@NonNull AdapterCollectBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
