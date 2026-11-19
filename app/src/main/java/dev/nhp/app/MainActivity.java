package dev.nhp.app;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import dev.nhp.protocol.BatteryLevels;
import dev.nhp.protocol.Frame;

/**
 * M1 harness: connect to the bonded NiceHCK earbuds, poll the battery opcode and
 * show everything on screen (there is no logcat in this build/install flow).
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1001;
    private static final String[] PERMS = {Manifest.permission.BLUETOOTH_CONNECT};

    private static final int BG = 0xFF101014;
    private static final int FG = 0xFFEDEDF0;
    private static final int DIM = 0xFF9A9AA5;
    private static final int ACCENT = 0xFF6FD3A0;

    private TextView statusView;
    private TextView batteryView;
    private TextView logView;
    private ScrollView logScroll;
    private Button connectButton;
    private Button disconnectButton;

    private NiceHckClient client;

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
        DebugLog.i("APP", "M1 harness ready (protocol layer 0x0005)");
        ensurePermission();
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

    private void ensurePermission() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            DebugLog.w("PERM", "requesting BLUETOOTH_CONNECT");
            requestPermissions(PERMS, REQ_PERMS);
        } else {
            DebugLog.i("PERM", "BLUETOOTH_CONNECT granted");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            DebugLog.i("PERM", "granted");
        } else {
            DebugLog.e("PERM", "denied");
            toast("需要「附近设备」权限才能读取电量");
        }
    }

    // --- connect --------------------------------------------------------

    private void connect() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            toast("请先授予蓝牙权限");
            ensurePermission();
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
            toast("已配对列表里没有找到原道耳机，请先在系统设置里配对");
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
                // already logged by the client
            }

            @Override
            public void onDisconnected(final String reason) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setStatus("未连接 (" + reason + ")");
                        connectButton.setEnabled(true);
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
        connectButton.setEnabled(true);
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

    // --- ui -------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(28), dp(18), dp(18));

        TextView title = new TextView(this);
        title.setText("原道电量读取 · M1");
        title.setTextColor(FG);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("NiceHCK / YuanDao · RFCOMM 0x0005");
        subtitle.setTextColor(DIM);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        root.addView(subtitle, verticalParams(2, 0, 0, 0));

        statusView = new TextView(this);
        statusView.setTextColor(DIM);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        root.addView(statusView, verticalParams(12, 0, 0, 0));

        batteryView = new TextView(this);
        batteryView.setTextColor(ACCENT);
        batteryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        batteryView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        batteryView.setGravity(Gravity.CENTER);
        root.addView(batteryView, verticalParams(18, 0, 0, 0));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        connectButton = makeButton("连接并读电量", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        disconnectButton = makeButton("断开", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect();
            }
        });
        disconnectButton.setEnabled(false);
        Button copyButton = makeButton("复制日志", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog();
            }
        });
        Button clearButton = makeButton("清空", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                DebugLog.clear();
                logView.setText("");
            }
        });

        addWeighted(buttons, connectButton, 1.6f);
        addWeighted(buttons, disconnectButton, 1f);
        addWeighted(buttons, copyButton, 1f);
        addWeighted(buttons, clearButton, 1f);
        root.addView(buttons, verticalParams(18, 0, 0, 0));

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
        logParams.topMargin = dp(18);
        root.addView(logScroll, logParams);

        return root;
    }

    private Button makeButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setOnClickListener(listener);
        return button;
    }

    private void addWeighted(LinearLayout parent, View child, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.rightMargin = dp(6);
        parent.addView(child, lp);
    }

    private LinearLayout.LayoutParams verticalParams(int topDp, int bottomDp,
                                                     int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        lp.bottomMargin = dp(bottomDp);
        lp.leftMargin = dp(leftDp);
        lp.rightMargin = dp(rightDp);
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
