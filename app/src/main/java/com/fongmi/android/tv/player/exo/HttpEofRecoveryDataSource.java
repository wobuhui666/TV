package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reopens a truncated HTTP response at the exact unread byte offset. */
final class HttpEofRecoveryDataSource implements DataSource {

    private static final int MAX_CONSECUTIVE_RECOVERIES = 3;

    private final DataSource upstream;
    private DataSpec dataSpec;
    private long bytesRead;
    private int consecutiveRecoveries;

    HttpEofRecoveryDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        this.dataSpec = dataSpec;
        bytesRead = 0;
        consecutiveRecoveries = 0;
        return upstream.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        while (true) {
            try {
                int count = upstream.read(buffer, offset, length);
                if (count > 0) {
                    bytesRead += count;
                    consecutiveRecoveries = 0;
                }
                return count;
            } catch (IOException error) {
                if (dataSpec == null || !isRecoverableEof(error) || !canRecoverConsecutively(consecutiveRecoveries)) throw error;
                consecutiveRecoveries++;
                reopen();
            }
        }
    }

    private void reopen() throws IOException {
        try {
            upstream.close();
        } catch (IOException ignored) {
        }
        long remaining = remainingLength(dataSpec.length, bytesRead);
        DataSpec retry = remaining == C.LENGTH_UNSET ? dataSpec.subrange(bytesRead) : dataSpec.subrange(bytesRead, remaining);
        upstream.open(retry);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        dataSpec = null;
        bytesRead = 0;
        consecutiveRecoveries = 0;
        upstream.close();
    }

    static long remainingLength(long requestedLength, long bytesRead) {
        if (requestedLength == C.LENGTH_UNSET) return C.LENGTH_UNSET;
        return Math.max(0, requestedLength - Math.max(0, bytesRead));
    }

    static boolean isRecoverableEof(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (cause instanceof ProtocolException && message != null && message.toLowerCase(Locale.ROOT).contains("unexpected end of stream")) return true;
        }
        return false;
    }

    static boolean canRecoverConsecutively(int count) {
        return count >= 0 && count < MAX_CONSECUTIVE_RECOVERIES;
    }

    static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstream;

        Factory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return new HttpEofRecoveryDataSource(upstream.createDataSource());
        }
    }
}
