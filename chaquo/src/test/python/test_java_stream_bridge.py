"""Compile the production stream bridge and test Java cancellation at its JNI boundary."""
from pathlib import Path
import subprocess
import tempfile
import unittest


MAIN = Path(__file__).resolve().parents[2] / 'main' / 'java' / 'com' / 'fongmi' / 'chaquo'


SOURCES = {
    'androidx/annotation/Keep.java': 'package androidx.annotation; public @interface Keep {}',
    'com/google/gson/Gson.java': 'package com.google.gson; public class Gson { public String toJson(Object value) { return String.valueOf(value); } }',
    'com/github/catvod/net/NetOptions.java': 'package com.github.catvod.net; public class NetOptions { public static NetOptions from(String json) { return new NetOptions(); } }',
    'com/github/catvod/utils/CallTimeout.java': r'''
package com.github.catvod.utils;
import java.util.*;
import java.util.concurrent.*;
public final class CallTimeout implements AutoCloseable {
    private static final ThreadLocal<CallTimeout> CURRENT = new ThreadLocal<>();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    public CallTimeout(long budget, CallTimeout parent, boolean dialogs) { }
    public Runnable enter() {
        CallTimeout previous = CURRENT.get(); CURRENT.set(this);
        return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
    }
    public static void trackCurrent(CompletableFuture<?> future) {
        CallTimeout current = CURRENT.get(); if (current != null) current.pending.add(future);
    }
    @Override public void close() { for (CompletableFuture<?> future : pending) future.cancel(true); }
}
''',
    'com/chaquo/python/PyObject.java': r'''
package com.chaquo.python;
public final class PyObject {
    public interface Target { PyObject call(String method, Object[] args); }
    private final Object value;
    public PyObject(Object value) { this.value = value; }
    public PyObject callAttr(String method, Object... args) { return ((Target) value).call(method, args); }
    public <T> T toJava(Class<T> type) { return type.cast(value); }
}
''',
    'com/github/catvod/net/Net.java': r'''
package com.github.catvod.net;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
public final class Net {
    public CompletableFuture<StreamResult> pending;
    public final CountDownLatch requested = new CountDownLatch(1);
    public Net(CompletableFuture<StreamResult> pending) { this.pending = pending; }
    public CompletableFuture<StreamResult> requestStreamAsync(String url, NetOptions options) {
        requested.countDown(); return pending;
    }
    public static final class StreamResult extends InputStream {
        public final int code = 200;
        public final Map<String,Object> headers = Map.of("X-Test", "yes");
        public final String contentType = "video/x-flv";
        public final InputStream body;
        public StreamResult(InputStream body) { this.body = body; }
        @Override public int read() throws IOException { return body.read(); }
        @Override public int read(byte[] target, int offset, int length) throws IOException { return body.read(target, offset, length); }
        @Override public void close() { try { body.close(); } catch (IOException e) { throw new RuntimeException(e); } }
    }
}
''',
    'com/fongmi/chaquo/StreamBridgeCheck.java': r'''
package com.fongmi.chaquo;
import com.chaquo.python.PyObject;
import com.github.catvod.net.Net;
import com.github.catvod.utils.CallTimeout;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class StreamBridgeCheck {
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static PyObject source(AtomicInteger closed, java.util.function.Supplier<byte[]> pull) {
        return new PyObject((PyObject.Target) (method, args) -> {
            if (method.equals("close")) { closed.incrementAndGet(); return null; }
            byte[] value = pull.get(); return value == null ? null : new PyObject(value);
        });
    }
    static void chunkAndEof() throws Exception {
        Streams streams = new Streams(); AtomicInteger closed = new AtomicInteger(); AtomicInteger index = new AtomicInteger();
        InputStream body = streams.body(source(closed, () -> switch (index.getAndIncrement()) {
            case 0 -> new byte[0]; case 1 -> new byte[]{(byte)255, 1, 2}; default -> null;
        }));
        byte[] target = new byte[6];
        require(body.read(target, 2, 2) == 2 && (target[2] & 255) == 255 && target[3] == 1, "offset and chunk read");
        require(body.read() == 2, "remaining chunk");
        require(body.read() == -1 && body.read() == -1, "EOF");
        body.close(); streams.close();
        require(closed.get() == 1, "generator closes exactly once on EOF");
    }
    static void oversizeAndFailure() throws Exception {
        Streams streams = new Streams(); AtomicInteger closed = new AtomicInteger();
        InputStream body = streams.body(source(closed, () -> new byte[65537]));
        try { body.read(); throw new AssertionError("oversize accepted"); } catch (IOException expected) {}
        require(closed.get() == 1, "oversize releases generator");
        AtomicInteger failedClosed = new AtomicInteger();
        InputStream failed = streams.body(source(failedClosed, () -> { throw new IllegalStateException("source failed"); }));
        try { failed.read(); throw new AssertionError("failure accepted"); } catch (IOException expected) {}
        require(failedClosed.get() == 1, "exception releases generator"); streams.close();
    }
    static void cancelPendingOpen() throws Exception {
        Streams streams = new Streams(); AtomicInteger closed = new AtomicInteger();
        Net net = new Net(new CompletableFuture<>());
        InputStream body = streams.body(source(closed, () -> {
            try { streams.open(net, "https://fixture.invalid", "{}"); return null; }
            catch (IOException error) { throw new UncheckedIOException(error); }
        }));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> { try { body.read(); } catch (IOException expected) {} });
            require(net.requested.await(2, TimeUnit.SECONDS), "request entered");
            body.close(); read.get(2, TimeUnit.SECONDS);
            require(net.pending.isCancelled(), "close cancels pending request");
            require(closed.get() == 1, "canceled open releases generator");
        } finally { worker.shutdownNow(); streams.close(); }
    }
    static final class Blocking extends InputStream {
        final CountDownLatch entered = new CountDownLatch(1); final CountDownLatch ended = new CountDownLatch(1);
        final AtomicInteger closes = new AtomicInteger();
        @Override public int read() throws IOException { return read(new byte[1],0,1); }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            entered.countDown();
            try { if (!ended.await(3, TimeUnit.SECONDS)) throw new IOException("read remained blocked"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            throw new IOException("closed");
        }
        @Override public void close() { closes.incrementAndGet(); ended.countDown(); }
    }
    static void cancelBlockingRead() throws Exception {
        Streams streams = new Streams(); AtomicInteger closed = new AtomicInteger(); Blocking upstream = new Blocking();
        Net net = new Net(CompletableFuture.completedFuture(new Net.StreamResult(upstream)));
        InputStream body = streams.body(source(closed, () -> {
            try { return streams.open(net,"https://fixture.invalid","{}").readChunk(8); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        }));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> { try { body.read(); } catch (IOException expected) {} });
            require(upstream.entered.await(2, TimeUnit.SECONDS), "blocking read entered");
            body.close(); read.get(2, TimeUnit.SECONDS);
            require(upstream.closes.get() == 1, "close releases upstream once");
            require(closed.get() == 1, "close releases active generator after read ends");
        } finally { worker.shutdownNow(); streams.close(); }
    }
    static void nativeIdentityAndReaderEof() throws Exception {
        Streams streams = new Streams(); Blocking stream = new Blocking();
        require(streams.track(stream) == stream, "forwarded InputStream identity"); streams.close();
        require(stream.closes.get() == 1, "owner releases forwarded stream");
        AtomicInteger closed = new AtomicInteger();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[]{1,2}) { @Override public void close() { closed.incrementAndGet(); } };
        StreamReader reader = new StreamReader(new Net.StreamResult(input), null);
        require(Arrays.equals(reader.readChunk(1),new byte[]{1}), "small native read");
        require(reader.read() == 2 && reader.readChunk(1) == null && reader.read() == -1, "native EOF");
        reader.close(); require(closed.get() == 1, "native EOF close once");
    }
    static void cancelOrdinaryRequest() throws Exception {
        Streams streams = new Streams(); AtomicInteger closed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1); CompletableFuture<byte[]> ordinary = new CompletableFuture<>();
        InputStream body = streams.body(source(closed, () -> {
            CallTimeout.trackCurrent(ordinary); entered.countDown();
            try { return ordinary.get(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
            catch (ExecutionException error) { throw new RuntimeException(error); }
        }));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> { try { body.read(); } catch (IOException expected) {} });
            require(entered.await(2,TimeUnit.SECONDS), "ordinary request entered");
            body.close(); read.get(2,TimeUnit.SECONDS);
            require(ordinary.isCancelled(), "proxy scope cancels an ordinary request");
            require(closed.get()==1, "ordinary request cancellation releases generator");
            require(!worker.submit(() -> Thread.currentThread().isInterrupted()).get(), "close must not contaminate reused worker");
        } finally { worker.shutdownNow(); streams.close(); }
    }
    static void cancelSleep() throws Exception {
        Streams streams = new Streams(); AtomicInteger closed = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1);
        InputStream body = streams.body(source(closed, () -> {
            entered.countDown();
            try { Thread.sleep(60000); return new byte[]{1}; }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new RuntimeException(error); }
        }));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> read = worker.submit(() -> { try { body.read(); } catch (IOException expected) {} });
            require(entered.await(2,TimeUnit.SECONDS), "sleep entered"); body.close(); read.get(2,TimeUnit.SECONDS);
            require(closed.get()==1, "sleep cancellation releases generator");
            require(!worker.submit(() -> Thread.currentThread().isInterrupted()).get(), "sleep interrupt cleaned");
        } finally { worker.shutdownNow(); streams.close(); }
    }
    public static void main(String[] args) throws Exception {
        chunkAndEof(); oversizeAndFailure(); cancelPendingOpen(); cancelBlockingRead(); nativeIdentityAndReaderEof(); cancelOrdinaryRequest(); cancelSleep();
        require(!Thread.currentThread().isInterrupted(), "EOF close does not interrupt caller");
        System.out.println("stream bridge: 7 checks passed");
    }
}
'''
}


class JavaStreamBridgeTest(unittest.TestCase):
    def test_production_stream_java_cancellation_and_ownership(self):
        with tempfile.TemporaryDirectory(prefix='java-python-stream-check-') as temporary:
            directory = Path(temporary)
            paths = []
            for relative, source in SOURCES.items():
                path = directory / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                paths.append(str(path))
            paths.extend(str(MAIN / name) for name in ('Streams.java','ProxyBody.java','StreamReader.java'))
            compiled = subprocess.run(['javac','-d',str(directory),*paths], capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode,0,compiled.stderr)
            checked = subprocess.run(['java','-cp',str(directory),'com.fongmi.chaquo.StreamBridgeCheck'], capture_output=True,text=True,timeout=20)
            self.assertEqual(checked.returncode,0,checked.stderr)
            self.assertIn('7 checks passed',checked.stdout)
