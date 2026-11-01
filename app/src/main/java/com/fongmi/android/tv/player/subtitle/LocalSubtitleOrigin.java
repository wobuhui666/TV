package com.fongmi.android.tv.player.subtitle;

import android.os.Parcel;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;

/** Display-only provenance. Track ids, labels and saved selection descriptors stay intact. */
public final class LocalSubtitleOrigin implements Metadata.Entry {

    private static final LocalSubtitleOrigin INSTANCE = new LocalSubtitleOrigin();

    private LocalSubtitleOrigin() {
    }

    public static boolean isLocalUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) return false;
        return url.startsWith("/") && !url.startsWith("//")
                || url.regionMatches(true, 0, "file:", 0, 5)
                || url.regionMatches(true, 0, "content:", 0, 8);
    }

    @Nullable
    public static Metadata forUrl(@Nullable String url) {
        return isLocalUrl(url) ? new Metadata(INSTANCE) : null;
    }

    public static boolean isLocal(Format format) {
        if (format.metadata == null) return false;
        for (int i = 0; i < format.metadata.length(); i++) {
            if (format.metadata.get(i) instanceof LocalSubtitleOrigin) return true;
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel destination, int flags) {
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof LocalSubtitleOrigin;
    }

    @Override
    public int hashCode() {
        return LocalSubtitleOrigin.class.hashCode();
    }

    public static final Creator<LocalSubtitleOrigin> CREATOR = new Creator<>() {
        @Override
        public LocalSubtitleOrigin createFromParcel(Parcel source) {
            return INSTANCE;
        }

        @Override
        public LocalSubtitleOrigin[] newArray(int size) {
            return new LocalSubtitleOrigin[size];
        }
    };
}
