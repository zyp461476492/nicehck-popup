package dev.nhp.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the NiceHCK / YuanDao earbuds out of the bonded device list.
 *
 * <p>We only look at already paired devices: SPP needs a bond, and we do not
 * want to request BLUETOOTH_SCAN for M1.
 */
public final class DeviceFinder {

    /** Strong hints are checked first, weak hints only as a last resort. */
    private static final String[] STRONG = {"nicehck", "yuandao"};
    private static final String[] MEDIUM = {"origin"};
    private static final String[] WEAK = {"hck"};

    private DeviceFinder() {
    }

    public static List<BluetoothDevice> bonded(BluetoothAdapter adapter) {
        List<BluetoothDevice> out = new ArrayList<BluetoothDevice>();
        if (adapter == null) {
            return out;
        }
        try {
            Set<BluetoothDevice> set = adapter.getBondedDevices();
            if (set != null) {
                out.addAll(set);
            }
        } catch (SecurityException se) {
            DebugLog.e("BOND", "getBondedDevices denied: " + se.getMessage());
        }
        return out;
    }

    public static BluetoothDevice pick(BluetoothAdapter adapter) {
        List<BluetoothDevice> devices = bonded(adapter);
        BluetoothDevice strong = firstMatching(devices, STRONG);
        if (strong != null) {
            return strong;
        }
        BluetoothDevice medium = firstMatching(devices, MEDIUM);
        if (medium != null) {
            return medium;
        }
        return firstMatching(devices, WEAK);
    }

    /** True when the name looks like a NiceHCK / YuanDao product. */
    public static boolean matches(String name) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) {
            return false;
        }
        return containsAny(normalized, STRONG)
                || containsAny(normalized, MEDIUM)
                || containsAny(normalized, WEAK);
    }

    private static boolean containsAny(String normalized, String[] needles) {
        for (String needle : needles) {
            if (normalized.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The NiceHCK device that is connected right now, or null.
     *
     * <p>{@code BluetoothDevice.isConnected()} is a hidden API, so the public
     * {@link BluetoothManager#getConnectedDevices(int)} route is used instead.
     */
    public static BluetoothDevice findConnected(BluetoothManager manager) {
        if (manager == null) {
            return null;
        }
        List<BluetoothDevice> connected;
        try {
            connected = manager.getConnectedDevices(BluetoothProfile.A2DP);
        } catch (SecurityException se) {
            DebugLog.e("BOND", "getConnectedDevices denied: " + se.getMessage());
            return null;
        } catch (RuntimeException e) {
            DebugLog.e("BOND", "getConnectedDevices failed: " + e);
            return null;
        }
        for (BluetoothDevice device : connected) {
            if (matches(safeName(device))) {
                return device;
            }
        }
        return null;
    }

    private static BluetoothDevice firstMatching(List<BluetoothDevice> devices, String[] needles) {
        for (BluetoothDevice device : devices) {
            String name = normalize(safeName(device));
            if (name.isEmpty()) {
                continue;
            }
            for (String needle : needles) {
                if (name.contains(needle)) {
                    return device;
                }
            }
        }
        return null;
    }

    /** Lowercase, letters and digits only: "OriG in" -> "origin". */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 'a' && c <= 'z') {
                sb.append(c);
            } else if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String safeName(BluetoothDevice device) {
        if (device == null) {
            return "?";
        }
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? "(no name)" : name;
        } catch (SecurityException se) {
            return "(name denied)";
        }
    }

    public static String describeAll(BluetoothAdapter adapter) {
        List<BluetoothDevice> devices = bonded(adapter);
        if (devices.isEmpty()) {
            return "(no bonded devices)";
        }
        StringBuilder sb = new StringBuilder();
        for (BluetoothDevice device : devices) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(safeName(device));
        }
        return sb.toString();
    }
}
