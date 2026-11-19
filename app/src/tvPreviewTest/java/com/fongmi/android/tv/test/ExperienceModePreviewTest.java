package com.fongmi.android.tv.test;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.ExperienceMode;
import com.fongmi.android.tv.setting.ExperienceSettings;
import com.fongmi.android.tv.setting.ThemeMode;
import com.fongmi.android.tv.ui.activity.ExperienceModeActivity;
import com.fongmi.android.tv.ui.theme.JetStreamThemeController;
import com.fongmi.android.tv.ui.theme.RexTheme;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Shipping mode picker, with actual remote input and no catalog/network fixtures. */
@RunWith(AndroidJUnit4.class)
public final class ExperienceModePreviewTest {
    private static final List<String> RESTORED_KEYS = List.of("tv_experience_mode_v1", "tv_theme_mode", "tv_light_theme");
    private static final List<String> PROTECTED_KEYS = List.of("browse_home_layout_v1", "browse_poster_home",
            "player_scale", "player_engine", "source_selection_mode", "community_skip_enabled", "preload_next",
            "tmdb_connection_mode", "tmdb_proxy_url");
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiDevice device = UiDevice.getInstance(instrumentation);
    private Map<String, ?> savedPreferences;
    private ExperienceModeActivity activity;

    @Before public void save() {
        assertEquals("Isolated preview only", "com.fongmi.android.tv.preview",
                instrumentation.getTargetContext().getPackageName());
        main(() -> savedPreferences = Prefers.getPrefers().getAll());
    }

    @After public void restore() {
        closePicker();
        main(() -> {
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            for (String key : RESTORED_KEYS) {
                Object value = savedPreferences.get(key);
                if (value instanceof String text) editor.putString(key, text);
                else if (value instanceof Integer number) editor.putInt(key, number);
                else if (value instanceof Boolean flag) editor.putBoolean(key, flag);
                else editor.remove(key);
            }
            assertTrue("Restore mode and theme preference presence exactly", editor.commit());
            JetStreamThemeController.refresh();
        });
    }

    @Test public void shouldShowAllThreeChoicesAndFocusTheCurrentModeInBothThemes() {
        for (ThemeMode theme : List.of(ThemeMode.DARK, ThemeMode.LIGHT)) {
            main(() -> RexTheme.setMode(theme));
            for (ExperienceMode current : ExperienceMode.values()) {
                openPicker(current);
                main(() -> {
                    for (ExperienceMode mode : ExperienceMode.values()) {
                        View card = card(mode);
                        assertTrue("The full choice is visible in " + theme, completelyVisible(card));
                        assertTrue("Each choice is one remote focus target", card.isFocusable());
                        assertEquals("Only the current mode is selected", current == mode, card.isSelected());
                    }
                    assertSame("The current selection receives initial remote focus", card(current), activity.getCurrentFocus());
                    assertTrue("Back remains reachable inside the safe area", completelyVisible(back()));
                });
                NativeUiEvidence.capture("mode-" + theme.name().toLowerCase(java.util.Locale.ROOT)
                        + "-" + current.name().toLowerCase(java.util.Locale.ROOT));
                closePicker();
            }
        }
    }

    @Test public void shouldSwitchEachModeWithTheRemoteAndPreserveOtherPreferences() {
        main(() -> RexTheme.setMode(ThemeMode.DARK));
        for (ExperienceMode chosen : ExperienceMode.values()) {
            openPicker(ExperienceMode.LIBRARY);
            for (int i = 0; i < chosen.ordinal(); i++) device.pressDPadRight();
            await("Remote reaches " + chosen, () -> activity.getCurrentFocus() == card(chosen));
            device.pressDPadCenter();
            await("Confirm closes the picker", activity::isDestroyed);
            main(() -> {
                assertEquals("Remote confirmation applies the focused choice", chosen, ExperienceSettings.getMode());
                Map<String, ?> preferences = Prefers.getPrefers().getAll();
                for (String key : PROTECTED_KEYS) {
                    assertEquals("Mode switching preserves preference presence: " + key,
                            savedPreferences.containsKey(key), preferences.containsKey(key));
                    assertEquals("Mode switching preserves preference value: " + key,
                            savedPreferences.get(key), preferences.get(key));
                }
            });
        }
    }

    @Test public void shouldRestoreTheLastCardFromBackAndExitWithoutChangingTheMode() {
        main(() -> RexTheme.setMode(ThemeMode.DARK));
        openPicker(ExperienceMode.LIBRARY);
        device.pressDPadRight();
        await("Right moves to the simple choice", () -> activity.getCurrentFocus() == card(ExperienceMode.SIMPLE));
        device.pressDPadDown();
        await("Down reaches the back control", () -> activity.getCurrentFocus() == back());
        device.pressDPadUp();
        await("Up restores the last focused mode", () -> activity.getCurrentFocus() == card(ExperienceMode.SIMPLE));
        device.pressDPadRight();
        await("The classic choice remains reachable", () -> activity.getCurrentFocus() == card(ExperienceMode.CLASSIC));
        device.pressDPadLeft();
        await("Left returns to simple", () -> activity.getCurrentFocus() == card(ExperienceMode.SIMPLE));
        device.pressBack();
        await("Remote Back closes the mode picker", activity::isDestroyed);
        assertEquals("Browsing choices does not apply them", ExperienceMode.LIBRARY, ExperienceSettings.getMode());
    }

    private void openPicker(ExperienceMode current) {
        closePicker();
        main(() -> ExperienceSettings.putMode(current));
        activity = (ExperienceModeActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), ExperienceModeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("Mode picker has its current focus", () -> activity.hasWindowFocus()
                && activity.getCurrentFocus() == card(current));
    }

    private void closePicker() {
        if (activity == null) return;
        if (!activity.isDestroyed()) {
            main(activity::finish);
            await("Picker closes before the next theme or choice", activity::isDestroyed);
        }
        activity = null;
    }

    private View card(ExperienceMode mode) {
        View found = activity.findViewById(android.R.id.content).findViewWithTag(mode.name());
        assertNotNull("Mode card " + mode, found);
        return found;
    }

    private View back() {
        View found = findText(activity.findViewById(android.R.id.content), activity.getString(R.string.library_back));
        assertNotNull("Back control", found);
        return found;
    }

    private View findText(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText()) && view.isFocusable()) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findText(group.getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private boolean completelyVisible(View view) {
        Rect bounds = new Rect();
        return view.getGlobalVisibleRect(bounds) && bounds.width() >= view.getWidth() && bounds.height() >= view.getHeight();
    }

    private void main(Runnable action) {
        instrumentation.runOnMainSync(action);
        instrumentation.waitForIdleSync();
    }

    private void await(String description, BooleanSupplier ready) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        AtomicBoolean complete = new AtomicBoolean();
        do {
            main(() -> complete.set(ready.getAsBoolean()));
            if (complete.get()) return;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(description);
    }
}
