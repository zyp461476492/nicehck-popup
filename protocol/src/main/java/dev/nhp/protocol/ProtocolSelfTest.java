package dev.nhp.protocol;

import java.util.List;

/**
 * Zero dependency self test: runs on a plain JVM (no Android, no JUnit).
 *
 * <pre>
 *   javac -d out protocol/src/main/java/dev/nhp/protocol/*.java
 *   java -cp out dev.nhp.protocol.ProtocolSelfTest
 * </pre>
 *
 * Exit code 0 = all good.
 */
public final class ProtocolSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        testBatteryQueryBytes();
        testRoundTrip();
        testSplitReads();
        testCoalescedReads();
        testGarbageResync();
        testBogusLengthResync();
        testBatteryParsing();
        testCaseUnknownKeepsLast();
        testShortBatteryFrame();
        testTruncatedFrameWaits();

        System.out.println();
        System.out.println("passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // --- frame encoding -------------------------------------------------

    private static void testBatteryQueryBytes() {
        byte[] cmd = Frame.queryBattery();
        check("battery query length", cmd.length == 6);
        check("battery query bytes",
                hex(cmd).equals("4E 03 00 00 05 00"));
    }

    private static void testRoundTrip() {
        byte[] wire = Frame.encode(NiceHckOps.BATTERY, params(85, 90, 40));
        check("battery report length", wire.length == 9);

        FrameDecoder decoder = new FrameDecoder();
        List<Frame> frames = decoder.offer(wire);
        check("round trip one frame", frames.size() == 1);
        Frame f = frames.get(0);
        check("round trip opcode", f.opcode() == NiceHckOps.BATTERY);
        check("round trip params", f.paramsLength() == 3);
        check("round trip values", f.param(0) == 85 && f.param(1) == 90 && f.param(2) == 40);
        check("no leftover", decoder.bufferedBytes() == 0);
    }

    private static void testSplitReads() {
        byte[] wire = Frame.encode(NiceHckOps.BATTERY, params(12, 34, 56));
        FrameDecoder decoder = new FrameDecoder();
        int total = 0;
        for (int i = 0; i < wire.length; i++) {
            List<Frame> frames = decoder.offer(new byte[] {wire[i]});
            total += frames.size();
            if (i < wire.length - 1) {
                check("split read: no frame before last byte (i=" + i + ")", frames.isEmpty());
            }
        }
        check("split read: exactly one frame", total == 1);
    }

    private static void testCoalescedReads() {
        byte[] a = Frame.encode(NiceHckOps.BATTERY, params(10, 20, 30));
        byte[] b = Frame.encode(NiceHckOps.NOISE_QUERY, params(2));
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);

        FrameDecoder decoder = new FrameDecoder();
        List<Frame> frames = decoder.offer(both);
        check("coalesced: two frames", frames.size() == 2);
        check("coalesced: first battery", frames.get(0).opcode() == NiceHckOps.BATTERY);
        check("coalesced: second noise", frames.get(1).opcode() == NiceHckOps.NOISE_QUERY);
    }

    private static void testGarbageResync() {
        byte[] frame = Frame.encode(NiceHckOps.BATTERY, params(50, 60, 70));
        byte[] wire = new byte[3 + frame.length];
        wire[0] = 0x00;
        wire[1] = (byte) 0xAB;
        wire[2] = 0x4E; // a stray marker: decoder must resync on the real frame
        System.arraycopy(frame, 0, wire, 3, frame.length);

        FrameDecoder decoder = new FrameDecoder();
        List<Frame> frames = decoder.offer(wire);
        check("garbage: resynced", frames.size() == 1);
        check("garbage: values intact",
                frames.get(0).param(0) == 50 && frames.get(0).param(1) == 60);
    }

    private static void testBogusLengthResync() {
        // marker + insane length + a valid frame after it
        byte[] frame = Frame.encode(NiceHckOps.BATTERY, params(1, 2, 3));
        byte[] wire = new byte[4 + frame.length];
        wire[0] = 0x4E;
        wire[1] = (byte) 0xFF;
        wire[2] = (byte) 0xFF; // payloadLength 65535 -> reject, resync
        wire[3] = 0x00;
        System.arraycopy(frame, 0, wire, 4, frame.length);

        FrameDecoder decoder = new FrameDecoder();
        List<Frame> frames = decoder.offer(wire);
        check("bogus length: valid frame recovered", frames.size() == 1);
        check("bogus length: opcode", frames.get(0).opcode() == NiceHckOps.BATTERY);
    }

    // --- battery semantics ----------------------------------------------

    private static void testBatteryParsing() {
        BatteryLevels levels = new BatteryLevels();
        boolean ok = levels.apply(new Frame(NiceHckOps.BATTERY, params(87, 92, 60)));
        check("battery parse accepted", ok);
        check("battery left", levels.left() == 87);
        check("battery right", levels.right() == 92);
        check("battery case", levels.casePercent() == 60);
        check("battery describe", levels.describe().equals("L 87%  R 92%  CASE 60%"));
    }

    private static void testCaseUnknownKeepsLast() {
        BatteryLevels levels = new BatteryLevels();
        levels.apply(new Frame(NiceHckOps.BATTERY, params(80, 81, 55)));
        // bud taken out: right becomes 0, case not reporting
        levels.apply(new Frame(NiceHckOps.BATTERY, params(78, 0, 0)));
        check("stale: left live", levels.left() == 78);
        check("stale: right unknown", levels.right() == BatteryLevels.UNKNOWN);
        check("stale: right last kept", levels.lastRight() == 81);
        check("stale: case last kept", levels.lastCase() == 55);
        check("stale: describe marks stale", levels.describe().equals("L 78%  R ~81%  CASE ~55%"));
    }

    private static void testShortBatteryFrame() {
        BatteryLevels levels = new BatteryLevels();
        boolean ok = levels.apply(new Frame(NiceHckOps.BATTERY, params(70)));
        check("short battery frame rejected", !ok);
        check("short battery frame keeps unknown", levels.left() == BatteryLevels.UNKNOWN);
    }

    private static void testTruncatedFrameWaits() {
        byte[] wire = Frame.encode(NiceHckOps.BATTERY, params(11, 22, 33));
        byte[] head = new byte[4];
        System.arraycopy(wire, 0, head, 0, 4);

        FrameDecoder decoder = new FrameDecoder();
        check("truncated: nothing yet", decoder.offer(head).isEmpty());
        check("truncated: buffered", decoder.bufferedBytes() == 4);
        List<Frame> rest = decoder.offer(new byte[] {wire[4], wire[5], wire[6], wire[7], wire[8]});
        check("truncated: completed later", rest.size() == 1);
    }

    // --- helpers --------------------------------------------------------

    private static byte[] params(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(java.util.Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    private ProtocolSelfTest() {
    }
}
