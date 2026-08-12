package com.fongmi.android.tv.player.mpv;

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
}
