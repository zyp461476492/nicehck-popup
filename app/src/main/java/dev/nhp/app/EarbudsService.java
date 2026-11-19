package dev.nhp.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import dev.nhp.protocol.BatteryLevels;
import dev.nhp.protocol.Frame;
import dev.nhp.protocol.StatusFrame;

/**
 * Foreground service that keeps the SPP session alive and pops the battery card
 * when the earbuds connect.
 *
 * <p>Trigger is {@code ACL_CONNECTED} only: an open-lid event cannot be observed
 * from the phone before the earbuds are connected, because the SPP channel that
 * carries {@code 0x0006} does not exist yet.
 */
public final class EarbudsService extends Service {

    public static final String ACTION_START = "dev.nhp.app.action.START";
    public static final String ACTION_STOP = "dev.nhp.app.action.STOP";
    public static final String EXTRA_POPUP = "popup";

    private static final String CHANNEL_ID = "earbuds";
    private static final int NOTIFY_ID = 1001;

    private NiceHckClient client;
    private PopupOverlay popup;
    private BroadcastReceiver receiver;
    private boolean popupEnabled = true;
    private String title = "原道 OriG in";

    @Override
    public void onCreate() {
        super.onCreate();
        popup = new PopupOverlay(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            DebugLog.i("SVC", "stop requested");
            stopSelf();
            return START_NOT_STICKY;
        }

        popupEnabled = intent == null || intent.getBooleanExtra(EXTRA_POPUP, true);
        startInForeground();
        registerBluetoothReceiver();

        if (client == null) {
            BluetoothDevice device = connectedTarget();
            if (device != null) {
                DebugLog.i("SVC", "already connected: " + DeviceFinder.safeName(device));
                connect(device);
            } else {
                updateNotification("等待耳机连接");
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        DebugLog.i("SVC", "destroyed");
        unregisterBluetoothReceiver();
        teardown();
        if (popup != null) {
            popup.release();
            popup = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- bluetooth ------------------------------------------------------

    private void registerBluetoothReceiver() {
        if (receiver != null) {
            return;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) {
                    return;
                }
                BluetoothDevice device = deviceOf(intent);
                if (device == null) {
                    return;
                }
                String name = DeviceFinder.safeName(device);
                if (!DeviceFinder.matches(name)) {
                    return;
                }
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    DebugLog.i("SVC", "ACL_CONNECTED: " + name);
                    title = name;
                    connect(device);
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                    DebugLog.i("SVC", "ACL_DISCONNECTED: " + name);
                    teardown();
                    updateNotification("等待耳机连接");
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            DebugLog.i("SVC", "bluetooth receiver registered");
        } catch (RuntimeException e) {
            DebugLog.e("SVC", "registerReceiver failed: " + e);
            receiver = null;
        }
    }

    private void unregisterBluetoothReceiver() {
        if (receiver == null) {
            return;
        }
        try {
            unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
            // already gone
        }
        receiver = null;
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceOf(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private BluetoothDevice connectedTarget() {
        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return DeviceFinder.findConnected(manager);
    }

    // --- session --------------------------------------------------------

    private void connect(BluetoothDevice device) {
        teardown();
        client = new NiceHckClient(new NiceHckClient.Callback() {
            @Override
            public void onConnected(String deviceName) {
                title = deviceName;
                updateNotification("已连接 " + deviceName);
            }

            @Override
            public void onBattery(final BatteryLevels levels) {
                updateNotification(levels.describe());
                if (popupEnabled && popup != null) {
                    popup.show(title, levels);
                }
            }

            @Override
            public void onFrame(Frame frame) {
                StatusFrame status = StatusFrame.from(frame);
                if (status != null) {
                    DebugLog.i("SVC", "status " + status.describe());
                }
            }

            @Override
            public void onDisconnected(String reason) {
                updateNotification("等待耳机连接");
                DebugLog.w("SVC", "session ended: " + reason);
            }
        });
        client.start(device);
        updateNotification("连接中…");
    }

    private void teardown() {
        if (client != null) {
            client.stop();
            client = null;
        }
    }

    // --- notification ---------------------------------------------------

    private void startInForeground() {
        createChannel();
        Notification notification = buildNotification("等待耳机连接");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFY_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFY_ID, notification);
        }
    }

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "耳机电量", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("原道电量")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(NOTIFY_ID, buildNotification(text));
    }
}
