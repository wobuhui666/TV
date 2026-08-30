package com.fongmi.android.tv.model;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.media3.common.C;

import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.LiveApi;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.extractor.Source;
import com.fongmi.android.tv.playback.live.LivePlaybackController;
import com.fongmi.android.tv.playback.live.LivePlaybackHost;
import com.fongmi.android.tv.playback.live.LivePlaybackState;

import java.time.ZoneId;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class LiveViewModel extends ViewModel {

    private final MutableLiveData<Boolean> xml;
    private final MutableLiveData<Result> url;
    private final MutableLiveData<Live> live;
    private final MutableLiveData<Epg> epg;

    private final ViewModelTaskRunner<TaskType> tasks;
    private final LivePlaybackState playbackState;
    private volatile ZoneId zoneId;

    public LiveViewModel() {
        this.epg = new MutableLiveData<>();
        this.xml = new MutableLiveData<>();
        this.url = new MutableLiveData<>();
        this.live = new MutableLiveData<>();
        this.zoneId = ZoneId.systemDefault();
        this.playbackState = new LivePlaybackState();
        this.tasks = new ViewModelTaskRunner<>(TaskType.class);
    }

    public LiveData<Result> url() {
        return url;
    }

    public LiveData<Boolean> xml() {
        return xml;
    }

    public LiveData<Epg> epg() {
        return epg;
    }

    public LiveData<Live> live() {
        return live;
    }

    public ZoneId getZoneId() {
        return zoneId;
    }

    public LivePlaybackController createPlaybackController(LivePlaybackHost host) {
        return new LivePlaybackController(host, playbackState);
    }

    public void parse(Live item) {
        execute(TaskType.LIVE, () -> {
            LiveApi.parse(item);
            return item;
        }, result -> {
            setTimeZone(result);
            live.setValue(result);
        }, this::handleParseError);
    }

    public void parseXml(Live item) {
        execute(TaskType.XML, () -> LiveApi.parseXml(item), xml::setValue, error -> xml.setValue(false));
    }

    public void getEpg(Channel item) {
        execute(TaskType.EPG, () -> LiveApi.getEpg(item, zoneId), epg::setValue, error -> epg.setValue(new Epg()));
    }

    public void getUrl(Channel item) {
        getUrl(item, C.TIME_UNSET);
    }

    public void getUrl(Channel item, long startPositionMs) {
        Source.ResolveRequest request = Source.get().beginResolve();
        requestUrl(() -> LiveApi.getUrl(request, item), startPositionMs);
    }

    public void getUrl(Channel item, EpgData data) {
        getUrl(item, data, C.TIME_UNSET);
    }

    public void getUrl(Channel item, EpgData data, long startPositionMs) {
        Source.ResolveRequest request = Source.get().beginResolve();
        requestUrl(() -> LiveApi.getUrl(request, item, data), startPositionMs);
    }

    private void requestUrl(Callable<Result> callable, long startPositionMs) {
        execute(TaskType.URL, callable, result -> postUrl(result, startPositionMs), error -> handleUrlError(error, startPositionMs));
    }

    private void postUrl(Result result, long startPositionMs) {
        if (startPositionMs != C.TIME_UNSET) result.setPosition(startPositionMs);
        url.setValue(result);
    }

    private void handleParseError(Throwable t) {
        if (t instanceof ExtractException) postUrl(Result.error(t.getMessage()), C.TIME_UNSET);
        else live.setValue(new Live());
    }

    private void handleUrlError(Throwable t, long startPositionMs) {
        if (t instanceof ExtractException) postUrl(Result.error(t.getMessage()), startPositionMs);
        else postUrl(new Result(), startPositionMs);
    }

    private void setTimeZone(Live live) {
        this.zoneId = live.getZoneId();
    }

    private <T> void execute(TaskType type, Callable<T> callable, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        tasks.execute(type, type.timeout, callable, onSuccess, onError);
    }

    @Override
    protected void onCleared() {
        tasks.cancelAll();
        playbackState.reset();
    }

    private enum TaskType {

        LIVE(Constant.TIMEOUT_LIVE),
        EPG(Constant.TIMEOUT_EPG),
        XML(Constant.TIMEOUT_XML),
        URL(Constant.TIMEOUT_PARSE_LIVE);

        final long timeout;

        TaskType(long timeout) {
            this.timeout = timeout;
        }
    }
}
