package dev.nhp.protocol;

/**
 * Decoded {@code 0x0006} status frame.
 *
 * <p><b>Partially calibrated.</b> Correlating the frames with lid and bud
 * movement produced a consistent reading for 0x02 / 0x03, but a third value
 * (0x01) also shows up in both bytes under conditions we have not isolated yet,
 * so this class only decodes the two values that were observed repeatedly and
 * reports the rest as unknown rather than guessing.
 *
 * <pre>
 *   params[1]  0x02 = lid closed,  0x03 = lid open,  anything else = unknown
 *   params[3]  0x02 = buds in the case, 0x03 = a bud is out,  else unknown
 * </pre>
 */
public final class StatusFrame {

    private static final int LID_CLOSED = 0x02;
    private static final int LID_OPEN = 0x03;
    private static final int BUDS_IN = 0x02;
    private static final int BUDS_OUT = 0x03;

    private final int lidByte;
    private final int budsByte;

    private StatusFrame(int lidByte, int budsByte) {
        this.lidByte = lidByte;
        this.budsByte = budsByte;
    }

    /** Returns null when the frame is not a usable status report. */
    public static StatusFrame from(Frame frame) {
        if (frame == null || frame.opcode() != NiceHckOps.STATUS || frame.paramsLength() < 4) {
            return null;
        }
        return new StatusFrame(frame.param(1), frame.param(3));
    }

    public int lidByte() {
        return lidByte;
    }

    public int budsByte() {
        return budsByte;
    }

    public boolean lidOpen() {
        return lidByte == LID_OPEN;
    }

    public boolean budsOut() {
        return budsByte == BUDS_OUT;
    }

    /** True when params[1] is one of the two calibrated values. */
    public boolean lidKnown() {
        return lidByte == LID_OPEN || lidByte == LID_CLOSED;
    }

    public boolean budsKnown() {
        return budsByte == BUDS_OUT || budsByte == BUDS_IN;
    }

    public String describe() {
        return "lid=" + lidText() + " buds=" + budsText()
                + " raw=[" + lidByte + "," + budsByte + "]";
    }

    private String lidText() {
        if (lidByte == LID_OPEN) {
            return "open";
        }
        if (lidByte == LID_CLOSED) {
            return "closed";
        }
        return "?" + lidByte;
    }

    private String budsText() {
        if (budsByte == BUDS_OUT) {
            return "out";
        }
        if (budsByte == BUDS_IN) {
            return "in";
        }
        return "?" + budsByte;
    }

    @Override
    public String toString() {
        return "StatusFrame{" + describe() + " raw=[" + lidByte + " " + budsByte + "]}";
    }
}
