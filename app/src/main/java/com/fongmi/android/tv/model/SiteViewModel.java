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
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.source.health.SourceHealthManager;
import com.github.catvod.utils.Trans;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class SiteViewModel extends ViewModel {

    private final MutableLiveData<Result> result;
    private final MutableLiveData<Result> player;
    private final MutableLiveData<Result> search;
    private final MutableLiveData<Result> action;

    private final ViewModelTaskRunner<TaskType> tasks;
    private final ViewModelSearchRunner searches;

    public SiteViewModel() {
        result = new MutableLiveData<>();
        player = new MutableLiveData<>();
        search = new MutableLiveData<>();
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

    public LiveData<Result> getAction() {
        return action;
    }

    public SiteViewModel init() {
        searches.stop();
        tasks.cancelAll();
        search.setValue(null);
        result.setValue(null);
        player.setValue(null);
        action.setValue(null);
        return this;
    }

    public void homeContent() {
        Site site = VodConfig.get().getHome();
        execute(TaskType.RESULT, result, () -> SiteApi.homeContent(site));
    }

    /** Captures the selected source before dispatch, so a later switch cannot change this request. */
    public void homeContent(Site site, Consumer<Result> onResult) {
        execute(TaskType.RESULT, onResult, () -> SiteApi.homeContent(site));
    }

    public void categoryContent(String key, String tid, String page, boolean filter, HashMap<String, String> extend) {
        HashMap<String, String> requestExtend = new HashMap<>(extend);
        execute(TaskType.RESULT, result, () -> SiteApi.categoryContent(key, tid, page, filter, requestExtend));
    }

    public void action(String key, String act) {
        execute(TaskType.ACTION, action, () -> SiteApi.action(key, act));
    }

    public void detailContent(String key, String id) {
        executeHealth(key, SourceHealthManager.Phase.DETAIL, () -> SiteApi.detailContent(key, id));
    }

    public void playerContent(String key, String flag, String id) {
        Source.ResolveRequest request = Source.get().beginResolve();
        execute(TaskType.PLAYER, player, () -> SiteApi.playerContent(request, key, flag, id));
    }

    public void searchContent(Site site, String keyword, boolean quick, String page) {
        if (quick && !site.isQuickSearch()) execute(TaskType.RESULT, result, () -> Result.empty());
        else executeHealth(site.getKey(), SourceHealthManager.Phase.SEARCH, SearchTask.create(site, keyword, quick, page));
    }

    public void searchContent(List<Site> sites, String keyword, boolean quick) {
        List<Site> ordered = SourceHealthManager.sort(sites);
        if (quick) ordered.removeIf(site -> !site.isQuickSearch());
        searches.start(ordered, site -> SearchTask.create(site, keyword, quick), search::setValue);
    }

    /** Uses the same global concurrency budget and generation cancellation as ordinary search. */
    public void searchRawContent(List<Site> sites, String keyword) {
        String query = Trans.t2s(keyword);
        searches.start(SourceHealthManager.sort(sites), site -> () -> SiteApi.searchRawContent(site, query, false, "1", null), search::setValue);
    }

    private void executeHealth(String siteKey, SourceHealthManager.Phase phase, Callable<Result> callable) {
        SourceHealthManager.Attempt attempt = SourceHealthManager.attempt(siteKey, phase);
        tasks.execute(TaskType.RESULT, Constant.TIMEOUT_VOD, () -> {
            attempt.start();
            try { return callable.call(); }
            finally { attempt.finish(); }
        }, value -> {
            attempt.complete(value, null);
            result.setValue(value);
        }, error -> {
            attempt.complete(null, error);
            if (error instanceof ExtractException) result.setValue(Result.error(error.getMessage()));
            else result.setValue(Result.empty());
            error.printStackTrace();
        });
    }

    private void execute(TaskType type, MutableLiveData<Result> liveData, Callable<Result> callable) {
        execute(type, liveData::setValue, callable);
    }

    private void execute(TaskType type, Consumer<Result> onResult, Callable<Result> callable) {
        tasks.execute(type, Constant.TIMEOUT_VOD, callable, onResult, error -> {
            if (error instanceof ExtractException) onResult.accept(Result.error(error.getMessage()));
            else onResult.accept(Result.empty());
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
}
