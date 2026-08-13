/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package androidx.media3.decoder.av3a;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.util.LibraryLoader;
import androidx.media3.common.util.UnstableApi;

/** Loads and identifies the dedicated AV3A decoder library. */
@UnstableApi
public final class Av3aLibrary {

    static {
        MediaLibraryInfo.registerModule("media3.decoder.av3a");
    }

    private static final LibraryLoader LOADER = new LibraryLoader("av3aJNI") {
        @Override
        protected void loadLibrary(String name) {
            System.loadLibrary(name);
        }
    };

    private Av3aLibrary() {
    }

    /** Returns whether the dedicated source-built decoder can be loaded. */
    public static boolean isAvailable() {
        return LOADER.isAvailable();
    }

    /** Returns the native decoder version. */
    @Nullable
    public static String getVersion() {
        return isAvailable() ? av3aGetVersion() : null;
    }

    private static native String av3aGetVersion();
}
