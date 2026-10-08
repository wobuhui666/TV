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
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.dialog.HistoryActionsDialog;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.utils.HistoryTaskQueue;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
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
    private HistoryActionsDialog historyActions;
    private Runnable historyActionFocus;

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
        binding.historyActionsBack.setOnClickListener(v -> finish());
        displayedCid = VodConfig.getCid();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (BrowseExperienceSettings.isHistoryActionsEnabled()) presenter.setDelete(false);
        updateHint();
        load();
    }

    private void load() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        HistoryRequestState.Request request = requests.begin(VodConfig.getCid(), false);
        if (displayedCid != request.cid()) {
            if (historyActions != null) historyActions.dismiss();
            historyActionFocus = null;
            items.clear();
            displayedCid = request.cid();
            presenter.setDelete(false);
            updateHint();
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
                    binding.historyActionsBack.setVisibility(BrowseExperienceSettings.isHistoryActionsEnabled() ? View.VISIBLE : View.GONE);
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
        binding.historyActionsBack.setVisibility(result.isEmpty() && BrowseExperienceSettings.isHistoryActionsEnabled() ? View.VISIBLE : View.GONE);
        if (result.isEmpty()) {
            presenter.setDelete(false);
            updateHint();
            if (historyActionFocus != null) restoreHistoryActionFocus();
            else if (restore && BrowseExperienceSettings.isHistoryActionsEnabled()) restoreFocus(binding.historyActionsBack, focus);
            return;
        }
        int selected = Math.max(0, Math.min(position, result.size() - 1));
        for (int i = 0; key != null && i < result.size(); i++) if (key.equals(result.get(i).getKey())) selected = i;
        binding.recycler.setSelectedPosition(selected);
        if (historyActionFocus != null) {
            restoreHistoryActionFocus();
            return;
        }
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
        if (BrowseExperienceSettings.isHistoryActionsEnabled()) {
            onLongClick(item);
            return;
        }
        if (enqueueHistoryDelete(item.getCid(), item.getKey())) load();
    }

    private boolean enqueueHistoryDelete(int cid, String key) {
        if (!resumed || isFinishing() || isDestroyed() || cid != VodConfig.getCid() || findHistoryPosition(cid, key) < 0) return false;
        requests.invalidate();
        tasks.cancelQuery();
        tasks.write(() -> {
            try {
                AppDatabase database = AppDatabase.get();
                database.runInTransaction(() -> {
                    // History.key is globally unique; never remove tracks after the record moved to another config.
                    if (database.getHistoryDao().find(cid, key) == null) return;
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
        return true;
    }

    private void deleteHistoryAction(int cid, String key) {
        if (!enqueueHistoryDelete(cid, key)) return;
        List<History> remaining = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            History item = (History) items.get(i);
            if (item.getCid() != cid || !item.getKey().equals(key)) remaining.add(item);
        }
        apply(remaining, false, focusGeneration);
        load();
    }

    @Override
    public boolean onLongClick() {
        if (BrowseExperienceSettings.isHistoryActionsEnabled()) {
            int position = binding.recycler.getSelectedPosition();
            if (position >= 0 && position < items.size()) onLongClick((History) items.get(position));
            return true;
        }
        presenter.setDelete(!presenter.isDelete());
        updateHint();
        items.notifyArrayItemRangeChanged(0, items.size());
        return true;
    }

    @Override
    public boolean onLongClick(History item) {
        return onLongClick(item, null);
    }

    @Override
    public boolean onLongClick(History item, KeyEvent openingKey) {
        if (!BrowseExperienceSettings.isHistoryActionsEnabled()) return onLongClick();
        int cid = item.getCid();
        String key = item.getKey();
        int position = findHistoryPosition(cid, key);
        if (!resumed || isFinishing() || isDestroyed() || cid != VodConfig.getCid() || position < 0) return true;
        if (historyActions != null && historyActions.isShowing()) return true;
        if (presenter.isDelete()) {
            presenter.setDelete(false);
            items.notifyArrayItemRangeChanged(0, items.size());
        }
        updateHint();
        historyActions = HistoryActionsDialog.show(this, item, openingKey, new HistoryActionsDialog.Listener() {
            @Override public void onDelete(int selectedCid, String selectedKey) {
                deleteHistoryAction(selectedCid, selectedKey);
            }

            @Override public void onClosed(boolean restoreFocus) {
                historyActions = null;
                if (!restoreFocus) return;
                long generation = ++focusGeneration;
                historyActionFocus = () -> {
                    if (generation != focusGeneration || cid != VodConfig.getCid()) return;
                    if (items.size() == 0) {
                        restoreFocus(binding.historyActionsBack, generation);
                        return;
                    }
                    int selected = findHistoryPosition(cid, key);
                    binding.recycler.setSelectedPosition(selected >= 0 ? selected : Math.min(position, items.size() - 1));
                    restoreFocus(binding.recycler, generation);
                };
                restoreHistoryActionFocus();
            }
        });
        return true;
    }

    private int findHistoryPosition(int cid, String key) {
        for (int i = 0; i < items.size(); i++) {
            History item = (History) items.get(i);
            if (item.getCid() == cid && item.getKey().equals(key)) return i;
        }
        return -1;
    }

    private void updateHint() {
        binding.hint.setText(BrowseExperienceSettings.isHistoryActionsEnabled() ? R.string.history_actions_hint
                : presenter.isDelete() ? R.string.my_history_delete_hint : R.string.my_history_hint);
    }

    private void restoreHistoryActionFocus() {
        if (historyActionFocus == null || !resumed || !hasWindowFocus() || isFinishing() || isDestroyed()) return;
        Runnable restore = historyActionFocus;
        historyActionFocus = null;
        restore.run();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            focusGeneration++;
            historyActionFocus = null;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_MENU && BrowseExperienceSettings.isHistoryActionsEnabled()
                && binding.recycler.hasFocus()) {
            int position = binding.recycler.getSelectedPosition();
            if (position >= 0 && position < items.size()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) onLongClick((History) items.get(position), event);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) restoreHistoryActionFocus();
    }

    @Override
    protected void onBackInvoked() {
        if (presenter.isDelete()) {
            presenter.setDelete(false);
            updateHint();
            items.notifyArrayItemRangeChanged(0, items.size());
        } else super.onBackInvoked();
    }

    @Override
    protected void onPause() {
        resumed = false;
        requests.invalidate();
        tasks.cancelQuery();
        focusGeneration++;
        historyActionFocus = null;
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
