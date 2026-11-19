package dev.nhp.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming frame decoder: tolerates split reads (half frames) and coalesced
 * reads (several frames in one chunk), and resynchronises on the 0x4E marker
 * after garbage.
 */
public final class FrameDecoder {

    private byte[] pending = new byte[0];

    /** Feed raw bytes from the socket, return every complete frame now available. */
    public List<Frame> offer(byte[] bytes) {
        List<Frame> out = new ArrayList<>();
        if (bytes == null || bytes.length == 0) {
            return out;
        }
        pending = concat(pending, bytes);

        while (pending.length >= Frame.MIN_FRAME_SIZE) {
            int marker = indexOfMarker(pending);
            if (marker < 0) {
                pending = new byte[0];
                break;
            }
            if (marker > 0) {
                pending = slice(pending, marker, pending.length);
            }
            if (pending.length < Frame.MIN_FRAME_SIZE) {
                break;
            }

            int payloadLength = (pending[1] & 0xFF) | ((pending[2] & 0xFF) << 8);
            int frameSize = payloadLength + Frame.LENGTH_PREFIX_SIZE;
            if (payloadLength < Frame.HEADER_PAYLOAD_SIZE || frameSize > Frame.MAX_FRAME_SIZE) {
                // bogus length: drop one byte and resynchronise on the next marker
                pending = slice(pending, 1, pending.length);
                continue;
            }
            if (pending.length < frameSize) {
                break;
            }

            int opcode = (pending[4] & 0xFF) | ((pending[5] & 0xFF) << 8);
            byte[] params = slice(pending, Frame.MIN_FRAME_SIZE, frameSize);
            out.add(new Frame(opcode, params));
            pending = frameSize == pending.length
                    ? new byte[0]
                    : slice(pending, frameSize, pending.length);
        }
        return out;
    }

    public void reset() {
        pending = new byte[0];
    }

    public int bufferedBytes() {
        return pending.length;
    }

    private static int indexOfMarker(byte[] buf) {
        for (int i = 0; i < buf.length; i++) {
            if ((buf[i] & 0xFF) == Frame.MARKER) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] slice(byte[] src, int from, int to) {
        int len = to - from;
        if (len <= 0) {
            return new byte[0];
        }
        byte[] out = new byte[len];
        System.arraycopy(src, from, out, 0, len);
        return out;
    }
}
