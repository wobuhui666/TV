package com.fongmi.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaChapter;
import androidx.media3.common.MediaEdition;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.ui.danmaku.DanmakuConfig;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.cache.CacheLease;
import com.fongmi.android.tv.cache.CacheManager;
import com.github.catvod.crawler.diagnostics.DiagnosticLog;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlayerEngineFactory;
import com.fongmi.android.tv.player.audio.AudioTrackRecovery;
import com.fongmi.android.tv.player.media.MediaUrlGuard;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.mpv.MpvPlayerEngine;
import com.fongmi.android.tv.player.parse.ParseJob;
import com.fongmi.android.tv.player.track.TrackUtil;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.MpvLogCollector;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlayerManager implements ParseCallback, TimeshiftPlayback.Host {

    private final Runnable runnable;
    private final Callback callback;
    private final CacheLease cacheLease;
    private final TimeshiftPlayback timeshift;
    private PlayerEngine engine;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private Sub pendingPrimarySubtitle;
    private Format pendingSecondarySubtitle;

    private DanmakuConfig danmakuConfig;
    private long pendingStartPositionMs;
    private boolean danmakuEnabled;
    private boolean initTrack;
    private boolean forcePlatformEngine;
    private int retry;
    private int decode;
    private boolean softwareEngineFallback;
    private int audioRecoveryAttempts;
    private String audioRecoveryKey;
    private boolean pendingTimeshiftRebuild;

    public PlayerManager(Callback callback) {
        this.callback = callback;
        this.runnable = this::onPlayTimeout;
        this.decode = PlayerEngine.HARD;
        this.cacheLease = CacheManager.playbackLease();
        try {
            this.engine = PlayerEngineFactory.create(decode, listener);
        } catch (RuntimeException | Error failure) {
            cacheLease.close();
            throw failure;
        }
        this.player = engine.getPlayer();
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.danmakuConfig = DanmakuSetting.getConfig();
        this.danmakuEnabled = DanmakuSetting.isShow();
        this.timeshift = new TimeshiftPlayback(this);
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void release() {
        timeshift.clear();
        stopParse();
        App.removeCallbacks(runnable);
        if (player != null) player.removeListener(listener);
        try {
            if (engine != null) engine.release();
        } finally {
            engine = null;
            player = null;
            cacheLease.close();
            DiagnosticLog.record("playback", "released");
        }
    }

    public Player getPlayer() {
        return player;
    }

    public PlaybackSpecs getPlaybackSpecs() {
        return engine == null ? PlaybackSpecs.EMPTY : engine.getPlaybackSpecs();
    }

    public TimeshiftPlayback getTimeshift() {
        return timeshift;
    }

    public boolean canSeek() {
        return timeshift.isActive() || !isLive() && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM);
    }

    public boolean canPreload() {
        return engine != null && engine.getType() == PlayerEngine.Type.EXO && !PlayerSetting.isMpv() && !softwareEngineFallback;
    }

    public boolean preload(Result result, long position, MediaMetadata metadata) {
        if (result.getTimeshift() != null || !canPreload() || result.needParse() || result.isUseParse() || result.getDrm() != null) return false;
        PlaySpec next = PlaySpec.from(result, getKey(), metadata).checkUa();
        return engine.preload(next, position);
    }

    public void clearPreload() {
        if (engine != null) engine.clearPreload();
    }

    public void bindPlayerView(androidx.media3.ui.PlayerView view) {
        if (engine != null) engine.bindPlayerView(view);
    }

    public String getSecondaryTrack() { return engine == null ? null : engine.getSecondaryTrack(); }

    public void setSecondaryTrack(Track track) {
        if (engine == null) return;
        String format = track == null || !track.isSelected() ? null : track.getFormat();
        engine.setSecondaryTrack(format);
        String key = getKey() + "|secondary";
        if (format == null) Track.delete(key);
        else track.key(key).save();
    }

    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    public List<MediaChapter> getCurrentMediaChapters() {
        return player.getCurrentMediaChapters();
    }

    public List<MediaEdition> getCurrentMediaEditions() {
        return player.getCurrentMediaEditions();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    private void setDanmakus(List<Danmaku> items) {
        if (spec != null) spec.setDanmaku(getSelectedDanmaku(items));
        notifyDanmakuSourceChanged();
    }

    private void notifyDanmakuSourceChanged() {
        callback.onDanmakuSourceChanged(getSelectedDanmakuUri());
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return timeshift.isActive() || engine.isLive();
    }

    public boolean isVod() {
        return !timeshift.isActive() && engine.isVod();
    }

    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    public boolean haveEdition() {
        return !getCurrentMediaEditions().isEmpty();
    }

    public boolean haveChapter() {
        return !getCurrentMediaChapters().isEmpty();
    }

    public boolean haveDanmaku() {
        return !getSelectedDanmaku().isEmpty();
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return player == null ? 0 : player.getVideoSize().width;
    }

    public int getVideoHeight() {
        return player == null ? 0 : player.getVideoSize().height;
    }

    public long getPosition() {
        return timeshift.isActive() ? timeshift.getPosition() : player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    public int getEngine() {
        return engine.getType() == PlayerEngine.Type.MPV ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
    }

    public void setEngine(int targetEngine) {
        int oldEngine = getEngine();
        forcePlatformEngine = false;
        PlayerSetting.putEngine(targetEngine);
        MpvLogCollector.log("PlayerManager", "切换播放器内核: " + engineName(oldEngine) + " -> " + engineName(targetEngine) + ", isEmpty=" + isEmpty());
        if (oldEngine == targetEngine || isEmpty()) return;
        // Capture position before engine teardown; after ensureEngine the old player is gone.
        long position = getPosition();
        reset(); // cancel play-timeout so a slow MPV teardown cannot fire onPlayTimeout → fallback
        restartCurrent(position, false);
    }

    public String getPositionTime(long delta) {
        return Util.timeMs(Math.clamp(getPosition() + delta, 0, Math.max(0, getDuration())));
    }

    public long getDuration() {
        return timeshift.isActive() ? timeshift.getDuration() : player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        setSub(sub, false);
    }

    /** Online subtitle selection must not resume a paused video or change its speed. */
    public void setSub(Sub sub, boolean preservePlaybackState) {
        if (sub == null || sub.isEmpty() || engine == null || player == null) return;
        pendingPrimarySubtitle = null;
        pendingSecondarySubtitle = null;
        if (preservePlaybackState && engine.getType() == PlayerEngine.Type.EXO) {
            pendingPrimarySubtitle = sub;
            String selected = engine.getSecondaryTrack();
            for (Tracks.Group group : getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    if (PlayerHelper.describeFormat(format).equals(selected)) pendingSecondarySubtitle = format;
                }
            }
        }
        if (spec != null) spec.setSub(sub);
        boolean playing = player.getPlayWhenReady();
        if (engine.addSubtitle(sub)) {
            if (preservePlaybackState) player.setPlayWhenReady(playing);
            else play();
        } else if (preservePlaybackState) restartCurrent(getPosition(), false);
        else startCurrent();
    }

    private void restoreSelectedSubtitle(Tracks tracks) {
        if (pendingPrimarySubtitle != null) {
            Tracks.Group target = null;
            int index = C.INDEX_UNSET;
            int matches = 0;
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    if (!pendingPrimarySubtitle.getName().equals(format.label)
                            || (format.selectionFlags & C.SELECTION_FLAG_FORCED) == 0) continue;
                    target = group;
                    index = i;
                    matches++;
                }
            }
            if (matches == 1) {
                // A manual choice takes priority over automatic language/default preferences.
                pendingPrimarySubtitle = null;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(new TrackSelectionOverride(target.getMediaTrackGroup(), List.of(index)))
                        .build());
            }
        }
        if (pendingSecondarySubtitle != null) {
            Format identity = subtitleIdentity(pendingSecondarySubtitle);
            Format target = null;
            int matches = 0;
            for (Tracks.Group group : tracks.getGroups()) {
                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                for (int i = 0; i < group.length; i++) {
                    Format format = group.getTrackFormat(i);
                    if (!identity.equals(subtitleIdentity(format))) continue;
                    target = format;
                    matches++;
                }
            }
            if (matches == 1) {
                // Adding an external source changes Media3's source prefix and selection flags.
                pendingSecondarySubtitle = null;
                Track track = new Track(C.TRACK_TYPE_TEXT, target.label, PlayerHelper.describeFormat(target));
                track.setSelected(true);
                setSecondaryTrack(track);
            }
        }
    }

    private static Format subtitleIdentity(Format format) {
        return format.buildUpon().setId(null).setSelectionFlags(0).build();
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        startCurrent();
    }

    public void selectChapter(MediaChapter chapter) {
        player.selectChapter(chapter);
    }

    public void selectEdition(MediaEdition edition) {
        player.selectEdition(edition);
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        danmakuConfig = config;
        callback.onDanmakuConfigChanged(danmakuConfig);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuEnabled == enabled) return;
        danmakuEnabled = enabled;
        callback.onDanmakuEnabledChanged(danmakuEnabled);
    }

    public void sendDanmaku(String text) {
        callback.onDanmakuSent(text);
    }

    public String setSpeed(float speed) {
        if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
        player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float step = speed >= 2 ? 1f : 0.25f;
        return setSpeed(speed >= 5 ? 0.25f : Math.min(speed + step, 5.0f));
    }

    public String addSpeed(float value) {
        return setSpeed(Math.clamp(getSpeed() + value, 0.25f, 5.0f));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.clamp(getSpeed() - value, 0.25f, 5.0f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? PlayerSetting.getSpeed() : 1);
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) TrackUtil.setTrackSelection(player, tracks);
    }

    public void setSubtitleStyle() {
        if (engine != null) engine.setSubtitleStyle();
    }

    public void play() {
        player.play();
    }

    public void pause() {
        player.pause();
    }

    public void stop() {
        pendingTimeshiftRebuild = false;
        timeshift.clear();
        App.removeCallbacks(runnable);
        engine.stop();
        stopParse();
    }

    public void clearMediaItems() {
        timeshift.clear();
        player.clearMediaItems();
    }

    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    public void replay(long positionMs) {
        if (timeshift.isActive()) timeshift.seekTo(positionMs);
        else if (positionMs == C.TIME_UNSET) player.seekToDefaultPosition();
        else player.seekTo(positionMs);
        player.play();
    }

    public void seekTo(long time) {
        if (timeshift.isActive()) timeshift.seekTo(time);
        else player.seekTo(time);
    }

    public long getTextOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_TEXT_OFFSET) ? player.getTextOffsetMs() : 0;
    }

    public void setTextOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_TEXT_OFFSET)) player.setTextOffsetMs(offsetMs);
    }

    public long getAudioOffsetMs() {
        return player.isCommandAvailable(Player.COMMAND_GET_AUDIO_OFFSET) ? player.getAudioOffsetMs() : 0;
    }

    public void setAudioOffsetMs(long offsetMs) {
        if (player.isCommandAvailable(Player.COMMAND_SET_AUDIO_OFFSET)) player.setAudioOffsetMs(offsetMs);
    }

    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        pendingPrimarySubtitle = null;
        pendingSecondarySubtitle = null;
        pendingTimeshiftRebuild = false;
        timeshift.clear();
        spec = null;
        forcePlatformEngine = false;
    }

    public void resetTrack() {
        TrackUtil.reset(player);
    }

    public void toggleDecode() {
        decode = isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD;
        if (forcePlatformEngine) {
            long position = Math.max(0, getPosition());
            forcePlatformEngine = false;
            MpvLogCollector.log("PlayerManager", "切换解码模式并退出 Dolby Vision 平台转交");
            callback.onDecodeChanged();
            restartCurrent(position, false);
            return;
        }
        boolean rebuild = engine.setDecode(decode);
        MpvLogCollector.log("PlayerManager", "切换解码模式: " + (decode == PlayerEngine.HARD ? "硬解" : "软解") + ", rebuild=" + rebuild);
        callback.onDecodeChanged();
        if (!rebuild) return;
        long position = isLive() && !timeshift.isReplaying() ? C.TIME_UNSET : getPosition();
        restartCurrent(position, true);
    }

    /** Rebuilds the renderer/AudioSink so the AI PCM tap and lookahead buffer change immediately. */
    public void rebuildAudioPipeline() {
        if (engine == null || spec == null || player == null) return;
        long position = player.isCurrentMediaItemLive() && !timeshift.isReplaying() ? C.TIME_UNSET : getPosition();
        restartCurrent(position, true);
    }

    public void refreshAudioEffects() {
        if (engine == null) return;
        if (engine.getType() == PlayerEngine.Type.MPV) engine.applyAudioEffects();
        else rebuildAudioPipeline();
    }

    public void refreshSubtitles() {
        if (engine == null) return;
        if (engine.getType() == PlayerEngine.Type.EXO) rebuildAudioPipeline();
        setSubtitleStyle();
    }

    /**
     * Exo still reports the audio track when it cannot decode it, but then keeps the audio renderer
     * disabled: the video plays, no error is raised and the user hears nothing. Recover once per
     * media item instead of playing silent video.
     */
    private void recoverUnrenderedAudio(Tracks tracks) {
        if (engine == null || spec == null || player == null) return;
        String key = getKey();
        if (TextUtils.isEmpty(key)) return;
        if (!key.equals(audioRecoveryKey)) {
            audioRecoveryKey = key;
            audioRecoveryAttempts = 0;
        }
        boolean audioDisabledByUser = player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO);
        boolean exo = engine.getType() == PlayerEngine.Type.EXO;
        AudioTrackRecovery.Action action = AudioTrackRecovery.decide(tracks, audioDisabledByUser, exo,
                exo && MpvPlayerEngine.isAvailable(), audioRecoveryAttempts);
        if (action == AudioTrackRecovery.Action.NONE) return;
        audioRecoveryAttempts++;
        MpvLogCollector.log("PlayerManager", "音轨无可用解码器: action=" + action + ", attempts=" + audioRecoveryAttempts + ", key=" + key);
        switch (action) {
            case RESELECT_TRACK -> reselectAudioTrack();
            case SWITCH_ENGINE -> switchToSoftwareEngine();
            case NOTIFY_ONLY -> Notify.show(R.string.error_audio_track_undecodable);
            default -> {
            }
        }
    }

    /**
     * Clears the saved/forced audio selection so the normal selection can pick a track the device
     * can actually decode, and forgets that selection because it cannot be played here.
     */
    private void reselectAudioTrack() {
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon();
        builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO);
        builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
        player.setTrackSelectionParameters(builder.build());
        Track.delete(getKey());
        Notify.show(R.string.error_audio_track_reselect);
        App.post(() -> recoverUnrenderedAudio(player.getCurrentTracks()), 800);
    }

    private void switchToSoftwareEngine() {
        long position = Math.max(0, getPosition());
        softwareEngineFallback = true;
        Notify.show(R.string.error_audio_track_fallback);
        reset();
        restartCurrent(position, false);
    }

    private void handleDecodeError(PlaybackException e) {
        if (++retry > 1) {
            callback.onError(engine.getErrorMessage(e));
        } else {
            Notify.show(R.string.error_decode_fallback);
            toggleDecode();
        }
    }

    private void handleTunnelError(PlaybackException e) {
        long position = Math.max(0, getPosition());
        MpvLogCollector.logError("PlayerManager", "隧道播放失败，关闭后重试: errorCode=" + e.errorCode);
        PlayerSetting.putTunnel(false);
        Notify.show(R.string.error_tunnel_fallback);
        restartCurrent(position, true);
    }

    private void handlePlatformDecoderFallback(PlaybackException e) {
        long position = Math.max(0, getPosition());
        forcePlatformEngine = true;
        MpvLogCollector.log("PlayerManager", "MPV Dolby Vision 硬解转交平台解码器: " + e.getMessage());
        Notify.show(R.string.error_dolby_fallback);
        restartCurrent(position, false);
    }

    private boolean isHard() {
        return decode == PlayerEngine.HARD;
    }

    private void onPlayTimeout() {
        if (retryWithSoftwareEngine()) return;
        stop();
        callback.onError(ResUtil.getString(R.string.error_play_timeout));
    }

    /**
     * Some formats never reach READY on Exo (a Matroska DTS stream did not), so the audio recovery
     * never sees a track to react to. Retry that item once on the software engine instead of failing
     * the user with "Timed out"; setMediaItem re-arms this timeout for the retry.
     */
    private boolean retryWithSoftwareEngine() {
        if (engine == null || spec == null || TextUtils.isEmpty(spec.getUrl())) return false;
        boolean exo = engine.getType() == PlayerEngine.Type.EXO;
        if (!AudioTrackRecovery.shouldRetryOnTimeout(exo, MpvPlayerEngine.isAvailable(), softwareEngineFallback)) return false;
        MpvLogCollector.log("PlayerManager", "Exo 播放超时, 改用 MPV 引擎重试: " + getKey());
        softwareEngineFallback = true;
        Notify.show(R.string.error_play_timeout_mpv);
        restartCurrent(Math.max(0, getPosition()), false);
        return true;
    }

    private void ensureEngine(PlaySpec spec) {
        if (forcePlatformEngine && engine.getType() == PlayerEngine.Type.EXO) return;
        if (softwareEngineFallback && engine.getType() == PlayerEngine.Type.MPV) return;
        if (!forcePlatformEngine && !softwareEngineFallback && PlayerEngineFactory.matches(engine, spec)) return;
        PlayerEngine old = engine;
        androidx.media3.common.PlaybackParameters parameters = player.getPlaybackParameters();
        String targetEngine = forcePlatformEngine ? "EXO (Dolby Vision)"
                : softwareEngineFallback ? "MPV (音轨回退)" : engineName(PlayerSetting.getEngine());
        MpvLogCollector.log("PlayerManager", "重建播放器实例: " + engineName(old.getType()) + " -> " + targetEngine);
        // Release first so MPV enters DESTROYING before a replacement is selected.
        // The factory immediately uses Exo while native teardown is still in progress.
        try {
            player.removeListener(listener);
        } catch (Throwable ignored) {
        }
        try {
            old.release();
        } catch (Throwable e) {
            MpvLogCollector.logError("PlayerManager", "旧引擎 release 异常: " + e.getMessage());
        }
        engine = forcePlatformEngine
                ? PlayerEngineFactory.createPlatform(decode, listener)
                : softwareEngineFallback
                ? PlayerEngineFactory.createMpv(decode, listener)
                : PlayerEngineFactory.create(decode, spec, listener);
        if (softwareEngineFallback && engine.getType() != PlayerEngine.Type.MPV) {
            // The factory already reported why MPV was unavailable; do not retry it for every item.
            softwareEngineFallback = false;
        }
        setPlayer(engine.getPlayer());
        restorePlaybackParameters(parameters);
    }

    private void restartCurrent(long positionMs, boolean rebuild) {
        if (timeshift.isActive()) {
            // Resolve first: a failed replay URL must not tear down the working stream.
            pendingTimeshiftRebuild |= rebuild;
            timeshift.seekTo(positionMs);
            return;
        }
        androidx.media3.common.PlaybackParameters parameters = player.getPlaybackParameters();
        boolean playing = player.getPlayWhenReady();
        if (rebuild) setPlayer(engine.rebuild());
        startCurrent(positionMs);
        // Both engines start a new item playing. Restore after start, including a paused
        // Syncplay session or an audio/subtitle settings change made while paused.
        restorePlaybackParameters(parameters);
        player.setPlayWhenReady(playing);
    }

    private void restorePlaybackParameters(androidx.media3.common.PlaybackParameters parameters) {
        if (player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) player.setPlaybackParameters(parameters);
    }

    private void setPlayer(Player player) {
        this.player = player;
        MpvLogCollector.log("PlayerManager", "回调播放器重绑: " + player.getClass().getSimpleName());
        callback.onPlayerRebuild(player);
    }

    private String engineName(int engine) {
        return engine == PlayerSetting.ENGINE_MPV ? "MPV" : "EXO";
    }

    private String engineName(PlayerEngine.Type type) {
        return type == PlayerEngine.Type.MPV ? "MPV" : "EXO";
    }

    public void browse(PlaySpec spec, long startPositionMs) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY, startPositionMs);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, long startPositionMs) {
        stopParse();
        pendingPrimarySubtitle = null;
        pendingSecondarySubtitle = null;
        DiagnosticLog.record("playback", "request startPositionMs=" + startPositionMs + " decode=" + decode);
        forcePlatformEngine = false;
        // A new media item is decoded by Exo again: only a track that again cannot be rendered
        // moves this item to the software engine, so playable files keep Exo's own pipeline.
        softwareEngineFallback = false;
        audioRecoveryAttempts = 0;
        audioRecoveryKey = null;
        retry = 0;
        this.spec = spec;
        pendingTimeshiftRebuild = false;
        timeshift.start(spec);
        setMediaItem(timeout, timeshift.isActive() ? C.TIME_UNSET : startPositionMs);
        if (timeshift.isActive() && startPositionMs != C.TIME_UNSET) timeshift.seekTo(startPositionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs) {
        pendingPrimarySubtitle = null;
        pendingSecondarySubtitle = null;
        stopParse();
        forcePlatformEngine = false;
        pendingStartPositionMs = startPositionMs;
        retry = 0;
        pendingTimeshiftRebuild = false;
        timeshift.clear();
        spec = PlaySpec.fromParse(result, key, metadata);
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
        pendingStartPositionMs = C.TIME_UNSET;
    }

    private void setMediaItem(long timeout, long startPositionMs) {
        if (spec == null || spec.getUrl() == null) return;
        if (MediaUrlGuard.shouldReject(spec.getUrl(), spec.getFormat())) {
            rejectMediaItem(spec);
            return;
        }
        ensureEngine(spec.checkUa());
        engine.start(spec, startPositionMs);
        setDanmakus(spec.getDanmakus());
        App.post(runnable, timeout);
        callback.onPrepare();
        initTrack = false;
    }

    private void rejectMediaItem(PlaySpec spec) {
        App.removeCallbacks(runnable);
        engine.stop();
        stopParse();
        MpvLogCollector.logError("PlayerManager", "拒绝非视频播放地址: key=" + spec.getKey() + ", format=" + spec.getFormat() + ", url=" + spec.getUrl());
        callback.onError(ResUtil.getString(R.string.error_play_url));
    }

    private void startCurrent() {
        startCurrent(getPosition());
    }

    private void startCurrent(long startPositionMs) {
        if (timeshift.isActive()) timeshift.seekTo(startPositionMs);
        else setMediaItem(Constant.TIMEOUT_PLAY, startPositionMs);
    }

    @Override
    public void applyTimeshift(String url, String format, Map<String, String> headers) {
        if (spec == null || player == null) return;
        boolean playing = player.getPlayWhenReady();
        androidx.media3.common.PlaybackParameters parameters = player.getPlaybackParameters();
        PlaySpec previous = spec;
        long position = player.getCurrentPosition();
        PlaySpec replacement = spec.withSource(url, format, new HashMap<>(headers)).checkUa();
        // Validate the complete media item before replacing the current source.
        MediaItemFactory.from(replacement);
        // Resolving another position is still the same logical stream. In particular a
        // decoder fallback must keep its retry budget until playback reaches READY.
        App.removeCallbacks(runnable);
        clearPreload();
        spec = replacement;
        boolean rebuild = pendingTimeshiftRebuild;
        pendingTimeshiftRebuild = false;
        try {
            if (rebuild) setPlayer(engine.rebuild());
            setMediaItem(Constant.TIMEOUT_PLAY, C.TIME_UNSET);
        } catch (RuntimeException failure) {
            spec = previous;
            try {
                setMediaItem(Constant.TIMEOUT_PLAY, position);
            } catch (RuntimeException recoveryFailure) {
                failure.addSuppressed(recoveryFailure);
            }
            throw failure;
        } finally {
            restorePlaybackParameters(parameters);
            player.setPlayWhenReady(playing);
        }
    }

    @Override
    public void onTimeshiftChanged() {
        callback.onTimeshiftChanged();
    }

    @Override
    public void onTimeshiftError() {
        pendingTimeshiftRebuild = false;
        Notify.show(R.string.timeshift_resolve_failed);
    }

    private Danmaku getSelectedDanmaku(List<Danmaku> items) {
        if (items == null || items.isEmpty()) return Danmaku.empty();
        return items.stream().filter(Danmaku::isSelected).findFirst().orElse(items.get(0));
    }

    public Danmaku getSelectedDanmaku() {
        return getSelectedDanmaku(getDanmakus());
    }

    public Uri getSelectedDanmakuUri() {
        Uri uri = getSelectedDanmaku().getUri();
        // Timed file-based comments keep their normal playback clock. Live subscriptions must
        // not show the current broadcast's comments while an earlier point is replayed.
        String scheme = uri == null ? "" : uri.getScheme();
        boolean realtime = "spider".equalsIgnoreCase(scheme) || "ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme);
        return timeshift.isReplaying() && realtime ? null : uri;
    }

    public void setDanmaku(Danmaku item) {
        if (spec == null) return;
        spec.setDanmaku(item);
        notifyDanmakuSourceChanged();
    }

    public void addDanmaku(Danmaku item) {
        if (spec != null) spec.addDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        // PlaySpec.checkUa() copies and sanitizes the map before it reaches any data source.
        // Keep the parser-owned map untouched because some parsers return immutable maps.
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        if (spec != null) timeshift.start(spec);
        setMediaItem(Constant.TIMEOUT_PLAY, timeshift.isActive() ? C.TIME_UNSET : pendingStartPositionMs);
        if (timeshift.isActive() && pendingStartPositionMs != C.TIME_UNSET) timeshift.seekTo(pendingStartPositionMs);
        pendingStartPositionMs = C.TIME_UNSET;
    }

    @Override
    public void onParseError() {
        DiagnosticLog.record("parse", "failed");
        pendingStartPositionMs = C.TIME_UNSET;
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        default void onTimeshiftChanged() {
        }

        void onPrepare();

        void onTracksChanged();

        void onDecodeChanged();

        void onMediaOptionsChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);

        void onDanmakuSourceChanged(Uri uri);

        void onDanmakuConfigChanged(DanmakuConfig config);

        void onDanmakuEnabledChanged(boolean enabled);

        void onDanmakuSent(String text);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            DiagnosticLog.record("playback", "state=" + state + " engine=" + (engine == null ? "none" : engine.getType()));
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) App.removeCallbacks(runnable);
            if (state == Player.STATE_READY) {
                if (timeshift.isActive()) retry = 0;
                timeshift.onReady();
            }
            if (state == Player.STATE_ENDED && timeshift.isActive()) {
                timeshift.seekTo(C.TIME_UNSET);
                player.play();
            }
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty()) return;
            if (!initTrack) {
                initTrack = true;
                setTrack(Track.find(getKey()));
                List<Track> secondary = Track.find(getKey() + "|secondary");
                engine.setSecondaryTrack(secondary.isEmpty() ? null : secondary.get(0).getFormat());
            }
            restoreSelectedSubtitle(tracks);
            callback.onTracksChanged();
            initTrack = true;
            recoverUnrenderedAudio(tracks);
        }

        @Override
        public void onMediaChaptersChanged(@NonNull List<MediaChapter> chapters) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onMediaEditionsChanged(@NonNull List<MediaEdition> editions) {
            callback.onMediaOptionsChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            DiagnosticLog.record("playback", "errorCode=" + e.errorCode);
            DiagnosticLog.record("playback", e);
            if (spec == null) return;
            PlayerEngine.ErrorAction action = engine.handleError(e);
            if (action != PlayerEngine.ErrorAction.RECOVERED) App.removeCallbacks(runnable);
            switch (action) {
                case DECODE -> handleDecodeError(e);
                case TUNNEL -> handleTunnelError(e);
                case PLATFORM -> handlePlatformDecoderFallback(e);
                case RECOVERED -> setDanmakus(spec.getDanmakus());
                case FATAL -> callback.onError(engine.getErrorMessage(e));
            }
        }
    };

}
