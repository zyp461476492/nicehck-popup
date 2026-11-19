package dev.nhp.protocol;

import java.util.Arrays;
import java.util.Locale;

/**
 * One NiceHCK control frame.
 *
 * <pre>
 *   0x4E | len_lo | len_hi | 0x00 | op_lo | op_hi | params...
 *          \- len = 3 + params.length -/
 *   total size = 3 + len = 6 + params.length
 * </pre>
 */
public final class Frame {

    public static final int MARKER = 0x4E;
    /** bytes after the opcode counted by the length field: opcode(2) + 1 padding byte. */
    public static final int HEADER_PAYLOAD_SIZE = 3;
    /** size of `marker + len(2)` = bytes before the payload counted by `len`. */
    public static final int LENGTH_PREFIX_SIZE = 3;
    /** marker(1) + len(2) + pad(1) + opcode(2). */
    public static final int MIN_FRAME_SIZE = 6;
    public static final int MAX_FRAME_SIZE = 1024;

    private static final byte[] EMPTY = new byte[0];

    private final int opcode;
    private final byte[] params;

    public Frame(int opcode, byte[] params) {
        this.opcode = opcode;
        this.params = params == null || params.length == 0 ? EMPTY : params;
    }

    public int opcode() {
        return opcode;
    }

    public byte[] params() {
        return params;
    }

    public int paramsLength() {
        return params.length;
    }

    /** Unsigned value of params[index], or -1 when out of range. */
    public int param(int index) {
        if (index < 0 || index >= params.length) {
            return -1;
        }
        return params[index] & 0xFF;
    }

    public String opcodeName() {
        return NiceHckOps.name(opcode);
    }

    public String paramsHex() {
        if (params.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(params.length * 3);
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02X", params[i] & 0xFF));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Frame{op=" + opcodeName() + ", params=[" + paramsHex() + "]}";
    }

    /** Build the wire bytes for one command. */
    public static byte[] encode(int opcode, byte[] params) {
        int n = params == null ? 0 : params.length;
        int payloadLength = HEADER_PAYLOAD_SIZE + n;
        byte[] out = new byte[LENGTH_PREFIX_SIZE + payloadLength];
        out[0] = (byte) MARKER;
        out[1] = (byte) (payloadLength & 0xFF);
        out[2] = (byte) ((payloadLength >>> 8) & 0xFF);
        out[3] = 0;
        out[4] = (byte) (opcode & 0xFF);
        out[5] = (byte) ((opcode >>> 8) & 0xFF);
        if (n > 0) {
            System.arraycopy(params, 0, out, MIN_FRAME_SIZE, n);
        }
        return out;
    }

    public static byte[] encode(int opcode) {
        return encode(opcode, null);
    }

    /** The battery query command, ready to write to the socket. */
    public static byte[] queryBattery() {
        return encode(NiceHckOps.BATTERY);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Frame)) {
            return false;
        }
        Frame other = (Frame) o;
        return opcode == other.opcode && Arrays.equals(params, other.params);
    }

    @Override
    public int hashCode() {
        return 31 * opcode + Arrays.hashCode(params);
    }
}
