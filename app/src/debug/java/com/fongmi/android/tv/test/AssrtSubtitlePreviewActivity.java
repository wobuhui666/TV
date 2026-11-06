package com.fongmi.android.tv.test;

import android.net.Uri;
import android.os.Bundle;
import android.content.Context;
import android.content.res.Configuration;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleSearchDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Disposable subtitle-search surface; this activity is absent from release APKs. */
public final class AssrtSubtitlePreviewActivity extends FragmentActivity implements TrackDialog.Listener {

    public static final String KEY = "assrt-subtitle-ui-fixture";
    public static final String TITLE = "字幕搜索演示";
    public static final String EXISTING_NAME = "Existing English.srt";
    public PlayerView view;
    public PlayerManager manager;
    public String error;
    private File media;
    private File existing;

    @Override
    protected void attachBaseContext(Context base) {
        Configuration configuration = new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(Locale.SIMPLIFIED_CHINESE);
        super.attachBaseContext(base.createConfigurationContext(configuration));
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout surface = new FrameLayout(this);
        surface.setBackgroundColor(0xff151a25);
        view = new PlayerView(this);
        view.setUseController(false);
        surface.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView title = new TextView(this);
        title.setText(TITLE + " · S02E03");
        title.setTextSize(24);
        title.setTextColor(0xffeeeeee);
        title.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams heading = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP);
        surface.addView(title, heading);
        setContentView(surface);
        try {
            String fixture = UUID.randomUUID().toString();
            media = new File(getCacheDir(), "assrt-ui-pcm-" + fixture + ".wav");
            existing = new File(getCacheDir(), "assrt-existing-en-" + fixture + ".srt");
            write(media, wave());
            write(existing, "1\n00:00:00,000 --> 00:01:30,000\nExisting English subtitle\n".getBytes(StandardCharsets.UTF_8));
            manager = new PlayerManager(new Callback());
            view.setPlayer(manager.getPlayer());
            manager.bindPlayerView(view);
            Result result = new Result();
            result.setUrl(Uri.fromFile(media).toString());
            result.setFormat("audio/wav");
            if (getIntent().getBooleanExtra("existing_subtitle", false)) {
                result.setSubs(List.of(Sub.from(EXISTING_NAME, Uri.fromFile(existing).toString(), "en", "application/x-subrip")));
            }
            MediaMetadata metadata = PlayerManager.buildMetadata(TITLE, "S02E03", "");
            manager.start(PlaySpec.from(result, KEY, metadata), 20_000, 20_000);
            manager.getPlayer().setVolume(0);
            manager.pause();
            manager.setSpeed(1.25f);
        } catch (Exception failure) {
            error = failure.getClass().getSimpleName();
        }
    }

    public void openTracks(SubtitleSearchDialog.Services services) {
        TrackDialog.create().player(manager).type(androidx.media3.common.C.TRACK_TYPE_TEXT)
                .subtitleSearchServices(services).show(this);
    }

    public void openSearch(SubtitleSearchDialog.Services services) {
        SubtitleSearchDialog.show(this, manager, services);
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().player(manager).view(view.getSubtitleView()).show(this);
    }

    @Override
    public void onDestroy() {
        if (manager != null) {
            manager.bindPlayerView(null);
            view.setPlayer(null);
            manager.release();
            manager = null;
        }
        if (media != null) media.delete();
        if (existing != null) existing.delete();
        super.onDestroy();
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static byte[] wave() {
        int rate = 16_000;
        int samples = rate * 90;
        int bytes = samples * 2;
        ByteBuffer output = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN);
        output.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
        output.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        output.putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        output.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        return output.array();
    }

    private final class Callback implements PlayerManager.Callback {
        @Override public void onPrepare() {}
        @Override public void onTracksChanged() {}
        @Override public void onDecodeChanged() {}
        @Override public void onMediaOptionsChanged() {}
        @Override public void onError(String message) { error = message; }
        @Override public void onPlayerRebuild(Player player) {
            view.setPlayer(player);
            manager.bindPlayerView(view);
        }
        @Override public void onDanmakuSourceChanged(Uri uri) {}
        @Override public void onDanmakuConfigChanged(DanmakuConfig config) {}
        @Override public void onDanmakuEnabledChanged(boolean enabled) {}
        @Override public void onDanmakuSent(String text) {}
    }
}
