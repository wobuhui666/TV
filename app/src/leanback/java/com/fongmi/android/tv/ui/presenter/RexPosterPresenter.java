package com.fongmi.android.tv.ui.presenter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterRexPosterBinding;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.rex.RexCaptions;
import com.fongmi.android.tv.ui.theme.JetStreamAmbient;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

/** 混合推荐流小卡：海报 + 「年份 · 类型」常显副题，点击进入详情。 */
public class RexPosterPresenter extends Presenter {

    private final VodPresenter.OnClickListener listener;
    private final boolean landscape;

    public RexPosterPresenter(VodPresenter.OnClickListener listener) {
        this(listener, false);
    }

    public RexPosterPresenter(VodPresenter.OnClickListener listener, boolean landscape) {
        this.listener = listener;
        this.landscape = landscape;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        Holder holder = new Holder(AdapterRexPosterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        if (landscape) {
            holder.view.getLayoutParams().width = ResUtil.dp2px(164);
            holder.binding.poster.getLayoutParams().height = ResUtil.dp2px(92);
            ViewGroup.MarginLayoutParams title = (ViewGroup.MarginLayoutParams) holder.binding.name.getLayoutParams();
            title.height = ResUtil.dp2px(22);
            title.topMargin = ResUtil.dp2px(8);
            holder.binding.name.setTextSize(14);
            holder.binding.meta.getLayoutParams().height = ResUtil.dp2px(18);
            holder.binding.meta.setTextSize(11);
        }
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object item) {
        Holder holder = (Holder) viewHolder;
        Vod vod = (Vod) item;
        holder.binding.name.setText(vod.getName());
        String meta = RexCaptions.subtitle(vod);
        holder.binding.meta.setText(meta);
        holder.binding.meta.setVisibility(TextUtils.isEmpty(meta) ? View.GONE : View.VISIBLE);
        ImgUtil.load(vod.getName(), landscape ? RexSpotlightPresenter.landscapeOf(vod) : vod.getPic(), holder.binding.image);
        holder.view.setOnClickListener(view -> listener.onItemClick(vod, holder.binding.image));
        holder.view.setOnLongClickListener(view -> listener.onLongClick(vod));
        holder.view.setOnFocusChangeListener((view, focused) -> {
            JetStreamAnimator.animateFocus(view, focused, JetStreamAnimator.FOCUS_SCALE_CARD, 0);
            if (focused && !landscape) JetStreamAmbient.push(vod.getPic());
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

    private static class Holder extends ViewHolder {

        private final AdapterRexPosterBinding binding;

        Holder(AdapterRexPosterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
