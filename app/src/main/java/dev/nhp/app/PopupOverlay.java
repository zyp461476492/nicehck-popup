package dev.nhp.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.nhp.protocol.BatteryLevels;

/**
 * Fake-system connection popup: a rounded card at the top of the screen showing
 * the three battery readings, dismissed automatically after a few seconds.
 *
 * <p>Needs {@code SYSTEM_ALERT_WINDOW}; on HyperOS it additionally needs the
 * vendor "后台弹出界面" (background popup) permission, which has no public API and
 * can only be granted by hand in the app details screen.
 */
public final class PopupOverlay {

    private static final long AUTO_DISMISS_MS = 3600L;
    private static final String TARGET_PACKAGE = "com.yuandao.nicehck";

    private final Context context;
    private final WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View root;
    private TextView titleView;
    private TextView batteryView;
    private boolean showing;

    public PopupOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager =
                (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    /** True when the overlay permission is granted. */
    public static boolean canShow(Context context) {
        return Settings.canDrawOverlays(context);
    }

    public void show(String title, BatteryLevels levels) {
        if (windowManager == null) {
            return;
        }
        if (!canShow(context)) {
            DebugLog.w("POPUP", "overlay permission missing, popup skipped");
            return;
        }
        if (root == null) {
            build();
        }
        titleView.setText(title);
        batteryView.setText(format(levels));

        if (!showing) {
            attach();
        }
        scheduleDismiss();
    }

    public void dismiss() {
        handler.removeCallbacksAndMessages(null);
        if (root == null || !showing) {
            return;
        }
        root.animate()
                .alpha(0f)
                .translationY(-dp(24))
                .setDuration(160)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        detach();
                    }
                })
                .start();
    }

    public void release() {
        handler.removeCallbacksAndMessages(null);
        detach();
        root = null;
    }

    // --- window plumbing -------------------------------------------------

    private void attach() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        params.format = PixelFormat.TRANSLUCENT;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        params.width = dp(300);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(44);

        try {
            windowManager.addView(root, params);
        } catch (RuntimeException e) {
            DebugLog.e("POPUP", "addView failed: " + e);
            return;
        }
        showing = true;

        root.setAlpha(0f);
        root.setTranslationY(-dp(28));
        root.animate().alpha(1f).translationY(0f).setDuration(220).start();
    }

    private void detach() {
        if (!showing || root == null) {
            showing = false;
            return;
        }
        try {
            windowManager.removeView(root);
        } catch (RuntimeException e) {
            DebugLog.w("POPUP", "removeView failed: " + e.getMessage());
        }
        showing = false;
        if (root != null) {
            root.setAlpha(1f);
            root.setTranslationY(0f);
        }
    }

    private void scheduleDismiss() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        }, AUTO_DISMISS_MS);
    }

    // --- content --------------------------------------------------------

    private void build() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(14), dp(18), dp(16));

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2151519);
        background.setCornerRadius(dp(22));
        background.setStroke(dp(1), 0x33FFFFFF);
        card.setBackground(background);

        titleView = new TextView(context);
        titleView.setTextColor(0xFFFFFFFF);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleView, matchWrap());

        batteryView = new TextView(context);
        batteryView.setTextColor(0xFF6FD3A0);
        batteryView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        batteryView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        LinearLayout.LayoutParams batteryParams = matchWrap();
        batteryParams.topMargin = dp(6);
        card.addView(batteryView, batteryParams);

        TextView hint = new TextView(context);
        hint.setText("轻点打开原道 App");
        hint.setTextColor(0xFF8A8A95);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        LinearLayout.LayoutParams hintParams = matchWrap();
        hintParams.topMargin = dp(8);
        card.addView(hint, hintParams);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openControlApp();
                dismiss();
            }
        });

        root = card;
    }

    private void openControlApp() {
        try {
            Intent launch = context.getPackageManager()
                    .getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launch);
                return;
            }
            DebugLog.w("POPUP", "control app not installed: " + TARGET_PACKAGE);
        } catch (RuntimeException e) {
            DebugLog.w("POPUP", "launch failed: " + e.getMessage());
        }
    }

    private static String format(BatteryLevels levels) {
        return "L " + show(levels.left(), levels.lastLeft())
                + "   R " + show(levels.right(), levels.lastRight())
                + "   CASE " + show(levels.casePercent(), levels.lastCase());
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

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }
}
