package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.databinding.AdapterTypeBinding;

import java.util.ArrayList;
import java.util.List;

public class CollectAdapter extends RecyclerView.Adapter<CollectAdapter.ViewHolder> {

    private final List<Collect> mItems;

    public CollectAdapter() {
        mItems = new ArrayList<>();
    }

    public void add(Collect item) {
        mItems.add(item);
        notifyItemInserted(mItems.size() - 1);
    }

    public void setItems(List<Collect> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public Collect get(int position) {
        if (position < 0 || position >= mItems.size()) return Collect.all();
        return mItems.get(position);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterTypeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Collect item = mItems.get(position);
        holder.binding.getRoot().setOnClickListener(null);
        holder.binding.text.setText(item.getDisplayName());
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterTypeBinding binding;

        ViewHolder(@NonNull AdapterTypeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
