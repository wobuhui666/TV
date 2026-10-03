package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.AdapterHistoryBinding;
import com.fongmi.android.tv.utils.ContinueWatchingProgress;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

public class HistoryPresenter extends Presenter {

    private final OnClickListener listener;
    private int width, height;
    private boolean delete;

    public HistoryPresenter(OnClickListener listener) {
        this.listener = listener;
        setLayoutSize();
    }

    public HistoryPresenter(OnClickListener listener, int[] size) {
        this.listener = listener;
        this.width = size[0];
        this.height = size[1];
    }

    public interface OnClickListener {

        void onItemClick(History item);

        void onItemDelete(History item);

        boolean onLongClick();
    }

    private void setLayoutSize() {
        width = Math.round((ResUtil.getScreenWidth() - ResUtil.dp2px(136)) / 3.15f);
        height = ResUtil.dp2px(112);
    }

    public boolean isDelete() {
        return delete;
    }

    public void setDelete(boolean delete) {
        this.delete = delete;
    }

    private void setClickListener(View root, History item) {
        root.setOnLongClickListener(view -> listener.onLongClick());
        root.setOnClickListener(view -> {
            if (isDelete()) listener.onItemDelete(item);
            else listener.onItemClick(item);
        });
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        ViewHolder holder = new ViewHolder(AdapterHistoryBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.getRoot().getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        History item = (History) object;
        boolean same = item.getVodName().equals(item.getVodRemarks());
        ViewHolder holder = (ViewHolder) viewHolder;
        setClickListener(holder.view, item);
        holder.binding.name.setText(item.getVodName());
        holder.binding.remark.setText(item.getVodRemarks());
        holder.binding.delete.setVisibility(!delete ? View.GONE : View.VISIBLE);
        holder.binding.remark.setVisibility(delete || same ? View.GONE : View.VISIBLE);
        long remaining = ContinueWatchingProgress.remainingMinutes(item.getPosition(), item.getDuration());
        String time = remaining < 0 ? holder.view.getContext().getString(R.string.home_continue_watching)
                : remaining == 0 ? holder.view.getContext().getString(R.string.home_history_watched)
                : holder.view.getContext().getString(R.string.home_history_remaining, remaining);
        holder.binding.remaining.setText(time);
        holder.binding.progress.setProgress(ContinueWatchingProgress.fraction(item.getPosition(), item.getDuration()));
        holder.view.setContentDescription(item.getVodName() + ", " + item.getVodRemarks() + ", " + time);
        ImgUtil.loadArtwork(item.getVodName(), item.getVodPic(), holder.binding.image, null);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        ImgUtil.clear(holder.binding.image);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterHistoryBinding binding;

        public ViewHolder(@NonNull AdapterHistoryBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
