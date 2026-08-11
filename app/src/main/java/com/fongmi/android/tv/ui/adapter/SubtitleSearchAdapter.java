package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.SubtitleSearchItem;
import com.fongmi.android.tv.databinding.AdapterSubtitleSearchBinding;

import java.util.ArrayList;
import java.util.List;

public final class SubtitleSearchAdapter extends RecyclerView.Adapter<SubtitleSearchAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<SubtitleSearchItem> items = new ArrayList<>();

    public SubtitleSearchAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<SubtitleSearchItem> values) {
        int size = items.size();
        items.clear();
        notifyItemRangeRemoved(0, size);
        addAll(values);
    }

    public void addAll(List<SubtitleSearchItem> values) {
        if (values == null || values.isEmpty()) return;
        int start = items.size();
        items.addAll(values);
        notifyItemRangeInserted(start, values.size());
    }

    public void clear() {
        setItems(List.of());
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSubtitleSearchBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(items.get(position).getText());
    }

    public interface OnClickListener {

        void onItemClick(SubtitleSearchItem item);
    }

    public final class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener {

        private final AdapterSubtitleSearchBinding binding;

        private ViewHolder(AdapterSubtitleSearchBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnClickListener(this);
        }

        @Override
        public void onClick(View view) {
            int position = getBindingAdapterPosition();
            if (position != RecyclerView.NO_POSITION) listener.onItemClick(items.get(position));
        }
    }
}
