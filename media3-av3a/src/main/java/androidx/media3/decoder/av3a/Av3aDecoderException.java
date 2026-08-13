/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package androidx.media3.decoder.av3a;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;

/** Thrown when AV3A native decoding fails. */
@UnstableApi
public final class Av3aDecoderException extends DecoderException {

    public Av3aDecoderException(String message) {
        super(message);
    }

    public Av3aDecoderException(String message, Throwable cause) {
        super(message, cause);
    }
}
