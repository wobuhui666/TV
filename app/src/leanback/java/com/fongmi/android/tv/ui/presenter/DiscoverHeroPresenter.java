package com.fongmi.android.tv.ui.presenter;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DiscoverHero;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterDiscoverHeroBinding;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.JetStreamFeaturedIndicatorDotView;
import com.fongmi.android.tv.ui.theme.JetStreamAmbient;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;
import java.util.List;

public final class DiscoverHeroPresenter extends Presenter {

    private final VodPresenter.OnClickListener listener;

    public DiscoverHeroPresenter(VodPresenter.OnClickListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new Holder(AdapterDiscoverHeroBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object object) {
        ((Holder) viewHolder).bind((DiscoverHero) object);
    }

    @Override
    public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
        ((Holder) viewHolder).unbind();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder viewHolder) {
        super.onViewAttachedToWindow(viewHolder);
        ((Holder) viewHolder).attach();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder viewHolder) {
        ((Holder) viewHolder).detach();
        super.onViewDetachedFromWindow(viewHolder);
    }

    private static final class Holder extends ViewHolder {

        private static final long AUTO_DELAY = 7000;
        private static final long IMAGE_FADE = 850;
        private static final long TEXT_FADE = 220;
        private final AdapterDiscoverHeroBinding binding;
        private final VodPresenter.OnClickListener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<Vod> items = new ArrayList<>();
        private final Runnable rotate;
        private ShapeableImageView front;
        private int index;
        private int bindGeneration;
        private int artworkGeneration;
        private String displayedArtwork;

        private Holder(AdapterDiscoverHeroBinding binding, VodPresenter.OnClickListener listener) {
            super(binding.getRoot());
            this.binding = binding;
            this.listener = listener;
            this.front = binding.imageA;
            binding.details.setVisibility(View.INVISIBLE);
            this.rotate = () -> {
                if (this.binding.getRoot().hasWindowFocus()) show(index + 1, true);
                schedule();
            };
            setListeners();
        }

