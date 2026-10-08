package com.fongmi.android.tv.ui.presenter;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
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
        private final ViewTreeObserver.OnWindowFocusChangeListener windowFocusListener;
        private ShapeableImageView front;
        private int index = -1;
        private int artworkGeneration;
        private String displayedArtwork;
        private boolean attached;
        private boolean windowFocusListenerRegistered;

        private Holder(AdapterDiscoverHeroBinding binding, VodPresenter.OnClickListener listener) {
            super(binding.getRoot());
            this.binding = binding;
            this.listener = listener;
            this.front = binding.imageA;
            binding.details.setVisibility(View.INVISIBLE);
            this.rotate = () -> {
                if (canRotate()) show(index + 1, true);
                schedule();
            };
            this.windowFocusListener = hasFocus -> {
                if (hasFocus) publishArtwork(displayedArtwork);
                schedule();
            };
            setListeners();
        }

        private void setListeners() {
            binding.getRoot().setOnFocusChangeListener((view, hasFocus) -> {
                view.setSelected(hasFocus);
                binding.action.animate().cancel();
                binding.action.animate().alpha(hasFocus ? 1f : 0.76f).setDuration(160).start();
                schedule();
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
                // A pending or failed image must not animate the preceding film into this detail page.
                if (item != null) listener.onItemClick(item,
                        TextUtils.equals(displayedArtwork, item.getBackdrop()) ? front : null);
            });
        }

        private void bind(DiscoverHero hero) {
            stop();
            String previousId = current() == null ? "" : current().getId();
            items.clear();
            items.addAll(hero.getItems());
            index = findIndex(previousId);
            binding.details.animate().cancel();
            binding.getRoot().setSelected(binding.getRoot().hasFocus());
            bindIndicator(items.size());
            if (items.isEmpty()) {
                binding.details.setVisibility(View.INVISIBLE);
                clearArtwork();
                return;
            }
            binding.details.setVisibility(View.VISIBLE);
            show(index, false);
            schedule();
        }

        private void show(int position, boolean animate) {
            if (items.isEmpty()) return;
            index = Math.floorMod(position, items.size());
            Vod item = current();
            if (item == null) return;
            // Commit the visible name and click destination in one UI operation. A delayed fade-out
            // callback used to leave the previous title clickable as the newly selected film.
            binding.details.animate().cancel();
            bindText(item);
            binding.details.setAlpha(animate ? 0.7f : 1f);
            if (animate) binding.details.animate().alpha(1f).setDuration(TEXT_FADE).start();
            updateIndicator();
            showArtwork(item, animate);
        }

        private void showArtwork(Vod item, boolean animate) {
            cancelPendingArtwork();
            String artwork = item.getBackdrop();
            if (TextUtils.equals(displayedArtwork, artwork) && front.getDrawable() != null) {
                publishArtwork(artwork);
                return;
            }
            if (!attached || !binding.getRoot().isAttachedToWindow()) return;
            ShapeableImageView next = front == binding.imageA ? binding.imageB : binding.imageA;
            ShapeableImageView old = front;
            next.setVisibility(View.INVISIBLE);
            loadArtwork(item, artwork, next, old, animate, artworkGeneration);
        }

        private void loadArtwork(Vod item, String artwork, ShapeableImageView next, ShapeableImageView old,
                                 boolean animate, int generation) {
            try {
                Glide.with(next).load(ImgUtil.getUrl(artwork)).centerCrop().listener(new RequestListener<>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model, @NonNull Target<Drawable> target, boolean firstResource) {
                        if (!isCurrentArtwork(item, generation)) return true;
                        next.setImageResource(R.drawable.artwork);
                        displayArtwork(null, next, old, animate, generation);
                        return true;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource source, boolean firstResource) {
                        // Suppress Glide's own target update too: this view may have been reused.
                        if (!isCurrentArtwork(item, generation)) return true;
                        next.setImageDrawable(resource);
                        displayArtwork(artwork, next, old, animate, generation);
                        return true;
                    }
                }).into(next);
            } catch (RuntimeException e) {
                if (!isCurrentArtwork(item, generation)) return;
                next.setImageResource(R.drawable.artwork);
                displayArtwork(null, next, old, animate, generation);
            }
        }

        private boolean isCurrentArtwork(Vod item, int generation) {
            return attached && binding.getRoot().isAttachedToWindow()
                    && generation == artworkGeneration && item == current();
        }

        private void displayArtwork(String artwork, ShapeableImageView next, ShapeableImageView old,
                                    boolean animate, int generation) {
            old.animate().cancel();
            front = next;
            displayedArtwork = artwork;
            next.setVisibility(View.VISIBLE);
            if (animate && old.getDrawable() != null) {
                next.animate().alpha(1f).setDuration(IMAGE_FADE).start();
                old.animate().alpha(0f).setDuration(IMAGE_FADE).withEndAction(() -> {
                    if (generation == artworkGeneration && old != front) old.setVisibility(View.GONE);
                }).start();
            } else {
                next.setAlpha(1f);
                old.setAlpha(0f);
                old.setVisibility(View.GONE);
            }
            publishArtwork(artwork);
        }

        private void cancelPendingArtwork() {
            artworkGeneration++;
            binding.imageA.animate().withEndAction(null).cancel();
            binding.imageB.animate().withEndAction(null).cancel();
            ShapeableImageView back = front == binding.imageA ? binding.imageB : binding.imageA;
            ImgUtil.clear(back);
            back.setImageDrawable(null);
            back.setAlpha(0f);
            back.setVisibility(View.GONE);
            front.setAlpha(1f);
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
            cancelPendingArtwork();
            displayedArtwork = null;
            front = binding.imageA;
            ImgUtil.clear(binding.imageA);
            ImgUtil.clear(binding.imageB);
            binding.imageA.setImageDrawable(null);
            binding.imageB.setImageDrawable(null);
            binding.imageA.setAlpha(1f);
            binding.imageA.setVisibility(View.GONE);
            binding.imageB.setAlpha(0f);
            binding.imageB.setVisibility(View.GONE);
        }

        private void publishArtwork(String artwork) {
            if (!TextUtils.isEmpty(artwork) && attached && binding.getRoot().isAttachedToWindow()
                    && binding.getRoot().hasWindowFocus()) JetStreamAmbient.push(artwork);
        }

        private boolean canRotate() {
            if (!attached || items.size() < 2 || !binding.getRoot().isAttachedToWindow()
                    || !binding.getRoot().isShown() || !binding.getRoot().hasWindowFocus()) return false;
            int mode = BrowseExperienceSettings.getHeroRotationMode();
            if (mode == BrowseExperienceSettings.HERO_ROTATION_MANUAL) return false;
            return mode != BrowseExperienceSettings.HERO_ROTATION_FOCUS_PAUSED || !binding.getRoot().hasFocus();
        }

        private void schedule() {
            stop();
            if (canRotate()) handler.postDelayed(rotate, AUTO_DELAY);
        }

        private void restart() {
            stop();
            schedule();
        }

        private void stop() {
            handler.removeCallbacks(rotate);
        }

        private void attach() {
            if (attached) {
                schedule();
                return;
            }
            attached = true;
            ViewTreeObserver observer = binding.getRoot().getViewTreeObserver();
            if (!windowFocusListenerRegistered && observer.isAlive()) {
                observer.addOnWindowFocusChangeListener(windowFocusListener);
                windowFocusListenerRegistered = true;
            }
            Vod item = current();
            if (item != null) showArtwork(item, false);
            restart();
        }

        private void detach() {
            attached = false;
            stop();
            ViewTreeObserver observer = binding.getRoot().getViewTreeObserver();
            if (windowFocusListenerRegistered && observer.isAlive()) observer.removeOnWindowFocusChangeListener(windowFocusListener);
            windowFocusListenerRegistered = false;
            binding.details.animate().cancel();
            binding.details.setAlpha(1f);
            binding.action.animate().cancel();
            clearArtwork();
        }

        private void unbind() {
            detach();
            items.clear();
            index = -1;
            binding.details.setVisibility(View.INVISIBLE);
            JetStreamAnimator.reset(binding.getRoot());
        }
    }
}
