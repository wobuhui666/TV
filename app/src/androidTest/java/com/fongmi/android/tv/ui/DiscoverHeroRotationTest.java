package com.fongmi.android.tv.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.DiscoverHero;
import com.fongmi.android.tv.bean.DiscoverMediaKey;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.setting.BrowseExperienceSettings;
import com.fongmi.android.tv.test.CorePlaybackActivity;
import com.fongmi.android.tv.test.NativeUiEvidence;
import com.fongmi.android.tv.ui.presenter.DiscoverHeroPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.github.catvod.utils.Prefers;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import fi.iki.elonen.NanoHTTPD;

/** Attached native heroes, real seven-second timers, and controlled loopback image responses. */
@RunWith(AndroidJUnit4.class)
public final class DiscoverHeroRotationTest {
    private static final String ROTATION_KEY = "browse_hero_rotation";
    private static final long FULL_ROTATION_WAIT = 7_450;
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private CorePlaybackActivity activity;
    private LinearLayout host;
    private Button other;
    private DiscoverHeroPresenter presenter;
    private Presenter.ViewHolder holder;
    private TextView title;
    private Fixture fixture;
    private Dialog coveringDialog;
    private Vod clicked;
    private String titleAtClick;
    private View sharedArtwork;
    private Object savedRotation;
    private boolean preferenceSaved;

    @Before public void setup() throws Exception {
        String packageName = instrumentation.getTargetContext().getPackageName();
        assertTrue("Use an isolated validation app", packageName.equals("com.fongmi.android.tv.preview")
                || packageName.equals("com.fongmi.android.tv.sourceprobe"));
        assertEquals("Native TV hero", "leanback", BuildConfig.FLAVOR_mode);
        savedRotation = Prefers.getPrefers().getAll().get(ROTATION_KEY);
        preferenceSaved = true;
        fixture = new Fixture();
        fixture.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
        activity = (CorePlaybackActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), CorePlaybackActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        main(() -> {
            host = new LinearLayout(activity);
            host.setOrientation(LinearLayout.VERTICAL);
            other = new Button(activity);
            other.setText("Other focus target");
            other.setFocusableInTouchMode(true);
            host.addView(other, new LinearLayout.LayoutParams(dp(280), dp(48)));
            activity.setContentView(host);
            assertTrue(other.requestFocus());
        });
        await(other::hasWindowFocus, "activity window");
    }

    @After public void cleanup() {
        try {
            main(() -> {
                if (coveringDialog != null) coveringDialog.dismiss();
                if (presenter != null && holder != null) presenter.onUnbindViewHolder(holder);
                if (activity != null) activity.finish();
                if (preferenceSaved) {
                    SharedPreferences.Editor editor = Prefers.getPrefers().edit();
                    if (savedRotation instanceof Integer mode) editor.putInt(ROTATION_KEY, mode);
                    else editor.remove(ROTATION_KEY);
                    assertTrue("Restore the exact pre-test preference", editor.commit());
                }
            });
        } finally {
            if (fixture != null) fixture.shutdown();
        }
        instrumentation.waitForIdleSync();
    }

    @Test(timeout = 25_000)
    public void originalModeStillRotatesWhileTheHeroHasFocus() {
        mount(BrowseExperienceSettings.HERO_ROTATION_ORIGINAL, normalItems());
        main(() -> assertTrue(holder.view.requestFocus()));
        await(() -> "Second film".contentEquals(title.getText()), "original automatic rotation");
        assertTrue(value(holder.view::hasFocus));
        clickAndExpect("Second film");
    }

    @Test(timeout = 35_000)
    public void focusPausedModeStaysStillUntilBlurAndKeepsManualNavigation() {
        mount(BrowseExperienceSettings.HERO_ROTATION_FOCUS_PAUSED, normalItems());
        main(() -> assertTrue(holder.view.requestFocus()));
        NativeUiEvidence.capture("hero-focus-paused");
        assertTitleStays("First film", FULL_ROTATION_WAIT);
        keyAndClick(KeyEvent.KEYCODE_DPAD_LEFT, "Third film");
        main(() -> assertTrue(other.requestFocus()));
        await(() -> "First film".contentEquals(title.getText()), "rotation resumes after blur");
        clickAndExpect("First film");
    }

