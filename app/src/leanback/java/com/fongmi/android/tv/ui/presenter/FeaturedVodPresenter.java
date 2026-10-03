package com.fongmi.android.tv.ui.presenter;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.FeaturedVodRow;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterFeaturedVodBinding;
import com.fongmi.android.tv.ui.custom.JetStreamAnimator;
import com.fongmi.android.tv.ui.custom.JetStreamFeaturedIndicatorDotView;
import com.fongmi.android.tv.ui.theme.JetStreamAmbient;
import com.fongmi.android.tv.utils.FeaturedPosterCache;
import com.fongmi.android.tv.utils.ImageRetryPolicy;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.TmdbLogoHelper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FeaturedVodPresenter extends Presenter {

    private final VodPresenter.OnClickListener listener;
    private final int actionText;
    private final Set<ViewHolder> boundHolders = new HashSet<>();

    public FeaturedVodPresenter(VodPresenter.OnClickListener listener) {
        this(listener, R.string.home_hero_more);
    }

    public FeaturedVodPresenter(VodPresenter.OnClickListener listener, @StringRes int actionText) {
        this.listener = listener;
        this.actionText = actionText;
    }

    /** Remote interaction anywhere on the home page gives the user time to read. */
    public void onUserInteraction() {
        for (ViewHolder holder : boundHolders) holder.pauseRotation();
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        return new ViewHolder(AdapterFeaturedVodBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false), listener, actionText);
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        ViewHolder holder = (ViewHolder) viewHolder;
        boundHolders.add(holder);
        holder.bind((FeaturedVodRow) object);
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        boundHolders.remove(holder);
        holder.unbind();
    }

    @Override
    public void onViewAttachedToWindow(@NonNull Presenter.ViewHolder viewHolder) {
        super.onViewAttachedToWindow(viewHolder);
        ((ViewHolder) viewHolder).attach();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull Presenter.ViewHolder viewHolder) {
        ((ViewHolder) viewHolder).detach();
        super.onViewDetachedFromWindow(viewHolder);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private static final long AUTO_DELAY = 8000;
        private static final long INTERACTION_DELAY = 15_000;
        private static final long ARTWORK_FADE = 260;

        private final AdapterFeaturedVodBinding binding;
        private final VodPresenter.OnClickListener listener;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final FeaturedPosterCache posterCache = new FeaturedPosterCache();
        private final ImageRetryPolicy artworkRetry = new ImageRetryPolicy();
        private final Set<String> artworkMissing = new HashSet<>();
        private final Runnable rotate;
        private final ViewTreeObserver.OnWindowFocusChangeListener windowFocusListener;
        private FeaturedVodRow row;
        private ImageView front;
        private String currentArtwork;
        private long generation;
        private long pausedUntil;
        private boolean attached;
        private boolean loading;
        private boolean windowFocusListenerRegistered;
        private int index = -1;
        private int requestedIndex;

        ViewHolder(AdapterFeaturedVodBinding binding, VodPresenter.OnClickListener listener, int actionText) {
            super(binding.getRoot());
            this.binding = binding;
            this.listener = listener;
            this.front = binding.imageA;
            binding.actionText.setText(actionText);
            binding.getRoot().getLayoutParams().height = Math.round(ResUtil.getScreenHeight() * 0.58f);
            binding.copy.getLayoutParams().width = Math.round(ResUtil.getScreenWidth() * 0.44f);
            rotate = () -> {
                if (canRotate() && SystemClock.uptimeMillis() >= pausedUntil) show(index + 1, true);
                schedule();
            };
            windowFocusListener = hasFocus -> {
                if (hasFocus) {
                    publishArtwork();
                    schedule();
                } else stop();
            };
            binding.getRoot().setOnFocusChangeListener((view, focused) -> {
                // The hero stays still; only its persistent action indicates focus.
                binding.action.setSelected(focused);
                JetStreamAnimator.animateFocus(binding.action, focused, JetStreamAnimator.FOCUS_SCALE_CARD, 0);
                if (focused) pauseRotation();
                else schedule();
            });
            binding.getRoot().setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                pauseRotation();
                if (row == null || row.size() < 2) return false;
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    show(requestedIndex + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1), true);
                    return true;
                }
                return false;
            });
        }

        private void bind(FeaturedVodRow row) {
            this.row = row;
            this.index = -1;
            this.requestedIndex = 0;
            this.currentArtwork = null;
            generation++;
            stop();
            if (posterCache.prepare(row.getItems())) {
                artworkMissing.clear();
                artworkRetry.clear();
            }
            for (ImageView image : new ImageView[]{binding.imageA, binding.imageB}) {
                image.animate().cancel();
                ImgUtil.clear(image);
                image.setImageDrawable(null);
                image.setAlpha(0f);
                image.setVisibility(View.INVISIBLE);
            }
            binding.copy.animate().cancel();
            binding.copy.setAlpha(0f);
            front = binding.imageA;
            bindIndicator(row.size());
            binding.getRoot().setOnClickListener(view -> {
                pauseRotation();
                // During a load the visible title and the click destination remain the same item.
                if (this.row != null && index >= 0) listener.onItemClick(this.row.get(index), front);
            });
            binding.getRoot().setOnLongClickListener(view -> {
                pauseRotation();
                return this.row != null && index >= 0 && listener.onLongClick(this.row.get(index));
            });
            show(0, false);
        }

        private void show(int position, boolean animate) {
            if (row == null || row.isEmpty()) return;
            stop();
            requestedIndex = Math.floorMod(position, row.size());
            int nextIndex = requestedIndex;
            long request = ++generation;
            loading = true;
            binding.copy.animate().cancel();
            if (index >= 0) binding.copy.setAlpha(1f);
            Vod item = row.get(nextIndex);
            String cached = posterCache.get(item);
            String url = TextUtils.isEmpty(cached) ? item.getBackdrop() : cached;
            loadCandidate(item, url, nextIndex, request, animate, false);
        }

        private void loadCandidate(Vod item, String url, int nextIndex, long request, boolean animate, boolean upgrade) {
            if (!isCurrent(request)) return;
            ImageView next = front == binding.imageA ? binding.imageB : binding.imageA;
            next.animate().cancel();
            ImgUtil.clear(next);
            next.setAlpha(0f);
            next.setVisibility(View.INVISIBLE);
            ImgUtil.loadArtwork(item.getName(), url, next, success -> handler.post(() -> {
                // Post also leaves Glide's listener before loading a fallback into the same view.
                if (!isCurrent(request)) return;
                if (!success && (upgrade || TextUtils.equals(url, posterCache.get(item)))) {
                    posterCache.invalidate(item, url);
                    artworkRetry.onFailure(FeaturedPosterCache.keyOf(item));
                }
                if (!success && upgrade) return;
                if (!success && !TextUtils.equals(url, item.getPic())) {
                    loadCandidate(item, item.getPic(), nextIndex, request, animate, false);
                    return;
                }
                if (!success) next.setImageDrawable(null);
                present(item, success ? url : "", next, nextIndex, request, animate, upgrade);
            }));
        }

        private void present(Vod item, String url, ImageView next, int nextIndex, long request, boolean animate, boolean upgrade) {
            Runnable commit = () -> {
                if (!isCurrent(request)) return;
                ImageView previous = front;
                previous.animate().cancel();
                front = next;
                index = nextIndex;
                loading = false;
                bindText(item);
                updateIndicator();
                next.setVisibility(View.VISIBLE);
                next.animate().alpha(1f).setDuration(animate ? ARTWORK_FADE : 0).start();
                previous.animate().alpha(0f).setDuration(animate ? ARTWORK_FADE : 0).withEndAction(() -> {
                    if (previous != front) previous.setVisibility(View.INVISIBLE);
                }).start();
                binding.copy.animate().alpha(1f).setDuration(animate ? ARTWORK_FADE : 0).start();
                currentArtwork = url;
                publishArtwork();
                schedule();
                if (!upgrade) lookupArtwork(item, request);
            };
            if (animate && index >= 0 && !upgrade) {
                binding.copy.animate().alpha(0f).setDuration(100).withEndAction(commit).start();
            } else commit.run();
        }

        private void lookupArtwork(Vod item, long request) {
            String key = FeaturedPosterCache.keyOf(item);
            if (!TextUtils.isEmpty(posterCache.get(item)) || TextUtils.isEmpty(item.getName())
                    || !TextUtils.equals(item.getBackdrop(), item.getPic())
                    || artworkMissing.contains(key) || !artworkRetry.canLoad(key)) return;
            String signature = posterCache.getSignature();
            TmdbLogoHelper.findPoster(BuildConfig.TMDB_API_KEY, item.getName(), item.getYear(), item.getTypeName(), "w780", new TmdbLogoHelper.ImageCallback() {
                @Override
                public void onFound(@NonNull String imageUrl) {
                    if (!posterCache.isCurrent(signature)) return;
                    posterCache.put(signature, item, imageUrl);
                    artworkRetry.onSuccess(key);
                    if (isCurrent(request) && !TextUtils.equals(currentArtwork, imageUrl)) {
                        loadCandidate(item, imageUrl, index, request, true, true);
                    }
                }

                @Override
                public void onNotFound() {
                    if (posterCache.isCurrent(signature)) artworkMissing.add(key);
                }

                @Override
                public void onError(@NonNull Exception error) {
                    if (posterCache.isCurrent(signature)) artworkRetry.onFailure(key);
                }
            });
        }

        private boolean isCurrent(long request) {
            return row != null && request == generation;
        }

        private void bindText(Vod item) {
            binding.name.setText(item.getName());
            List<String> values = new ArrayList<>();
            if (!TextUtils.isEmpty(item.getYear())) values.add(item.getYear());
            if (!TextUtils.isEmpty(item.getTypeName())) values.add(item.getTypeName());
            if (!TextUtils.isEmpty(item.getRemarks())) values.add(item.getRemarks());
            binding.meta.setText(TextUtils.join("  ·  ", values));
            binding.kicker.setText(TextUtils.isEmpty(item.getSiteName()) ? binding.getRoot().getContext().getString(R.string.home_hero_kicker) : item.getSiteName());
            binding.getRoot().setContentDescription(item.getName() + ", " + binding.actionText.getText());
        }

        private void bindIndicator(int count) {
            binding.indicator.removeAllViews();
            binding.indicator.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
            for (int i = 0; i < count; i++) {
                View dot = new JetStreamFeaturedIndicatorDotView(binding.indicator.getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ResUtil.dp2px(5), ResUtil.dp2px(5));
                params.setMarginStart(ResUtil.dp2px(4));
                params.setMarginEnd(ResUtil.dp2px(4));
                binding.indicator.addView(dot, params);
            }
        }

        private void updateIndicator() {
            for (int i = 0; i < binding.indicator.getChildCount(); i++) binding.indicator.getChildAt(i).setSelected(i == index);
            binding.indicator.setContentDescription(binding.getRoot().getContext().getString(R.string.home_hero_position, index + 1, row.size()));
        }

        private void publishArtwork() {
            if (!TextUtils.isEmpty(currentArtwork) && attached && binding.getRoot().hasWindowFocus()) JetStreamAmbient.push(currentArtwork);
        }

        private boolean canRotate() {
            return attached && row != null && row.size() > 1 && !loading
                    && binding.getRoot().hasWindowFocus() && !binding.getRoot().hasFocus();
        }

        private void pauseRotation() {
            pausedUntil = SystemClock.uptimeMillis() + INTERACTION_DELAY;
            schedule();
        }

        private void schedule() {
            stop();
            if (canRotate()) handler.postDelayed(rotate, Math.max(AUTO_DELAY, pausedUntil - SystemClock.uptimeMillis()));
        }

        private void stop() {
            handler.removeCallbacks(rotate);
        }

        private void attach() {
            attached = true;
            if (!windowFocusListenerRegistered) {
                binding.getRoot().getViewTreeObserver().addOnWindowFocusChangeListener(windowFocusListener);
                windowFocusListenerRegistered = true;
            }
            publishArtwork();
            schedule();
        }

        private void detach() {
            attached = false;
            stop();
            ViewTreeObserver observer = binding.getRoot().getViewTreeObserver();
            if (windowFocusListenerRegistered && observer.isAlive()) observer.removeOnWindowFocusChangeListener(windowFocusListener);
            windowFocusListenerRegistered = false;
        }

        private void unbind() {
            detach();
            generation++;
            row = null;
            loading = false;
            currentArtwork = null;
            binding.copy.animate().cancel();
            binding.imageA.animate().cancel();
            binding.imageB.animate().cancel();
            binding.getRoot().setOnClickListener(null);
            binding.getRoot().setOnLongClickListener(null);
            ImgUtil.clear(binding.imageA);
            ImgUtil.clear(binding.imageB);
        }
    }
}
