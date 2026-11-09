package com.fongmi.quickjs.method;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pulls only the next bounded JS chunk from a consumer thread; close never waits for JS. */
final class JsProxyBody extends InputStream {
    interface Source {
        CompletableFuture<byte[]> pull(int size);
        void close();
    }

    private final Source source;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object readLock = new Object();
    private volatile CompletableFuture<byte[]> pending;
    private byte[] chunk;
    private int offset;

    JsProxyBody(Source source) { this.source = source; }

    @Override public int read() throws IOException {
        byte[] value = new byte[1];
        return read(value, 0, 1) == -1 ? -1 : value[0] & 255;
    }

    @Override public int read(byte[] bytes, int start, int length) throws IOException {
        if (start < 0 || length < 0 || length > bytes.length - start) throw new IndexOutOfBoundsException();
        if (length == 0) return 0;
        synchronized (readLock) {
            try {
                while (!closed.get() && (chunk == null || offset == chunk.length)) {
                    CompletableFuture<byte[]> next = source.pull(Math.min(length, 65536));
                    pending = next;
                    if (closed.get()) next.cancel(true);
                    chunk = next.get();
                    pending = null;
                    offset = 0;
                    if (chunk == null) { close(); return -1; }
                    if (chunk.length > Math.min(length, 65536)) throw new IOException("Invalid proxy stream chunk");
                }
                if (closed.get()) return -1;
                int count = Math.min(length, chunk.length - offset);
                System.arraycopy(chunk, offset, bytes, start, count);
                offset += count;
                return count;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                close();
                throw new InterruptedIOException("Proxy stream interrupted");
            } catch (ExecutionException | java.util.concurrent.CancellationException error) {
                close();
                throw new IOException("Proxy stream failed", error.getCause() == null ? error : error.getCause());
            } catch (IOException | RuntimeException error) {
                close();
                throw error;
            }
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        CompletableFuture<byte[]> current = pending;
        BridgeCleanup cleanup = new BridgeCleanup();
        if (current != null) cleanup.run(() -> current.cancel(true));
        cleanup.run(source::close);
        cleanup.finish();
    }
}
