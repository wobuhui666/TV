package com.fongmi.android.tv.browse;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;

import com.fongmi.android.tv.bean.Result;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class BrowseTreeResolutionCandidateTest {

    @Test
    public void shouldRollbackStagedResultWithoutCommittingHistory() {
        Result result = new Result();
        AtomicInteger commits = new AtomicInteger();
        BrowseTree.ResolutionCandidate candidate = BrowseTree.resolution(item("media-rollback"), result, C.TIME_UNSET, commits::incrementAndGet);

        candidate.stage(1);
        candidate.rollback();

        assertNull(BrowseTree.consumeBrowseResult("media-rollback"));
        assertEquals(0, commits.get());
    }

    @Test
    public void shouldCommitHistoryOnlyWhenPlayerConsumesResult() {
        Result result = new Result();
        AtomicInteger commits = new AtomicInteger();
        BrowseTree.ResolutionCandidate candidate = BrowseTree.resolution(item("media-consume"), result, 12_000, commits::incrementAndGet);

        candidate.stage(2);

        assertEquals(12_000, candidate.resumePositionMs());
        assertNull(BrowseTree.consumeBrowseResult("media-consume", 1));
        assertSame(result, BrowseTree.consumeBrowseResult("media-consume", 2));
        assertEquals(1, commits.get());
        assertNull(BrowseTree.consumeBrowseResult("media-consume"));
    }

    @Test
    public void shouldDiscardOnlyResultsFromCanceledGeneration() {
        Result first = new Result();
        Result second = new Result();
        BrowseTree.ResolutionCandidate canceled = BrowseTree.resolution(item("media-canceled"), first, C.TIME_UNSET, null);
        BrowseTree.ResolutionCandidate current = BrowseTree.resolution(item("media-current"), second, C.TIME_UNSET, null);
        canceled.stage(7);
        current.stage(8);

        BrowseTree.discardBrowseResult(7);

        assertNull(BrowseTree.consumeBrowseResult("media-canceled", 7));
        assertSame(second, BrowseTree.consumeBrowseResult("media-current", 8));
    }

    private static MediaItem item(String mediaId) {
        return new MediaItem.Builder().setMediaId(mediaId).build();
    }
}
