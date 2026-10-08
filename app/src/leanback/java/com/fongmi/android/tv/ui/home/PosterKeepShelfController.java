package com.fongmi.android.tv.ui.home;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.DiffCallback;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.PosterKeepShelfState;
import com.fongmi.android.tv.databinding.AdapterHomeKeepShelfBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.ui.activity.DiscoverDetailActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.TouchFocus;
import com.fongmi.android.tv.ui.presenter.PosterKeepPresenter;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/** One opt-in, local collection row. Its lifetime follows the native poster home. */
final class PosterKeepShelfController implements PosterKeepPresenter.Listener {

    private static final DiffCallback<Keep> DIFF = new DiffCallback<>() {
        @Override
        public boolean areItemsTheSame(@NonNull Keep oldItem, @NonNull Keep newItem) {
            return oldItem.getType() == newItem.getType() && oldItem.getCid() == newItem.getCid()
                    && Objects.equals(oldItem.getKey(), newItem.getKey());
        }

        @Override
        public boolean areContentsTheSame(@NonNull Keep oldItem, @NonNull Keep newItem) {
            return Objects.equals(oldItem.getVodName(), newItem.getVodName())
                    && Objects.equals(oldItem.getVodPic(), newItem.getVodPic())
                    && Objects.equals(oldItem.getSiteName(), newItem.getSiteName())
                    && oldItem.getCreateTime() == newItem.getCreateTime();
        }
    };

