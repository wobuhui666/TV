package com.fongmi.android.tv.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PlaybackSyncProtocolTest {

    @Test
    public void parsesPageAndRejectsCursorRollback() {
        PlaybackSyncProtocol.Page page = PlaybackSyncProtocol.parsePage(
                "{\"schema\":\"webhtv.playback.v1\",\"items\":[{}],\"cursor\":12,\"hasMore\":true}", 10);
        assertEquals(1, page.items().size());
        assertEquals(12, page.cursor());
        assertTrue(page.hasMore());
        assertThrows(IllegalStateException.class, () -> PlaybackSyncProtocol.parsePage("{\"cursor\":9}", 10));
    }

    @Test
    public void rejectsUnknownSchemaWithoutAdvancingCursor() {
        assertThrows(IllegalStateException.class, () -> PlaybackSyncProtocol.parsePage("{\"schema\":\"other\",\"cursor\":20}", 10));
    }
}
