package dev.nhp.app;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * In-memory ring buffer log.
 *
 * <p>This app is built in CI and installed by hand, so there is no adb/logcat
 * during real-device testing. Everything that matters goes here and is shown in
 * the UI, where it can be copied to the clipboard.
 */
public final class DebugLog {

    private static final int CAPACITY = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<String>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static volatile Listener listener;

    public interface Listener {
        void onLogChanged(String dump);
    }

    private DebugLog() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static void i(String tag, String msg) {
        add("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        add("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        add("E", tag, msg);
    }

    private static void add(String level, String tag, String msg) {
        Listener l;
        synchronized (LINES) {
            if (LINES.size() >= CAPACITY) {
                LINES.removeFirst();
            }
            LINES.addLast(TS.format(new Date()) + " " + level + "/" + tag + ": " + msg);
            l = listener;
        }
        if (l != null) {
            l.onLogChanged(dump());
        }
    }

    public static String dump() {
        synchronized (LINES) {
            StringBuilder sb = new StringBuilder(LINES.size() * 64);
            for (String line : LINES) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    /** "4E 03 00 00 05 00" */
    public static String hex(byte[] bytes, int length) {
        if (bytes == null || length <= 0) {
            return "";
        }
        int n = Math.min(length, bytes.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
