package com.fongmi.android.tv.player.subtitle;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.BaseRenderer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.source.MediaSource;

import com.fongmi.android.tv.setting.PlayerSetting;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.peerless2012.ass.Ass;
import io.github.peerless2012.ass.AssFrame;
import io.github.peerless2012.ass.AssRender;
import io.github.peerless2012.ass.AssTexType;
import io.github.peerless2012.ass.AssTrack;

/** Raw SSA renderer: libass work runs outside both the UI thread and audio playback thread. */
public final class AssTextRenderer extends BaseRenderer {
    public interface Output {
        void frame(AssFrame frame, int width, int height, int generation);
        void clear(int generation);
    }
    private final java.util.function.Supplier<SubtitleSource> fontSource;
    private SubtitleFonts fonts;
    private volatile SubtitleSource source;
    private long streamOffsetUs;
    private long historyPackets;
    private final Output output;
    private final FormatHolder holder = new FormatHolder();
    private final DecoderInputBuffer buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL);
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger queuedBytes = new AtomicInteger();
    private final AtomicBoolean rendering = new AtomicBoolean();
    private final AssRenderSchedule schedule = new AssRenderSchedule();
    private final AssFrameCache frames = new AssFrameCache();
    private HandlerThread thread;
    private Handler worker;
    private Ass ass;
    private AssRender render;
    private AssTrack track;
    private Format format;
    private int fontVersion = -1;
    private int eventBytes;
    private volatile int width = 1920;
    private volatile int height = 1080;
    private volatile int generation;
    private volatile long positionUs;
    private long offsetUs;
    private final AtomicBoolean refresh = new AtomicBoolean(true);
    private boolean ended;

    public AssTextRenderer(java.util.function.Supplier<SubtitleSource> fonts, Output output) {
        super(C.TRACK_TYPE_TEXT);
        this.fontSource = fonts;
        this.output = output;
    }
    public static boolean available() {
        try { Class.forName("io.github.peerless2012.ass.Ass"); return true; }
        catch (LinkageError | ClassNotFoundException error) { Log.w("Libass", "Native renderer unavailable", error); return false; }
    }
    public int generation() { return generation; }
    public void refresh() { refresh.set(true); }
    public void size(int width, int height) {
        if (width <= 0 || height <= 0) return;
        double scale = Math.min(1, Math.sqrt(1920d * 1080 / ((double) width * height)));
        this.width = Math.max(1, (int) (width * scale));
        this.height = Math.max(1, (int) (height * scale));
        refresh();
    }
    @Override public String getName() { return "TVLibassRenderer"; }
    @Override public int supportsFormat(Format format) {
        return RendererCapabilities.create(MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
                ? format.cryptoType == C.CRYPTO_TYPE_NONE ? C.FORMAT_HANDLED : C.FORMAT_UNSUPPORTED_DRM
                : C.FORMAT_UNSUPPORTED_TYPE);
    }
    @Override public boolean isReady() { return true; }
    @Override public boolean isEnded() { return ended; }

    private Handler worker() {
        if (worker == null) {
            thread = new HandlerThread("TVLibass", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            worker = new Handler(thread.getLooper());
        }
        return worker;
    }

    @Override protected void onStreamChanged(Format[] formats, long start, long offset, MediaSource.MediaPeriodId period) {
        format = formats[0];
        Format current = format;
        SubtitleSource streamSource = fontSource.get();
        source = streamSource;
        streamOffsetUs = offset;
        generation++;
        schedule.reset();
        output.clear(generation);
        worker().post(() -> { fonts = streamSource.fonts; initialize(current); });
    }

    private void initialize(Format format) {
        closeNative();
        historyPackets = 0;
        try {
            ass = new Ass();
            fonts.apply(ass);
            fontVersion = fonts.version();
            render = ass.createRender();
            render.setCacheLimit(4096, 24);
            refresh();
            track = ass.createTrack();
            byte[] header = AssPacket.header(format.initializationData).getBytes(StandardCharsets.UTF_8);
            if (header.length > 0) track.readBuffer(header, 0, header.length);
            render.setTrack(track);
        } catch (RuntimeException | LinkageError error) { Log.w("Libass", "Initialization failed", error); closeNative(); }
    }

    @Override protected void onPositionReset(long position, boolean joining, boolean keyFrame) {
        ended = false;
        generation++;
        schedule.reset();
        output.clear(generation);
        worker().post(() -> { if (track != null) track.clearEvent(); frames.clear(); eventBytes = 0; historyPackets = 0; });
        refresh();
    }

    @Override public void render(long position, long elapsedRealtimeUs) {
        positionUs = position - offsetUs;
        for (int i = 0; !ended && i < 16 && queued.get() < 32; i++) {
            buffer.clear();
            int read = readSource(holder, buffer, 0);
            if (read == C.RESULT_FORMAT_READ) { format = holder.format; continue; }
            if (read != C.RESULT_BUFFER_READ) break;
            if (buffer.isEndOfStream()) { ended = true; break; }
            if (buffer.data == null) continue;
            if (source != null && format != null && source.history.contains(format.id)) { refresh(); continue; }
            buffer.flip();
            if (buffer.data.remaining() > 16 * 1024 * 1024) continue;
            byte[] data = new byte[buffer.data.remaining()];
            buffer.data.get(data);
            long sampleTime = buffer.timeUs;
            List<byte[]> initialization = format == null ? List.of() : format.initializationData;
            if (queuedBytes.addAndGet(data.length) > 32 * 1024 * 1024) {
                queuedBytes.addAndGet(-data.length);
                continue;
            }
            int packetGeneration = generation;
            queued.incrementAndGet();
            worker().post(() -> {
                try {
                    if (track == null || packetGeneration != generation) return;
                    if (eventBytes + data.length > 32 * 1024 * 1024) { track.clearEvent(); eventBytes = 0; }
                    String text = AssPacket.decode(data);
                    byte[] shifted = AssPacket.shift(text, initialization, sampleTime).getBytes(StandardCharsets.UTF_8);
                    track.readBuffer(shifted, 0, shifted.length);
                    eventBytes += data.length;
                    refresh();
                } catch (RuntimeException error) { Log.w("Libass", "Invalid subtitle packet", error); }
                finally { queued.decrementAndGet(); queuedBytes.addAndGet(-data.length); }
            });
        }
        if (!schedule.isDue(positionUs, elapsedRealtimeUs, refresh.get())) return;
        if (!rendering.compareAndSet(false, true)) return;
        schedule.scheduled(positionUs, elapsedRealtimeUs);
        int ticket = generation;
        worker().post(() -> {
            try {
                if (render == null || ticket != generation) return;
                refresh.set(false);
                if (source != null && format != null && source.history.contains(format.id)) {
                    List<AssHistory.Packet> pending = source.history.since(format.id, historyPackets);
                    long pendingBytes = 0;
                    for (AssHistory.Packet packet : pending) pendingBytes += packet.text().length() * 3L + 128;
                    if (eventBytes + pendingBytes > 32 * 1024 * 1024) {
                        track.clearEvent();
                        eventBytes = 0;
                        historyPackets = 0;
                        pending = source.history.since(format.id, 0);
                    }
                    for (AssHistory.Packet packet : pending) {
                        byte[] shifted = AssPacket.shift(packet.text(), format.initializationData,
                                packet.timeUs() + streamOffsetUs).getBytes(StandardCharsets.UTF_8);
                        track.readBuffer(shifted, 0, shifted.length);
                        eventBytes += shifted.length;
                        historyPackets = packet.sequence() + 1;
                    }
                }
                if (fontVersion != fonts.version()) {
                    render.release();
                    fonts.apply(ass);
                    fontVersion = fonts.version();
                    render = ass.createRender();
                    render.setCacheLimit(4096, 24);
                    render.setTrack(track);
                }
                int renderWidth = width;
                int renderHeight = height;
                render.setFrameSize(renderWidth, renderHeight);
                render.setStorageSize(renderWidth, renderHeight);
                float size = PlayerSetting.getSubtitleTextSize();
                render.setFontScale(size == 0 ? 1 : Math.clamp(size / .0533f, .5f, 3f));
                AssFrame frame = render.renderFrame(positionUs / 1000, AssTexType.BITMAP_ALPHA);
                if (frames.update(frame)) output.frame(frames.getFrame(), renderWidth, renderHeight, ticket);
            } catch (RuntimeException error) { Log.w("Libass", "Render failed", error); }
            finally { rendering.set(false); }
        });
    }

    @Override public void handleMessage(int type, Object value) throws ExoPlaybackException {
        if (type == Renderer.MSG_SET_TEXT_OFFSET) { offsetUs = (Long) value * 1000; refresh(); }
        else super.handleMessage(type, value);
    }
    @Override protected void onDisabled() {
        generation++;
        output.clear(generation);
        if (worker != null) worker.post(this::closeNative);
    }
    @Override protected void onRelease() {
        if (worker != null) { worker.post(this::closeNative); thread.quitSafely(); worker = null; thread = null; }
    }
    private void closeNative() {
        frames.clear();
        if (render != null) render.release();
        if (track != null) track.release();
        if (ass != null) ass.release();
        render = null; track = null; ass = null;
        eventBytes = 0;
    }
}
