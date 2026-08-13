package com.fongmi.android.tv.player.mpv;

import androidx.media3.common.PlaybackException;

/**
 * libmpv {@code mpv_end_file_reason} values plus the string mapping used by the
 * current AAR, whose extended JNI delivers END_FILE reasons as node strings.
 */
final class MpvEndFile {

    static final int REASON_EOF = 0;
    static final int REASON_STOP = 2;
    static final int REASON_QUIT = 3;
    static final int REASON_ERROR = 4;
    static final int REASON_REDIRECT = 5;

    private MpvEndFile() {
    }

    /** Unknown reasons map to stop: never fabricate an EOF that would trigger auto-next. */
    static int mapReason(String reason) {
        if (reason == null) return REASON_STOP;
        return switch (reason) {
            case "eof" -> REASON_EOF;
            case "stop" -> REASON_STOP;
            case "quit" -> REASON_QUIT;
            case "error" -> REASON_ERROR;
            case "redirect" -> REASON_REDIRECT;
            default -> REASON_STOP;
        };
    }

    /** Maps stable native file errors to Media3 actions without inspecting free-form text. */
    static int playbackErrorCode(int fileError) {
        return switch (fileError) {
            case 2 -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND;
            case 13 -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION;
            case -13 -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
            case -14 -> PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED;
            case -15 -> PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED;
            case -16 -> PlaybackException.ERROR_CODE_BAD_VALUE;
            case -17, -18 -> PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
            default -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
        };
    }
}
