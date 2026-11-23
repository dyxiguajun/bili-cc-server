package dev.xigua.bilicc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {
    static final int BACKGROUND = 0xff0b121b;
    static final int SURFACE = 0xff111a24;
    static final int FIELD = 0xff0d1620;
    static final int TEXT = 0xffedf4fa;
    static final int MUTED = 0xffa9bbca;
    static final int EDGE = 0xff374b60;
    static final int ACCENT = 0xff8bdcf0;
    static final int ACCENT_INK = 0xff08212c;
    static final int ERROR = 0xffffb8b9;

    static int dp(Context context, int value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static LinearLayout column(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    static LinearLayout row(Context context) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return box;
    }

    static GradientDrawable panelBackground(Context context, int fill) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(context, 12));
        background.setStroke(dp(context, 1), EDGE);
        return background;
    }

    static LinearLayout panel(Context context) {
        LinearLayout box = column(context);
        box.setBackground(panelBackground(context, SURFACE));
        int inset = dp(context, 14);
        box.setPadding(inset, inset, inset, inset);
        return box;
    }

    static LinearLayout page(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.setFillViewport(true);
        LinearLayout column = column(activity);
        int side = dp(activity, 18);
        column.setPadding(side, side, side, side);
        scroll.addView(column);
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        activity.setContentView(scroll);
        column.setFocusableInTouchMode(true);
        column.requestFocus();
        return column;
    }

    static void add(LinearLayout column, View view, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(column.getContext(), gap);
        column.addView(view, params);
    }

    static void weight(LinearLayout row, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMarginStart(dp(row.getContext(), gap));
        row.addView(child, params);
    }

    static TextView text(Context context, String value, int sp, int color, boolean bold) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextColor(color);
        label.setTextSize(sp);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    static EditText field(Context context, String hint, int inputType) {
        EditText edit = new EditText(context);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setHint(hint);
        edit.setTextSize(16);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setBackground(panelBackground(context, FIELD));
        int pad = dp(context, 12);
        edit.setPadding(pad, pad, pad, pad);
        edit.setMinHeight(dp(context, 48));
        return edit;
    }

    static Button button(Context context, String label, boolean primary, View.OnClickListener action) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 8), dp(context, 5), dp(context, 8), dp(context, 5));
        button.setTextColor(primary ? ACCENT_INK : TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(primary ? ACCENT : EDGE));
        button.setOnClickListener(action);
        return button;
    }

    static void toast(Context context, String message) {
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    static void copy(Context context, String text) throws BiliClient.Failure {
        if (text.isEmpty()) throw new BiliClient.Failure("empty", "没有可以复制的字幕。");
        if (text.length() > 300_000) {
            throw new BiliClient.Failure("clipboard_size", "字幕太长，无法整段复制。可在应用里保存 SRT。");
        }
        try {
            ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
            clipboard.setPrimaryClip(ClipData.newPlainText("B 站字幕", text));
        } catch (RuntimeException failure) {
            throw new BiliClient.Failure("clipboard", "系统未能复制字幕，请在应用中重试。");
        }
        toast(context, "字幕已复制");
    }

    static void openTailscale(Activity activity) {
        Intent intent = activity.getPackageManager().getLaunchIntentForPackage("com.tailscale.ipn");
        if (intent == null) toast(activity, "请安装 Tailscale 后登录你的网络。");
        else activity.startActivity(intent);
    }

    static String message(Exception error) {
        return error instanceof BiliClient.Failure ? error.getMessage() : "处理失败，请稍后重试。";
    }
}
