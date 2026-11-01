package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.databinding.DialogTrackBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.ThemeMode;
import com.fongmi.android.tv.ui.adapter.TrackAdapter;
import com.fongmi.android.tv.ui.custom.JetStreamTrackChoiceButton;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/** Uses the shipping row adapter and sheet layout; files and tracks are synthetic. */
@RunWith(AndroidJUnit4.class)
public class TrackPresentationTest {
    private static final String FILE_NAME = "绿里奇迹.1999.2160p.REMUX.HDR10.完整对白及导演评论.zh-Hans.vtt";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final AtomicReference<Track> chosen = new AtomicReference<>();
    private CorePlaybackActivity activity;
    private DialogTrackBinding binding;
    private List<Track> tracks;

    @Test public void shouldShowLocalBadgesAndEllipsisWithoutChangingTouchOrRemoteSelectionInBothThemes() throws Exception {
        assertEquals("Disposable preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        ThemeMode original = Setting.getThemeMode();
        try {
            for (boolean light : new boolean[]{false, true}) {
                main(() -> Setting.putLightTheme(light));
                activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                        instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                showRows();
                await(() -> row(0).getLayout() != null && row(0).getLayout().getEllipsisCount(0) > 0,
                        "long local subtitle ellipsis");
                main(() -> {
                    assertTrue(row(0).getLocalSubtitle());
                    assertFalse("Same-named remote subtitle must not inherit the badge", row(1).getLocalSubtitle());
                    assertFalse("Embedded subtitle must not inherit the badge", row(2).getLocalSubtitle());
                    assertEquals(TextUtils.TruncateAt.END, row(0).getEllipsize());
                    assertEquals(TextUtils.TruncateAt.END, row(1).getEllipsize());
                    assertEquals(FILE_NAME, row(0).getText().toString());
                    assertEquals(FILE_NAME + ", 本地字幕", row(0).getContentDescription().toString());
                    assertEquals(FILE_NAME, row(1).getContentDescription().toString());
                    assertTrue("The label reserves its own space", row(0).getPaddingEnd() > row(1).getPaddingEnd());
                    assertTrue(row(0).requestFocus());
                });
                capture(light ? "subtitle-light-local" : "subtitle-dark-local");

                int[] touch = read(() -> {
                    int[] location = new int[2];
                    row(0).getLocationOnScreen(location);
                    return new int[]{location[0] + row(0).getWidth() - dp(42), location[1] + row(0).getHeight() / 2};
                });
                device.click(touch[0], touch[1]);
                await(() -> chosen.get() == tracks.get(0), "touching the badge selects its original row");
                assertEquals("local-format-original", chosen.get().getFormat());
                assertEquals(FILE_NAME, chosen.get().getName());

                // Reopen a fresh list, as the real selection dialog does after a choice.
                showRows();
                main(() -> assertTrue(row(1).requestFocus()));
                device.pressDPadDown();
                await(() -> row(2).hasFocus(), "remote navigation after touching a subtitle");
                capture(light ? "subtitle-light-embedded" : "subtitle-dark-embedded");
                device.pressDPadCenter();
                await(() -> chosen.get() == tracks.get(2), "remote selects the original embedded track");
                assertEquals("embedded-format-original", chosen.get().getFormat());
                assertFalse(chosen.get().isLocalSubtitle());
                main(activity::finish);
                activity = null;
            }
        } finally {
            main(() -> {
                if (activity != null) activity.finish();
                Setting.putThemeMode(original);
            });
        }
    }

    private void showRows() {
        chosen.set(null);
        main(() -> {
            Track local = new Track(C.TRACK_TYPE_TEXT, FILE_NAME, "local-format-original");
            local.setLocalSubtitle(true);
            local.setSelected(true);
            tracks = List.of(local, new Track(C.TRACK_TYPE_TEXT, FILE_NAME, "remote-format-original"),
                    new Track(C.TRACK_TYPE_TEXT, "简体中文（内嵌字幕）", "embedded-format-original"));
            Configuration configuration = new Configuration(activity.getResources().getConfiguration());
            configuration.setLocale(Locale.SIMPLIFIED_CHINESE);
            Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.Theme_App);
            FrameLayout host = new FrameLayout(activity);
            binding = DialogTrackBinding.inflate(LayoutInflater.from(context), host, false);
            FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END);
            host.addView(binding.getRoot(), size);
            binding.title.setText("选择字幕");
            binding.recycler.setItemAnimator(null);
            binding.recycler.setHasFixedSize(true);
            binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
            binding.recycler.setAdapter(new TrackAdapter(chosen::set).addAll(tracks));
            activity.setContentView(host);
        });
        await(() -> binding.recycler.findViewHolderForAdapterPosition(2) != null && !binding.recycler.isComputingLayout(),
                "subtitle rows attached");
    }

    private JetStreamTrackChoiceButton row(int position) {
        RecyclerView.ViewHolder holder = binding.recycler.findViewHolderForAdapterPosition(position);
        assertNotNull(holder);
        return (JetStreamTrackChoiceButton) holder.itemView;
    }

    private void capture(String name) throws Exception {
        SystemClock.sleep(350);
        File directory = new File(activity.getExternalFilesDir(null), "tv-polish-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }
    }

    private int dp(int value) { return Math.round(value * instrumentation.getTargetContext().getResources().getDisplayMetrics().density); }
    private void main(Runnable task) { instrumentation.runOnMainSync(task); }
    private <T> T read(Supplier<T> task) {
        AtomicReference<T> value = new AtomicReference<>();
        main(() -> value.set(task.get()));
        return value.get();
    }
    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.uptimeMillis() + 8000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (read(condition::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail(message);
    }
}
