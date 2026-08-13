/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package androidx.media3.decoder.av3a;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;

import java.nio.ByteBuffer;

/** Direct AVS3/AV3A decoder. No FFmpeg APIs are used by this class. */
@UnstableApi
public final class Av3aDecoder extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, Av3aDecoderException> {

    private static final int OUTPUT_BUFFER_SIZE = 64 * 1024;
    private static final int ERROR_INVALID_DATA = -1;
    private static final int ERROR_OTHER = -2;

    private long nativeContext;
    private volatile int channelCount;
    private volatile int sampleRate;

    public Av3aDecoder(int numBuffers, int initialInputBufferSize) throws Av3aDecoderException {
        super(new DecoderInputBuffer[numBuffers], new SimpleDecoderOutputBuffer[numBuffers]);
        if (!Av3aLibrary.isAvailable()) throw new Av3aDecoderException("Failed to load libav3aJNI.so");
        nativeContext = av3aInit();
        if (nativeContext == 0) throw new Av3aDecoderException("Failed to initialize AV3A decoder");
        setInitialInputBufferSize(initialInputBufferSize);
    }

    @Override
    public String getName() {
        return "libav3a-" + Av3aLibrary.getVersion();
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected SimpleDecoderOutputBuffer createOutputBuffer() {
        return new SimpleDecoderOutputBuffer(this::releaseOutputBuffer);
    }

    @Override
    protected Av3aDecoderException createUnexpectedDecodeException(Throwable error) {
        return new Av3aDecoderException("Unexpected AV3A decode error", error);
    }

    @Override
    @Nullable
    protected Av3aDecoderException decode(DecoderInputBuffer inputBuffer, SimpleDecoderOutputBuffer outputBuffer, boolean reset) {
        if (reset && !av3aReset(nativeContext)) return new Av3aDecoderException("Failed to reset AV3A decoder");
        ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
        ByteBuffer outputData = outputBuffer.init(inputBuffer.timeUs, OUTPUT_BUFFER_SIZE);
        int result = av3aDecode(nativeContext, inputData, inputData.limit(), outputData, OUTPUT_BUFFER_SIZE);
        if (result == ERROR_OTHER) return new Av3aDecoderException("AV3A native decode failed; see logcat");
        if (result == ERROR_INVALID_DATA || result == 0) {
            outputBuffer.shouldBeSkipped = true;
            return null;
        }
        channelCount = av3aGetChannelCount(nativeContext);
        sampleRate = av3aGetSampleRate(nativeContext);
        outputData.position(0);
        outputData.limit(result);
        return null;
    }

    @Override
    public void release() {
        super.release();
        av3aRelease(nativeContext);
        nativeContext = 0;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public @C.PcmEncoding int getEncoding() {
        return C.ENCODING_PCM_16BIT;
    }

    private static native long av3aInit();

    private static native boolean av3aReset(long context);

    private static native int av3aDecode(long context, ByteBuffer inputData, int inputSize, ByteBuffer outputData, int outputSize);

    private static native int av3aGetChannelCount(long context);

    private static native int av3aGetSampleRate(long context);

    private static native void av3aRelease(long context);
}
