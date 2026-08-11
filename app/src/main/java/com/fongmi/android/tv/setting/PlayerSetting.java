package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

public class PlayerSetting {

    public static final int ENGINE_EXO = 0;
    public static final int ENGINE_MPV = 1;
    public static final int RENDER_SURFACE = 0;
    public static final int RENDER_TEXTURE = 1;
    public static final int MPV_ANIME4K_OFF = 0;
    public static final int MPV_ANIME4K_FAST_A = 1;
    public static final int MPV_ANIME4K_FAST_B = 2;
    public static final int MPV_ANIME4K_FAST_C = 3;
    public static final int MPV_ANIME4K_HIGH_A = 4;
    public static final int MPV_ANIME4K_HIGH_B = 5;
    public static final int MPV_ANIME4K_HIGH_C = 6;
    public static final int MIN_SCALE = 0;
    public static final int MAX_SCALE = 4;
    private static final int MIN_SIZE = 0;
    private static final int MAX_SIZE = 3;
    private static final int MIN_BACKGROUND = 0;
    private static final int MAX_BACKGROUND = 2;
    private static final int MIN_MPV_ANIME4K = MPV_ANIME4K_OFF;
    private static final int MAX_MPV_ANIME4K = MPV_ANIME4K_HIGH_C;
    private static final float MIN_SPEED = 2.0f;
    private static final float MAX_SPEED = 5.0f;

    public static int getEngine() {
        return Math.clamp(Prefers.getInt("player_engine", ENGINE_EXO), ENGINE_EXO, ENGINE_MPV);
    }

    public static void putEngine(int engine) {
        Prefers.put("player_engine", Math.clamp(engine, ENGINE_EXO, ENGINE_MPV));
        if (!isMpv() && isTunnel()) Prefers.put("render", RENDER_SURFACE);
    }

    public static boolean isMpv() {
        return getEngine() == ENGINE_MPV;
    }

    public static boolean isMpvGpuNext() {
        return Prefers.getBoolean("mpv_gpu_next");
    }

    public static void putMpvGpuNext(boolean gpuNext) {
        Prefers.put("mpv_gpu_next", gpuNext);
    }

    public static boolean isMpvVulkan() {
        return Prefers.getBoolean("mpv_vulkan");
    }

    public static void putMpvVulkan(boolean vulkan) {
        Prefers.put("mpv_vulkan", vulkan);
    }

    /** MPV HDR: 0=auto, 1=on, 2=off. Default auto. */
    public static final int MPV_HDR_AUTO = 0;
    public static final int MPV_HDR_ON = 1;
    public static final int MPV_HDR_OFF = 2;

    public static int getMpvHdr() {
        return Math.clamp(Prefers.getInt("mpv_hdr", MPV_HDR_AUTO), MPV_HDR_AUTO, MPV_HDR_OFF);
    }

    public static void putMpvHdr(int mode) {
        Prefers.put("mpv_hdr", Math.clamp(mode, MPV_HDR_AUTO, MPV_HDR_OFF));
    }

    public static int getMpvAnime4K() {
        return Math.clamp(Prefers.getInt("mpv_anime4k", MPV_ANIME4K_OFF), MIN_MPV_ANIME4K, MAX_MPV_ANIME4K);
    }

    public static void putMpvAnime4K(int anime4K) {
        Prefers.put("mpv_anime4k", Math.clamp(anime4K, MIN_MPV_ANIME4K, MAX_MPV_ANIME4K));
    }

    public static int getRender() {
        return Math.clamp(Prefers.getInt("render", RENDER_SURFACE), RENDER_SURFACE, RENDER_TEXTURE);
    }

    public static void putRender(int render) {
        Prefers.put("render", Math.clamp(render, RENDER_SURFACE, RENDER_TEXTURE));
        if (!isMpv() && isTunnel() && getRender() == RENDER_TEXTURE) Prefers.put("tunnel", false);
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("tunnel");
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("tunnel", tunnel);
        if (!isMpv() && tunnel) Prefers.put("render", RENDER_SURFACE);
    }

