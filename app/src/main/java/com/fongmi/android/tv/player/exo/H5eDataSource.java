package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Transforms H5E transport streams before the HLS extractor sees their bytes. */
final class H5eDataSource implements DataSource {

    static final int MAX_SEGMENT_BYTES = 16 * 1024 * 1024;

    static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstream;

        Factory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new H5eDataSource(upstream.createDataSource());
        }
    }

    private final DataSource upstream;
    private final Object lock = new Object();
    private Session session;

    H5eDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    static boolean handles(Uri uri) {
        String path = uri.getPath();
        if (path == null) return false;
        path = path.toLowerCase(Locale.ROOT);
        return path.contains("cdrmldcctv") && path.endsWith(".ts");
    }

    @Override
    public void addTransferListener(@NonNull TransferListener listener) {
        upstream.addTransferListener(listener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        Session current = new Session(handles(dataSpec.uri));
        synchronized (lock) {
            if (session != null) throw new IllegalStateException("Data source is already open");
            session = current;
            if (current.transform) current.loadingThread = Thread.currentThread();
        }
        if (!current.transform) return upstream.open(dataSpec);

        try {
            checkCanceled(current);
            // H5E can remove bytes. Retry/seek positions refer to the transformed segment,
            // so fetch the whole original resource before applying the requested range.
            DataSpec fullSegment = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET).build();
            long length = upstream.open(fullSegment);
            checkCanceled(current);
            byte[] data = H5eTransform.transform(readSegment(current, length));
            checkCanceled(current);
            if (dataSpec.position > data.length) {
                throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            }
            synchronized (lock) {
                checkCanceled(current);
                current.data = data;
                current.position = (int) dataSpec.position;
                current.remaining = data.length - current.position;
                if (dataSpec.length != C.LENGTH_UNSET) {
                    current.remaining = (int) Math.min(current.remaining, dataSpec.length);
                }
                return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : current.remaining;
            }
        } catch (IOException | RuntimeException | Error failure) {
            synchronized (lock) {
                current.closed = true;
                current.data = null;
            }
            try {
                closeUpstream(current);
            } catch (IOException | RuntimeException closeFailure) {
                if (closeFailure != failure) failure.addSuppressed(closeFailure);
            }
            throw failure;
        } finally {
            synchronized (lock) {
                current.loadingThread = null;
                if (current.closed && session == current) session = null;
            }
        }
    }

    private byte[] readSegment(Session current, long length) throws IOException {
        if (length > MAX_SEGMENT_BYTES) throw new IOException("H5E segment exceeds size limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[32 * 1024];
        while (true) {
            checkCanceled(current);
            int count = upstream.read(buffer, 0, buffer.length);
            if (count == C.RESULT_END_OF_INPUT) break;
            if (count > MAX_SEGMENT_BYTES - output.size()) {
                throw new IOException("H5E segment exceeds size limit");
            }
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void checkCanceled(Session current) throws InterruptedIOException {
        if (current.closed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("H5E segment load canceled");
        }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        synchronized (lock) {
            if (session == null || session.closed) throw new IOException("Data source is closed");
            if (session.transform) {
                if (session.data == null) throw new IOException("H5E segment is not ready");
                if (session.remaining == 0) return C.RESULT_END_OF_INPUT;
                int count = Math.min(length, session.remaining);
                System.arraycopy(session.data, session.position, buffer, offset, count);
                session.position += count;
                session.remaining -= count;
                return count;
            }
        }
        return upstream.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
        synchronized (lock) {
            return session == null || session.closed ? null : upstream.getUri();
        }
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        synchronized (lock) {
            return session == null || session.closed ? Collections.emptyMap() : upstream.getResponseHeaders();
        }
    }

    @Override
    public void close() throws IOException {
        Session current;
        synchronized (lock) {
            current = session;
            if (current == null) return;
            current.closed = true;
            current.data = null;
            // OkHttp's pending open waits on an interruptible future; closing its response
            // alone cannot cancel a call which has not received response headers yet.
            if (current.loadingThread != null && current.loadingThread != Thread.currentThread()) {
                current.loadingThread.interrupt();
            }
        }
        try {
            closeUpstream(current);
        } finally {
            synchronized (lock) {
                if (current.loadingThread == null && session == current) session = null;
            }
        }
    }

    private void closeUpstream(Session current) throws IOException {
        // Do not hold the state lock across I/O. A canceled open also closes here after
        // unwinding, covering a close that arrived just before upstream.open acquired data.
        synchronized (current.closeLock) {
            upstream.close();
        }
    }

    private static final class Session {

        private final boolean transform;
        private final Object closeLock = new Object();
        private volatile boolean closed;
        private Thread loadingThread;
        private byte[] data;
        private int position;
        private int remaining;

        private Session(boolean transform) {
            this.transform = transform;
        }
    }
}
