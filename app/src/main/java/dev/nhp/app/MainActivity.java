package dev.nhp.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import dev.nhp.protocol.BatteryLevels;
import dev.nhp.protocol.Frame;

/**
 * Control panel: the auto-popup switch (M3), permission guidance, plus the M1
 * manual harness kept around for diagnostics.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1001;
    private static final String PREFS = "settings";
    private static final String KEY_AUTO = "auto_popup";

    private static final int BG = 0xFF101014;
    private static final int FG = 0xFFEDEDF0;
    private static final int DIM = 0xFF9A9AA5;
    private static final int ACCENT = 0xFF6FD3A0;

    private SharedPreferences prefs;

    private TextView statusView;
    private TextView batteryView;
    private TextView logView;
    private ScrollView logScroll;
    private Switch autoSwitch;
    private Button connectButton;
    private Button disconnectButton;
    private Button overlayButton;
    private Button notifyButton;

    private NiceHckClient client;
    private boolean autoMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        DebugLog.setListener(new DebugLog.Listener() {
            @Override
            public void onLogChanged(final String dump) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        logView.setText(dump);
                        logScroll.post(new Runnable() {
                            @Override
                            public void run() {
                                logScroll.fullScroll(View.FOCUS_DOWN);
                            }
                        });
                    }
                });
            }
        });

        logView.setText(DebugLog.dump());
        setStatus("未连接");
        setBatteryText("L --　R --　CASE --");
        DebugLog.i("APP", "M3 control panel ready");

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        autoMode = prefs.getBoolean(KEY_AUTO, false);
        autoSwitch.setChecked(autoMode);
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                onAutoModeChanged(isChecked);
            }
        });

        updatePermissionHints();
        ensurePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionHints();
    }

    @Override
    protected void onDestroy() {
        if (client != null) {
            client.stop();
            client = null;
        }
        DebugLog.setListener(null);
        super.onDestroy();
    }

    // --- permissions ----------------------------------------------------

    private void ensurePermissions() {
        java.util.List<String> needed = new java.util.ArrayList<String>();
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (needed.isEmpty()) {
            DebugLog.i("PERM", "all runtime permissions granted");
            return;
        }
        DebugLog.w("PERM", "requesting: " + needed);
        requestPermissions(needed.toArray(new String[0]), REQ_PERMS);
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) {
            return;
        }
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
            DebugLog.i("PERM", permissions[i] + " = " + (granted ? "granted" : "denied"));
        }
    }

    private void updatePermissionHints() {
        if (overlayButton != null) {
            boolean ok = PopupOverlay.canShow(this);
            overlayButton.setText(ok ? "悬浮窗 ✓" : "悬浮窗 ✗");
        }
        if (notifyButton != null) {
            boolean ok = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    || hasPermission(Manifest.permission.POST_NOTIFICATIONS);
            notifyButton.setText(ok ? "通知 ✓" : "通知 ✗");
        }
    }

    // --- auto mode ------------------------------------------------------

    private void onAutoModeChanged(boolean enabled) {
        autoMode = enabled;
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply();
        if (enabled) {
            startEarbudsService();
        } else {
            stopService(new Intent(this, EarbudsService.class));
            DebugLog.i("APP", "auto mode off, service stopped");
        }
        updateManualControls();
    }

    private void startEarbudsService() {
        Intent intent = new Intent(this, EarbudsService.class);
        intent.setAction(EarbudsService.ACTION_START);
        intent.putExtra(EarbudsService.EXTRA_POPUP, true);
        try {
            startForegroundService(intent);
            DebugLog.i("APP", "auto mode on, service started");
        } catch (RuntimeException e) {
            DebugLog.e("APP", "startForegroundService failed: " + e);
            toast("启动后台服务失败: " + e.getMessage());
        }
        updateManualControls();
    }

    private void updateManualControls() {
        boolean manualAllowed = !autoMode;
        connectButton.setEnabled(manualAllowed);
        disconnectButton.setEnabled(manualAllowed && client != null);
        if (autoMode) {
            setStatus("自动模式运行中（后台监听连接）");
        }
    }

    // --- manual harness -------------------------------------------------

    private void connect() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            toast("请先授予「附近设备」权限");
            ensurePermissions();
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            DebugLog.e("BT", "no bluetooth adapter");
            toast("此设备没有蓝牙适配器");
            return;
        }
        if (!adapter.isEnabled()) {
            DebugLog.e("BT", "bluetooth disabled");
            toast("请先打开蓝牙，并让耳机处于连接状态");
            return;
        }

        BluetoothDevice device = DeviceFinder.pick(adapter);
        if (device == null) {
            DebugLog.e("BT", "no NiceHCK device in bonded list: "
                    + DeviceFinder.describeAll(adapter));
            toast("已配对列表里没有找到原道耳机");
            return;
        }

        DebugLog.i("BT", "target = " + DeviceFinder.safeName(device)
                + " (bonded: " + DeviceFinder.describeAll(adapter) + ")");

        if (client != null) {
            client.stop();
        }
        client = new NiceHckClient(new NiceHckClient.Callback() {
            @Override
            public void onConnected(final String deviceName) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("已连接: " + deviceName);
                        connectButton.setEnabled(false);
                        disconnectButton.setEnabled(true);
                    }
                });
            }

            @Override
            public void onBattery(final BatteryLevels levels) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBatteryText(format(levels));
                    }
                });
            }

            @Override
            public void onFrame(Frame frame) {
                // logged by the client already
            }

            @Override
            public void onDisconnected(final String reason) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("未连接 (" + reason + ")");
                        connectButton.setEnabled(!autoMode);
                        disconnectButton.setEnabled(false);
                    }
                });
            }
        });

        setStatus("连接中…");
        connectButton.setEnabled(false);
        client.start(device);
    }

    private void disconnect() {
        if (client != null) {
            client.stop();
            client = null;
        }
        setStatus("未连接");
        connectButton.setEnabled(!autoMode);
        disconnectButton.setEnabled(false);
        DebugLog.i("APP", "stopped by user");
    }

    private static String format(BatteryLevels levels) {
        return "L " + show(levels.left(), levels.lastLeft())
                + "　R " + show(levels.right(), levels.lastRight())
                + "　CASE " + show(levels.casePercent(), levels.lastCase());
    }

    private static String show(int live, int last) {
        if (live != BatteryLevels.UNKNOWN) {
            return live + "%";
        }
        if (last != BatteryLevels.UNKNOWN) {
            return "~" + last + "%";
        }
        return "--";
    }

    // --- permission shortcuts -------------------------------------------

    private void requestOverlay() {
        if (PopupOverlay.canShow(this)) {
            toast("悬浮窗权限已开启");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            toast("请开启「显示在其他应用上层」");
        } catch (RuntimeException e) {
            DebugLog.e("PERM", "overlay settings failed: " + e);
        }
    }

    private void requestNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
            return;
        }
        toast("通知权限已开启");
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            toast("请打开「后台弹出界面」和「自启动」");
        } catch (RuntimeException e) {
            DebugLog.e("PERM", "app details failed: " + e);
        }
    }

    // --- ui -------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(24), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("原道电量弹窗");
        title.setTextColor(FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("NiceHCK / YuanDao · RFCOMM 0x0005");
        subtitle.setTextColor(DIM);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        root.addView(subtitle, topParams(2));

        autoSwitch = new Switch(this);
        autoSwitch.setText("连接耳机时自动弹窗");
        autoSwitch.setTextColor(FG);
        autoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(autoSwitch, topParams(14));

        statusView = new TextView(this);
        statusView.setTextColor(DIM);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        root.addView(statusView, topParams(10));

        batteryView = new TextView(this);
        batteryView.setTextColor(ACCENT);
        batteryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        batteryView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        batteryView.setGravity(Gravity.CENTER);
        root.addView(batteryView, topParams(12));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        connectButton = makeButton("手动连接读电量", 0, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        disconnectButton = makeButton("断开", 0, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect();
            }
        });
        disconnectButton.setEnabled(false);
        addWeighted(row1, connectButton, 1.7f);
        addWeighted(row1, disconnectButton, 1f);
        root.addView(row1, topParams(14));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        overlayButton = makeButton("悬浮窗", 11, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlay();
            }
        });
        notifyButton = makeButton("通知", 11, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestNotification();
            }
        });
        Button detailsButton = makeButton("后台权限", 11, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppDetails();
            }
        });
        Button copyButton = makeButton("复制日志", 11, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        });
        addWeighted(row2, overlayButton, 1f);
        addWeighted(row2, notifyButton, 1f);
        addWeighted(row2, detailsButton, 1f);
        addWeighted(row2, copyButton, 1f);
        root.addView(row2, topParams(6));

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(0xFF08080A);
        logScroll.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView = new TextView(this);
        logView.setTextColor(0xFFC8C8D0);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logParams.topMargin = dp(14);
        root.addView(logScroll, logParams);

        return root;
    }

    private Button makeButton(String text, int textSp, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp > 0 ? textSp : 13);
        button.setOnClickListener(listener);
        return button;
    }

    private void addWeighted(LinearLayout parent, View child, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(6);
        parent.addView(child, lp);
    }

    private LinearLayout.LayoutParams topParams(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private void setBatteryText(String text) {
        batteryView.setText(text);
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            toast("剪贴板不可用");
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText("nicehck-log", DebugLog.dump()));
        toast("日志已复制");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
