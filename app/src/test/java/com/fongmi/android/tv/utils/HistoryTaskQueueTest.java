package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryTaskQueueTest {

    @Test(timeout = 5000)
    public void shouldFinishEveryAcceptedDeleteWhenQueriesAreReplacedAndScreenCloses() throws Exception {
        HistoryTaskQueue queue = new HistoryTaskQueue();
        CountDownLatch deleting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(2);
        AtomicBoolean interruptedWrite = new AtomicBoolean();
        AtomicBoolean obsoleteQuery = new AtomicBoolean();
        List<String> records = new ArrayList<>(List.of("first", "second"));
        try {
            queue.write(() -> {
                deleting.countDown();
                try {
                    release.await();
                    records.remove("first");
                } catch (InterruptedException error) {
                    interruptedWrite.set(true);
                } finally {
                    deleted.countDown();
                }
            });
            assertTrue(deleting.await(1, TimeUnit.SECONDS));
            queue.replaceQuery(() -> obsoleteQuery.set(true));
            queue.write(() -> {
                records.remove("second");
                deleted.countDown();
            });
            queue.replaceQuery(() -> obsoleteQuery.set(true));

            queue.close();
            release.countDown();

            assertTrue(deleted.await(2, TimeUnit.SECONDS));
            assertTrue(records.isEmpty());
            assertFalse(interruptedWrite.get());
            assertFalse(obsoleteQuery.get());
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test(timeout = 5000)
    public void shouldReadAfterAllEarlierDeletesWithoutRestoringRemovedRecords() throws Exception {
        HistoryTaskQueue queue = new HistoryTaskQueue();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<List<String>> displayed = new AtomicReference<>();
        List<String> records = new ArrayList<>(List.of("deleted-a", "deleted-b", "kept"));
        try {
            queue.write(() -> {
                writing.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    throw new AssertionError("Accepted deletion was interrupted", error);
                }
                records.remove("deleted-a");
            });
            assertTrue(writing.await(1, TimeUnit.SECONDS));
            queue.replaceQuery(() -> displayed.set(new ArrayList<>(records)));
            queue.write(() -> records.remove("deleted-b"));
            queue.replaceQuery(() -> {
                displayed.set(new ArrayList<>(records));
                loaded.countDown();
            });

            release.countDown();

            assertTrue(loaded.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("kept"), displayed.get());
        } finally {
            release.countDown();
            queue.close();
        }
    }

    @Test(timeout = 5000)
    public void shouldCancelPausedScreenReadAndKeepItsQueuedDeletion() throws Exception {
        HistoryTaskQueue queue = new HistoryTaskQueue();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch deleted = new CountDownLatch(1);
        try {
            queue.replaceQuery(() -> {
                reading.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException error) {
                    interrupted.countDown();
                }
            });
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            queue.write(deleted::countDown);

            queue.cancelQuery();

            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertTrue(deleted.await(1, TimeUnit.SECONDS));
        } finally {
            queue.close();
        }
    }
}