    @Test(timeout = 35_000)
    public void manualModeNeverRotatesWithOrWithoutCardFocus() {
        mount(BrowseExperienceSettings.HERO_ROTATION_MANUAL, normalItems());
        main(() -> assertTrue(holder.view.requestFocus()));
        assertTitleStays("First film", FULL_ROTATION_WAIT);
        main(() -> assertTrue(other.requestFocus()));
        assertTitleStays("First film", FULL_ROTATION_WAIT);
        main(() -> assertTrue(holder.view.requestFocus()));
        keyAndClick(KeyEvent.KEYCODE_DPAD_RIGHT, "Second film");
        keyAndClick(KeyEvent.KEYCODE_DPAD_RIGHT, "Third film");
    }

    @Test(timeout = 45_000)
    public void windowFocusLossStopsTheTimerAndReturnReadsTheNewMode() {
        mount(BrowseExperienceSettings.HERO_ROTATION_ORIGINAL, normalItems());
        main(() -> {
            coveringDialog = new Dialog(activity);
            Button button = new Button(activity);
            button.setText("Covered window");
            coveringDialog.setContentView(button);
            coveringDialog.show();
            button.requestFocus();
        });
        await(() -> !holder.view.hasWindowFocus(), "hero window is covered");
        assertTitleStays("First film", FULL_ROTATION_WAIT);
        main(() -> {
            BrowseExperienceSettings.putHeroRotationMode(BrowseExperienceSettings.HERO_ROTATION_MANUAL);
            coveringDialog.dismiss();
        });
        await(holder.view::hasWindowFocus, "hero window returns");
        assertTitleStays("First film", FULL_ROTATION_WAIT);

        main(coveringDialog::show);
        await(() -> !holder.view.hasWindowFocus(), "hero window is covered again");
        main(() -> {
            BrowseExperienceSettings.putHeroRotationMode(BrowseExperienceSettings.HERO_ROTATION_ORIGINAL);
            coveringDialog.dismiss();
        });
        await(holder.view::hasWindowFocus, "new automatic mode on return");
        assertTitleStays("First film", 600);
        await(() -> "Second film".contentEquals(title.getText()), "rotation after window return");
    }

    @Test(timeout = 30_000)
    public void rapidClicksAndDelayedOrFailedImagesKeepTheVisibleTitleAsTheDestination() throws Exception {
        mount(BrowseExperienceSettings.HERO_ROTATION_MANUAL, List.of(
                item("first", "First film"), item("slow", "Slow film"),
                item("third", "Third film"), item("failure", "Unavailable artwork")));
        await(() -> hasColor(Color.RED), "first artwork");
        main(() -> assertTrue(holder.view.requestFocus()));

        keyAndClick(KeyEvent.KEYCODE_DPAD_RIGHT, "Slow film");
        assertNull("Do not share the preceding film's artwork", value(() -> sharedArtwork));
        assertTrue("The delayed image was actually requested", fixture.slowArrived.await(5, TimeUnit.SECONDS));
        keyAndClick(KeyEvent.KEYCODE_DPAD_RIGHT, "Third film");
        await(() -> hasColor(Color.GREEN), "newer artwork");
        fixture.releaseSlow.countDown();
        assertTrue("The old response completed", fixture.slowClosed.await(5, TimeUnit.SECONDS));
        assertTitleStays("Third film", 900);
        assertTrue("A late image cannot replace the selected artwork", value(() -> hasColor(Color.GREEN)));
        clickAndExpect("Third film");
        assertNotNull(value(() -> sharedArtwork));

        keyAndClick(KeyEvent.KEYCODE_DPAD_RIGHT, "Unavailable artwork");
        assertTrue("The failure was actually requested", fixture.failureArrived.await(5, TimeUnit.SECONDS));
        await(() -> !hasColor(Color.GREEN), "failed artwork replaces the old film image");
        clickAndExpect("Unavailable artwork");
        assertNull(value(() -> sharedArtwork));

        // All events are dispatched before a frame or animation callback can run. This caught the
        // former 110 ms interval in which the old title pointed at the newly selected film.
        main(() -> {
            dispatch(KeyEvent.KEYCODE_DPAD_RIGHT);
            assertClickMatches("First film");
            dispatch(KeyEvent.KEYCODE_DPAD_LEFT);
            assertClickMatches("Unavailable artwork");
            dispatch(KeyEvent.KEYCODE_DPAD_RIGHT);
            assertClickMatches("First film");
            for (int mode : new int[]{BrowseExperienceSettings.HERO_ROTATION_ORIGINAL,
                    BrowseExperienceSettings.HERO_ROTATION_FOCUS_PAUSED, BrowseExperienceSettings.HERO_ROTATION_MANUAL}) {
                BrowseExperienceSettings.putHeroRotationMode(mode);
                dispatch(KeyEvent.KEYCODE_DPAD_RIGHT);
                assertClickMatches("Slow film");
                dispatch(KeyEvent.KEYCODE_DPAD_LEFT);
                assertClickMatches("First film");
            }
        });
    }

