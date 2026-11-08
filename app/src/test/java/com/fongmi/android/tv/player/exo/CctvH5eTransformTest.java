package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CctvH5eTransformTest {

    @Test
    public void shouldLeaveOrdinaryTransportStreamByteIdentical() throws Exception {
        byte[] clear = plaintextFixture();
        assertSame(clear, CctvH5eTransform.transform(clear));
    }

    @Test
    public void shouldDecryptAcrossPacketsAndPesWithoutMutatingInput() throws Exception {
        byte[] encrypted = encryptedFixture();
        byte[] original = encrypted.clone();
        byte[] actual = CctvH5eTransform.transform(encrypted);
        assertArrayEquals(plaintextFixture(), actual);
        assertArrayEquals(original, encrypted);
        assertSame(actual, CctvH5eTransform.transform(actual));
    }

    @Test
    public void shouldPreservePcrAudioAndContinuityWhileRemovingEscapeBytes() throws Exception {
        byte[] encrypted = encryptedFixture();
        byte[] actual = CctvH5eTransform.transform(encrypted);
        for (int offset = 0; offset < encrypted.length; offset += 188) {
            assertArrayEquals(Arrays.copyOfRange(encrypted, offset, offset + 4),
                    Arrays.copyOfRange(actual, offset, offset + 4));
            if ((encrypted[offset + 2] & 0xff) == 0) {
                assertArrayEquals(Arrays.copyOfRange(encrypted, offset + 5, offset + 12),
                        Arrays.copyOfRange(actual, offset + 5, offset + 12));
            } else {
                assertArrayEquals(Arrays.copyOfRange(encrypted, offset, offset + 188),
                        Arrays.copyOfRange(actual, offset, offset + 188));
            }
        }
        // The EPB was in the second packet of each video PES; its adaptation field grows.
        assertEquals((encrypted[3 * 188 + 4] & 0xff) + 1, actual[3 * 188 + 4] & 0xff);
        assertEquals((encrypted[5 * 188 + 4] & 0xff) + 1, actual[5 * 188 + 4] & 0xff);
    }

    @Test
    public void shouldFindStartCodeSplitAcrossTransportPackets() throws Exception {
        byte[] filler = new byte[150];
        Arrays.fill(filler, (byte) 0x44);
        filler[0] = filler[1] = 0;
        filler[2] = 1;
        filler[3] = 6;
        byte[] encodedPayload = join(sps(true), filler, new byte[]{0, 0, 0, 1}, escape(encryptedSlice()));
        byte[] clearPayload = join(sps(false), filler, new byte[]{0, 0, 0, 1}, clearSlice());
        assertArrayEquals(transport(clearPayload, false),
                CctvH5eTransform.transform(transport(encodedPayload, false)));
    }

    @Test
    public void shouldKeepEveryPacketValidWhenEscapeRemovalSavesWholePackets() throws Exception {
        byte[] nal = Arrays.copyOf(encryptedSlice(), 2400);
        for (int i = 300; i < nal.length; i++) nal[i] = (byte) (i % 3 == 2 ? 2 : 0);
        byte[] encrypted = transport(join(sps(true), new byte[]{0, 0, 1}, escape(nal)), false);
        byte[] actual = CctvH5eTransform.transform(encrypted);
        assertEquals(encrypted.length, actual.length);
        int payloadBytes = 0;
        for (int offset = 0; offset < actual.length; offset += 188) {
            assertEquals(encrypted[offset + 3] & 15, actual[offset + 3] & 15);
            assertEquals(3, (actual[offset + 3] >>> 4) & 3);
            int size = offset + 188 - payloadStart(actual, offset);
            assertTrue(size >= 1);
            payloadBytes += size;
            assertArrayEquals(Arrays.copyOfRange(encrypted, offset + 5, offset + 12),
                    Arrays.copyOfRange(actual, offset + 5, offset + 12));
        }
        assertEquals(14 + sps(false).length + 3 + nal.length, payloadBytes);
    }

    @Test
    public void shouldLeaveOtherVideoPidUnchanged() throws Exception {
        byte[] ordinary = transport(join(new byte[]{0, 0, 1}, clearSlice()), false);
        for (int offset = 0; offset < ordinary.length; offset += 188) ordinary[offset + 2] = 2;
        byte[] input = join(encryptedFixture(), ordinary);
        assertArrayEquals(join(plaintextFixture(), ordinary), CctvH5eTransform.transform(input));
    }

    @Test
    public void shouldLeaveUnknownSchemeUnchanged() throws Exception {
        byte[] payload = join(sps(true), new byte[]{0, 0, 1, 0x19, 0, 1, 9},
                new byte[]{0, 0, 1}, escape(encryptedSlice()));
        byte[] input = transport(payload, false);
        assertSame(input, CctvH5eTransform.transform(input));
    }

    @Test
    public void shouldLeaveMalformedAndNonTransportInputUnchanged() throws Exception {
        byte[][] inputs = {new byte[0], new byte[]{1, 2, 3},
                Arrays.copyOf(encryptedFixture(), encryptedFixture().length - 1), encryptedFixture(),
                encryptedFixture(), encryptedFixture()};
        inputs[3][0] = 0;
        inputs[4][1] |= (byte) 0x80;
        inputs[5][188 + 4] = (byte) 255;
        for (byte[] input : inputs) assertSame(input, CctvH5eTransform.transform(input));
    }

    @Test
    public void shouldRejectIncompletePesAfterRecognizingH5e() throws Exception {
        byte[] input = encryptedFixture();
        int start = payloadStart(input, 4 * 188);
        input[start + 4] = input[start + 5] = (byte) 0xff;
        byte[] original = input.clone();
        try {
            CctvH5eTransform.transform(input);
            fail("An incomplete marked video PID must not be partially converted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Incomplete"));
        }
        assertArrayEquals(original, input);
    }

    // Shared by DataSource tests. These fixtures contain no captured broadcast media.
    static byte[] encryptedFixture() {
        return fixture(true);
    }

    static byte[] plaintextFixture() {
        return fixture(false);
    }

    private static byte[] fixture(boolean encrypted) {
        byte[] slice = encrypted ? escape(encryptedSlice()) : clearSlice();
        byte[] first = transport(join(sps(encrypted), new byte[]{0, 0, 1}, slice), false);
        byte[] second = transport(join(new byte[]{0, 0, 0, 1}, slice), true);
        for (int offset = 0; offset < second.length; offset += 188) {
            second[offset + 3] = (byte) ((second[offset + 3] & 0xf0) | ((offset / 188 + 2) & 15));
        }
        byte[] unrelated = new byte[188];
        Arrays.fill(unrelated, (byte) 0x55);
        unrelated[0] = 0x47;
        unrelated[1] = 0x41;
        unrelated[2] = 1;
        unrelated[3] = 0x10;
        byte[] tables = unrelated.clone();
        tables[2] = 0x11;
        return join(tables, Arrays.copyOfRange(first, 0, 188), unrelated,
                Arrays.copyOfRange(first, 188, first.length), second);
    }

    private static byte[] sps(boolean encrypted) {
        return new byte[]{0, 0, 0, 1, 0x67, 0x64, (byte) (encrypted ? 1 : 0), 0x28, 0x55, 0x66};
    }

    private static byte[] clearSlice() {
        byte[] result = new byte[300];
        for (int i = 0; i < result.length; i++) result[i] = (byte) ((i * 37 + 19) % 253 + 1);
        result[0] = 0x65;
        result[64] = result[65] = 0;
        result[66] = 2;
        return result;
    }

    private static byte[] encryptedSlice() {
        byte[] result = clearSlice();
        // Golden ciphertext generated independently using forward TEA, 16 rounds, LE words.
        String[] blocks = {"d6f35b9985d38ba1", "235baa768bf66ce9", "5078dd7da51cca9d", "c0b73cbb0ff48b44"};
        for (int block = 0; block < blocks.length; block++) {
            for (int i = 0; i < 8; i++) {
                result[32 + block * 80 + i] = (byte) Integer.parseInt(blocks[block].substring(i * 2, i * 2 + 2), 16);
            }
        }
        return result;
    }

    private static byte[] escape(byte[] nal) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int zeros = 0;
        for (byte value : nal) {
            if (zeros == 2 && (value & 0xff) <= 3) {
                output.write(3);
                zeros = 0;
            }
            output.write(value);
            zeros = value == 0 ? zeros + 1 : 0;
        }
        return output.toByteArray();
    }

    private static byte[] transport(byte[] payload, boolean unboundedPes) {
        byte[] pes = new byte[14 + payload.length];
        pes[2] = 1;
        pes[3] = (byte) 0xe0;
        if (!unboundedPes) {
            pes[4] = (byte) ((pes.length - 6) >>> 8);
            pes[5] = (byte) (pes.length - 6);
        }
        pes[6] = (byte) 0x80;
        pes[7] = (byte) 0x80;
        pes[8] = 5;
        pes[9] = 0x21;
        pes[11] = pes[13] = 1;
        System.arraycopy(payload, 0, pes, 14, payload.length);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int copied = 0;
        int counter = 0;
        while (copied < pes.length) {
            byte[] packet = new byte[188];
            Arrays.fill(packet, (byte) 0xff);
            int count = Math.min(176, pes.length - copied);
            packet[0] = 0x47;
            packet[1] = (byte) (copied == 0 ? 0x41 : 0x01);
            packet[2] = 0;
            packet[3] = (byte) (0x30 | (counter++ & 15));
            packet[4] = (byte) (183 - count);
            packet[5] = 0x10;
            for (int i = 6; i < 12; i++) packet[i] = (byte) (0x20 + i);
            System.arraycopy(pes, copied, packet, 188 - count, count);
            output.write(packet, 0, packet.length);
            copied += count;
        }
        return output.toByteArray();
    }

    private static int payloadStart(byte[] data, int offset) {
        return offset + 5 + (data[offset + 4] & 0xff);
    }

    private static byte[] join(byte[]... arrays) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] array : arrays) output.write(array, 0, array.length);
        return output.toByteArray();
    }
}
