package com.fongmi.android.tv.player;

import static org.junit.Assert.*;

import androidx.media3.ui.danmaku.Danmaku;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class LiveDanmakuTest {

    @Test
    public void shouldUseWhiteScrollingCommentsByDefault() throws Exception {
        Danmaku item = LiveDanmaku.parseItem(comment("hello"));
        assertEquals("hello", item.text);
        assertEquals(0, item.timeMs);
        assertEquals(Danmaku.TYPE_SCROLL, item.type);
        assertEquals(0xffffffff, item.color);
        assertEquals(0, item.textSizeSp, 0);
    }

    @Test
    public void shouldPreserveTypeSizeAndGradientColors() throws Exception {
        JSONObject input = comment("fixture").put("type", 5).put("size", 32)
                .put("color", 0x112233).put("colors", new JSONArray().put(0x112233).put(0xaabbcc));
        Danmaku item = LiveDanmaku.parseItem(input);
        assertEquals(Danmaku.TYPE_TOP, item.type);
        assertEquals(32, item.textSizeSp, 0);
        assertEquals(0xff112233, item.color);
        assertArrayEquals(new int[]{0xff112233, 0xffaabbcc}, item.getSourceGradientColors());
    }

    @Test
    public void shouldRejectBlankAndOversizedText() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> LiveDanmaku.parseItem(comment(" ")));
        assertThrows(IllegalArgumentException.class, () -> LiveDanmaku.parseItem(comment("x".repeat(4097))));
    }

    @Test
    public void shouldRejectInvalidNumericTypesAndRanges() throws Exception {
        for (JSONObject input : new JSONObject[]{
                comment("x").put("type", 4.5), comment("x").put("type", "1"),
                comment("x").put("type", 7), comment("x").put("size", -1),
                comment("x").put("size", 97), comment("x").put("color", true),
                comment("x").put("color", 0x1000000), comment("x").put("color", 4.5),
                new JSONObject("{\"text\":\"x\",\"size\":1e400}")}) {
            assertThrows(IllegalArgumentException.class, () -> LiveDanmaku.parseItem(input));
        }
    }

    @Test
    public void shouldRejectInvalidGradients() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> LiveDanmaku.parseItem(
                comment("x").put("colors", new JSONArray().put(0x112233))));
        assertThrows(IllegalArgumentException.class, () -> LiveDanmaku.parseItem(
                comment("x").put("colors", new JSONArray().put(0x112233).put("white"))));
    }

    @Test
    public void shouldStopPreviousSourceAndDiscardItsLateComments() throws Exception {
        try (Fixture fixture = new Fixture(Runnable::run)) {
            fixture.start("first");
            Consumer<JSONObject> old = fixture.receivers.get("first");
            old.accept(comment("first comment"));
            assertEquals("first comment", fixture.takeComment().text);
            fixture.start("second");
            assertEquals("first", fixture.takeStopped());
            old.accept(comment("late first comment"));
            assertTrue(fixture.comments.isEmpty());
            fixture.receivers.get("second").accept(comment("second comment"));
            assertEquals("second comment", fixture.takeComment().text);
            assertEquals(1, fixture.closed.get("first").get());
        }
    }

    @Test
    public void shouldDiscardOldEventsAlreadyQueuedForTheView() throws Exception {
        Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
        try (Fixture fixture = new Fixture(pending::add)) {
            fixture.start("first");
            fixture.receivers.get("first").accept(comment("queued old"));
            fixture.start("second");
            assertEquals("first", fixture.takeStopped());
            pending.remove().run();
            assertTrue(fixture.comments.isEmpty());
            fixture.receivers.get("second").accept(comment("new"));
            pending.remove().run();
            assertEquals("new", fixture.takeComment().text);
        }
    }

    @Test
    public void shouldDropQueuedBroadcastCommentsAfterReplayAndReturningLive() throws Exception {
        Queue<Runnable> pending = new ConcurrentLinkedQueue<>();
        try (Fixture fixture = new Fixture(pending::add)) {
            fixture.start("same-room");
            fixture.receivers.get("same-room").accept(comment("before replay"));
            fixture.controller.setSource(null);
            assertEquals("same-room", fixture.takeStopped());
            fixture.start("same-room");
            pending.remove().run();
            assertTrue(fixture.comments.isEmpty());
            fixture.receivers.get("same-room").accept(comment("current broadcast"));
            pending.remove().run();
            assertEquals("current broadcast", fixture.takeComment().text);
        }
    }

    @Test
    public void shouldReleaseSubscriptionWhenDisabledAndResubscribeWhenEnabled() throws Exception {
        try (Fixture fixture = new Fixture(Runnable::run)) {
            fixture.start("first");
            Consumer<JSONObject> previous = fixture.receivers.get("first");
            fixture.controller.setEnabled(false);
            assertEquals("first", fixture.takeStopped());
            previous.accept(comment("disabled"));
            assertTrue(fixture.comments.isEmpty());
            fixture.controller.setEnabled(true);
            assertEquals("first", fixture.takeStarted());
            fixture.receivers.get("first").accept(comment("enabled"));
            assertEquals("enabled", fixture.takeComment().text);
        }
    }

    @Test
    public void shouldReleaseSubscriptionWhenTheViewDetaches() throws Exception {
        try (Fixture fixture = new Fixture(Runnable::run)) {
            fixture.start("first");
            Consumer<JSONObject> receiver = fixture.receivers.get("first");
            fixture.controller.setSource(null);
            assertEquals("first", fixture.takeStopped());
            receiver.accept(comment("detached"));
            assertTrue(fixture.comments.isEmpty());
        }
    }

    @Test
    public void shouldNotSubscribeToAChangedSourceWhileTheViewIsDetached() throws Exception {
        try (Fixture fixture = new Fixture(Runnable::run)) {
            fixture.start("first");
            fixture.controller.setAttached(false);
            assertEquals("first", fixture.takeStopped());
            fixture.controller.setSource("second", false);
            fixture.controller.setEnabled(false);
            fixture.controller.setEnabled(true);
            assertNull(fixture.started.poll(100, TimeUnit.MILLISECONDS));
            fixture.controller.setSource("second", true);
            assertEquals("second", fixture.takeStarted());
            fixture.receivers.get("second").accept(comment("attached again"));
            assertEquals("attached again", fixture.takeComment().text);
        }
    }

    @Test
    public void shouldCloseOnceAndRejectSubsequentEvents() throws Exception {
        try (Fixture fixture = new Fixture(Runnable::run)) {
            fixture.start("first");
            fixture.controller.close();
            fixture.controller.close();
            assertEquals("first", fixture.takeStopped());
            fixture.receivers.get("first").accept(comment("closed"));
            assertTrue(fixture.comments.isEmpty());
            fixture.controller.setSource("second");
            assertEquals(1, fixture.closed.get("first").get());
            assertTrue(fixture.started.isEmpty());
        }
    }

    @Test
    public void shouldReleaseTheSubscriptionAfterAProtocolError() throws Exception {
        try (Fixture fixture = new Fixture(Runnable::run)) {
            fixture.start("first");
            fixture.receivers.get("first").accept(new JSONObject().put("state", "ready"));
            assertEquals("ready", fixture.state.get());
            fixture.receivers.get("first").accept(new JSONObject().put("state", "error").put("msg", "fixture"));
            assertEquals("first", fixture.takeStopped());
            assertEquals("error", fixture.state.get());
            fixture.receivers.get("first").accept(comment("after error"));
            assertTrue(fixture.comments.isEmpty());
        }
    }

    @Test
    public void shouldStopRatherThanPropagateInvalidSourcePayloads() throws Exception {
        try (Fixture fixture = new Fixture(Runnable::run)) {
            fixture.start("first");
            fixture.receivers.get("first").accept(new JSONObject().put("state", "unknown"));
            assertEquals("first", fixture.takeStopped());
            assertEquals("error", fixture.state.get());
        }
    }

    @Test
    public void shouldInterruptASetupStillWaitingWhenClosed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        LiveDanmaku controller = new LiveDanmaku(item -> {}, (state, message) -> {}, (source, emit) -> {
            entered.countDown();
            try {
                waiting.await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                throw expected;
            }
            return () -> {};
        }, Runnable::run);
        try {
            controller.setSource("first");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            controller.close();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        } finally {
            waiting.countDown();
            controller.close();
        }
    }

    @Test
    public void shouldCloseASetupWhichReturnsAfterItsSourceWasReplaced() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch firstClosed = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        LiveDanmaku controller = new LiveDanmaku(item -> {}, (state, message) -> {}, (source, emit) -> {
            if (source.equals("first")) {
                firstStarted.countDown();
                while (true) {
                    try {
                        finishFirst.await();
                        break;
                    } catch (InterruptedException ignored) {
                        // A slow source must still have its returned subscription released.
                    }
                }
                return () -> { closes.incrementAndGet(); firstClosed.countDown(); };
            }
            secondStarted.countDown();
            return () -> {};
        }, Runnable::run);
        try {
            controller.setSource("first");
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            controller.setSource("second");
            finishFirst.countDown();
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertTrue(firstClosed.await(5, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
        } finally {
            finishFirst.countDown();
            controller.close();
        }
    }

    private static JSONObject comment(String text) throws Exception {
        return new JSONObject().put("text", text);
    }

    private static final class Fixture implements AutoCloseable {
        final BlockingQueue<Danmaku> comments = new LinkedBlockingQueue<>();
        final BlockingQueue<String> started = new LinkedBlockingQueue<>();
        final BlockingQueue<String> stopped = new LinkedBlockingQueue<>();
        final Map<String, Consumer<JSONObject>> receivers = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> closed = new ConcurrentHashMap<>();
        final AtomicReference<String> state = new AtomicReference<>("");
        final LiveDanmaku controller;

        Fixture(Executor delivery) {
            controller = new LiveDanmaku(comments::add, (value, message) -> state.set(value), (source, emit) -> {
                receivers.put(source, emit);
                closed.computeIfAbsent(source, key -> new AtomicInteger());
                started.add(source);
                return () -> { closed.get(source).incrementAndGet(); stopped.add(source); };
            }, delivery);
        }

        void start(String source) throws Exception {
            controller.setSource(source);
            assertEquals(source, takeStarted());
        }

        String takeStarted() throws Exception {
            String value = started.poll(5, TimeUnit.SECONDS);
            assertNotNull("Subscription must start", value);
            return value;
        }

        String takeStopped() throws Exception {
            String value = stopped.poll(5, TimeUnit.SECONDS);
            assertNotNull("Subscription must stop", value);
            return value;
        }

        Danmaku takeComment() throws Exception {
            Danmaku value = comments.poll(5, TimeUnit.SECONDS);
            assertNotNull("Comment must be delivered", value);
            return value;
        }

        @Override
        public void close() {
            controller.close();
        }
    }
}
