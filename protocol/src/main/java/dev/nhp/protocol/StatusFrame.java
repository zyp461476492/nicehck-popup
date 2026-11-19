package dev.nhp.protocol;

/**
 * Decoded {@code 0x0006} status frame.
 *
 * <p>Byte meaning was calibrated on real hardware by correlating the frames with
 * lid and bud movement (single measurement session, consistent over five
 * transitions):
 * <pre>
 *   params[0]  constant 0x01
 *   params[1]  0x02 = lid closed,  0x03 = lid open
 *   params[2]  constant 0x02
 *   params[3]  0x02 = both buds in the case, 0x03 = at least one bud out
 * </pre>
 * Anything else is reported as unknown rather than guessed.
 */
public final class StatusFrame {

    private static final int HINT_LID_OPEN = 0x03;
    private static final int HINT_BUDS_OUT = 0x03;

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
        return lidByte == HINT_LID_OPEN;
    }

    public boolean budsOut() {
        return budsByte == HINT_BUDS_OUT;
    }

    public String describe() {
        return "lid=" + (lidOpen() ? "open" : "closed") + " buds=" + (budsOut() ? "out" : "in");
    }

    @Override
    public String toString() {
        return "StatusFrame{" + describe() + " raw=[" + lidByte + " " + budsByte + "]}";
    }
}
