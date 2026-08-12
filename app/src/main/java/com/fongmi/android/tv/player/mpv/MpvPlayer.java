package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.MpvLogCollector;
import com.github.catvod.utils.Path;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import is.xyz.mpv.MPVLib;
import is.xyz.mpv.MPVLib.MpvEvent;
import is.xyz.mpv.MPVLib.MpvFormat;
import is.xyz.mpv.MPVLib.MpvLogLevel;
import is.xyz.mpv.MPVNode;

@UnstableApi
final class MpvPlayer extends SimpleBasePlayer implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final long LIVE_DURATION_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int[] SELECTABLE_TRACK_TYPES = {C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT};
    private static final Object NATIVE_LOCK = new Object();
    private static final long DESTROY_TIMEOUT_MS = 5_000L;
    private static NativeState nativeState = NativeState.IDLE;
    private static long nativeGeneration;

    private enum NativeState {
        IDLE,
        CREATING,
        ALIVE,
        DESTROYING
    }

    private final Context context;
    private final Player.Commands commands;
    private final Map<String, Integer> trackIdsByGroupId;

    private PlaybackParameters playbackParameters;
    private TrackSelectionParameters trackSelectionParameters;
    private @Player.State int playbackState;
    private @Player.RepeatMode int repeatMode;
    private @Nullable PlaybackException playerError;
    private @Nullable MediaItem mediaItem;
    private @Nullable PlaySpec spec;
    private @Nullable Object videoOutput;
    private @Nullable SurfaceHolder surfaceHolder;
    private @Nullable SurfaceHolder.Callback surfaceCallback;
    private @Nullable TextureView textureView;
    private @Nullable TextureView.SurfaceTextureListener textureListener;
    private @Nullable Surface attachedSurface;
    private @Nullable String pendingUrl;
    private VideoSize videoSize;
    private Size surfaceSize;
    private Tracks tracks;
    private boolean ownsSurface;
    private boolean playWhenReady;
    private boolean loading;
    private boolean closed;
    private boolean fileLoaded;
    private boolean renderedFirstFrame;
    private boolean newlyRenderedFirstFrame;
    private long positionMs;
    private long durationMs;
    private long bufferedPositionMs;
    private long pendingStartPositionMs;
    private long pendingSeekAfterLoadMs;
    private long audioOffsetMs;
    private long textOffsetMs;
    private float volume;
    private int decode;
    private @Nullable String dolbyDecoderOverride;
    private boolean dolbyPlatformFallbackRequested;

    MpvPlayer(Context context, int decode) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.decode = decode;
        this.commands = buildCommands();
        this.trackIdsByGroupId = new HashMap<>();
        this.playbackParameters = PlaybackParameters.DEFAULT;
        this.trackSelectionParameters = TrackSelectionParameters.DEFAULT;
        this.playbackState = Player.STATE_IDLE;
        this.repeatMode = Player.REPEAT_MODE_OFF;
        this.videoSize = VideoSize.UNKNOWN;
        this.surfaceSize = Size.UNKNOWN;
        this.tracks = Tracks.EMPTY;
        this.positionMs = 0;
        this.durationMs = C.TIME_UNSET;
        this.bufferedPositionMs = C.TIME_UNSET;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.pendingSeekAfterLoadMs = C.TIME_UNSET;
        this.volume = 1f;
        initialize();
    }

    void start(PlaySpec spec, long startPositionMs, int decode) {
        runOnApplicationThread(() -> startInternal(spec, startPositionMs, decode));
    }

    boolean addSubtitle(Sub sub) {
        if (sub == null || sub.isEmpty()) return false;
        return command("sub-add", sub.getUrl(), "select", sub.getName(), sub.getLang());
    }

    void setSubtitleStyle() {
    }

    /** Applies soft/hard decode in the current native context and reopens the media. */
    void setDecode(int decode) {
        this.decode = decode;
        if (Looper.myLooper() != getApplicationLooper()) {
            runOnApplicationThread(() -> reloadForDecoderChange(
                    "切换解码模式", this::restorePlaybackOptions));
            return;
        }
        reloadForDecoderChange("切换解码模式", this::restorePlaybackOptions);
    }

    boolean isLive() {
        return mediaItem != null && (durationMs == C.TIME_UNSET || durationMs < LIVE_DURATION_THRESHOLD_MS);
    }

    boolean isVod() {
        return mediaItem != null && durationMs >= LIVE_DURATION_THRESHOLD_MS;
    }

    static boolean isNativeAvailable() {
        synchronized (NATIVE_LOCK) {
            return nativeState != NativeState.CREATING && nativeState != NativeState.DESTROYING;
        }
    }

    @Override
    protected State getState() {
        State.Builder builder = new State.Builder()
                .setAvailableCommands(commands)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(mediaItem == null && playbackState != Player.STATE_ENDED ? Player.STATE_IDLE : playbackState)
                .setPlayerError(playerError)
                .setRepeatMode(repeatMode)
                .setIsLoading(loading && playbackState != Player.STATE_IDLE && playbackState != Player.STATE_ENDED)
                .setPlaybackParameters(playbackParameters)
                .setTrackSelectionParameters(trackSelectionParameters)
                .setVideoSize(videoSize)
                .setSurfaceSize(surfaceSize)
                .setNewlyRenderedFirstFrame(newlyRenderedFirstFrame)
                .setVolume(volume)
                .setAudioOffsetMs(audioOffsetMs)
                .setTextOffsetMs(textOffsetMs);
        newlyRenderedFirstFrame = false;
        if (mediaItem == null) return builder.build();
        builder.setPlaylist(ImmutableList.of(buildMediaItemData()))
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs(Math.max(0, positionMs))
                .setContentBufferedPositionMs(PositionSupplier.getConstant(getBufferedPositionMs()));
        return builder.build();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        if (playWhenReady && attachedSurface != null && !TextUtils.isEmpty(pendingUrl)) loadPendingUrl();
        else command("set", "pause", playWhenReady ? "no" : "yes");
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (spec != null && playbackState == Player.STATE_IDLE) startInternal(spec, positionMs, decode);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        command("stop");
        pendingUrl = null;
        pendingStartPositionMs = C.TIME_UNSET;
        pendingSeekAfterLoadMs = C.TIME_UNSET;
        fileLoaded = false;
        renderedFirstFrame = false;
        newlyRenderedFirstFrame = false;
        loading = false;
        playbackState = Player.STATE_IDLE;
        playerError = null;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        if (closed) return Futures.immediateVoidFuture();
        closed = true;
        MpvLogCollector.log("MpvPlayer", "handleRelease: stop + async destroy");
        try {
            MPVLib.removeObserver(this);
            MPVLib.removeLogObserver(this);
        } catch (Throwable ignored) {
        }
        try {
            clearVideoOutputInternal(null);
        } catch (Throwable ignored) {
        }
        try {
            command("stop");
        } catch (Throwable ignored) {
        }
        try {
            command("quit");
        } catch (Throwable ignored) {
        }
        destroyNativeAsync("release");
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetRepeatMode(int repeatMode) {
        this.repeatMode = repeatMode;
        command("set", "loop-file", repeatMode == Player.REPEAT_MODE_ONE ? "inf" : "no");
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        command("set", "speed", Float.toString(playbackParameters.speed));
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetTrackSelectionParameters(TrackSelectionParameters trackSelectionParameters) {
        this.trackSelectionParameters = trackSelectionParameters;
        applyTrackSelectionParameters();
        readTracks();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetAudioOffsetMs(long audioOffsetMs) {
        this.audioOffsetMs = audioOffsetMs;
        command("set", "audio-delay", seconds(audioOffsetMs));
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetTextOffsetMs(long textOffsetMs) {
        this.textOffsetMs = textOffsetMs;
        command("set", "sub-delay", seconds(textOffsetMs));
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, int volumeOperationType) {
        this.volume = volume;
        command("set", "volume", Float.toString(volume * 100f));
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        setVideoOutputInternal(videoOutput);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        clearVideoOutputInternal(videoOutput);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        if (mediaItems.isEmpty()) clearPlaylist();
        else startMediaItem(mediaItems.get(resolveStartIndex(mediaItems, startIndex)), startPositionMs);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) startMediaItem(mediaItems.get(0), C.TIME_UNSET);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        if (fromIndex == 0) clearPlaylist();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        if (fromIndex == 0 && !mediaItems.isEmpty()) {
            mediaItem = mediaItems.get(0);
        } else if (fromIndex == 0) {
            clearPlaylist();
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        long targetMs = positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
        if (durationMs > 0) targetMs = Math.min(targetMs, durationMs);
        this.positionMs = targetMs;
        command("set", "time-pos", seconds(targetMs));
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY;
        return Futures.immediateVoidFuture();
    }

    @Override
    public void eventProperty(String property) {
        if ("track-list".equals(property)) refreshTracksOnApplicationThread();
    }

    @Override
    public void eventProperty(String property, long value) {
        runOnApplicationThread(() -> {
            if ("time-pos".equals(property)) positionMs = Math.max(0, value * 1000L);
            invalidateState();
        });
    }

    @Override
    public void eventProperty(String property, boolean value) {
        runOnApplicationThread(() -> {
            switch (property) {
                case "pause" -> playWhenReady = !value;
                case "paused-for-cache" -> {
                    loading = value;
                    if (value) playbackState = Player.STATE_BUFFERING;
                    else if (mediaItem != null && playbackState == Player.STATE_BUFFERING) playbackState = Player.STATE_READY;
                }
            }
            invalidateState();
        });
    }

    @Override
    public void eventProperty(String property, String value) {
        runOnApplicationThread(() -> {
            if ("speed".equals(property)) playbackParameters = new PlaybackParameters(parseFloat(value, playbackParameters.speed));
            invalidateState();
        });
    }

    @Override
    public void eventProperty(String property, double value) {
        runOnApplicationThread(() -> {
            switch (property) {
                case "time-pos/full" -> positionMs = secondsToMs(value, positionMs);
                case "duration/full", "duration" -> durationMs = secondsToMs(value, C.TIME_UNSET);
                case "speed" -> playbackParameters = new PlaybackParameters((float) value);
                case "audio-delay" -> audioOffsetMs = secondsToMs(value, 0);
                case "sub-delay" -> textOffsetMs = secondsToMs(value, 0);
            }
            invalidateState();
        });
    }

    /**
     * Extended-JNI callback (v0.0.3). Gold {@code MPVLib} has no Node overload;
     * when present we only treat it as a track-list change signal and re-read via strings.
     */
    @Override
    public void eventProperty(String property, MPVNode value) {
        if ("track-list".equals(property)) refreshTracksOnApplicationThread();
    }

    /**
     * Extended-JNI callback (v0.0.3): {@code event(id, node)}.
     * Gold public API uses {@code event(id)} + {@code eventEndFile}; keep this for current AAR.
     */
    @Override
    public void event(int eventId, MPVNode data) {
        runOnApplicationThread(() -> handleEvent(eventId, data));
    }

    /** Gold-compatible shape if JNI dispatches bare event ids without node payload. */
    public void event(int eventId) {
        runOnApplicationThread(() -> handleEvent(eventId, null));
    }

    /** Gold MPVLib.EventObserver default path for END_FILE with reason/error strings. */
    public void eventEndFile(int reason, int error, @Nullable String errorString) {
        runOnApplicationThread(() -> handleEndFile(reason, error, errorString));
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        if (TextUtils.isEmpty(text) || level > MpvLogLevel.MPV_LOG_LEVEL_WARN) return;
        String message = (TextUtils.isEmpty(prefix) ? "" : prefix + ": ") + text.trim();
        if (level <= MpvLogLevel.MPV_LOG_LEVEL_ERROR) MpvLogCollector.logError("MPV", message);
        else MpvLogCollector.log("MPV", message);
    }

    private void initialize() {
        MpvLogCollector.log("MpvPlayer", "initialize 开始 decode=" + (decode == PlayerEngine.HARD ? "硬解" : "软解")
                + " vulkan=" + PlayerSetting.isMpvVulkan()
                + " hdr=" + PlayerSetting.getMpvHdr());
        File configDir = Path.mpv();
        boolean ownsNative = false;
        boolean initialized = false;
        try {
            createNative(context);
            ownsNative = true;
            MpvLogCollector.log("MpvPlayer", "MPVLib.create 完成");
            MPVLib.addLogObserver(this);
            // All pre-init options (config/vo/vulkan/hwdec/tls/...) live in MpvOptions.
            MpvOptions.applyPreInit(context, decode);
            MPVLib.INSTANCE.init();
            MpvLogCollector.log("MpvPlayer", "MPVLib.init 完成 vo=" + MpvOptions.videoOutputDriver()
                    + " vulkan=" + PlayerSetting.isMpvVulkan()
                    + " gpu-next=" + PlayerSetting.isMpvGpuNext());
            MpvOptions.applyPostInit(configDir);
            MPVLib.addObserver(this);
            observeProperties();
            initialized = true;
        } finally {
            if (!initialized && ownsNative) rollbackInitialization();
        }
    }

    private static void createNative(Context context) {
        long generation;
        synchronized (NATIVE_LOCK) {
            if (nativeState != NativeState.IDLE) {
                throw new IllegalStateException("MPV native 当前不可创建: " + nativeState);
            }
            nativeState = NativeState.CREATING;
            generation = ++nativeGeneration;
        }
        try {
            MPVLib.INSTANCE.create(context);
            synchronized (NATIVE_LOCK) {
                if (nativeGeneration != generation || nativeState != NativeState.CREATING) {
                    throw new IllegalStateException("MPV native 创建状态异常: " + nativeState);
                }
                nativeState = NativeState.ALIVE;
            }
        } catch (Throwable e) {
            destroyNativeAsync("create rollback");
            if (e instanceof Error error) throw error;
            if (e instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("MPV native 创建失败", e);
        }
    }

    private void rollbackInitialization() {
        MpvLogCollector.logError("MpvPlayer", "初始化失败, 异步回滚 native");
        try {
            MPVLib.removeObserver(this);
        } catch (Throwable ignored) {
        }
        try {
            MPVLib.removeLogObserver(this);
        } catch (Throwable ignored) {
        }
        destroyNativeAsync("initialize rollback");
    }

    private static void destroyNativeAsync(String reason) {
        long generation;
        synchronized (NATIVE_LOCK) {
            if (nativeState == NativeState.IDLE) {
                MpvLogCollector.log("MpvPlayer", "destroy 跳过: native 已空闲");
                return;
            }
            if (nativeState == NativeState.DESTROYING) {
                MpvLogCollector.log("MpvPlayer", "destroy 跳过: 已在释放, reason=" + reason);
                return;
            }
            nativeState = NativeState.DESTROYING;
            generation = nativeGeneration;
        }
        Thread destroy = new Thread(() -> {
            boolean destroyed = false;
            try {
                MPVLib.INSTANCE.destroy();
                destroyed = true;
                MpvLogCollector.log("MpvPlayer", "destroy 完成, reason=" + reason);
            } catch (Throwable e) {
                MpvLogCollector.logError("MpvPlayer", "destroy 异常, MPV 保持不可用: " + e.getMessage());
            } finally {
                synchronized (NATIVE_LOCK) {
                    if (destroyed && nativeGeneration == generation && nativeState == NativeState.DESTROYING) {
                        nativeState = NativeState.IDLE;
                    }
                }
            }
        }, "mpv-destroy");
        destroy.setDaemon(true);
        destroy.start();

        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(DESTROY_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (NATIVE_LOCK) {
                if (destroy.isAlive() && nativeGeneration == generation && nativeState == NativeState.DESTROYING) {
                    MpvLogCollector.logError("MpvPlayer", "destroy 超时, MPV 保持不可用, reason=" + reason);
                }
            }
        }, "mpv-destroy-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static Player.Commands buildCommands() {
        return new Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_PREPARE)
                .add(Player.COMMAND_STOP)
                .add(Player.COMMAND_RELEASE)
                .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_BACK)
                .add(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SET_SPEED_AND_PITCH)
                .add(Player.COMMAND_SET_REPEAT_MODE)
                .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
                .add(Player.COMMAND_GET_TIMELINE)
                .add(Player.COMMAND_GET_METADATA)
                .add(Player.COMMAND_SET_MEDIA_ITEM)
                .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .add(Player.COMMAND_GET_TRACKS)
                .add(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
                .add(Player.COMMAND_GET_VOLUME)
                .add(Player.COMMAND_SET_VOLUME)
                .add(Player.COMMAND_SET_VIDEO_SURFACE)
                .add(Player.COMMAND_GET_AUDIO_OFFSET)
                .add(Player.COMMAND_SET_AUDIO_OFFSET)
                .add(Player.COMMAND_GET_TEXT_OFFSET)
                .add(Player.COMMAND_SET_TEXT_OFFSET)
                .build();
    }

    private SimpleBasePlayer.MediaItemData buildMediaItemData() {
        long durationUs = durationMs == C.TIME_UNSET ? C.TIME_UNSET : Util.msToUs(durationMs);
        return new SimpleBasePlayer.MediaItemData.Builder("mpv")
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setTracks(tracks)
                .setIsSeekable(durationMs > 0)
                .setIsDynamic(isLive())
                .setDurationUs(durationUs)
                .build();
    }

    private void startInternal(PlaySpec spec, long startPositionMs, int decode) {
        this.spec = spec;
        this.decode = decode;
        this.mediaItem = MediaItemFactory.from(spec);
        this.positionMs = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        this.durationMs = C.TIME_UNSET;
        this.bufferedPositionMs = C.TIME_UNSET;
        this.videoSize = VideoSize.UNKNOWN;
        this.tracks = Tracks.EMPTY;
        this.trackIdsByGroupId.clear();
        this.pendingUrl = null;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.pendingSeekAfterLoadMs = C.TIME_UNSET;
        this.fileLoaded = false;
        this.renderedFirstFrame = false;
        this.newlyRenderedFirstFrame = false;
        this.playWhenReady = true;
        this.loading = true;
        this.playerError = null;
        this.playbackState = Player.STATE_BUFFERING;

        // Log playback start
        MpvLogCollector.log("MpvPlayer", "=== 开始播放 ===");
        MpvLogCollector.log("MpvPlayer", "URL: " + spec.getUrl());
        MpvLogCollector.log("MpvPlayer", "起始位置: " + startPositionMs + "ms");
        MpvLogCollector.log("MpvPlayer", "解码模式: " + (decode == PlayerEngine.HARD ? "硬解" : "软解"));

        restorePlaybackOptions();
        applyHeaders(spec.getHeaders());
        loadUrl(spec.getUrl(), this.positionMs);
        invalidateState();
    }

    private void startMediaItem(MediaItem item, long startPositionMs) {
        if (item.localConfiguration == null) return;
        this.spec = null;
        this.mediaItem = item;
        this.positionMs = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        this.durationMs = C.TIME_UNSET;
        this.bufferedPositionMs = C.TIME_UNSET;
        this.tracks = Tracks.EMPTY;
        this.trackIdsByGroupId.clear();
        this.pendingUrl = null;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.pendingSeekAfterLoadMs = C.TIME_UNSET;
        this.fileLoaded = false;
        this.renderedFirstFrame = false;
        this.newlyRenderedFirstFrame = false;
        this.playWhenReady = true;
        this.loading = true;
        this.playerError = null;
        this.playbackState = Player.STATE_BUFFERING;
        restorePlaybackOptions();
        loadUrl(item.localConfiguration.uri.toString(), this.positionMs);
        invalidateState();
    }

    private void clearPlaylist() {
        command("stop");
        mediaItem = null;
        spec = null;
        positionMs = 0;
        durationMs = C.TIME_UNSET;
        bufferedPositionMs = C.TIME_UNSET;
        tracks = Tracks.EMPTY;
        trackIdsByGroupId.clear();
        pendingUrl = null;
        pendingStartPositionMs = C.TIME_UNSET;
        pendingSeekAfterLoadMs = C.TIME_UNSET;
        fileLoaded = false;
        renderedFirstFrame = false;
        newlyRenderedFirstFrame = false;
        loading = false;
        playerError = null;
        playbackState = Player.STATE_IDLE;
    }

    private void loadUrl(String url, long startPositionMs) {
        if (TextUtils.isEmpty(url)) return;
        pendingUrl = url;
        pendingStartPositionMs = startPositionMs;
        if (attachedSurface != null) loadPendingUrl();
    }

    private void loadPendingUrl() {
        if (TextUtils.isEmpty(pendingUrl)) return;
        if (attachedSurface == null || !attachedSurface.isValid()) {
            MpvLogCollector.log("MpvPlayer", "延迟加载: Surface未准备好");
            return;
        }
        String url = pendingUrl;
        long startPositionMs = pendingStartPositionMs;
        pendingUrl = null;
        pendingStartPositionMs = C.TIME_UNSET;

        MpvLogCollector.log("MpvPlayer", "开始加载URL: " + url);
        MpvLogCollector.log("MpvPlayer", "起始位置: " + startPositionMs + "ms");

        if (startPositionMs > 0 && shouldDeferInitialSeek(url)) {
            pendingSeekAfterLoadMs = startPositionMs;
            command("loadfile", url, "replace");
        } else if (startPositionMs > 0) {
            pendingSeekAfterLoadMs = C.TIME_UNSET;
            command("loadfile", url, "replace", "start=" + seconds(startPositionMs));
        } else {
            command("loadfile", url, "replace");
        }
        command("set", "pause", playWhenReady ? "no" : "yes");
    }

    private void handleEvent(int eventId, @Nullable MPVNode data) {
        // Queued events may land after release, racing the async native destroy thread.
        if (closed) return;
        switch (eventId) {
            case MpvEvent.MPV_EVENT_START_FILE -> {
                fileLoaded = false;
                loading = true;
                playbackState = Player.STATE_BUFFERING;
                playerError = null;
                invalidateState();
            }
            case MpvEvent.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true;
                loading = false;
                playbackState = Player.STATE_READY;
                MpvLogCollector.log("MpvPlayer", "文件加载成功");
                seekAfterLoadIfNeeded();
                readRuntimeState();
                if (applyDolbyPolicy()) return;
                addInitialSubtitles();
                invalidateState();
            }
            case MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                MpvLogCollector.log("MpvPlayer", "视频重新配置");
                seekAfterLoadIfNeeded();
                readVideoSize();
                // DV metadata may surface late (HLS) or via track switch; policy is idempotent.
                if (applyDolbyPolicy()) return;
                invalidateState();
            }
            case MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                fileLoaded = true;
                loading = false;
                if (mediaItem != null) playbackState = Player.STATE_READY;
                seekAfterLoadIfNeeded();
                readRuntimeState();
                invalidateState();
            }
            case MpvEvent.MPV_EVENT_END_FILE -> {
                // Prefer gold-style reason/error ints when node payload is absent.
                if (data == null) {
                    handleEndFile(MpvEndFile.REASON_EOF, /*error*/ 0, null);
                } else {
                    String reason = nodeString(data, "reason");
                    String errorMsg = nodeString(data, "error");
                    int fileError = nodeInt(data, "file_error", 0);
                    handleEndFile(MpvEndFile.mapReason(reason), fileError, errorMsg.isEmpty() ? reason : errorMsg);
                }
            }
        }
    }

    private void handleEndFile(int reason, int error, @Nullable String errorString) {
        if (closed) return;
        loading = false;
        fileLoaded = false;
        boolean isError = reason == MpvEndFile.REASON_ERROR || error != 0
                || (errorString != null && errorString.toLowerCase(Locale.US).contains("error"));
        if (isError) {
            String errorMsg = errorString == null ? "" : errorString;
            int fileError = error;

            MpvLogCollector.logError("MpvPlayer", "=== MPV播放错误详情 ===");
            MpvLogCollector.logError("MpvPlayer", "错误原因码: " + reason);
            MpvLogCollector.logError("MpvPlayer", "错误消息: " + errorMsg);
            MpvLogCollector.logError("MpvPlayer", "文件错误码: " + fileError);
            MpvLogCollector.logError("MpvPlayer", "URL: " + (mediaItem != null && mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri.toString() : "null"));
            MpvLogCollector.logError("MpvPlayer", "Surface已附加: " + (attachedSurface != null));
            MpvLogCollector.logError("MpvPlayer", "Surface有效: " + (attachedSurface != null && attachedSurface.isValid()));

            String hwdec = MPVLib.INSTANCE.getPropertyString("hwdec");
            String hwdecCurrent = MPVLib.INSTANCE.getPropertyString("hwdec-current");
            MpvLogCollector.logError("MpvPlayer", "硬解配置: " + hwdec);
            MpvLogCollector.logError("MpvPlayer", "当前硬解: " + hwdecCurrent);

            String videoCodec = MPVLib.INSTANCE.getPropertyString("video-codec");
            MpvLogCollector.logError("MpvPlayer", "视频编码: " + videoCodec);

            StringBuilder msgBuilder = new StringBuilder("MPV播放失败");
            if (!errorMsg.isEmpty()) {
                msgBuilder.append(": ").append(errorMsg);
            }
            if (fileError != 0) {
                msgBuilder.append(" (错误码: ").append(fileError).append(")");
            }

            int errorCode = PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
            String errorLower = errorMsg.toLowerCase(Locale.US);
            if (errorLower.contains("decode") || errorLower.contains("codec")) {
                errorCode = PlaybackException.ERROR_CODE_DECODING_FAILED;
            } else if (errorLower.contains("format") || errorLower.contains("demux")) {
                errorCode = PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
            } else if (errorLower.contains("network") || errorLower.contains("connection") || errorLower.contains("http")) {
                errorCode = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
            }

            fail(new PlaybackException(msgBuilder.toString(), null, errorCode));
            return;
        }
        // Only a genuine EOF may surface STATE_ENDED — upstream treats it as "auto play next".
        if (reason == MpvEndFile.REASON_EOF) playbackState = Player.STATE_ENDED;
        else if (reason == MpvEndFile.REASON_STOP && mediaItem == null) playbackState = Player.STATE_IDLE;
        else if (reason == MpvEndFile.REASON_QUIT) playbackState = Player.STATE_IDLE;
        invalidateState();
    }

    private void seekAfterLoadIfNeeded() {
        if (pendingSeekAfterLoadMs == C.TIME_UNSET) return;
        long seekMs = Math.max(0, pendingSeekAfterLoadMs);
        pendingSeekAfterLoadMs = C.TIME_UNSET;
        positionMs = seekMs;
        MpvLogCollector.log("MpvPlayer", "加载后定位: " + seekMs + "ms");
        command("set", "time-pos", seconds(seekMs));
    }

    private void readRuntimeState() {
        readPosition();
        readDuration();
        readVideoSize();
        readTracks();
        Double speed = MPVLib.INSTANCE.getPropertyDouble("speed");
        if (speed != null) playbackParameters = new PlaybackParameters(speed.floatValue());
        Double audioDelay = MPVLib.INSTANCE.getPropertyDouble("audio-delay");
        if (audioDelay != null) audioOffsetMs = secondsToMs(audioDelay, 0);
        Double subDelay = MPVLib.INSTANCE.getPropertyDouble("sub-delay");
        if (subDelay != null) textOffsetMs = secondsToMs(subDelay, 0);
    }

    private void readPosition() {
        Double value = MPVLib.INSTANCE.getPropertyDouble("time-pos/full");
        if (value == null) value = MPVLib.INSTANCE.getPropertyDouble("time-pos");
        if (value != null) positionMs = secondsToMs(value, positionMs);
    }

    private void readDuration() {
        Double value = MPVLib.INSTANCE.getPropertyDouble("duration/full");
        if (value == null) value = MPVLib.INSTANCE.getPropertyDouble("duration");
        durationMs = value == null ? C.TIME_UNSET : secondsToMs(value, C.TIME_UNSET);
        bufferedPositionMs = durationMs;
    }

    private void readVideoSize() {
        int codedWidth = getInt("width", 0);
        int codedHeight = getInt("height", 0);
        if (codedWidth <= 0 || codedHeight <= 0) {
            codedWidth = getInt("video-params/w", 0);
            codedHeight = getInt("video-params/h", 0);
        }
        int displayWidth = getInt("dwidth", 0);
        int displayHeight = getInt("dheight", 0);
        int width = codedWidth > 0 ? codedWidth : displayWidth;
        int height = codedHeight > 0 ? codedHeight : displayHeight;
        if (width > 0 && height > 0) {
            videoSize = new VideoSize(width, height);
            MpvLogCollector.log("MpvPlayer", "视频尺寸: " + width + "x" + height + ", 显示尺寸: " + displayWidth + "x" + displayHeight);
            markRenderedFirstFrame();
        } else {
            MpvLogCollector.log("MpvPlayer", "无视频尺寸信息 (可能是纯音频)");
        }
    }

    private void markRenderedFirstFrame() {
        if (renderedFirstFrame) return;
        if (!fileLoaded) return;
        if (attachedSurface == null || !attachedSurface.isValid()) return;
        if (videoSize.width <= 0 || videoSize.height <= 0) return;
        renderedFirstFrame = true;
        newlyRenderedFirstFrame = true;
        MpvLogCollector.log("MpvPlayer", "通知首帧已渲染");
        invalidateState();
    }

    /**
     * Read tracks via gold-compatible string properties:
     * {@code track-list/count} + {@code track-list/N/{type,id,title,lang,...}}.
     * Does not call {@code getPropertyNode} (absent from gold libplayer).
     */
    private void readTracks() {
        try {
            int count = trackListCount();
            if (count <= 0) {
                tracks = Tracks.EMPTY;
                trackIdsByGroupId.clear();
                return;
            }
            List<Tracks.Group> groups = new ArrayList<>();
            Map<String, Integer> idsByGroupId = new HashMap<>();
            for (int i = 0; i < count; i++) {
                String prefix = "track-list/" + i + "/";
                int type = toTrackType(propString(prefix + "type", ""));
                int id = propInt(prefix + "id", C.INDEX_UNSET);
                if (type == C.TRACK_TYPE_UNKNOWN || id == C.INDEX_UNSET) continue;
                String groupId = trackGroupId(type, id);
                TrackGroup group = new TrackGroup(groupId, buildTrackFormat(prefix, type, id));
                boolean selected = propBoolean(prefix + "selected");
                groups.add(new Tracks.Group(group, false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected}));
                idsByGroupId.put(groupId, id);
            }
            tracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
            trackIdsByGroupId.clear();
            trackIdsByGroupId.putAll(idsByGroupId);
        } catch (Throwable e) {
            MpvLogCollector.logError("MpvPlayer", "读取 track-list 失败: " + e.getMessage());
            tracks = Tracks.EMPTY;
            trackIdsByGroupId.clear();
        }
    }

    private int trackListCount() {
        Integer count = MPVLib.INSTANCE.getPropertyInt("track-list/count");
        if (count != null && count >= 0) return count;
        // Fallback: probe until type is empty (cap to avoid runaway).
        for (int i = 0; i < 64; i++) {
            String type = propString("track-list/" + i + "/type", null);
            if (type == null) return i;
        }
        return 64;
    }

    private Format buildTrackFormat(String prefix, int type, int id) {
        String codec = emptyToNull(propString(prefix + "codec", ""));
        Format.Builder builder = new Format.Builder()
                .setId(Integer.toString(id))
                .setLabel(buildTrackLabel(prefix, type, id))
                .setLanguage(emptyToNull(propString(prefix + "lang", "")))
                .setCodecs(codec)
                .setSampleMimeType(getSampleMimeType(type, codec))
                .setSelectionFlags(trackSelectionFlags(prefix));
        int bitrate = propInt(prefix + "demux-bitrate", C.LENGTH_UNSET);
        if (bitrate > 0) builder.setAverageBitrate(bitrate);
        if (type == C.TRACK_TYPE_VIDEO) {
            int width = propInt(prefix + "demux-w", C.LENGTH_UNSET);
            int height = propInt(prefix + "demux-h", C.LENGTH_UNSET);
            double frameRate = propDouble(prefix + "demux-fps", 0);
            if (width > 0) builder.setWidth(width);
            if (height > 0) builder.setHeight(height);
            if (frameRate > 0) builder.setFrameRate((float) frameRate);
        } else if (type == C.TRACK_TYPE_AUDIO) {
            int channelCount = propInt(prefix + "demux-channel-count", C.LENGTH_UNSET);
            int sampleRate = propInt(prefix + "demux-samplerate", C.RATE_UNSET_INT);
            if (channelCount > 0) builder.setChannelCount(channelCount);
            if (sampleRate > 0) builder.setSampleRate(sampleRate);
        }
        return builder.build();
    }

    private String buildTrackLabel(String prefix, int type, int id) {
        String title = propString(prefix + "title", "");
        if (!TextUtils.isEmpty(title)) return title;
        String lang = propString(prefix + "lang", "");
        String codec = propString(prefix + "codec", "");
        String trackPrefix = switch (type) {
            case C.TRACK_TYPE_VIDEO -> "Video";
            case C.TRACK_TYPE_AUDIO -> "Audio";
            case C.TRACK_TYPE_TEXT -> "Subtitle";
            default -> "Track";
        };
        List<String> parts = new ArrayList<>();
        parts.add(trackPrefix + " " + id);
        if (!TextUtils.isEmpty(lang)) parts.add(lang);
        if (!TextUtils.isEmpty(codec)) parts.add(codec);
        return String.join(" - ", parts);
    }

    private static int trackSelectionFlags(String prefix) {
        int flags = 0;
        if (propBoolean(prefix + "default")) flags |= C.SELECTION_FLAG_DEFAULT;
        if (propBoolean(prefix + "forced")) flags |= C.SELECTION_FLAG_FORCED;
        return flags;
    }

    private static String propString(String name, @Nullable String fallback) {
        try {
            String value = MPVLib.INSTANCE.getPropertyString(name);
            return value == null ? fallback : value;
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static int propInt(String name, int fallback) {
        try {
            Integer value = MPVLib.INSTANCE.getPropertyInt(name);
            if (value != null) return value;
            Double d = MPVLib.INSTANCE.getPropertyDouble(name);
            return d == null ? fallback : (int) Math.round(d);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static double propDouble(String name, double fallback) {
        try {
            Double value = MPVLib.INSTANCE.getPropertyDouble(name);
            return value == null ? fallback : value;
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static boolean propBoolean(String name) {
        try {
            Boolean value = MPVLib.INSTANCE.getPropertyBoolean(name);
            if (value != null) return value;
            String s = MPVLib.INSTANCE.getPropertyString(name);
            return "yes".equalsIgnoreCase(s) || "true".equalsIgnoreCase(s);
        } catch (Throwable e) {
            return false;
        }
    }

    private void applyTrackSelectionParameters() {
        for (int type : SELECTABLE_TRACK_TYPES) {
            if (trackSelectionParameters.disabledTrackTypes.contains(type)) {
                setMpvTrack(type, "no");
                continue;
            }
            TrackSelectionOverride override = findOverride(type);
            if (override == null) {
                setMpvTrack(type, "auto");
                continue;
            }
            if (override.trackIndices.isEmpty()) {
                setMpvTrack(type, "no");
                continue;
            }
            Integer trackId = trackIdsByGroupId.get(override.mediaTrackGroup.id);
            if (trackId != null) setMpvTrack(type, Integer.toString(trackId));
        }
    }

    private @Nullable TrackSelectionOverride findOverride(int type) {
        for (TrackSelectionOverride override : trackSelectionParameters.overrides.values()) {
            if (override.getType() == type) return override;
        }
        return null;
    }

    private void setMpvTrack(int type, String value) {
        String property = switch (type) {
            case C.TRACK_TYPE_VIDEO -> "vid";
            case C.TRACK_TYPE_AUDIO -> "aid";
            case C.TRACK_TYPE_TEXT -> "sid";
            default -> "";
        };
        if (!TextUtils.isEmpty(property)) command("set", property, value);
    }

    private void addInitialSubtitles() {
        if (spec == null || spec.getSubs() == null) return;
        for (int i = 0; i < spec.getSubs().size(); i++) {
            Sub sub = spec.getSubs().get(i);
            if (sub == null || sub.isEmpty()) continue;
            command("sub-add", sub.getUrl(), i == 0 ? "select" : "auto", sub.getName(), sub.getLang());
        }
    }

    private void observeProperties() {
        MPVLib.INSTANCE.observeProperty("time-pos", MpvFormat.MPV_FORMAT_INT64);
        MPVLib.INSTANCE.observeProperty("time-pos/full", MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.INSTANCE.observeProperty("duration/full", MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.INSTANCE.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.INSTANCE.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.INSTANCE.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.INSTANCE.observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.INSTANCE.observeProperty("audio-delay", MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.INSTANCE.observeProperty("sub-delay", MpvFormat.MPV_FORMAT_DOUBLE);
        // Prefer flag/none notify; gold has no node format requirement for refresh.
        // MPV_FORMAT_NONE (0) if present triggers eventProperty(name) only.
        try {
            MPVLib.INSTANCE.observeProperty("track-list", MpvFormat.MPV_FORMAT_NONE);
        } catch (Throwable e) {
            MPVLib.INSTANCE.observeProperty("track-list", MpvFormat.MPV_FORMAT_NODE);
        }
    }

    private void applyHeaders(@Nullable Map<String, String> headers) {
        String userAgent = "";
        String referrer = "";
        List<String> fields = new ArrayList<>();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().trim();
                String value = entry.getValue();
                if (TextUtils.isEmpty(key) || value == null) continue;
                if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key)) {
                    userAgent = value;
                    continue;
                }
                if (isReferrerHeader(key)) {
                    referrer = value;
                    continue;
                }
                if (HttpHeaders.RANGE.equalsIgnoreCase(key)) {
                    MpvLogCollector.log("MpvPlayer", "跳过静态 Range 请求头");
                    continue;
                }
                fields.add(key + ": " + value);
            }
        }
        // Never write an empty UA: it would wipe the pre-init default from MpvOptions.
        if (TextUtils.isEmpty(userAgent)) userAgent = MpvOptions.defaultUserAgent();
        MPVLib.INSTANCE.setPropertyString("user-agent", userAgent);
        MPVLib.INSTANCE.setPropertyString("referrer", referrer);
        applyHttpHeaderFields(fields);
        MpvLogCollector.log("MpvPlayer", "请求头: User-Agent=" + !TextUtils.isEmpty(userAgent) + ", Referer=" + !TextUtils.isEmpty(referrer) + ", extra=" + fields.size());
    }

    private void applyHttpHeaderFields(List<String> fields) {
        if (!command("change-list", "http-header-fields", "clr", "")) return;
        for (String field : fields) if (!command("change-list", "http-header-fields", "append", field)) return;
    }

    private static boolean isReferrerHeader(String key) {
        return HttpHeaders.REFERER.equalsIgnoreCase(key) || "Referrer".equalsIgnoreCase(key);
    }

    private void reloadForDecoderChange(String reason, Runnable applyOptions) {
        if (closed) return;
        String url = spec != null ? spec.getUrl() : mediaItem != null && mediaItem.localConfiguration != null
                ? mediaItem.localConfiguration.uri.toString() : null;
        try {
            if (TextUtils.isEmpty(url)) {
                applyOptions.run();
                return;
            }
            long resumePositionMs = Math.max(0, positionMs);
            MpvLogCollector.log("MpvPlayer", reason + ": stop/rebind/loadfile, position=" + resumePositionMs + "ms");
            if (!command("stop")) return;
            pendingUrl = null;
            pendingStartPositionMs = C.TIME_UNSET;
            pendingSeekAfterLoadMs = C.TIME_UNSET;
            fileLoaded = false;
            renderedFirstFrame = false;
            newlyRenderedFirstFrame = false;
            loading = true;
            playerError = null;
            playbackState = Player.STATE_BUFFERING;
            applyOptions.run();
            if (!rebindVideoOutputForDecoderChange(reason)) {
                fail(new PlaybackException(reason + "时重绑视频输出失败", null,
                        PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK));
                return;
            }
            loadUrl(url, resumePositionMs);
            invalidateState();
        } catch (Throwable e) {
            MpvLogCollector.logError("MpvPlayer", reason + "失败: " + e.getMessage());
            fail(new PlaybackException(reason + "失败", e, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK));
        }
    }

    private boolean rebindVideoOutputForDecoderChange(String reason) {
        if (attachedSurface == null || !attachedSurface.isValid()) {
            MpvLogCollector.log("MpvPlayer", reason + " rebind 跳过: 无有效 Surface");
            return true;
        }
        try {
            try {
                MPVLib.INSTANCE.detachSurface();
            } catch (Throwable ignored) {
            }
            MPVLib.INSTANCE.attachSurface(attachedSurface);
            MPVLib.INSTANCE.setOptionString("force-window", "yes");
            if (surfaceSize.getWidth() > 0 && surfaceSize.getHeight() > 0) {
                MPVLib.INSTANCE.setPropertyString("android-surface-size",
                        surfaceSize.getWidth() + "x" + surfaceSize.getHeight());
            }
            MpvLogCollector.log("MpvPlayer", reason + " rebind Surface 完成");
            return true;
        } catch (Throwable e) {
            MpvLogCollector.logError("MpvPlayer", reason + " rebind 失败: " + e.getMessage());
            return false;
        }
    }

    private boolean applyDolbyPolicy() {
        int profile = selectedVideoDolbyProfile();
        boolean hardDecode = decode == PlayerEngine.HARD;
        boolean dolbyHwdecEnabled = PlayerSetting.isMpvDolbyHwdecEnabled();
        if (profile < 0) return false;
        if (MpvDolbyPolicy.shouldUsePlatformDecoder(hardDecode, dolbyHwdecEnabled, profile)) {
            if (dolbyPlatformFallbackRequested) return true;
            dolbyPlatformFallbackRequested = true;
            MpvDolbyVisionException cause = new MpvDolbyVisionException(profile);
            fail(new PlaybackException(cause.getMessage(), cause,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED));
            return true;
        }
        if (!MpvDolbyPolicy.shouldUseSoftwareDecoder(hardDecode, dolbyHwdecEnabled, profile)) return false;
        if ("no".equals(dolbyDecoderOverride)) return false;
        dolbyDecoderOverride = "no";
        MpvLogCollector.log("MpvPlayer", "Dolby Vision profile " + profile
                + ": reload with hwdec=no, vo=gpu-next");
        if (profile == 7) {
            MpvLogCollector.log("MpvPlayer", "Dolby Vision profile 7 enhancement layer is unsupported; using BL/RPU");
        }
        reloadForDecoderChange("Dolby Vision 软件解码", MpvOptions::applyDolbyVisionSoftwareDecode);
        return true;
    }

    private void restorePlaybackOptions() {
        dolbyDecoderOverride = null;
        dolbyPlatformFallbackRequested = false;
        try {
            MpvOptions.applyPlaybackDefaults(decode);
        } catch (Throwable e) {
            MpvLogCollector.logError("MpvPlayer", "恢复默认视频输出失败: " + e.getMessage());
        }
    }

    private String activeVideoOutputDriver() {
        return "no".equals(dolbyDecoderOverride) ? "gpu-next" : MpvOptions.videoOutputDriver();
    }

    private int selectedVideoDolbyProfile() {
        try {
            int count = trackListCount();
            for (int i = 0; i < count; i++) {
                String prefix = "track-list/" + i + "/";
                if (!"video".equals(propString(prefix + "type", ""))) continue;
                if (!propBoolean(prefix + "selected")) continue;
                return propInt(prefix + "dolby-vision-profile", -1);
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private void setVideoOutputInternal(Object output) {
        clearVideoOutputInternal(null);
        videoOutput = output;
        if (output instanceof SurfaceView view) {
            attachHolder(view.getHolder());
        } else if (output instanceof SurfaceHolder holder) {
            attachHolder(holder);
        } else if (output instanceof TextureView view) {
            attachTexture(view);
        } else if (output instanceof Surface surface) {
            attachSurface(surface, C.LENGTH_UNSET, C.LENGTH_UNSET, false);
        }
    }

    private void clearVideoOutputInternal(@Nullable Object output) {
        if (output != null && output != videoOutput) return;
        if (surfaceHolder != null && surfaceCallback != null) surfaceHolder.removeCallback(surfaceCallback);
        if (textureView != null && textureView.getSurfaceTextureListener() == textureListener) textureView.setSurfaceTextureListener(null);
        surfaceHolder = null;
        surfaceCallback = null;
        textureView = null;
        textureListener = null;
        videoOutput = null;
        renderedFirstFrame = false;
        newlyRenderedFirstFrame = false;
        detachSurface(true);
        surfaceSize = Size.UNKNOWN;
    }

    private void attachHolder(SurfaceHolder holder) {
        surfaceHolder = holder;
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                if (holder != surfaceHolder) return;
                attachHolderSurface(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (holder != surfaceHolder) return;
                updateSurfaceSize(width, height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                if (holder != surfaceHolder) return;
                detachSurface(false);
            }
        };
        holder.addCallback(surfaceCallback);
        attachHolderSurface(holder);
    }

    private void attachHolderSurface(SurfaceHolder holder) {
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) return;
        Rect frame = holder.getSurfaceFrame();
        attachSurface(surface, frame.width(), frame.height(), false);
    }

    private void attachTexture(TextureView view) {
        textureView = view;
        textureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                if (view != textureView) return;
                attachSurface(new Surface(surfaceTexture), width, height, true);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                if (view != textureView) return;
                updateSurfaceSize(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                if (view != textureView) return true;
                detachSurface(true);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        };
        view.setSurfaceTextureListener(textureListener);
        if (view.isAvailable()) attachSurface(new Surface(view.getSurfaceTexture()), view.getWidth(), view.getHeight(), true);
    }

    private void attachSurface(Surface surface, int width, int height, boolean ownsSurface) {
        if (surface == null || !surface.isValid()) return;
        if (surface == attachedSurface) {
            updateSurfaceSize(width, height);
            loadPendingUrl();
            return;
        }
        detachSurface(true);
        attachedSurface = surface;
        this.ownsSurface = ownsSurface;
        renderedFirstFrame = false;

        MpvLogCollector.log("MpvPlayer", "=== 附加 Surface ===");
        MpvLogCollector.log("MpvPlayer", "Surface有效: " + surface.isValid());
        MpvLogCollector.log("MpvPlayer", "尺寸: " + width + "x" + height);

        MPVLib.INSTANCE.attachSurface(surface);
        MPVLib.INSTANCE.setOptionString("force-window", "yes");
        updateSurfaceSize(width, height);
        if (TextUtils.isEmpty(pendingUrl)) MPVLib.INSTANCE.setPropertyString("vo", activeVideoOutputDriver());
        markRenderedFirstFrame();
        loadPendingUrl();
    }

    private void detachSurface(boolean releaseOwned) {
        if (attachedSurface == null) return;
        MpvLogCollector.log("MpvPlayer", "=== 分离 Surface ===");
        try {
            MPVLib.INSTANCE.setPropertyString("vo", "null");
            MPVLib.INSTANCE.setOptionString("force-window", "no");
            MPVLib.INSTANCE.detachSurface();
        } catch (RuntimeException e) {
            MpvLogCollector.logError("MpvPlayer", "分离Surface异常: " + e.getMessage());
        }
        if (releaseOwned && ownsSurface) {
            attachedSurface.release();
        }
        attachedSurface = null;
        ownsSurface = false;
    }

    private void updateSurfaceSize(int width, int height) {
        if ((width <= 0 || height <= 0) && surfaceSize.getWidth() > 0 && surfaceSize.getHeight() > 0) {
            width = surfaceSize.getWidth();
            height = surfaceSize.getHeight();
        }
        if (width > 0 && height > 0) {
            boolean changed = surfaceSize.getWidth() != width || surfaceSize.getHeight() != height;
            surfaceSize = new Size(width, height);
            if (attachedSurface != null && attachedSurface.isValid()) {
                MPVLib.INSTANCE.setPropertyString("android-surface-size", width + "x" + height);
                if (changed) MpvLogCollector.log("MpvPlayer", "Surface尺寸更新: " + width + "x" + height);
            }
        }
        invalidateOnApplicationThread();
    }

    private void fail(PlaybackException exception) {
        loading = false;
        playerError = exception;
        playbackState = Player.STATE_IDLE;
        invalidateState();
    }

    private boolean command(String... args) {
        try {
            MPVLib.INSTANCE.command(args);
            return true;
        } catch (RuntimeException e) {
            runOnApplicationThread(() -> fail(new PlaybackException(e.getMessage(), e, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK)));
            return false;
        }
    }

    private int resolveStartIndex(List<MediaItem> mediaItems, int startIndex) {
        return startIndex >= 0 && startIndex < mediaItems.size() ? startIndex : 0;
    }

    private long getBufferedPositionMs() {
        if (bufferedPositionMs != C.TIME_UNSET) return bufferedPositionMs;
        if (durationMs != C.TIME_UNSET) return durationMs;
        return Math.max(0, positionMs);
    }

    private int getInt(String property, int fallback) {
        Integer value = MPVLib.INSTANCE.getPropertyInt(property);
        return value == null ? fallback : value;
    }

    private static int toTrackType(String type) {
        return switch (type) {
            case "video" -> C.TRACK_TYPE_VIDEO;
            case "audio" -> C.TRACK_TYPE_AUDIO;
            case "sub" -> C.TRACK_TYPE_TEXT;
            default -> C.TRACK_TYPE_UNKNOWN;
        };
    }

    private static String trackGroupId(int type, int id) {
        return "mpv:" + type + ":" + id;
    }

    private static String getSampleMimeType(int type, @Nullable String codec) {
        String normalized = codec == null ? "" : codec.toLowerCase(Locale.US);
        return switch (type) {
            case C.TRACK_TYPE_VIDEO -> switch (normalized) {
                case "h264", "avc1" -> MimeTypes.VIDEO_H264;
                case "h265", "hevc", "hev1" -> MimeTypes.VIDEO_H265;
                case "av1" -> MimeTypes.VIDEO_AV1;
                case "vp8" -> MimeTypes.VIDEO_VP8;
                case "vp9" -> MimeTypes.VIDEO_VP9;
                case "mpeg2video" -> MimeTypes.VIDEO_MPEG2;
                case "mpeg4" -> MimeTypes.VIDEO_MP4V;
                default -> MimeTypes.VIDEO_UNKNOWN;
            };
            case C.TRACK_TYPE_AUDIO -> switch (normalized) {
                case "aac" -> MimeTypes.AUDIO_AAC;
                case "ac3" -> MimeTypes.AUDIO_AC3;
                case "eac3" -> MimeTypes.AUDIO_E_AC3;
                case "dts" -> MimeTypes.AUDIO_DTS;
                case "flac" -> MimeTypes.AUDIO_FLAC;
                case "mp3" -> MimeTypes.AUDIO_MPEG;
                case "opus" -> MimeTypes.AUDIO_OPUS;
                case "vorbis" -> MimeTypes.AUDIO_VORBIS;
                default -> MimeTypes.AUDIO_UNKNOWN;
            };
            case C.TRACK_TYPE_TEXT -> switch (normalized) {
                case "ass", "ssa" -> MimeTypes.TEXT_SSA;
                case "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP;
                case "webvtt", "vtt" -> MimeTypes.TEXT_VTT;
                case "mov_text", "tx3g" -> MimeTypes.APPLICATION_TX3G;
                case "hdmv_pgs_subtitle" -> MimeTypes.APPLICATION_PGS;
                case "dvb_subtitle" -> MimeTypes.APPLICATION_DVBSUBS;
                default -> MimeTypes.TEXT_UNKNOWN;
            };
            default -> MimeTypes.APPLICATION_OCTET_STREAM;
        };
    }

    /** Best-effort field read from extended-JNI END_FILE node payload (v0.0.3 only). */
    private static String nodeString(@Nullable MPVNode node, String key) {
        if (node == null) return "";
        try {
            MPVNode child = node.get(key);
            String value = child == null ? null : child.asString();
            return value == null ? "" : value;
        } catch (Throwable e) {
            return "";
        }
    }

    private static int nodeInt(@Nullable MPVNode node, String key, int fallback) {
        if (node == null) return fallback;
        try {
            MPVNode child = node.get(key);
            Long value = child == null ? null : child.asInt();
            if (value != null) return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
            Double d = child == null ? null : child.asDouble();
            return d == null ? fallback : (int) Math.round(d);
        } catch (Throwable e) {
            return fallback;
        }
    }

    private static @Nullable String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    private static long secondsToMs(double seconds, long fallback) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) return fallback;
        return (long) Math.ceil(seconds * 1000.0);
    }

    private static String seconds(long milliseconds) {
        return String.format(Locale.US, "%.3f", milliseconds / 1000.0);
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean isHlsUrl(String url) {
        return !TextUtils.isEmpty(url) && url.toLowerCase(Locale.US).contains(".m3u8");
    }

    private static boolean isHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static boolean shouldDeferInitialSeek(String url) {
        return isHlsUrl(url) || isHttpUrl(url);
    }

    private void runOnApplicationThread(Runnable runnable) {
        if (Looper.myLooper() == getApplicationLooper()) runnable.run();
        else App.post(runnable);
    }

    private void invalidateOnApplicationThread() {
        runOnApplicationThread(() -> {
            if (!closed) invalidateState();
        });
    }

    private void refreshTracksOnApplicationThread() {
        runOnApplicationThread(() -> {
            if (closed) return;
            readTracks();
            invalidateState();
        });
    }
}
