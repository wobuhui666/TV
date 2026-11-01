package com.fongmi.android.tv.ai.skip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class WavEncoder {
    private WavEncoder() {
    }

    static byte[] encode(short[] pcm, int length, int sampleRate) {
        int dataSize = Math.max(0, length) * 2;
        ByteArrayOutputStream output = new ByteArrayOutputStream(44 + dataSize);
        try {
            output.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            writeInt(output, 36 + dataSize);
            output.write("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            writeInt(output, 16);
            writeShort(output, (short) 1);
            writeShort(output, (short) 1);
            writeInt(output, sampleRate);
            writeInt(output, sampleRate * 2);
            writeShort(output, (short) 2);
            writeShort(output, (short) 16);
            output.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            writeInt(output, dataSize);
            ByteBuffer buffer = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < length; i++) buffer.putShort(pcm[i]);
            output.write(buffer.array());
            return output.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void writeInt(ByteArrayOutputStream output, int value) throws IOException {
        output.write(value & 0xff); output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff); output.write((value >>> 24) & 0xff);
    }

    private static void writeShort(ByteArrayOutputStream output, short value) throws IOException {
        output.write(value & 0xff); output.write((value >>> 8) & 0xff);
    }
}
