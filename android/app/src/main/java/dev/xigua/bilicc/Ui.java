package dev.xigua.bilicc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {
    static final int TEXT = R.color.bili_ink;
    static final int MUTED = R.color.bili_muted;
    static final int ERROR = R.color.bili_error;
    static final int ACCENT = R.color.bili_accent;
    static final int SUCCESS = R.color.bili_success;
    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] FOCUSED = {android.R.attr.state_focused};
    private static final int[] DEFAULT = {};

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

    static GradientDrawable shape(Context context, int fill, int radius, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(context.getColor(fill));
        background.setCornerRadius(dp(context, radius));
        if (stroke != 0) background.setStroke(dp(context, 1), context.getColor(stroke));
        return background;
    }

    static GradientDrawable readingBackground(Context context) {
        return shape(context, R.color.bili_well, 14, R.color.bili_line);
    }

    static LinearLayout panel(Context context) {
        LinearLayout box = column(context);
        box.setBackground(shape(context, R.color.bili_surface, 24, R.color.bili_line));
        int inset = dp(context, 16);
        box.setPadding(inset, inset, inset, inset);
        return box;
    }

    static LinearLayout page(Activity activity) {
        FrameLayout viewport = new FrameLayout(activity);
        viewport.setBackgroundColor(activity.getColor(R.color.bili_canvas));
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        viewport.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout column = column(activity);
        int side = dp(activity, 18);
        column.setPadding(side, dp(activity, 8), side, dp(activity, 24));
        int width = activity.getResources().getConfiguration().screenWidthDp >= 680 ? dp(activity, 640) : -1;
        scroll.addView(column, new FrameLayout.LayoutParams(width, -2, Gravity.CENTER_HORIZONTAL));
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            viewport.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
        activity.setContentView(viewport);
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
        label.setTextColor(context.getColor(color));
        label.setTextSize(sp);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    static TextView heading(Context context, String value, int sp) {
        TextView label = text(context, value, sp, TEXT, true);
        label.setAccessibilityHeading(true);
        return label;
    }

    static LinearLayout brand(Context context) {
        LinearLayout brand = row(context);
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.bili_icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)));
        TextView name = text(context, "Bili CC", 17, TEXT, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.setMarginStart(dp(context, 8));
        brand.addView(name, params);
        return brand;
    }

    static void labeledField(LinearLayout parent, String label, EditText input, int gap) {
        Context context = parent.getContext();
        TextView caption = text(context, label, 14, MUTED, true);
        input.setId(View.generateViewId());
        input.setSaveEnabled(false);
        caption.setLabelFor(input.getId());
        add(parent, caption, gap);
        add(parent, input, 4);
    }

    static EditText field(Context context, String hint, int inputType) {
        EditText edit = new EditText(context);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setHint(hint);
        edit.setTextSize(16);
        edit.setTextColor(context.getColor(TEXT));
        edit.setHintTextColor(context.getColor(MUTED));
        StateListDrawable borders = new StateListDrawable();
        borders.addState(FOCUSED, shape(context, R.color.bili_well, 14, ACCENT));
        borders.addState(DEFAULT, shape(context, R.color.bili_well, 14, R.color.bili_control));
        edit.setBackground(borders);
        int pad = dp(context, 12);
        edit.setPadding(pad, pad, pad, pad);
        edit.setMinHeight(dp(context, 48));
        return edit;
    }

    static Button button(Context context, String label, boolean primary, View.OnClickListener action) {
        return action(context, label, primary ? ACCENT : R.color.bili_accent_soft,
                primary ? R.color.bili_on_accent : R.color.bili_on_soft, action);
    }

    static Button quiet(Context context, String label, View.OnClickListener listener) {
        return action(context, label, android.R.color.transparent, ACCENT, listener);
    }

    private static Button action(Context context, String label, int fill, int ink, View.OnClickListener listener) {
        Button button = new Button(context);
        button.setStateListAnimator(null);
        button.setText(label);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setMinWidth(dp(context, 48));
        button.setMinimumWidth(dp(context, 48));
        button.setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8));
        int foreground = context.getColor(ink);
        int faint = (context.getColor(MUTED) & 0x00ffffff) | 0x8c000000;
        button.setTextColor(new ColorStateList(new int[][]{DISABLED, DEFAULT}, new int[]{faint, foreground}));
        StateListDrawable states = new StateListDrawable();
        states.addState(DISABLED, shape(context, R.color.bili_well, 14, R.color.bili_line));
        states.addState(FOCUSED, shape(context, fill, 14, ink));
        states.addState(DEFAULT, shape(context, fill, 14, 0));
        int ripple = (foreground & 0x00ffffff) | 0x26000000;
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), states,
                shape(context, android.R.color.white, 14, 0)));
        button.setOnClickListener(listener);
        return button;
    }

    static void toast(Context context, String message) {
        Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    static void copy(Context context, String text) throws BiliClient.Failure {
        copyToClipboard(context, text);
        toast(context, "字幕已复制");
    }

    static void copyToClipboard(Context context, String text) throws BiliClient.Failure {
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
