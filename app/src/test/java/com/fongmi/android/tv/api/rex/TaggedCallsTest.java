package com.fongmi.android.tv.api.rex;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TaggedCallsTest {

    private final OkHttpClient client = new OkHttpClient();

    @Test
    public void shouldDropReplyQueuedBeforePageCancellation() {
        TaggedCalls requests = new TaggedCalls();
        Call call = newCall();
        List<Runnable> uiQueue = new ArrayList<>();
        AtomicInteger replies = new AtomicInteger();
        requests.track("page", call);
        requests.deliver("page", call, replies::incrementAndGet, uiQueue::add);

        requests.cancel("page");
        uiQueue.get(0).run();

        assertTrue(call.isCanceled());
        assertEquals(0, replies.get());
    }

    @Test
    public void shouldDeliverNewRequestWhenPageTagIsReused() {
        TaggedCalls requests = new TaggedCalls();
        Call old = newCall();
        Call latest = newCall();
        List<Runnable> uiQueue = new ArrayList<>();
        AtomicInteger replies = new AtomicInteger();
        requests.track("page", old);
        requests.deliver("page", old, () -> replies.addAndGet(10), uiQueue::add);
        requests.cancel("page");
        requests.track("page", latest);
        requests.deliver("page", latest, replies::incrementAndGet, uiQueue::add);

        uiQueue.forEach(Runnable::run);

        assertEquals(1, replies.get());
        assertFalse(latest.isCanceled());
    }

    @Test
    public void shouldCancelOnlyUndeliveredRequestsForThePage() {
        TaggedCalls requests = new TaggedCalls();
        Call complete = newCall();
        Call pending = newCall();
        Call otherPage = newCall();
        requests.track("page", complete);
        requests.track("page", pending);
        requests.track("other", otherPage);
        requests.deliver("page", complete, () -> {}, Runnable::run);

        requests.cancel("page");

        assertFalse(complete.isCanceled());
        assertTrue(pending.isCanceled());
        assertFalse(otherPage.isCanceled());
    }

    @Test(timeout = 10_000)
    public void shouldKeepParallelRequestsRegisteredWhileRepliesFinish() throws Exception {
        TaggedCalls requests = new TaggedCalls();
        int count = 500;
        List<Call> pending = new ArrayList<>();
        for (int i = 0; i < count; i++) pending.add(newCall());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Thread completedReplies = new Thread(() -> {
            try {
                start.await();
                for (int i = 0; i < count; i++) {
                    Call complete = newCall();
                    requests.track("page", complete);
                    requests.deliver("page", complete, () -> {}, Runnable::run);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        completedReplies.start();
        start.countDown();
        for (Call call : pending) requests.track("page", call);
        assertTrue(finished.await(5, TimeUnit.SECONDS));

        requests.cancel("page");

        assertTrue(pending.stream().allMatch(Call::isCanceled));
    }

    private Call newCall() {
        return client.newCall(new Request.Builder().url("https://example.com/feed").build());
    }
}
