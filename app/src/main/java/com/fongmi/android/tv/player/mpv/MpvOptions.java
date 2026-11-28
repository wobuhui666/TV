package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.util.PlayerHelper;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.MpvLogCollector;
import com.github.catvod.utils.Path;

import java.io.File;

import is.xyz.mpv.MPVLib;

/**
 * Central MPV option funnel (self-hosted), behaviour aligned with FongMi {@code MpvUtil}:
 * <ul>
 *   <li>Vulkan → only pre-init {@code gpu-api=vulkan} + {@code gpu-context=androidvk}</li>
 *   <li>{@code gpu-next} only selects {@code vo}, never forced by Vulkan</li>
 *   <li>config-dir / font cache / TLS CA / demuxer defaults</li>
 * </ul>
 * Does <b>not</b> import {@code androidx.media3.mpvplayer}.
 */
final class MpvOptions {

    static final String HWDEC_HARD = "mediacodec,mediacodec-copy";
    private static final String HWDEC_CODECS = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1";
    private static final long DEFAULT_DEMUXER_BYTES = 64L * 1024L * 1024L;

    private MpvOptions() {
    }

    /**
     * FongMi: gpu-next is opt-in only; Vulkan does not force it.
     * HDR=on implies gpu-next because {@code target-colorspace-hint} is only implemented there.
     */
    static String videoOutputDriver() {
        if (PlayerSetting.isMpvGpuNext()) return "gpu-next";
        if (PlayerSetting.getMpvHdr() == PlayerSetting.MPV_HDR_ON) return "gpu-next";
        return "gpu";
    }

    static String decodeMode(int decode) {
        return decode == PlayerEngine.HARD ? HWDEC_HARD : "no";
    }

    /**
     * Options that must be set after {@code create} and before {@code init}.
     */
    static void applyPreInit(Context context, int decode) {
        File configDir = Path.mpv();
        File cacheDir = Path.mpvCache();
        MpvAssets.ensure(context, configDir);

        set("config", "yes");
        set("config-dir", configDir.getAbsolutePath());
        set("sub-fonts-dir", com.fongmi.android.tv.player.subtitle.SubtitleFonts.directory().getAbsolutePath());
        // MediaCodec reports its native colour format, stride and crop at FFmpeg INFO,
        // mapped to mpv's verbose level. Per-frame FFmpeg debug/trace stays disabled.
        set("msg-level", "all=warn,ffmpeg/video=v,vd=v,ao/audiotrack=v");
        set("gpu-shader-cache-dir", cacheDir.getAbsolutePath());
        set("icc-cache-dir", cacheDir.getAbsolutePath());
        set("profile", "fast");
        set("vo", videoOutputDriver());
        applyVideoOutputOptions();
        applyHdrHint();
        applyDecode(decode);
        set("hwdec-codecs", HWDEC_CODECS);
        set("ao", "audiotrack,opensles");
        set("audio-set-media-role", "yes");
        set("tls-verify", "yes");
        set("tls-ca-file", new File(context.getFilesDir(), "cacert.pem").getAbsolutePath());
        set("input-default-bindings", "yes");
        // FongMi: HLS HTTP persistent off
        set("demuxer-lavf-o", "http_persistent=0");
        applyDemuxerBudget();
        applyDefaultUserAgent();
        MpvLogCollector.log("MpvOptions", "pre-init vo=" + videoOutputDriver()
                + " vulkan=" + PlayerSetting.isMpvVulkan()
                + " gpu-next=" + PlayerSetting.isMpvGpuNext()
                + " decode=" + (decode == PlayerEngine.HARD ? "hard" : "soft"));
    }

    /**
     * Options / side effects after {@code init}.
     */
    static void applyPostInit(File configDir) {
        MpvAnime4K.apply(configDir);
        set("save-position-on-quit", "no");
        set("force-window", "no");
        // This embedded player reuses one native handle for stop/reload and decoder/VO changes.
        // idle=once shuts that handle down after the first stop, before loadfile can run.
        // Release remains explicit (quit followed by asynchronous native destroy).
        set("idle", "yes");
    }

