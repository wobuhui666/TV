package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.databinding.AdapterHeaderBinding;
import com.fongmi.android.tv.utils.ResUtil;

public class HeaderPresenter extends Presenter {

    private final int horizontalInset;

    public HeaderPresenter() {
        this(0);
    }

    public HeaderPresenter(int horizontalInset) {
        this.horizontalInset = horizontalInset;
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterHeaderBinding binding = AdapterHeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        if (horizontalInset > 0) {
            int inset = ResUtil.dp2px(horizontalInset);
            View root = binding.getRoot();
            ViewGroup wrapper = new ViewGroup(parent.getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    int childWidthSpec = MeasureSpec.makeMeasureSpec(
                            Math.max(0, MeasureSpec.getSize(widthMeasureSpec) - inset * 2),
                            MeasureSpec.AT_MOST);
                    root.measure(childWidthSpec, heightMeasureSpec);
                    setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), root.getMeasuredHeight());
                }

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    root.layout(inset, 0, inset + root.getMeasuredWidth(), root.getMeasuredHeight());
                }
            };
            wrapper.setClipChildren(false);
            wrapper.setClipToPadding(false);
            wrapper.addView(root);
            return new HeaderPresenter.ViewHolder(wrapper, binding);
        }
        return new HeaderPresenter.ViewHolder(binding.getRoot(), binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        HeaderPresenter.ViewHolder holder = (HeaderPresenter.ViewHolder) viewHolder;
        holder.binding.text.setText(object instanceof String ? object.toString() : ResUtil.getString((int) object));
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterHeaderBinding binding;

        public ViewHolder(@NonNull View root, @NonNull AdapterHeaderBinding binding) {
            super(root);
            this.binding = binding;
        }
    }
}
