package com.fongmi.android.tv.bean;

import java.util.List;

/** ASSRT metadata. Search entries carry file names but only detail entries have fresh URLs. */
public final class AssrtSubtitle {

    private final long id;
    private final String title;
    private final String videoName;
    private final String format;
    private final String language;
    private final String releaseSite;
    private final String uploadTime;
    private final List<AssrtSubtitleFile> files;

    public AssrtSubtitle(long id, String title, String videoName, String format, String language,
                         String releaseSite, String uploadTime, List<AssrtSubtitleFile> files) {
        this.id = id;
        this.title = safe(title);
        this.videoName = safe(videoName);
        this.format = safe(format);
        this.language = safe(language);
        this.releaseSite = safe(releaseSite);
        this.uploadTime = safe(uploadTime);
        this.files = files == null ? List.of() : List.copyOf(files);
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getVideoName() {
        return videoName;
    }

    public String getFormat() {
        return format;
    }

    public String getLanguage() {
        return language;
    }

    public String getLanguageText() {
        return language;
    }

    public String getReleaseSite() {
        return releaseSite;
    }

    public String getReleaseName() {
        return videoName.isEmpty() ? releaseSite : videoName;
    }

    public String getUploadTime() {
        return uploadTime;
    }

    public List<AssrtSubtitleFile> getFiles() {
        return files;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