    public static boolean isTunnelingEnabled() {
        return isTunnel() && getRender() == RENDER_SURFACE;
    }

    public static int getSize() {
        return Math.clamp(Prefers.getInt("size", 2), MIN_SIZE, MAX_SIZE);
    }

    public static void putSize(int size) {
        Prefers.put("size", Math.clamp(size, MIN_SIZE, MAX_SIZE));
    }

    public static int getScale() {
        return Math.clamp(Prefers.getInt("scale"), MIN_SCALE, MAX_SCALE);
    }

    public static void putScale(int scale) {
        Prefers.put("scale", Math.clamp(scale, MIN_SCALE, MAX_SCALE));
    }

    public static int getBackground() {
        return Math.clamp(Prefers.getInt("background", 2), MIN_BACKGROUND, MAX_BACKGROUND);
    }

    public static void putBackground(int background) {
        Prefers.put("background", Math.clamp(background, MIN_BACKGROUND, MAX_BACKGROUND));
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static float getSpeed() {
        return Math.clamp(Prefers.getFloat("speed", 3), MIN_SPEED, MAX_SPEED);
    }

    public static void putSpeed(float speed) {
        Prefers.put("speed", Math.clamp(speed, MIN_SPEED, MAX_SPEED));
    }

    public static boolean isCaption() {
        return Prefers.getBoolean("caption");
    }

    public static void putCaption(boolean caption) {
        Prefers.put("caption", caption);
    }

    public static float getSubtitleTextSize() {
        return Prefers.getFloat("subtitle_text_size");
    }

    public static void putSubtitleTextSize(float value) {
        Prefers.put("subtitle_text_size", value);
    }

    public static float getSubtitlePosition() {
        return Prefers.getFloat("subtitle_position");
    }

    public static void putSubtitlePosition(float value) {
        Prefers.put("subtitle_position", value);
    }

    public static boolean hasCaption() {
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static boolean isAudioPassThrough() {
        return Prefers.getBoolean("audio_pass_through", true);
    }

    public static void putAudioPassThrough(boolean audioPassThrough) {
        Prefers.put("audio_pass_through", audioPassThrough);
    }

    public static boolean isLoudnessNormalization() {
        return Prefers.getBoolean("audio_loudness_normalization");
    }

    public static void putLoudnessNormalization(boolean enabled) {
        Prefers.put("audio_loudness_normalization", enabled);
    }

    public static int getAudioChannelMode() {
        return Math.clamp(Prefers.getInt("audio_channel_mode"), 0, 3);
    }

    public static void putAudioChannelMode(int mode) {
        Prefers.put("audio_channel_mode", Math.clamp(mode, 0, 3));
    }

    public static boolean isAudioPrefer() {
        return Prefers.getBoolean("audio_prefer");
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        Prefers.put("audio_prefer", audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return Prefers.getBoolean("video_prefer");
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        Prefers.put("video_prefer", videoPrefer);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("prefer_aac");
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("prefer_aac", preferAAC);
    }

    public static boolean isAv3a() {
        return Prefers.getBoolean("av3a");
    }

    public static void putAv3a(boolean av3a) {
        Prefers.put("av3a", av3a);
    }

    public static boolean isDv7HevcFallback() {
        return Prefers.getBoolean("dv7_hevc");
    }

    public static void putDv7HevcFallback(boolean dv7HevcFallback) {
        Prefers.put("dv7_hevc", dv7HevcFallback);
    }

    /** Uses the platform Dolby Vision decoder instead of MPV software decoding. */
    public static boolean isMpvDolbyHwdecEnabled() {
        return Prefers.getBoolean("dolby", true);
    }

    public static void putMpvDolbyHwdecEnabled(boolean enabled) {
        Prefers.put("dolby", enabled);
    }
}