    static void applyDecode(int decode) {
        String mode = decodeMode(decode);
        set("hwdec", mode);
        try {
            MPVLib.INSTANCE.setPropertyString("hwdec", mode);
        } catch (Throwable ignored) {
            // Property writes can fail before init/runtime is ready.
        }
    }

    static void applyPlaybackDefaults(int decode, String openGlDriver) {
        MPVLib.INSTANCE.setPropertyString("vo", openGlDriver == null ? videoOutputDriver() : openGlDriver);
        applyDecode(decode);
    }

    static void applyDolbyVisionSoftwareDecode(String driver) {
        MPVLib.INSTANCE.setPropertyString("hwdec", "no");
        MPVLib.INSTANCE.setPropertyString("vo", driver);
    }

    /** Recreate the VO in the existing native player, keeping demux/network options intact. */
    static void applyOpenGlVideoOutput(String driver) {
        if (!"gpu".equals(driver) && !"gpu-next".equals(driver)) throw new IllegalArgumentException("Unsupported compatible video output");
        checked("vo", "null");
        checked("gpu-api", "opengl");
        checked("gpu-context", "android");
        checked("opengl-es", "yes");
        checked("vo", driver);
    }

    private static void checked(String name, String value) {
        int result = MPVLib.INSTANCE.setOptionString(name, value);
        if (result < 0) throw new IllegalStateException("MPV video option rejected: " + name + " (" + result + ")");
    }

    /**
     * Vulkan: only gpu-api + androidvk (FongMi MpvUtil.addVideoOutputOptions).
     * GL path keeps opengl-es + android context.
     */
    private static void applyVideoOutputOptions() {
        if (PlayerSetting.isMpvVulkan()) {
            set("gpu-api", "vulkan");
            set("gpu-context", "androidvk");
        } else {
            set("opengl-es", "yes");
            set("gpu-context", "android");
        }
    }

    /**
     * {@code target-colorspace-hint} is only implemented by vo=gpu-next; HDR=on therefore
     * forces gpu-next via {@link #videoOutputDriver()}. Still a hint, not an "HDR mode" guarantee.
     */
    private static void applyHdrHint() {
        int mode = PlayerSetting.getMpvHdr();
        String hint = switch (mode) {
            case PlayerSetting.MPV_HDR_ON -> "yes";
            case PlayerSetting.MPV_HDR_OFF -> "no";
            default -> "auto";
        };
        try {
            set("target-colorspace-hint", hint);
            MpvLogCollector.log("MpvOptions", "target-colorspace-hint=" + hint + " (kernel optional)");
        } catch (Throwable e) {
            MpvLogCollector.log("MpvOptions", "HDR hint not applied: " + e.getMessage());
        }
    }

    private static void applyDemuxerBudget() {
        long bytes = DEFAULT_DEMUXER_BYTES;
        if (PreloadSetting.isPreload()) {
            long preload = PreloadSetting.getPreloadSizeBytes();
            if (preload > 0) bytes = Math.max(bytes, preload);
        }
        String value = Long.toString(bytes);
        set("demuxer-max-bytes", value);
        set("demuxer-max-back-bytes", value);
    }

    /** Configured UA, falling back to the app default; never null. */
    static String defaultUserAgent() {
        String ua = Setting.getUa();
        if (TextUtils.isEmpty(ua)) ua = PlayerHelper.getDefaultUa();
        return ua == null ? "" : ua;
    }

    private static void applyDefaultUserAgent() {
        String ua = defaultUserAgent();
        if (!TextUtils.isEmpty(ua)) set("user-agent", ua);
    }

    private static void set(String name, String value) {
        MPVLib.INSTANCE.setOptionString(name, value);
    }
}
