package com.fongmi.android.tv.ui.presenter;

import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.DiscoverRankItem;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterDiscoverRankBinding;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.theme.JetStreamAmbient;
import com.fongmi.android.tv.utils.ImgUtil;

public final class DiscoverRankPresenter extends Presenter {

    private final VodPresenter.OnClickListener listener;

    public DiscoverRankPresenter(VodPresenter.OnClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new Holder(AdapterDiscoverRankBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object object) {
        Holder holder = (Holder) viewHolder;
        DiscoverRankItem rankItem = (DiscoverRankItem) object;
        Vod item = rankItem.getItem();
        holder.binding.rank.setText(String.valueOf(rankItem.getRank()));
        holder.binding.rank.setTextSize(TypedValue.COMPLEX_UNIT_SP, rankItem.getRank() < 10 ? 100 : 82);
        holder.binding.name.setText(item.getName());
        ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
        holder.view.setContentDescription(rankItem.getRank() + ". " + item.getName());
        holder.view.setOnClickListener(view -> listener.onItemClick(item, holder.binding.image));
        holder.view.setOnLongClickListener(view -> listener.onLongClick(item));
        holder.view.setOnFocusChangeListener((view, focused) -> {
            JetStreamAnimator.animateFocus(view, focused, JetStreamAnimator.FOCUS_SCALE_CARD, 0);
            if (focused) JetStreamAmbient.push(item.getBackdrop());
        });
    }

    @Override
    public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
        Holder holder = (Holder) viewHolder;
        holder.view.setOnClickListener(null);
        holder.view.setOnLongClickListener(null);
        holder.view.setOnFocusChangeListener(null);
        JetStreamAnimator.reset(holder.view);
        ImgUtil.clear(holder.binding.image);
    }

    private static final class Holder extends ViewHolder {

        private final AdapterDiscoverRankBinding binding;

        private Holder(AdapterDiscoverRankBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
