package com.fongmi.android.tv.ui.presenter;

import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.DiffCallback;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.BitmapImageViewTarget;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.bean.RexSection;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterRexSectionCardBinding;
import com.fongmi.android.tv.ui.rex.RexArtworkColors;
import com.fongmi.android.tv.ui.rex.RexFeedResult;
import com.fongmi.android.tv.ui.theme.JetStreamAmbient;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

/** A section card combines its representative artwork and independently navigable preview rail. */
public class RexSectionCardPresenter extends Presenter {
    public interface Listener {
        void onSectionClick(RexSection section, View card);
        boolean onSectionLongClick(RexSection section);
    }

    private static final DiffCallback<Vod> DIFF = new DiffCallback<>() {
        @Override public boolean areItemsTheSame(Vod old, Vod next) { return old.isSameItem(next); }
        @Override public boolean areContentsTheSame(Vod old, Vod next) { return RexFeedResult.sameContent(old, next); }
    };
    private final Listener listener;
    private final RexPosterPresenter posterPresenter;
    private final RecyclerView.RecycledViewPool posterPool = new RecyclerView.RecycledViewPool();

    public RexSectionCardPresenter(Listener listener, VodPresenter.OnClickListener posters) {
        this.listener = listener;
        posterPresenter = new RexPosterPresenter(posters, true);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        Holder holder = new Holder(AdapterRexSectionCardBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), posterPresenter);
        holder.binding.posters.setRecycledViewPool(posterPool);
        holder.binding.posters.setItemAnimator(null);
        holder.binding.posters.setHasFixedSize(true);
        holder.binding.posters.setItemViewCacheSize(2);
        holder.binding.posters.setRowHeight(ResUtil.dp2px(140));
        holder.binding.posters.setHorizontalSpacing(ResUtil.dp2px(16));
        holder.binding.posters.setFocusScrollStrategy(HorizontalGridView.FOCUS_SCROLL_ITEM);
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object item) {
        Holder holder = (Holder) viewHolder;
        RexFeedResult result = (RexFeedResult) item;
        if (result.getItems().isEmpty()) return;
        Vod vod = result.getItems().get(0);
        boolean newSection = !result.getSection().getKey().equals(holder.sectionKey);
        holder.sectionKey = result.getSection().getKey();
        holder.binding.section.setText(result.getSection().getLabelRes());
        holder.binding.name.setText(vod.getName());
        holder.binding.hero.setContentDescription(holder.binding.section.getText());
        holder.binding.hero.setOnClickListener(view -> listener.onSectionClick(result.getSection(), holder.binding.image));
        holder.binding.hero.setOnLongClickListener(view -> listener.onSectionLongClick(result.getSection()));
        holder.binding.hero.setOnFocusChangeListener((view, focused) -> {
            if (focused) JetStreamAmbient.push(landscapeOf(vod));
        });
        holder.posters.setItems(result.getItems(), DIFF);
        if (newSection) holder.binding.posters.setSelectedPosition(0);
        String artwork = landscapeOf(vod);
        if (!artwork.equals(holder.artwork) || holder.binding.image.getDrawable() == null) holder.loadArtwork(artwork);
    }

    @Override
    public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
        Holder holder = (Holder) viewHolder;
        holder.generation++;
        holder.artwork = "";
        holder.sectionKey = "";
        holder.binding.hero.setOnClickListener(null);
        holder.binding.hero.setOnLongClickListener(null);
        holder.binding.hero.setOnFocusChangeListener(null);
        holder.posters.clear();
        ImgUtil.clear(holder.binding.image);
        holder.binding.image.setImageDrawable(null);
        holder.binding.getRoot().setArtworkColor(null);
    }

    static String landscapeOf(Vod vod) {
        String backdrop = vod.getBackdrop();
        return TextUtils.isEmpty(backdrop) ? vod.getPic() : backdrop;
    }

    private static final class Holder extends ViewHolder {
        final AdapterRexSectionCardBinding binding;
        final ArrayObjectAdapter posters;
        String sectionKey = "";
        String artwork = "";
        int generation;

        Holder(AdapterRexSectionCardBinding binding, RexPosterPresenter presenter) {
            super(binding.getRoot());
            this.binding = binding;
            posters = new ArrayObjectAdapter(presenter);
            binding.posters.setAdapter(new ItemBridgeAdapter(posters));
        }

        void loadArtwork(String url) {
            artwork = url;
            int request = ++generation;
            binding.getRoot().setArtworkColor(null);
            Glide.with(binding.image).asBitmap().disallowHardwareConfig()
                    .load(ImgUtil.getUrl(url)).centerCrop().override(ResUtil.dp2px(480), ResUtil.dp2px(270))
                    .into(new BitmapImageViewTarget(binding.image) {
                        @Override public void onResourceReady(@NonNull Bitmap resource, @Nullable Transition<? super Bitmap> transition) {
                            if (request != generation) return;
                            super.onResourceReady(resource, transition);
                            RexArtworkColors.from(url, resource, color -> {
                                if (request == generation && url.equals(artwork)) binding.getRoot().setArtworkColor(color);
                            });
                        }
                    });
        }
    }
}