    @Test(timeout = 30_000)
    public void detachCancelsOldImagesAndRepeatedBindOrAttachKeepsOnlyOneRotation() throws Exception {
        mount(BrowseExperienceSettings.HERO_ROTATION_MANUAL,
                List.of(item("first", "First film"), item("slow", "Slow film")));
        await(() -> hasColor(Color.RED), "first artwork");
        main(() -> assertTrue(holder.view.requestFocus()));
        keyAndClick(KeyEvent.KEYCODE_DPAD_RIGHT, "Slow film");
        assertTrue(fixture.slowArrived.await(5, TimeUnit.SECONDS));
        main(() -> {
            presenter.onViewDetachedFromWindow(holder);
            host.removeView(holder.view);
            assertImagesCleared();
        });
        fixture.releaseSlow.countDown();
        assertTrue(fixture.slowClosed.await(5, TimeUnit.SECONDS));
        assertTitleStays("Slow film", 600);
        main(this::assertImagesCleared);

        List<String> titleChanges = new ArrayList<>();
        main(() -> {
            BrowseExperienceSettings.putHeroRotationMode(BrowseExperienceSettings.HERO_ROTATION_ORIGINAL);
            DiscoverHero replacement = hero(List.of(item("fourth", "Fourth film"), item("fifth", "Fifth film")));
            presenter.onBindViewHolder(holder, replacement);
            host.addView(holder.view);
            presenter.onViewAttachedToWindow(holder);
            presenter.onBindViewHolder(holder, replacement);
            presenter.onViewAttachedToWindow(holder);
            presenter.onViewAttachedToWindow(holder);
            assertTrue(other.requestFocus());
            title.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) { titleChanges.add(s.toString()); }
                @Override public void afterTextChanged(Editable s) { }
            });
        });
        await(() -> hasColor(Color.MAGENTA), "replacement artwork");
        await(() -> "Fifth film".contentEquals(title.getText()), "one rotation after reattachment");
        assertTitleStays("Fifth film", 800);
        main(() -> {
            assertEquals(List.of("Fifth film"), titleChanges);
            presenter.onUnbindViewHolder(holder);
            assertImagesCleared();
            assertEquals(View.INVISIBLE, holder.view.findViewById(R.id.details).getVisibility());
            clicked = null;
            holder.view.performClick();
            assertNull("An unbound holder cannot launch its former film", clicked);
        });
    }

    private void mount(int mode, List<Vod> values) {
        main(() -> {
            BrowseExperienceSettings.putHeroRotationMode(mode);
            presenter = new DiscoverHeroPresenter(new VodPresenter.OnClickListener() {
                @Override public void onItemClick(Vod item) { onItemClick(item, null); }
                @Override public void onItemClick(Vod item, View poster) {
                    clicked = item;
                    titleAtClick = title.getText().toString();
                    sharedArtwork = poster;
                }
                @Override public boolean onLongClick(Vod item) { return false; }
            });
            holder = presenter.onCreateViewHolder(host);
            title = holder.view.findViewById(R.id.name);
            presenter.onBindViewHolder(holder, hero(values));
            host.addView(holder.view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(392)));
            presenter.onViewAttachedToWindow(holder);
            assertTrue(other.requestFocus());
        });
        await(() -> holder.view.isAttachedToWindow() && holder.view.hasWindowFocus(), "attached hero");
        assertEquals(values.get(0).getName(), value(() -> title.getText().toString()));
    }

    private List<Vod> normalItems() {
        return List.of(item("first", "First film"), item("second", "Second film"), item("third", "Third film"));
    }

    private Vod item(String id, String name) {
        Vod item = new Vod();
        item.setId("tmdb:movie:" + id);
        item.setName(name);
        item.setTypeName(DiscoverMediaKey.MOVIE);
        item.setPic(fixture.url(id));
        item.setBackdrop(fixture.url(id));
        return item;
    }

    private static DiscoverHero hero(List<Vod> items) {
        DiscoverHero hero = new DiscoverHero();
        hero.replace(items);
        return hero;
    }

    private void keyAndClick(int key, String expected) {
        main(() -> {
            dispatch(key);
            assertClickMatches(expected);
        });
    }

    private void dispatch(int key) {
        assertTrue(holder.view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)));
        holder.view.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private void clickAndExpect(String expected) {
        main(() -> assertClickMatches(expected));
    }

    private void assertClickMatches(String expected) {
        clicked = null;
        holder.view.performClick();
        assertNotNull(clicked);
        assertEquals("The visible name and destination must agree in this same frame", titleAtClick, clicked.getName());
        assertEquals(expected, titleAtClick);
    }

    private boolean hasColor(int color) {
        for (int id : new int[]{R.id.imageA, R.id.imageB}) {
            ImageView image = holder.view.findViewById(id);
            if (image.getVisibility() != View.VISIBLE || image.getAlpha() < 0.99f
                    || !(image.getDrawable() instanceof BitmapDrawable drawable)) continue;
            Bitmap bitmap = drawable.getBitmap();
            if (bitmap.isRecycled()) continue;
            boolean hardware = Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE;
            Bitmap readable = hardware ? bitmap.copy(Bitmap.Config.ARGB_8888, false) : bitmap;
            try {
                if (readable != null && readable.getPixel(readable.getWidth() / 2, readable.getHeight() / 2) == color) return true;
            } finally { if (hardware && readable != null) readable.recycle(); }
        }
        return false;
    }

    private void assertImagesCleared() {
        for (int id : new int[]{R.id.imageA, R.id.imageB}) {
            ImageView image = holder.view.findViewById(id);
            assertNull(image.getDrawable());
            assertEquals(View.GONE, image.getVisibility());
        }
    }

    private void assertTitleStays(String expected, long duration) {
        long deadline = SystemClock.elapsedRealtime() + duration;
        do {
            assertEquals(expected, value(() -> title.getText().toString()));
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
    }

    private void await(BooleanSupplier condition, String message) {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (value(condition::getAsBoolean)) return;
            SystemClock.sleep(50);
        }
        fail("Timed out waiting for " + message);
    }

    private int dp(int size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }
    private void main(Runnable action) { value(() -> { action.run(); return null; }); }

    private <T> T value(Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { result.set(action.get()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError("Native UI operation failed", failure.get());
        return result.get();
    }

    private static final class Fixture extends NanoHTTPD {
        final CountDownLatch slowArrived = new CountDownLatch(1);
        final CountDownLatch releaseSlow = new CountDownLatch(1);
        final CountDownLatch slowClosed = new CountDownLatch(1);
        final CountDownLatch failureArrived = new CountDownLatch(1);
        private final String prefix = "/" + UUID.randomUUID() + "/";
        private final Map<String, byte[]> images = Map.of("first", png(Color.RED), "second", png(Color.BLUE),
                "slow", png(Color.BLUE), "third", png(Color.GREEN), "fourth", png(Color.MAGENTA), "fifth", png(Color.YELLOW));

        Fixture() { super("127.0.0.1", 0); }

        String url(String path) { return "http://127.0.0.1:" + getListeningPort() + prefix + path; }

        @Override public Response serve(IHTTPSession session) {
            String path = session.getUri();
            if (session.getMethod() != Method.GET || !path.startsWith(prefix)) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown image fixture");
            }
            String name = path.substring(prefix.length());
            if (name.equals("failure")) {
                failureArrived.countDown();
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Expected image failure");
            }
            byte[] bytes = images.get(name);
            if (bytes == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Unknown image fixture");
            boolean slow = name.equals("slow");
            if (slow) {
                slowArrived.countDown();
                try {
                    if (!releaseSlow.await(20, TimeUnit.SECONDS)) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Unreleased fixture");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Stopped fixture");
                }
            }
            ByteArrayInputStream body = new ByteArrayInputStream(bytes) {
                @Override public void close() throws IOException {
                    try { super.close(); } finally { if (slow) slowClosed.countDown(); }
                }
            };
            Response response = newFixedLengthResponse(Response.Status.OK, "image/png", body, bytes.length);
            response.addHeader("Cache-Control", "no-store");
            return response;
        }

        void shutdown() {
            releaseSlow.countDown();
            stop();
        }

        private static byte[] png(int color) {
            Bitmap bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888);
            try {
                bitmap.eraseColor(color);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
                return output.toByteArray();
            } finally { bitmap.recycle(); }
        }
    }
}
