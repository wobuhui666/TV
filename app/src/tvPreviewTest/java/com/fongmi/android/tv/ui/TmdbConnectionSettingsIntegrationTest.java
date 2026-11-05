package com.fongmi.android.tv.ui;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.activity.SettingActivity;
import com.fongmi.android.tv.ui.custom.JetStreamSettingView;
import com.fongmi.android.tv.utils.TmdbEndpoint;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Exercises the production settings chooser without using service credentials or network calls. */
@RunWith(AndroidJUnit4.class)
public final class TmdbConnectionSettingsIntegrationTest {
    private static final String[] KEYS = {"tmdb_connection_mode", "tmdb_proxy_url"};
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private final Map<String, Object> saved = new HashMap<>();
    private SettingActivity activity;

    @Before public void open() {
        assertEquals("Disposable preview only", "com.fongmi.android.tv.preview", instrumentation.getTargetContext().getPackageName());
        main(() -> {
            for (String key : KEYS) if (Prefers.getPrefers().contains(key)) saved.put(key, Prefers.getPrefers().getAll().get(key));
            Prefers.remove("tmdb_connection_mode");
            Setting.putTmdbProxyUrl("https://tmdb-settings-fixture.invalid/");
        });
        activity = (SettingActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), SettingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("Settings window", activity::hasWindowFocus);
        main(() -> settings().showSection(JetStreamSettingView.SECTION_SOURCE));
    }

    @After public void close() {
        if (activity != null) main(activity::finish);
        if (activity != null) await("Settings closed", activity::isDestroyed);
        main(() -> {
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : KEYS) {
                Object original = saved.get(key);
                if (original instanceof Integer mode) editor.putInt(key, mode);
                else if (original instanceof String text) editor.putString(key, text);
                else editor.remove(key);
            }
            assertTrue(editor.commit());
        });
    }

    @Test public void shouldSwitchOfficialDirectAndRetainCustomProxyForLater() {
        assertEquals("Unset mode migrates an existing custom URL", Setting.TMDB_CUSTOM_PROXY, value(Setting::getTmdbConnectionMode).intValue());
        assertTrue(value(() -> rowVisible(JetStreamSettingView.KEY_TMDB_PROXY)));

        choose(Setting.TMDB_DIRECT, R.string.setting_tmdb_direct);

        assertEquals("https://api.themoviedb.org/3/", value(TmdbEndpoint::getApiBase));
        assertEquals("https://image.tmdb.org/t/p/", value(TmdbEndpoint::getImageBase));
        assertFalse(value(() -> rowVisible(JetStreamSettingView.KEY_TMDB_PROXY)));
        assertEquals("https://tmdb-settings-fixture.invalid/", value(TmdbEndpoint::getCustomRoot));
        NativeUiEvidence.capture("tmdb-direct-settings");

        choose(Setting.TMDB_DEFAULT_PROXY, R.string.setting_tmdb_default_proxy);
        assertEquals(TmdbEndpoint.DEFAULT_API_BASE, value(TmdbEndpoint::getApiBase));
        assertFalse(value(() -> rowVisible(JetStreamSettingView.KEY_TMDB_PROXY)));

        choose(Setting.TMDB_CUSTOM_PROXY, R.string.setting_tmdb_custom_proxy);
        assertTrue(value(() -> rowVisible(JetStreamSettingView.KEY_TMDB_PROXY)));
        assertEquals("https://tmdb-settings-fixture.invalid/3/", value(TmdbEndpoint::getApiBase));
        assertEquals("https://tmdb-settings-fixture.invalid/t/p/", value(TmdbEndpoint::getImageBase));
    }

    private void choose(int mode, int label) {
        main(() -> activity.onSettingAction(JetStreamSettingView.KEY_TMDB_CONNECTION));
        UiObject2 item = device.wait(Until.findObject(By.text(activity.getString(label))), 5000);
        assertNotNull("Connection mode choice", item);
        item.click();
        await("Connection mode selected", () -> Setting.getTmdbConnectionMode() == mode && activity.hasWindowFocus());
    }

    private JetStreamSettingView settings() { return activity.findViewById(R.id.settingView); }

    @SuppressWarnings("unchecked") private boolean rowVisible(String key) {
        try {
            Field field = JetStreamSettingView.class.getDeclaredField("rowVisible");
            field.setAccessible(true);
            Map<String, Boolean> visibility = (Map<String, Boolean>) field.get(settings());
            return visibility.getOrDefault(key, true);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        main(() -> result.set(action.get()));
        return result.get();
    }
    private void await(String message, BooleanSupplier ready) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            if (value(ready::getAsBoolean)) return;
            SystemClock.sleep(40);
        }
        fail(message);
    }
}
