package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.junit.Test;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.ArrayList;
import java.util.List;

public class HttpEofRecoveryDataSourceTest {

    @Test
    public void recognizesOnlyUnexpectedProtocolEofInCauseChain() {
        assertTrue(HttpEofRecoveryDataSource.isRecoverableEof(new IOException(new ProtocolException("unexpected end of stream"))));
        assertFalse(HttpEofRecoveryDataSource.isRecoverableEof(new ProtocolException("invalid response")));
        assertFalse(HttpEofRecoveryDataSource.isRecoverableEof(new IOException("unexpected end of stream")));
    }

    @Test
    public void computesFixedAndUnknownRemainingLength() {
        assertEquals(6, HttpEofRecoveryDataSource.remainingLength(10, 4));
        assertEquals(0, HttpEofRecoveryDataSource.remainingLength(10, 12));
        assertEquals(C.LENGTH_UNSET, HttpEofRecoveryDataSource.remainingLength(C.LENGTH_UNSET, 4));
    }

    @Test
    public void reopensAtExactUnreadPositionAndResetsAfterProgress() throws Exception {
        ScriptedDataSource upstream = new ScriptedDataSource(2, -1, 2, -1, -1, -1, -1);
        HttpEofRecoveryDataSource source = new HttpEofRecoveryDataSource(upstream);
        source.open(new DataSpec.Builder().setUri(org.mockito.Mockito.mock(Uri.class)).setPosition(10).setLength(10).build());
        byte[] buffer = new byte[8];
        assertEquals(2, source.read(buffer, 0, buffer.length));
        assertEquals(2, source.read(buffer, 0, buffer.length));
        assertEquals(2, upstream.opens.size());
        assertEquals(12, upstream.opens.get(1).position);
        assertEquals(8, upstream.opens.get(1).length);
        try {
            source.read(buffer, 0, buffer.length);
        } catch (ProtocolException expected) {
            assertEquals(5, upstream.opens.size());
            assertEquals(14, upstream.opens.get(2).position);
            assertEquals(6, upstream.opens.get(2).length);
            return;
        }
        throw new AssertionError("Expected the fourth consecutive EOF to be rethrown");
    }

    private static final class ScriptedDataSource implements DataSource {

        private final int[] script;
        private final List<DataSpec> opens = new ArrayList<>();
        private int index;

        ScriptedDataSource(int... script) {
            this.script = script;
        }

        @Override
        public long open(DataSpec dataSpec) {
            opens.add(dataSpec);
            return dataSpec.length;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int value = script[index++];
            if (value < 0) throw new ProtocolException("unexpected end of stream");
            return value;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }
    }
}
