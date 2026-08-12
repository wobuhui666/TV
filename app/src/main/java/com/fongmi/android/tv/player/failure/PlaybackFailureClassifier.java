package com.fongmi.android.tv.player.failure;

import android.net.Uri;
import android.text.TextUtils;

import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.HttpDataSource;

import com.fongmi.android.tv.server.Server;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

public final class PlaybackFailureClassifier {

    private PlaybackFailureClassifier() {
    }

    public static PlaybackFailure classifyExo(PlaybackException error, String url) {
        return classify(error, url, false, 0);
    }

    public static PlaybackFailure classifyMpv(PlaybackException error, String url, int fileError) {
        if (fileError == 13) return failure(PlaybackFailure.Category.SOURCE, "播放地址无权限访问，请更换线路", error, fileError, false, true);
        if (fileError == 2) return failure(PlaybackFailure.Category.SOURCE, "播放地址不存在，请更换线路", error, fileError, false, true);
        if (fileError == -14 || fileError == -15) return failure(PlaybackFailure.Category.OUTPUT, "音视频输出失败，请检查输出设备后重试", error, fileError, false, false);
        if (fileError == -16) return failure(PlaybackFailure.Category.SOURCE, "播放地址没有可播放内容，请更换线路", error, fileError, false, true);
        if (fileError == -17 || fileError == -18) return failure(PlaybackFailure.Category.MEDIA, "媒体格式不受支持，请更换线路", error, fileError, false, true);
        if (fileError == -13 && isHttp(url)) return failure(PlaybackFailure.Category.NETWORK, "网络加载失败，请重试或更换线路", error, fileError, true, true);
        return classify(error, url, true, fileError);
    }

    private static PlaybackFailure classify(PlaybackException error, String url, boolean mpv, int fileError) {
        PlaybackFailure.Category category;
        String message;
        boolean recoverable = false;
        boolean affectsHealth = true;
        int code = error == null ? PlaybackException.ERROR_CODE_UNSPECIFIED : error.errorCode;

        if (isLoopbackConnectionFailure(error, url)) {
            category = PlaybackFailure.Category.LOCAL_PROXY;
            message = "本机播放服务连接失败，请重新打开视频";
            affectsHealth = false;
        } else if (isDrm(code)) {
            category = PlaybackFailure.Category.DRM;
            message = "DRM 授权失败，请检查授权或更换线路";
            affectsHealth = false;
        } else if (isDecoder(code)) {
            category = PlaybackFailure.Category.DECODER;
            message = "设备解码失败，请切换软解后重试";
            affectsHealth = false;
        } else if (isOutput(code)) {
            category = PlaybackFailure.Category.OUTPUT;
            message = "音视频输出失败，请检查输出设备后重试";
            affectsHealth = false;
        } else if (isMedia(code)) {
            category = PlaybackFailure.Category.MEDIA;
            message = "媒体清单或文件格式损坏，请更换线路";
        } else if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT || code == PlaybackException.ERROR_CODE_TIMEOUT || hasCause(error, SocketTimeoutException.class)) {
            category = PlaybackFailure.Category.NETWORK;
            message = "网络连接超时，请重试或更换线路";
            recoverable = true;
        } else if (isNetwork(code, mpv) || hasCause(error, UnknownHostException.class) || hasCause(error, ConnectException.class)) {
            category = PlaybackFailure.Category.NETWORK;
            message = httpMessage(error);
            recoverable = true;
        } else if (isSource(code)) {
            category = PlaybackFailure.Category.SOURCE;
            message = sourceMessage(code);
        } else {
            category = PlaybackFailure.Category.UNKNOWN;
            message = mpv ? "MPV 播放失败，请重试或更换播放器" : "播放失败，请重试或更换线路";
            affectsHealth = false;
        }
        String evidence = sanitize(error == null ? "" : error.getMessage());
        String technicalCode = (mpv ? "MPV" : "EXO") + "-" + code + (fileError == 0 ? "" : "-F" + fileError);
        return new PlaybackFailure(category, message, technicalCode, evidence, recoverable, affectsHealth);
    }

    private static PlaybackFailure failure(PlaybackFailure.Category category, String message, PlaybackException error, int fileError, boolean recoverable, boolean affectsHealth) {
        int code = error == null ? PlaybackException.ERROR_CODE_UNSPECIFIED : error.errorCode;
        return new PlaybackFailure(category, message, "MPV-" + code + "-F" + fileError, sanitize(error == null ? "" : error.getMessage()), recoverable, affectsHealth);
    }

    private static boolean isLoopbackConnectionFailure(Throwable error, String url) {
        if (!hasCause(error, ConnectException.class) || TextUtils.isEmpty(url)) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            int port = uri.getPort();
            if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) return false;
            Uri local = Uri.parse(Server.get().getAddress(true));
            return port == -1 || port == local.getPort();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNetwork(int code, boolean mpv) {
        return code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                || code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                || code == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
                || (!mpv && code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
    }

    private static boolean isHttp(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String normalized = url.trim().toLowerCase(Locale.US);
        return normalized.startsWith("http://") || normalized.startsWith("https://");
    }

    private static boolean isSource(int code) {
        return code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                || code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                || code == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
                || code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE
                || code == PlaybackException.ERROR_CODE_BAD_VALUE;
    }

    private static boolean isMedia(int code) {
        return code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                || code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED;
    }

    private static boolean isDecoder(int code) {
        return code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || code == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED;
    }

    private static boolean isOutput(int code) {
        return code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED
                || code == PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED
                || code == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED;
    }

    private static boolean isDrm(int code) {
        return code >= PlaybackException.ERROR_CODE_DRM_UNSPECIFIED && code <= PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED;
    }

    private static String sourceMessage(int code) {
        if (code == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED) return "系统禁止明文播放地址，请改用 HTTPS 线路";
        if (code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE) return "播放地址不支持断点读取，请更换线路";
        if (code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION) return "播放地址无权限访问，请更换线路";
        return "播放地址无效或已失效，请更换线路";
    }

    private static String httpMessage(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSource.InvalidResponseCodeException response) {
                return "网络请求失败（HTTP " + response.responseCode + "），请重试或更换线路";
            }
        }
        return "网络连接失败，请检查网络、重试或更换线路";
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) if (type.isInstance(cause)) return true;
        return false;
    }

    public static String sanitize(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(https?://)[^\\s]+", "$1<redacted>")
                .replaceAll("(?i)(cookie|authorization|token)\\s*[:=]\\s*[^,;\\s]+", "$1=<redacted>");
        return safe.length() <= 240 ? safe : safe.substring(0, 240);
    }
}
