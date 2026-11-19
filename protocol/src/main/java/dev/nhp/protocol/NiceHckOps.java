package dev.nhp.protocol;

/**
 * Opcodes used by the NiceHCK / YuanDao (OriG in) RFCOMM control channel.
 *
 * <p>Frame layout: {@code 4E | len(LE16) | 00 | opcode(LE16) | params}, where
 * {@code len = 3 + params.length}. See {@link Frame}.
 *
 * <p>Values below follow the publicly reverse engineered implementation
 * (NiceHCK_Controller / HyperEars NiceHckWireCodec).
 */
public final class NiceHckOps {

    /** Battery report: params = left, right, case (percent 1..100, 0 = unknown). */
    public static final int BATTERY = 0x0005;

    /**
     * Periodic status report, params = 4 bytes.
     *
     * <p>Calibrated on real hardware (OriG in) against lid and bud movement:
     * {@code params[1]}: 0x02 = lid closed, 0x03 = lid open;
     * {@code params[3]}: 0x02 = both buds in the case, 0x03 = at least one bud out.
     * {@code params[0]} and {@code params[2]} were constant (0x01 / 0x02).
     * The case battery byte also reports 0 while a bud is out.
     */
    public static final int STATUS = 0x0006;

    /** Firmware version. */
    public static final int VERSION = 0x0003;

    /** Query current noise (ANC) mode. */
    public static final int NOISE_QUERY = 0x0101;

    /** Set noise (ANC) mode: params = mode, 0. */
    public static final int NOISE_SET = 0x0201;

    /** Query full device state. */
    public static final int FULL_STATE = 0x0103;

    private NiceHckOps() {
    }

    public static String name(int opcode) {
        switch (opcode) {
            case BATTERY:
                return "BATTERY";
            case STATUS:
                return "STATUS";
            case VERSION:
                return "VERSION";
            case NOISE_QUERY:
                return "NOISE_QUERY";
            case NOISE_SET:
                return "NOISE_SET";
            case FULL_STATE:
                return "FULL_STATE";
            default:
                return "0x" + String.format(java.util.Locale.US, "%04X", opcode);
        }
    }
}
