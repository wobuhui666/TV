package com.fongmi.android.tv.ai.skip;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class WavEncoderTest {
    @Test
    public void shouldEncodeMonoPcmAsWav() {
        short[] pcm = {Short.MIN_VALUE, 0, Short.MAX_VALUE};

        byte[] wav = WavEncoder.encode(pcm, pcm.length, 8_000);

        assertEquals(50, wav.length);
        assertArrayEquals(new byte[]{'R', 'I', 'F', 'F'}, java.util.Arrays.copyOfRange(wav, 0, 4));
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(8_000, buffer.getInt(24));
        assertEquals(16_000, buffer.getInt(28));
        assertEquals(6, buffer.getInt(40));
        assertEquals(Short.MIN_VALUE, buffer.getShort(44));
        assertEquals(0, buffer.getShort(46));
        assertEquals(Short.MAX_VALUE, buffer.getShort(48));
    }
}
