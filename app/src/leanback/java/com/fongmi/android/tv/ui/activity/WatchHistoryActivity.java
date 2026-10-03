package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.HistoryRequestState;
import com.fongmi.android.tv.databinding.ActivityWatchHistoryBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.utils.HistoryTaskQueue;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

/** Complete history for the active configuration, queried independently of the home shelf limit. */
public class WatchHistoryActivity extends BaseActivity implements HistoryPresenter.OnClickListener {
    private ActivityWatchHistoryBinding binding;
    private ArrayObjectAdapter items;
    private HistoryPresenter presenter;
    private final HistoryRequestState requests = new HistoryRequestState();
    private final HistoryTaskQueue tasks = new HistoryTaskQueue();
    private boolean resumed;
    private int displayedCid;
    private long focusGeneration;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, WatchHistoryActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityWatchHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        int width = (ResUtil.getScreenWidth() - ResUtil.dp2px(160)) / 3;
        presenter = new HistoryPresenter(this, new int[]{width, ResUtil.dp2px(112)});
        items = new ArrayObjectAdapter(presenter);
        binding.recycler.setNumColumns(3);
        binding.recycler.setHorizontalSpacing(ResUtil.dp2px(24));
        binding.recycler.setVerticalSpacing(ResUtil.dp2px(24));
        binding.recycler.setAdapter(new ItemBridgeAdapter(items));
        binding.retry.setOnClickListener(v -> load());
        displayedCid = VodConfig.getCid();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        load();
    }

    private void load() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        HistoryRequestState.Request request = requests.begin(VodConfig.getCid(), false);
        if (displayedCid != request.cid()) {
            items.clear();
            displayedCid = request.cid();
            presenter.setDelete(false);
            binding.hint.setText(R.string.my_history_hint);
        }
        long focus = focusGeneration;
        boolean restore = items.size() == 0 || binding.recycler.hasFocus() || binding.retry.hasFocus();
        tasks.replaceQuery(() -> {
            try {
                List<History> result = AppDatabase.get().getHistoryDao().findAll(request.cid());
                if (Thread.currentThread().isInterrupted()) return;
                App.post(() -> {
                    if (!canApply(request)) return;
                    apply(result, restore, focus);
                    requests.applied(request, VodConfig.getCid());
                });
            } catch (Exception error) {
                App.post(() -> {
                    if (!canApply(request)) return;
                    binding.emptyText.setText(R.string.my_history_error);
                    binding.empty.setVisibility(View.VISIBLE);
                    binding.recycler.setVisibility(View.GONE);
                    binding.retry.setVisibility(View.VISIBLE);
                    if (restore) restoreFocus(binding.retry, focus);
                });
            }
        });
    }

    private boolean canApply(HistoryRequestState.Request request) {
        return resumed && requests.isCurrent(request, VodConfig.getCid()) && !isFinishing() && !isDestroyed();
    }

    private void apply(List<History> result, boolean restore, long focus) {
        int position = binding.recycler.getSelectedPosition();
        String key = position >= 0 && position < items.size() ? ((History) items.get(position)).getKey() : null;
        items.setItems(result, new BaseDiffCallback<History>());
        binding.emptyText.setText(R.string.my_history_empty);
        binding.empty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recycler.setVisibility(result.isEmpty() ? View.GONE : View.VISIBLE);
        binding.retry.setVisibility(View.GONE);
        if (result.isEmpty()) {
            presenter.setDelete(false);
            binding.hint.setText(R.string.my_history_hint);
            return;
        }
        int selected = Math.max(0, Math.min(position, result.size() - 1));
        for (int i = 0; key != null && i < result.size(); i++) if (key.equals(result.get(i).getKey())) selected = i;
        binding.recycler.setSelectedPosition(selected);
        if (restore) restoreFocus(binding.recycler, focus);
    }

    private void restoreFocus(View view, long generation) {
        view.post(() -> {
            if (generation == focusGeneration && resumed && hasWindowFocus() && view.isShown() && !isFinishing() && !isDestroyed()) view.requestFocus();
        });
    }

    @Override
    public void onItemClick(History item) {
        if (item.getCid() == VodConfig.getCid()) VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        if (!resumed || isFinishing() || isDestroyed() || item.getCid() != VodConfig.getCid()) return;
        int cid = item.getCid();
        String key = item.getKey();
        requests.invalidate();
        tasks.cancelQuery();
        tasks.write(() -> {
            try {
                AppDatabase database = AppDatabase.get();
                database.runInTransaction(() -> {
                    database.getHistoryDao().delete(cid, key);
                    database.getTrackDao().delete(key);
                });
                // Notify other screens even if this activity closed while the write was queued.
                App.post(RefreshEvent::history);
            } catch (Exception error) {
                App.post(() -> {
                    if (resumed && !isFinishing() && !isDestroyed()) Notify.show(R.string.tv_history_delete_error);
                });
            }
        });
        load();
    }

    @Override
    public boolean onLongClick() {
        presenter.setDelete(!presenter.isDelete());
        binding.hint.setText(presenter.isDelete() ? R.string.my_history_delete_hint : R.string.my_history_hint);
        items.notifyArrayItemRangeChanged(0, items.size());
        return true;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) focusGeneration++;
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onBackInvoked() {
        if (presenter.isDelete()) onLongClick();
        else super.onBackInvoked();
    }

    @Override
    protected void onPause() {
        resumed = false;
        requests.invalidate();
        tasks.cancelQuery();
        focusGeneration++;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        requests.close();
        focusGeneration++;
        tasks.close();
        super.onDestroy();
    }
}
