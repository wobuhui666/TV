package com.fongmi.android.tv.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.CastHistorySnapshot;
import com.fongmi.android.tv.bean.CastRequestState;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.databinding.DialogDeviceBinding;
import com.fongmi.android.tv.dlna.DLNACast;
import com.fongmi.android.tv.dlna.DLNACastManager;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.activity.ScanActivity;
import com.fongmi.android.tv.ui.adapter.DeviceAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ScanTask;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Response;

public class CastDialog extends BaseBottomSheetDialog implements DeviceAdapter.OnClickListener, ScanTask.Listener, DLNACastManager.DeviceListener {

    private final OkHttpClient client;
    private final String config;
    private final String device;

    private DialogDeviceBinding binding;
    private DeviceAdapter adapter;
    private ScanTask scanTask;
    private CastVideo video;
    private History history;
    private CastRequestState requests;
    private Object openedMedia;
    private Call activeCall;
    private boolean fm;

    public CastDialog() {
        scanTask = new ScanTask(this);
        device = Device.get().toString();
        config = Config.vod().toString();
        client = OkHttp.client(Constant.TIMEOUT_SYNC);
    }

    public static CastDialog create() {
        return new CastDialog();
    }

    public CastDialog history(History history) {
        this.history = history == null ? null : history.copy();
        return this;
    }

    public CastDialog video(CastVideo video) {
        this.video = video;
        return this;
    }

    public CastDialog fm(boolean fm) {
        this.fm = fm;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof CastDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogDeviceBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        requests = new CastRequestState();
        openedMedia = listener().getCastPlaybackIdentity();
        binding.scan.setVisibility(fm ? View.VISIBLE : View.GONE);
        DLNACastManager.get().init(requireActivity());
        DLNACastManager.get().setDeviceListener(this);
        setRecyclerView();
        getDevice();
    }

    @Override
    protected void initEvent() {
        binding.scan.setOnClickListener(v -> onScan());
        binding.refresh.setOnClickListener(v -> onRefresh());
    }

    private void setRecyclerView() {
        binding.recycler.setHasFixedSize(false);
        binding.recycler.setAdapter(adapter = new DeviceAdapter(this));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
    }

    private void setRecyclerVisible() {
        binding.recycler.setVisibility(adapter.getItemCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void getDevice() {
        adapter.setItems(Device.getAll(), () -> {
            adapter.sort(DLNACastManager.get().getRegistered(), this::setRecyclerVisible);
            if (adapter.getItemCount() == 0) onRefresh();
            else DLNACastManager.get().search();
        });
    }

    private void onScan() {
        launcher.launch(new Intent(requireActivity(), ScanActivity.class));
    }

    private void onRefresh() {
        adapter.clear(() -> {
            Device.delete();
            if (fm) scanTask.start();
            DLNACastManager.get().search();
            adapter.sort(DLNACastManager.get().getRegistered(), this::setRecyclerVisible);
        });
    }

    private Listener listener() { return (Listener) requireActivity(); }

    private boolean isCurrent(CastRequestState state, CastRequestState.Request request, History sentHistory) {
        if (binding == null || !isAdded() || state != requests
                || !state.isCurrent(request, listener().getCastPlaybackIdentity())) return false;
        History current = listener().getCastHistory();
        return sentHistory == null || current == null || sameHistory(sentHistory, current);
    }

    private static boolean sameHistory(History first, History second) {
        return first.getCid() == second.getCid() && Objects.equals(first.getKey(), second.getKey())
                && Objects.equals(first.getEpisodeUrl(), second.getEpisodeUrl());
    }

    private void onCasted(CastRequestState state, CastRequestState.Request request, History sentHistory) {
        if (!isCurrent(state, request, sentHistory)) return;
        listener().onCasted();
        dismiss();
    }

    private FormBody body(History snapshot) {
        FormBody.Builder body = new FormBody.Builder().add("device", device).add("config", config);
        if (snapshot != null) {
            History transported = CastHistorySnapshot.transport(snapshot, this::shareUrl);
            body.add("history", transported.toString());
        }
        return body.build();
    }

    private String shareUrl(String value) {
        if (value.startsWith("/")) value = Server.get().getAddress() + "/file" + value.replace(Path.rootPath(), "");
        if (value.startsWith("file")) value = Server.get().getAddress() + "/" + value.replace(Path.rootPath(), "").replace("://", "");
        return value.replace("127.0.0.1", Util.getIp());
    }

    @Override
    public void onDeviceAdded(Device device) {
        if (binding == null) return;
        binding.recycler.setVisibility(View.VISIBLE);
        adapter.sort(device);
    }

    @Override
    public void onDeviceRemoved(Device device) {
        if (binding == null) return;
        adapter.remove(device);
    }

    @Override
    public void onFind(Device device) {
        if (binding == null) return;
        binding.recycler.setVisibility(View.VISIBLE);
        adapter.sort(device);
    }

    @Override
    public void onItemClick(Device item) {
        if (binding == null || !isAdded()) return;
        Object identity = listener().getCastPlaybackIdentity();
        History currentHistory = listener().getCastHistory();
        CastVideo currentVideo = listener().getCastVideo();
        if (currentVideo == null) currentVideo = video;
        if (identity != openedMedia || currentVideo == null
                || !Objects.equals(video.url(), currentVideo.url())
                || (history != null && currentHistory != null && !sameHistory(history, currentHistory))) {
            dismiss();
            return;
        }
        History snapshot = CastHistorySnapshot.capture(currentHistory == null ? history : currentHistory, currentVideo.position());
        if (activeCall != null) activeCall.cancel();
        CastRequestState state = requests;
        CastRequestState.Request request = state.begin(identity);
        if (item.isDLNA()) new DLNACast(currentVideo, () -> onCasted(state, request, snapshot)).cast(item);
        else {
            activeCall = OkHttp.newCall(client, item.getIp().concat("/action?do=cast"), body(snapshot));
            activeCall.enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException error) {
                    App.post(() -> { if (isCurrent(state, request, snapshot)) Notify.show(R.string.device_offline); });
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    boolean success;
                    try (Response res = response) {
                        success = res.isSuccessful() && res.body() != null && res.body().string().equals("OK");
                    }
                    App.post(() -> {
                        if (success) onCasted(state, request, snapshot);
                        else if (isCurrent(state, request, snapshot)) Notify.show(R.string.device_offline);
                    });
                }
            });
        }
    }

    @Override
    public boolean onLongClick(Device item) {
        return false;
    }

    @Override
    public void onDestroyView() {
        if (requests != null) requests.close();
        if (activeCall != null) activeCall.cancel();
        binding = null;
        super.onDestroyView();
        DLNACastManager.get().setDeviceListener(null);
        DLNACastManager.get().release(requireActivity());
        scanTask.stop();
    }

    private final ActivityResultLauncher<Intent> launcher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) scanTask.start(result.getData().getStringExtra("address"));
    });

    public interface Listener {

        void onCasted();
        default History getCastHistory() { return null; }
        default CastVideo getCastVideo() { return null; }
        default Object getCastPlaybackIdentity() { return null; }
    }
}
