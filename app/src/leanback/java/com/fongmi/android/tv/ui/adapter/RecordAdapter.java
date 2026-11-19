package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.AdapterSearchRecordBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.search.SearchInputParser;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class RecordAdapter extends RecyclerView.Adapter<RecordAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<String> mItems;
    private final ExecutorService cacheWorker = Executors.newSingleThreadExecutor();
    private Future<?> cacheTask;
    private final Set<String> removed = new HashSet<>();
    private boolean edited, closed, cacheLoaded;

    public RecordAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        loadCache();
    }

    private void loadCache() {
        cacheTask = cacheWorker.submit(() -> {
            String source = Setting.getKeyword();
            List<String> cached = SearchInputParser.records(source);
            if (Thread.currentThread().isInterrupted()) return;
            App.post(() -> applyCache(source, cached));
        });
    }

    private void applyCache(String source, List<String> cached) {
        if (closed && !edited) { cacheWorker.shutdownNow(); return; }
        // Another search window may have saved while this one was loading.
        // Refresh that cache before merging; never replace a newer persisted list.
        if (!source.equals(Setting.getKeyword())) { loadCache(); return; }
        List<String> merged = SearchInputParser.mergeRecords(mItems, cached, removed);
        int previousSize = mItems.size();
        mItems.clear();
        mItems.addAll(merged);
        cacheLoaded = true;
        if (edited) persist();
        if (closed) { cacheWorker.shutdownNow(); return; }
        if (mItems.size() > previousSize) notifyItemRangeInserted(previousSize, mItems.size() - previousSize);
        listener.onDataChanged(mItems.size(), RecyclerView.NO_POSITION);
    }

    private void persist() {
        if (cacheLoaded) Setting.putKeyword(App.gson().toJson(mItems));
    }

    public interface OnClickListener {

        void onItemClick(String text);

        void onDataChanged(int size, int deletedPosition);
    }

    public void close() {
        closed = true;
        // Pending edits still need the old cache before they can be saved.
        // The bounded loader finishes and persists without notifying the closed UI.
        if (cacheLoaded || !edited) {
            cacheTask.cancel(true);
            cacheWorker.shutdownNow();
        }
    }

    private void checkToAdd(String item) {
        mItems.remove(item);
        mItems.add(0, item);
        while (mItems.size() > SearchInputParser.MAX_RECORDS) mItems.remove(mItems.size() - 1);
    }

    public void add(String item) {
        if (closed || item == null || item.trim().isEmpty()) return;
        edited = true;
        removed.remove(item);
        checkToAdd(item);
        persist();
        notifyDataSetChanged();
        listener.onDataChanged(getItemCount(), RecyclerView.NO_POSITION);
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSearchRecordBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.binding.text.setText(mItems.get(position));
        holder.binding.text.setOnClickListener(v -> {
            int adapterPosition = holder.getBindingAdapterPosition();
            if (isValidPosition(adapterPosition)) listener.onItemClick(mItems.get(adapterPosition));
        });
    }

    private boolean isValidPosition(int position) {
        return position >= 0 && position < mItems.size();
    }

    public class ViewHolder extends RecyclerView.ViewHolder implements View.OnLongClickListener {

        private final AdapterSearchRecordBinding binding;

        public ViewHolder(@NonNull AdapterSearchRecordBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            itemView.setOnLongClickListener(this);
        }

        @Override
        public boolean onLongClick(View v) {
            int position = getBindingAdapterPosition();
            if (!isValidPosition(position)) return true;
            edited = true;
            removed.add(mItems.get(position));
            mItems.remove(position);
            notifyItemRemoved(position);
            listener.onDataChanged(getItemCount(), position);
            persist();
            return true;
        }
    }
}
