package dev.nhp.protocol;

/**
 * Left / right / case battery percentages.
 *
 * <p>The earbuds report a single battery frame with three bytes. A byte of 0
 * (and anything above 100) means "not applicable right now": that happens for
 * the second bud in single-ear use, and for the case when it is not reporting.
 * In that situation the last known value is kept separately so the UI can show
 * it as stale instead of dropping to 0 %.
 */
public final class BatteryLevels {

    public static final int UNKNOWN = -1;

    private int left = UNKNOWN;
    private int right = UNKNOWN;
    private int casePercent = UNKNOWN;

    private int lastLeft = UNKNOWN;
    private int lastRight = UNKNOWN;
    private int lastCase = UNKNOWN;

    private long updatedAtMillis;

    /** Apply the params of a 0x0005 frame. Returns false when nothing usable. */
    public synchronized boolean apply(Frame frame) {
        if (frame == null || frame.opcode() != NiceHckOps.BATTERY || frame.paramsLength() < 3) {
            return false;
        }
        left = sanitize(frame.param(0));
        right = sanitize(frame.param(1));
        casePercent = sanitize(frame.param(2));
        if (left != UNKNOWN) {
            lastLeft = left;
        }
        if (right != UNKNOWN) {
            lastRight = right;
        }
        if (casePercent != UNKNOWN) {
            lastCase = casePercent;
        }
        updatedAtMillis = System.currentTimeMillis();
        return left != UNKNOWN || right != UNKNOWN || casePercent != UNKNOWN;
    }

    private static int sanitize(int raw) {
        if (raw < 0) {
            return UNKNOWN;
        }
        if (raw >= 1 && raw <= 100) {
            return raw;
        }
        // 0 means "unknown / not present"; >100 is nonsense
        return UNKNOWN;
    }

    public synchronized int left() {
        return left;
    }

    public synchronized int right() {
        return right;
    }

    public synchronized int casePercent() {
        return casePercent;
    }

    public synchronized int lastLeft() {
        return lastLeft;
    }

    public synchronized int lastRight() {
        return lastRight;
    }

    public synchronized int lastCase() {
        return lastCase;
    }

    public synchronized long updatedAtMillis() {
        return updatedAtMillis;
    }

    public synchronized void reset() {
        left = UNKNOWN;
        right = UNKNOWN;
        casePercent = UNKNOWN;
        lastLeft = UNKNOWN;
        lastRight = UNKNOWN;
        lastCase = UNKNOWN;
        updatedAtMillis = 0L;
    }

    /** "L 87%  R 92%  CASE 60%" — `--` for unknown, `~60%` for a stale value. */
    public synchronized String describe() {
        return "L " + show(left, lastLeft)
                + "  R " + show(right, lastRight)
                + "  CASE " + show(casePercent, lastCase);
    }

    private static String show(int live, int last) {
        if (live != UNKNOWN) {
            return live + "%";
        }
        if (last != UNKNOWN) {
            return "~" + last + "%";
        }
        return "--";
    }

    @Override
    public synchronized String toString() {
        return "BatteryLevels{" + describe() + "}";
    }
}