    private final Activity activity;
    private final IntFunction<List<Keep>> readKeeps;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PosterKeepShelfState state = new PosterKeepShelfState();
    private final Row row = new Row();
    // Repeated refreshes can replace only one queued read; even a query ignoring interruption
    // cannot create additional workers or an unbounded queue.
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> new Thread(runnable, "poster-keep-shelf"));
    private final View decor;
    private final ViewTreeObserver.OnWindowFocusChangeListener windowFocusListener;
    private ArrayObjectAdapter page;
    private Holder boundHolder;
    private Future<?> task;
    private Runnable focusFallback = () -> { };
    private View pendingFocusOwner;
    private boolean active;
    private boolean closed;

    PosterKeepShelfController(Activity activity) {
        this(activity, cid -> AppDatabase.get().getKeepDao().getPosterShelf(cid, PosterKeepShelfState.MAX_POSTERS + 1));
    }

    PosterKeepShelfController(Activity activity, IntFunction<List<Keep>> readKeeps) {
        this.activity = activity;
        this.readKeeps = readKeeps;
        executor.allowCoreThreadTimeOut(true);
        decor = activity.getWindow().getDecorView();
        windowFocusListener = focused -> {
            if (focused) recoverFocus();
        };
        decor.getViewTreeObserver().addOnWindowFocusChangeListener(windowFocusListener);
    }

    void register(CustomSelector selector) {
        selector.addPresenter(Row.class, new RowPresenter());
    }

    void attach(ArrayObjectAdapter page) {
        this.page = page;
    }

    boolean isRow(Object item) {
        return item == row;
    }

    boolean isFocusable() {
        return isEnabled() && state.getCid() == VodConfig.getCid() && state.isVisible();
    }

    void setFocusFallback(Runnable fallback) {
        focusFallback = fallback;
    }

    void resume() {
        if (closed) return;
        active = true;
        refresh();
    }

    void pause() {
        active = false;
        pendingFocusOwner = null;
        cancelRead();
    }

    void refresh() {
        if (closed || page == null) return;
        cancelRead();
        boolean enabled = isEnabled();
        PosterKeepShelfState.Request request = state.begin(VodConfig.getCid(), enabled);
        render();
        if (!active || !enabled) return;
        try {
            task = executor.submit(() -> read(request));
        } catch (RejectedExecutionException error) {
            if (state.fail(request, VodConfig.getCid(), isEnabled())) render();
        }
    }

    private void read(PosterKeepShelfState.Request request) {
        try {
            List<Keep> items = Objects.requireNonNull(readKeeps.apply(request.cid()));
            if (Thread.currentThread().isInterrupted()) return;
            handler.post(() -> {
                if (!canApply(request)) return;
                task = null;
                if (state.complete(request, VodConfig.getCid(), isEnabled(), items)) render();
            });
        } catch (Exception error) {
            if (Thread.currentThread().isInterrupted()) return;
            handler.post(() -> {
                if (!canApply(request)) return;
                task = null;
                if (state.fail(request, VodConfig.getCid(), isEnabled())) render();
            });
        }
    }

    private boolean canApply(PosterKeepShelfState.Request request) {
        return active && !closed && !activity.isFinishing() && !activity.isDestroyed()
                && state.isCurrent(request, VodConfig.getCid(), isEnabled());
    }

    private boolean isEnabled() {
        return BrowseExperienceSettings.isPosterHomeEnabled() && BrowseExperienceSettings.isPosterKeepShelfEnabled();
    }

    private void cancelRead() {
        state.invalidate();
        if (task != null) task.cancel(true);
        task = null;
        executor.purge();
        handler.removeCallbacksAndMessages(null);
    }

    private void render() {
        if (!state.isVisible() && boundHolder != null && boundHolder.view.hasFocus() && active) {
            pendingFocusOwner = boundHolder.view;
            recoverFocus();
        }
        int index = page.indexOf(row);
        if (!state.isVisible()) {
            // Removing the row also removes Leanback's inter-row spacing when disabled or empty.
            if (index >= 0) page.removeItems(index, 1);
        } else if (index >= 0) {
            page.notifyArrayItemRangeChanged(index, 1);
        } else {
            // Never insert between the legacy history and recommendation sentinels:
            // HomeActivity still identifies its history row from the distance between them.
            int recommendation = page.indexOf(R.string.home_recommend);
            page.add(recommendation < 0 ? page.size() : recommendation + 1, row);
        }
        recoverFocus();
    }

    private void recoverFocus() {
        if (pendingFocusOwner == null || !active || closed || !activity.hasWindowFocus()
                || activity.isFinishing() || activity.isDestroyed()) return;
        View focused = activity.getCurrentFocus();
        View previous = pendingFocusOwner;
        pendingFocusOwner = null;
        // A surviving card or a deliberate move elsewhere already provides valid focus.
        if (focused == null || focused instanceof RecyclerView || isInside(focused, previous)) focusFallback.run();
    }

    private static boolean isInside(View view, View parent) {
        for (View current = view; current != null; ) {
            if (current == parent) return true;
            ViewParent next = current.getParent();
            current = next instanceof View ? (View) next : null;
        }
        return false;
    }

    void close() {
        if (closed) return;
        closed = true;
        pause();
        state.close();
        executor.shutdownNow();
        decor.getViewTreeObserver().removeOnWindowFocusChangeListener(windowFocusListener);
        boundHolder = null;
        page = null;
    }

    @Override
    public void onKeepClick(Keep item, View poster) {
        if (!active || closed || !isEnabled() || activity.isFinishing() || activity.isDestroyed()) return;
        int cid = VodConfig.getCid();
        if (state.getCid() != cid || !PosterKeepShelfState.belongsToShelf(item, cid)) {
            refresh();
            return;
        }
        if (item.getType() == Keep.TYPE_DISCOVER) {
            DiscoverMediaKey key = DiscoverMediaKey.parse(item.getKey());
            if (key != null) DiscoverDetailActivity.start(activity, key, item.getVodName(), item.getVodPic(), "", "", "", "", poster);
        } else {
            VideoActivity.start(activity, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), poster);
        }
    }

    private static final class Row {
    }

    private final class RowPresenter extends Presenter {
        @NonNull
        @Override
        @SuppressLint("RestrictedApi")
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
            AdapterHomeKeepShelfBinding binding = AdapterHomeKeepShelfBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(new PosterKeepPresenter(PosterKeepShelfController.this));
            binding.keepPosters.setRowHeight(ResUtil.dp2px(230));
            binding.keepPosters.setHorizontalSpacing(ResUtil.dp2px(20));
            binding.keepPosters.setItemAnimator(null);
            binding.keepPosters.setFocusScrollStrategy(HorizontalGridView.FOCUS_SCROLL_ITEM);
            binding.keepPosters.setAdapter(new ItemBridgeAdapter(adapter));
            TouchFocus.bind(binding.keepMore, binding.keepRetry);
            binding.keepMore.setOnClickListener(view -> {
                if (active && !closed && isEnabled()) KeepActivity.start(activity);
            });
            binding.keepRetry.setOnClickListener(view -> refresh());
            return new Holder(binding, adapter);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder viewHolder, Object item) {
            Holder holder = (Holder) viewHolder;
            boundHolder = holder;
            AdapterHomeKeepShelfBinding binding = holder.binding;
            int position = binding.keepPosters.getSelectedPosition();
            Keep selected = position >= 0 && position < holder.adapter.size() ? (Keep) holder.adapter.get(position) : null;
            boolean restoreCard = binding.keepPosters.hasFocus();
            boolean restoreAction = binding.keepMore.hasFocus() && !state.hasMore()
                    || binding.keepRetry.hasFocus() && !state.hasFailed();
            List<Keep> items = state.getItems();
            holder.adapter.setItems(items, DIFF);
            binding.keepPosters.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
            if ((restoreCard || restoreAction) && !items.isEmpty()) {
                int target = Math.min(Math.max(position, 0), items.size() - 1);
                if (selected != null) {
                    for (int i = 0; i < items.size(); i++) {
                        if (DIFF.areItemsTheSame(selected, items.get(i))) { target = i; break; }
                    }
                }
                binding.keepPosters.setSelectedPosition(target);
                if (restoreAction && active && activity.hasWindowFocus()) binding.keepPosters.requestFocus();
            }
            binding.keepMore.setVisibility(state.hasMore() ? View.VISIBLE : View.GONE);
            binding.keepFailure.setVisibility(state.hasFailed() ? View.VISIBLE : View.GONE);
            boolean visible = state.isVisible();
            holder.view.setVisibility(visible ? View.VISIBLE : View.GONE);
            ViewGroup.LayoutParams params = holder.view.getLayoutParams();
            if (params != null && params.height != (visible ? ViewGroup.LayoutParams.WRAP_CONTENT : 0)) {
                params.height = visible ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
                holder.view.setLayoutParams(params);
            }
        }

        @Override
        public void onUnbindViewHolder(@NonNull ViewHolder viewHolder) {
            Holder holder = (Holder) viewHolder;
            if (boundHolder == holder) boundHolder = null;
            holder.adapter.clear();
        }
    }

    private static final class Holder extends Presenter.ViewHolder {
        final AdapterHomeKeepShelfBinding binding;
        final ArrayObjectAdapter adapter;

        Holder(AdapterHomeKeepShelfBinding binding, ArrayObjectAdapter adapter) {
            super(binding.getRoot());
            this.binding = binding;
            this.adapter = adapter;
        }
    }
}
