package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.setting.SiteHealthStore;
import com.github.catvod.utils.Trans;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> player;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<SiteSearchSnapshot> searchSnapshot;
    private final MutableLiveData<Result> action;

    private final ViewModelTaskRunner<TaskType> tasks;
    private final ViewModelSearchRunner searches;

    public SiteViewModel() {
        result = new MutableLiveData<>();
        player = new MutableLiveData<>();
        search = new MutableLiveData<>();
        searchSnapshot = new MutableLiveData<>();
        action = new MutableLiveData<>();
        tasks = new ViewModelTaskRunner<>(TaskType.class);
        searches = new ViewModelSearchRunner();
    }

    public LiveData<Result> getResult() {
        return result;
    }

    public LiveData<Result> getPlayer() {
        return player;
    }

    public LiveData<Result> getSearch() {
        return search;
    }

    public LiveData<SiteSearchSnapshot> getSearchSnapshot() {
        return searchSnapshot;
    }

    public LiveData<Result> getAction() {
        return action;
    }

    public SiteViewModel init() {
        search.setValue(null);
        searchSnapshot.setValue(null);
        result.setValue(null);
        player.setValue(null);
        action.setValue(null);
        return this;
    }

    public void homeContent() {
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(VodConfig.get().getHome()));
    }

    public void categoryContent(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
        execute(TaskType.RESULT, result, () -> SiteApi.categoryContent(key, tid, page, filter, extend));
    }

    public void action(String key, String act) {
        execute(TaskType.ACTION, action, () -> SiteApi.action(key, act));
    }

    public void detailContent(String key, String id) {
        long start = System.currentTimeMillis();
        tasks.execute(TaskType.RESULT, Constant.TIMEOUT_VOD, () -> SiteApi.detailContent(key, id), value -> {
            SiteHealthStore.recordDetail(key, true, System.currentTimeMillis() - start, "");
            result.postValue(value);
        }, error -> {
            SiteHealthStore.recordDetail(key, false, System.currentTimeMillis() - start, error.getMessage());
            result.postValue(error instanceof ExtractException ? Result.error(error.getMessage()) : Result.empty());
        });
    }

    public void playerContent(String key, String flag, String id) {
        execute(TaskType.PLAYER, player, () -> SiteApi.playerContent(key, flag, id));
    }

    public void searchContent(Site site, String keyword, boolean quick, String page) {
        long start = System.currentTimeMillis();
        tasks.execute(TaskType.RESULT, Constant.TIMEOUT_VOD, SearchTask.create(site, keyword, quick, page), value -> {
            SiteHealthStore.recordSearch(site, true, value.getList().size(), System.currentTimeMillis() - start, "");
            result.postValue(value);
        }, error -> {
            SiteHealthStore.recordSearch(site, false, 0, System.currentTimeMillis() - start, error.getMessage());
            result.postValue(error instanceof ExtractException ? Result.error(error.getMessage()) : Result.empty());
        });
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        List<Site> frozenOrder = SiteHealthStore.sort(sites);
        SearchSession session = new SearchSession(frozenOrder);
        searchSnapshot.setValue(session.snapshot());
        searches.start(frozenOrder, site -> SearchTask.create(site, keyword, quick), event -> {
            Result value = event.result();
            SiteHealthStore.recordSearch(event.site(), event.success(), value.getList().size(), event.elapsedMs(), event.error() == null ? "" : event.error().getMessage());
            search.postValue(value);
            searchSnapshot.postValue(session.accept(event));
        });
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        tasks.execute(type, Constant.TIMEOUT_VOD, callable, liveData::postValue, error -> {
            if (error instanceof ExtractException) liveData.postValue(Result.error(error.getMessage()));
            else liveData.postValue(Result.empty());
            error.printStackTrace();
        });
    }

    public void stopSearch() {
        searches.stop();
    }

    @Override
    protected void onCleared() {
        stopSearch();
        tasks.cancelAll();
    }

    private record SearchTask(Site site, String keyword, boolean quick, String page) implements Callable<Result> {

        private static final String FIRST_PAGE = "1";

        SearchTask {
            keyword = Trans.t2s(keyword);
        }

        private static SearchTask create(Site site, String keyword, boolean quick) {
            return create(site, keyword, quick, FIRST_PAGE);
        }

        private static SearchTask create(Site site, String keyword, boolean quick, String page) {
            return new SearchTask(site, keyword, quick, page);
        }

        @Override
        public Result call() throws Exception {
            if (quick && !site.isQuickSearch()) return Result.empty();
            return SiteApi.searchContent(site, keyword, quick, page);
        }
    }

    private enum TaskType {RESULT, PLAYER, ACTION}

    private static final class SearchSession {

        private final Map<String, SiteSearchSnapshot.Entry> entries = new LinkedHashMap<>();

        SearchSession(List<Site> sites) {
            for (Site site : sites) entries.put(site.getKey(), new SiteSearchSnapshot.Entry(site, SiteSearchSnapshot.State.LOADING, List.of(), 0, ""));
        }

        synchronized SiteSearchSnapshot accept(ViewModelSearchRunner.SearchEvent event) {
            for (com.fongmi.android.tv.bean.Vod item : event.result().getList()) if (item.getSite() == null) item.setSite(event.site());
            SiteSearchSnapshot.State state = !event.success() ? SiteSearchSnapshot.State.FAILURE
                    : event.result().getList().isEmpty() ? SiteSearchSnapshot.State.EMPTY : SiteSearchSnapshot.State.SUCCESS;
            String error = event.error() == null ? "" : String.valueOf(event.error().getMessage());
            entries.put(event.site().getKey(), new SiteSearchSnapshot.Entry(event.site(), state, event.result().getList(), event.elapsedMs(), error));
            return snapshot();
        }

        synchronized SiteSearchSnapshot snapshot() {
            return new SiteSearchSnapshot(new java.util.ArrayList<>(entries.values()));
        }
    }
}
