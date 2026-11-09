package com.fongmi.android.tv.player.exo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Converts the H264 H5E payload used by the matched H5E TS segments. */
public final class H5eTransform {

    private static final int TS_PACKET_SIZE = 188;
    private static final int MAX_SEGMENT_SIZE = 16 * 1024 * 1024;

    private H5eTransform() {
    }

    /**
     * Returns a copy when a video PID contains the H5E SPS marker. Ordinary TS and
     * non-TS data are returned unchanged; the supplied array is never modified.
     */
    public static byte[] transform(byte[] segment) throws IOException {
        if (segment.length > MAX_SEGMENT_SIZE) {
            throw new IOException("H5E segment exceeds size limit");
        }
        List<Pes> packets = readPesPackets(segment);
        Set<Integer> markedPids = new HashSet<>();
        for (Pes pes : packets) {
            byte[] payload = pes.payload(segment);
            if (payload == null) continue;
            for (Nal nal = nextNal(payload, 0); nal != null; nal = nextNal(payload, nal.end)) {
                // The low two constraint bits are reserved_zero_2bits in plain H264.
                if (isMarkedSps(payload, nal)) markedPids.add(pes.pid);
                // This signature selects a different scheme. Do not partially convert it.
                if (isUnsupportedScheme(payload, nal)) return segment;
            }
        }
        if (markedPids.isEmpty()) return segment;

        byte[] result = segment.clone();
        for (Pes pes : packets) {
            if (!markedPids.contains(pes.pid)) continue;
            byte[] payload = pes.payload(segment);
            if (payload == null) throw new IOException("Incomplete H5E PES payload");
            byte[] converted = convertPayload(payload);
            if (converted != payload) pes.write(segment, result, converted);
        }
        return result;
    }

    private static List<Pes> readPesPackets(byte[] data) {
        List<Pes> packets = new ArrayList<>();
        if (data.length == 0 || data.length % TS_PACKET_SIZE != 0) return packets;
        Map<Integer, Pes> active = new HashMap<>();
        for (int offset = 0; offset < data.length; offset += TS_PACKET_SIZE) {
            int end = offset + TS_PACKET_SIZE;
            int flags = data[offset + 1] & 0xff;
            int control = (data[offset + 3] >>> 4) & 3;
            if (data[offset] != 0x47 || (flags & 0x80) != 0 || control == 0
                    || (data[offset + 3] & 0xc0) != 0) return new ArrayList<>();
            int start = offset + 4;
            if ((control & 2) != 0) start += 1 + (data[start] & 0xff);
            if (start > end) return new ArrayList<>();
            if ((control & 1) == 0) continue;
            if (start == end) return new ArrayList<>();

            int pid = ((flags & 0x1f) << 8) | (data[offset + 2] & 0xff);
            if ((flags & 0x40) != 0) {
                active.remove(pid);
                if (isVideoPes(data, start, end)) {
                    int headerLength = 9 + (data[start + 8] & 0xff);
                    if (start + headerLength > end) return new ArrayList<>();
                    int packetLength = ((data[start + 4] & 0xff) << 8)
                            | (data[start + 5] & 0xff);
                    Pes pes = new Pes(pid, headerLength, packetLength);
                    active.put(pid, pes);
                    packets.add(pes);
                }
            }
            Pes pes = active.get(pid);
            if (pes != null) pes.add(new Span(offset, start, end - start));
        }
        return packets;
    }

    private static boolean isVideoPes(byte[] data, int start, int end) {
        return start + 9 <= end && data[start] == 0 && data[start + 1] == 0
                && data[start + 2] == 1 && (data[start + 3] & 0xf0) == 0xe0;
    }

    private static boolean isMarkedSps(byte[] data, Nal nal) {
        return (data[nal.start] & 0x1f) == 7 && nal.length() >= 3
                && (data[nal.start + 2] & 3) != 0;
    }

    private static boolean isUnsupportedScheme(byte[] data, Nal nal) {
        return (data[nal.start] & 0x1f) == 25 && nal.length() >= 4
                && data[nal.start + 2] == 1 && data[nal.start + 3] == 9;
    }

    private static Nal nextNal(byte[] data, int from) {
        int prefix = findStartCode(data, from);
        while (prefix >= 0) {
            int start = prefix + (data[prefix + 2] == 1 ? 3 : 4);
            int next = findStartCode(data, start);
            int end = next < 0 ? data.length : next;
            if (start < end) return new Nal(start, end);
            prefix = next;
        }
        return null;
    }

