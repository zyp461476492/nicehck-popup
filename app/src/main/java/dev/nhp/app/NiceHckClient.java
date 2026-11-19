package dev.nhp.app;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import dev.nhp.protocol.BatteryLevels;
import dev.nhp.protocol.Frame;
import dev.nhp.protocol.FrameDecoder;
import dev.nhp.protocol.NiceHckOps;

/**
 * Owns one RFCOMM/SPP session with the earbuds and polls {@code 0x0005}.
 *
 * <p>Two threads: the caller-supplied worker thread acts as the poll ticker
 * (write {@code 0x0005} every {@link #POLL_INTERVAL_MS}), a second thread does
 * the blocking reads. Android's {@code BluetoothSocket} exposes no read timeout,
 * so the tick has to come from its own clock rather than a socket timeout.
 */
public final class NiceHckClient {

    /** NiceHCK control channel ("NHCKCTRL" in the UUID tail). */
    public static final UUID SPP_UUID =
            UUID.fromString("0000a100-1000-8000-4e48-434b4354524c");

    private static final int POLL_INTERVAL_MS = 5000;
    private static final int READ_BUFFER = 256;

    public interface Callback {
        void onConnected(String deviceName);

        void onBattery(BatteryLevels levels);

        void onFrame(Frame frame);

        void onDisconnected(String reason);
    }

    private final Callback callback;
    private final BatteryLevels levels = new BatteryLevels();

    private volatile boolean running;
    private volatile BluetoothSocket socket;
    private Thread worker;

    public NiceHckClient(Callback callback) {
        this.callback = callback;
    }

    public BatteryLevels levels() {
        return levels;
    }

    public boolean isRunning() {
        return running;
    }

    public void start(final BluetoothDevice device) {
        stop();
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop(device);
            }
        }, "nicehck-io");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        running = false;
        closeSocket();
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void loop(BluetoothDevice device) {
        BluetoothSocket s;
        try {
            s = openSocket(device);
        } catch (SecurityException se) {
            DebugLog.e("RFCOMM", "no permission: " + se.getMessage());
            callback.onDisconnected("缺少蓝牙权限");
            return;
        } catch (IOException ioe) {
            DebugLog.e("RFCOMM", "connect failed: " + ioe);
            callback.onDisconnected("连接失败: " + ioe.getMessage());
            return;
        }

        socket = s;
        final InputStream in;
        final OutputStream out;
        try {
            in = s.getInputStream();
            out = s.getOutputStream();
        } catch (IOException ioe) {
            DebugLog.e("RFCOMM", "streams unavailable: " + ioe);
            closeSocket();
            callback.onDisconnected("通道打开失败");
            return;
        }

        String name = DeviceFinder.safeName(device);
        DebugLog.i("RFCOMM", "connected to " + name);
        callback.onConnected(name);

        final FrameDecoder decoder = new FrameDecoder();

        // Reader thread: one blocking read loop, decodes whatever arrives.
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(in, decoder);
            }
        }, "nicehck-rx");
        reader.setDaemon(true);
        reader.start();

        writeFrame(out, Frame.queryBattery());
        while (running) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                break;
            }
            if (!running) {
                break;
            }
            writeFrame(out, Frame.queryBattery());
        }

        closeSocket();
        try {
            reader.join(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        DebugLog.i("RFCOMM", "session closed");
    }

    private void readLoop(InputStream in, FrameDecoder decoder) {
        byte[] buf = new byte[READ_BUFFER];
        while (running) {
            try {
                int n = in.read(buf);
                if (n <= 0) {
                    continue;
                }
                DebugLog.i("RX", "(" + n + "B) " + DebugLog.hex(buf, n));
                List<Frame> frames = decoder.offer(Arrays.copyOf(buf, n));
                for (Frame frame : frames) {
                    handle(frame);
                }
            } catch (IOException ioe) {
                if (running) {
                    DebugLog.w("RFCOMM", "read failed: " + ioe);
                    callback.onDisconnected("连接断开: " + ioe.getMessage());
                }
                return;
            } catch (SecurityException se) {
                DebugLog.e("RFCOMM", "read denied: " + se.getMessage());
                return;
            }
        }
    }

    private void handle(Frame frame) {
        DebugLog.i("FRAME", frame.toString());
        if (frame.opcode() == NiceHckOps.BATTERY) {
            if (levels.apply(frame)) {
                DebugLog.i("BAT", levels.describe());
                callback.onBattery(levels);
            } else {
                DebugLog.w("BAT", "battery frame carries no usable value");
            }
        }
        callback.onFrame(frame);
    }

    private void writeFrame(OutputStream out, byte[] frame) {
        try {
            out.write(frame);
            out.flush();
            DebugLog.i("TX", DebugLog.hex(frame, frame.length));
        } catch (IOException ioe) {
            DebugLog.w("TX", "write failed: " + ioe.getMessage());
        }
    }

    /**
     * Secure SDP lookup first; some firmwares only answer an insecure socket, so
     * fall back instead of giving up.
     */
    private BluetoothSocket openSocket(BluetoothDevice device) throws IOException {
        BluetoothSocket secure = null;
        try {
            secure = device.createRfcommSocketToServiceRecord(SPP_UUID);
            secure.connect();
            DebugLog.i("RFCOMM", "secure SPP channel open");
            return secure;
        } catch (IOException first) {
            DebugLog.w("RFCOMM", "secure failed (" + first.getMessage() + "), trying insecure");
            closeQuietly(secure);
        }

        BluetoothSocket insecure = null;
        try {
            insecure = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
            insecure.connect();
            DebugLog.i("RFCOMM", "insecure SPP channel open");
            return insecure;
        } catch (IOException second) {
            DebugLog.e("RFCOMM", "insecure failed too: " + second.getMessage());
            closeQuietly(insecure);
            throw second;
        }
    }

    private void closeSocket() {
        BluetoothSocket s = socket;
        socket = null;
        closeQuietly(s);
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) {
            return;
        }
        try {
            s.close();
        } catch (IOException ignored) {
            // nothing useful to do while tearing down
        }
    }
}