        private void setListeners() {
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> {
                view.setSelected(hasFocus);
                binding.action.animate().cancel();
                binding.action.animate().alpha(hasFocus ? 1f : 0.76f).setDuration(160).start();
            });
            binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN || items.size() < 2) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    show(index - 1, true);
                    restart();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    show(index + 1, true);
                    restart();
                    return true;
                }
                return false;
            });
            binding.getRoot().setOnClickListener(view -> {
                Vod item = current();
                if (item != null) listener.onItemClick(item, front);
            });
        }

        private void bind(DiscoverHero hero) {
            stop();
            String previousId = current() == null ? "" : current().getId();
            items.clear();
            items.addAll(hero.getItems());
            index = findIndex(previousId);
            bindGeneration++;
            artworkGeneration++;
            binding.imageA.animate().cancel();
            binding.imageB.animate().cancel();
            binding.details.animate().cancel();
            binding.getRoot().setSelected(binding.getRoot().hasFocus());
            bindIndicator(items.size());
            if (items.isEmpty()) {
                binding.details.setVisibility(View.INVISIBLE);
                clearArtwork();
                return;
            }
            binding.details.setVisibility(View.VISIBLE);
            Vod item = current();
            if (item != null && TextUtils.equals(displayedArtwork, item.getBackdrop())) {
                binding.details.setAlpha(1f);
                bindText(item);
                updateIndicator();
                publishArtwork(displayedArtwork);
            } else {
                show(index, false);
            }
            schedule();
        }

        private void show(int position, boolean animate) {
            if (items.isEmpty()) return;
            index = Math.floorMod(position, items.size());
            Vod item = current();
            if (item == null) return;
            if (animate) {
                int generation = bindGeneration;
                binding.details.animate().cancel();
                binding.details.animate().alpha(0f).setDuration(TEXT_FADE / 2).withEndAction(() -> {
                    if (generation != bindGeneration || item != current()) return;
                    bindText(item);
                    binding.details.animate().alpha(1f).setDuration(TEXT_FADE).start();
                }).start();
            } else {
                binding.details.setAlpha(1f);
                bindText(item);
            }
            updateIndicator();
            String artwork = item.getBackdrop();
            if (!animate) {
                artworkGeneration++;
                ShapeableImageView back = front == binding.imageA ? binding.imageB : binding.imageA;
                back.animate().cancel();
                ImgUtil.clear(back);
                back.setAlpha(0f);
                back.setVisibility(View.GONE);
                front.setAlpha(1f);
                front.setVisibility(View.VISIBLE);
                ImgUtil.load(item.getName(), artwork, front);
                displayedArtwork = artwork;
                publishArtwork(artwork);
                return;
            }
            ShapeableImageView next = front == binding.imageA ? binding.imageB : binding.imageA;
            ShapeableImageView old = front;
            next.animate().cancel();
            ImgUtil.clear(next);
            next.setAlpha(0f);
            next.setVisibility(View.INVISIBLE);
            loadArtwork(item, artwork, next, old, ++artworkGeneration);
        }

        private void loadArtwork(Vod item, String artwork, ShapeableImageView next, ShapeableImageView old, int generation) {
            try {
                Glide.with(next).load(ImgUtil.getUrl(artwork)).centerCrop().listener(new RequestListener<>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean firstResource) {
                        if (generation == artworkGeneration) next.setVisibility(View.GONE);
                        return true;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource source, boolean firstResource) {
                        if (generation != artworkGeneration || item != current()) {
                            next.setVisibility(View.GONE);
                            return false;
                        }
                        old.animate().cancel();
                        front = next;
                        displayedArtwork = artwork;
                        next.setVisibility(View.VISIBLE);
                        next.animate().alpha(1f).setDuration(IMAGE_FADE).start();
                        old.animate().alpha(0f).setDuration(IMAGE_FADE).withEndAction(() -> {
                            if (old != front) old.setVisibility(View.GONE);
                        }).start();
                        publishArtwork(artwork);
                        return false;
                    }
                }).into(next);
            } catch (Throwable e) {
                next.setVisibility(View.GONE);
            }
        }

        private void bindText(Vod item) {
            binding.source.setText(source(item));
            binding.name.setText(item.getName());
            List<String> meta = new ArrayList<>();
            add(meta, item.getYear());
            add(meta, item.getRemarks());
            add(meta, mediaLabel(item));
            binding.meta.setText(TextUtils.join("  ·  ", meta));
            binding.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
            binding.content.setText(item.getContent());
            binding.content.setVisibility(TextUtils.isEmpty(item.getContent()) ? View.GONE : View.VISIBLE);
        }

        private String source(Vod item) {
            int string = item.getId().startsWith("douban:") ? R.string.discover_source_douban : R.string.discover_source_tmdb;
            return binding.getRoot().getContext().getString(string);
        }

        private String mediaLabel(Vod item) {
            if (DiscoverMediaKey.TV.equals(item.getTypeName())) return binding.getRoot().getContext().getString(R.string.discover_media_tv);
            if (DiscoverMediaKey.MOVIE.equals(item.getTypeName())) return binding.getRoot().getContext().getString(R.string.discover_media_movie);
            return item.getTypeName();
        }

        private void bindIndicator(int count) {
            binding.indicator.removeAllViews();
            binding.indicator.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < count; i++) {
                View dot = new JetStreamFeaturedIndicatorDotView(binding.indicator.getContext());
                int size = ResUtil.dp2px(7);
                int margin = ResUtil.dp2px(3);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
                params.setMarginStart(margin);
                params.setMarginEnd(margin);
                binding.indicator.addView(dot, params);
            }
            updateIndicator();
        }

        private void updateIndicator() {
            for (int i = 0; i < binding.indicator.getChildCount(); i++) binding.indicator.getChildAt(i).setSelected(i == index);
        }

        private void add(List<String> values, String value) {
            if (!TextUtils.isEmpty(value)) values.add(value);
        }

        private Vod current() {
            return index >= 0 && index < items.size() ? items.get(index) : null;
        }

        private int findIndex(String id) {
            if (!TextUtils.isEmpty(id)) {
                for (int i = 0; i < items.size(); i++) if (id.equals(items.get(i).getId())) return i;
            }
            return 0;
        }

        private void clearArtwork() {
            artworkGeneration++;
            displayedArtwork = null;
            front = binding.imageA;
            ImgUtil.clear(binding.imageA);
            ImgUtil.clear(binding.imageB);
            binding.imageA.setAlpha(1f);
            binding.imageA.setVisibility(View.GONE);
            binding.imageB.setAlpha(0f);
            binding.imageB.setVisibility(View.GONE);
        }

        private void publishArtwork(String artwork) {
            if (binding.getRoot().isAttachedToWindow() && binding.getRoot().hasWindowFocus()) JetStreamAmbient.push(artwork);
        }

        private void schedule() {
            handler.removeCallbacks(rotate);
            if (items.size() > 1 && binding.getRoot().isAttachedToWindow()) handler.postDelayed(rotate, AUTO_DELAY);
        }

        private void restart() {
            stop();
            schedule();
        }

        private void stop() {
            handler.removeCallbacks(rotate);
        }

        private void attach() {
            Vod item = current();
            if (item != null) publishArtwork(item.getBackdrop());
            restart();
        }

        private void detach() {
            stop();
        }

        private void unbind() {
            detach();
            bindGeneration++;
            artworkGeneration++;
            displayedArtwork = null;
            items.clear();
            binding.details.animate().cancel();
            binding.imageA.animate().cancel();
            binding.imageB.animate().cancel();
            binding.action.animate().cancel();
            JetStreamAnimator.reset(binding.getRoot());
            ImgUtil.clear(binding.imageA);
            ImgUtil.clear(binding.imageB);
        }
    }
}
