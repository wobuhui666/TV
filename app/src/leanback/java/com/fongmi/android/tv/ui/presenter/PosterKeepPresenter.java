package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.AdapterVodRectBinding;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.TouchFocus;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;

/** Uses the saved title and poster directly, without discovery or source metadata requests. */
public final class PosterKeepPresenter extends Presenter {

    public interface Listener {
        void onKeepClick(Keep item, View poster);
    }

    private final Listener listener;

    public PosterKeepPresenter(Listener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterVodRectBinding binding = AdapterVodRectBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        binding.getRoot().getLayoutParams().width = ResUtil.dp2px(132);
        binding.poster.getLayoutParams().height = ResUtil.dp2px(176);
        binding.image.getLayoutParams().height = ResUtil.dp2px(176);
        TouchFocus.bind(binding.getRoot());
        binding.getRoot().setOnFocusChangeListener((view, focused) -> {
            JetStreamAnimator.animateFocus(view, focused, JetStreamAnimator.FOCUS_SCALE_CARD, 0);
            binding.metadata.show(focused);
        });
        return new Holder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object object) {
        AdapterVodRectBinding binding = ((Holder) viewHolder).binding;
        Keep item = (Keep) object;
        binding.name.setText(item.getVodName());
        binding.metadata.bind("", "", item.getSiteName());
        binding.getRoot().setOnClickListener(view -> listener.onKeepClick(item, binding.image));
        binding.getRoot().setOnLongClickListener(view -> {
            listener.onKeepClick(item, binding.image);
            return true;
        });
        ImgUtil.load(item.getVodName(), item.getVodPic(), binding.image);
    }

    @Override
    public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
        AdapterVodRectBinding binding = ((Holder) viewHolder).binding;
        ImgUtil.clear(binding.image);
        binding.getRoot().setOnClickListener(null);
        binding.getRoot().setOnLongClickListener(null);
    }

    private static final class Holder extends ViewHolder {
        final AdapterVodRectBinding binding;

        Holder(AdapterVodRectBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
