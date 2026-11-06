package com.fongmi.android.tv.bean;

import java.util.Locale;

/** A file entry returned by ASSRT; download URLs are short lived. */
public final class AssrtSubtitleFile {

    private final String name;
    private final String url;
    private final String sizeText;
    private final long sizeBytes;

    public AssrtSubtitleFile(String name, String url, String sizeText, long sizeBytes) {
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.sizeText = sizeText == null ? "" : sizeText;
        this.sizeBytes = Math.max(0, sizeBytes);
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public String getSizeText() {
        return sizeText;
    }

    /** Zero means unknown; the human-readable file-list size may be rounded. */
    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getFormat() {
        int extension = name.lastIndexOf('.');
        return extension < 0 ? "" : name.substring(extension + 1).toLowerCase(Locale.ROOT);
    }
}