    private static int findStartCode(byte[] data, int from) {
        for (int i = from; i + 2 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1
                    || (i + 3 < data.length && data[i + 2] == 0 && data[i + 3] == 1))) return i;
        }
        return -1;
    }

    private static byte[] convertPayload(byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(payload.length);
        int cursor = 0;
        boolean changed = false;
        for (Nal nal = nextNal(payload, 0); nal != null; nal = nextNal(payload, nal.end)) {
            output.write(payload, cursor, nal.start - cursor);
            int type = payload[nal.start] & 0x1f;
            byte[] converted = null;
            if (isMarkedSps(payload, nal)) {
                converted = Arrays.copyOfRange(payload, nal.start, nal.end);
                converted[2] &= (byte) 0xfc;
            } else if ((type == 1 || type == 5) && nal.length() >= 128) {
                byte[] unescaped = unescape(payload, nal);
                if (unescaped.length >= 128) {
                    decryptSlice(unescaped);
                    converted = unescaped;
                }
            }
            if (converted == null) {
                output.write(payload, nal.start, nal.length());
            } else {
                output.write(converted, 0, converted.length);
                changed = true;
            }
            cursor = nal.end;
        }
        output.write(payload, cursor, payload.length - cursor);
        return changed ? output.toByteArray() : payload;
    }

    private static byte[] unescape(byte[] data, Nal nal) {
        byte[] result = new byte[nal.length()];
        int length = 0;
        for (int i = nal.start; i < nal.end; i++) {
            if (i + 2 < nal.end && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 3) {
                result[length++] = 0;
                result[length++] = 0;
                i += 2;
            } else {
                result[length++] = data[i];
            }
        }
        return Arrays.copyOf(result, length);
    }

    private static void decryptSlice(byte[] data) {
        int k0 = readIntLe(data, 16);
        int k1 = readIntLe(data, 20);
        int k2 = readIntLe(data, 24);
        int k3 = readIntLe(data, 28);
        // H5E stores the key in the slice and applies 16 TEA rounds to 8 of every 80 bytes.
        for (int offset = 32; offset + 8 <= data.length; offset += 80) {
            int left = readIntLe(data, offset);
            int right = readIntLe(data, offset + 4);
            int sum = 0xe3779b90;
            for (int round = 0; round < 16; round++) {
                right -= ((left << 4) + k2) ^ (left + sum) ^ ((left >>> 5) + k3);
                left -= ((right << 4) + k0) ^ (right + sum) ^ ((right >>> 5) + k1);
                sum -= 0x9e3779b9;
            }
            writeIntLe(data, offset, left);
            writeIntLe(data, offset + 4, right);
        }
    }

    private static int readIntLe(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16) | (data[offset + 3] << 24);
    }

    private static void writeIntLe(byte[] data, int offset, int value) {
        for (int i = 0; i < 4; i++) data[offset + i] = (byte) (value >>> (8 * i));
    }

    private static final class Nal {
        final int start;
        final int end;

        Nal(int start, int end) {
            this.start = start;
            this.end = end;
        }

        int length() {
            return end - start;
        }
    }

    private static final class Span {
        final int packetOffset;
        final int payloadOffset;
        final int length;

        Span(int packetOffset, int payloadOffset, int length) {
            this.packetOffset = packetOffset;
            this.payloadOffset = payloadOffset;
            this.length = length;
        }
    }

    private static final class Pes {
        final int pid;
        final int headerLength;
        final int packetLength;
        final List<Span> spans = new ArrayList<>();
        int capacity;

        Pes(int pid, int headerLength, int packetLength) {
            this.pid = pid;
            this.headerLength = headerLength;
            this.packetLength = packetLength;
        }

        void add(Span span) {
            spans.add(span);
            capacity += span.length;
        }

        byte[] payload(byte[] source) {
            int length = packetLength == 0 ? capacity - headerLength : packetLength + 6 - headerLength;
            if (length < 0 || length > capacity - headerLength) return null;
            byte[] result = new byte[length];
            int copied = 0;
            for (int i = 0; i < spans.size() && copied < length; i++) {
                Span span = spans.get(i);
                int skip = i == 0 ? headerLength : 0;
                int count = Math.min(span.length - skip, length - copied);
                System.arraycopy(source, span.payloadOffset + skip, result, copied, count);
                copied += count;
            }
            return result;
        }

        void write(byte[] source, byte[] target, byte[] payload) throws IOException {
            int length = headerLength + payload.length;
            if (length > capacity || (packetLength != 0 && length - 6 > 0xffff)
                    || length < headerLength + spans.size() - 1) {
                throw new IOException("Invalid H5E PES size");
            }
            byte[] pes = new byte[length];
            System.arraycopy(source, spans.get(0).payloadOffset, pes, 0, headerLength);
            System.arraycopy(payload, 0, pes, headerLength, payload.length);
            if (packetLength != 0) {
                pes[4] = (byte) ((length - 6) >>> 8);
                pes[5] = (byte) (length - 6);
            }
            int copied = 0;
            for (int i = 0; i < spans.size(); i++) {
                Span span = spans.get(i);
                // Keep payload in every original packet, preserving its continuity counter.
                int count = Math.min(span.length, length - copied - (spans.size() - i - 1));
                int destination = span.payloadOffset;
                if (count < span.length) {
                    int offset = span.packetOffset;
                    int adaptationLength = 183 - count;
                    boolean hadAdaptation = (source[offset + 3] & 0x20) != 0;
                    int oldLength = hadAdaptation ? source[offset + 4] & 0xff : 0;
                    target[offset + 3] = (byte) ((target[offset + 3] & 0xcf) | 0x30);
                    target[offset + 4] = (byte) adaptationLength;
                    if (adaptationLength > 0) {
                        // Existing flags, PCR and other adaptation fields must survive shrinkage.
                        if (!hadAdaptation || oldLength == 0) target[offset + 5] = 0;
                        int stuffingStart = offset + 5 + Math.max(1, oldLength);
                        Arrays.fill(target, stuffingStart, offset + 5 + adaptationLength, (byte) 0xff);
                    }
                    destination = offset + TS_PACKET_SIZE - count;
                }
                System.arraycopy(pes, copied, target, destination, count);
                copied += count;
            }
        }
    }
}
