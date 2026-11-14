package dev.dsh.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** Small view/dialog helpers shared by the native screens. */
public final class Ui {
    private Ui() {
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static int color(Context context, int id) {
        return Build.VERSION.SDK_INT >= 23
                ? context.getColor(id)
                : context.getResources().getColor(id);
    }

    public static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable circle(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    public static GradientDrawable outlined(Context context, int color, float radiusDp, int strokeColor) {
        GradientDrawable drawable = rounded(context, color, radiusDp);
        drawable.setStroke(dp(context, 1), strokeColor);
        return drawable;
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    public static TextView title(Context context, CharSequence value) {
        TextView view = text(context, value, 22, color(context, R.color.text));
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    public static TextView label(Context context, CharSequence value) {
        TextView view = text(context, value, 11, color(context, R.color.text_dim));
        view.setLetterSpacing(0.08f);
        view.setAllCaps(true);
        return view;
    }

    public static TextView button(Context context, CharSequence value, boolean primary) {
        TextView view = text(context, value, primary ? 15 : 14,
                primary ? Color.WHITE : color(context, R.color.text_dim));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12));
        view.setBackground(primary
                ? rounded(context, color(context, R.color.accent), 14)
                : outlined(context, Color.TRANSPARENT, 14, color(context, R.color.divider)));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static View spacer(Context context, float heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(context, heightDp)));
        return view;
    }

    public static LinearLayout toolbarBar(Activity activity, String heading, View.OnClickListener back) {
        LinearLayout bar = row(activity);
        bar.setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10));
        TextView backView = text(activity, "‹", 30, color(activity, R.color.text));
        backView.setPadding(dp(activity, 4), 0, dp(activity, 16), dp(activity, 2));
        backView.setClickable(true);
        backView.setOnClickListener(back);
        TextView title = text(activity, heading, 19, color(activity, R.color.text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(backView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bar;
    }

    public interface SheetItem {
        String label();

        void run();
    }

    public static final class Item implements SheetItem {
        private final String label;
        private final Runnable action;
        private final boolean danger;

        public Item(String label, Runnable action) {
            this(label, action, false);
        }

        public Item(String label, Runnable action, boolean danger) {
            this.label = label;
            this.action = action;
            this.danger = danger;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public void run() {
            action.run();
        }

        public boolean danger() {
            return danger;
        }
    }

    public static void sheet(Activity activity, String heading, List<Item> items) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = column(activity);
        int pad = dp(activity, 20);
        content.setPadding(pad, dp(activity, 14), pad, dp(activity, 18));
        GradientDrawable background = rounded(activity, color(activity, R.color.bg_elevated), 22);
        background.setCornerRadii(new float[]{22, 22, 22, 22, 0, 0, 0, 0});
        content.setBackground(background);

        View grabber = new View(activity);
        grabber.setBackground(rounded(activity, color(activity, R.color.divider), 4));
        LinearLayout.LayoutParams grabberParams =
                new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4));
        grabberParams.gravity = Gravity.CENTER_HORIZONTAL;
        grabberParams.bottomMargin = dp(activity, 14);
        content.addView(grabber, grabberParams);

        TextView title = text(activity, heading, 18, color(activity, R.color.text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.bottomMargin = dp(activity, 8);
        content.addView(title, titleParams);

        for (Item item : items) {
            TextView row = text(activity, item.label(), 16,
                    item.danger() ? color(activity, R.color.danger) : color(activity, R.color.text));
            row.setPadding(0, dp(activity, 15), 0, dp(activity, 15));
            row.setClickable(true);
            row.setBackground(rounded(activity, Color.TRANSPARENT, 10));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                item.run();
            });
            content.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.dimAmount = 0.55f;
            window.setAttributes(params);
        }
        dialog.show();
        Window shown = dialog.getWindow();
        if (shown != null) {
            shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    public static void info(Activity activity, String heading, String message) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = column(activity);
        int pad = dp(activity, 22);
        content.setPadding(pad, pad, pad, dp(activity, 18));
        content.setBackground(rounded(activity, color(activity, R.color.bg_elevated), 20));
        TextView title = text(activity, heading, 18, color(activity, R.color.text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        TextView body = text(activity, message, 14, color(activity, R.color.text_dim));
        body.setLineSpacing(dp(activity, 3), 1f);
        body.setPadding(0, dp(activity, 10), 0, 0);
        scroll.addView(body);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(activity, 4);
        content.addView(scroll, scrollParams);

        TextView ok = button(activity, "OK", true);
        ok.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okParams.gravity = Gravity.END;
        okParams.topMargin = dp(activity, 18);
        content.addView(ok, okParams);

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.dimAmount = 0.55f;
            window.setAttributes(params);
        }
        dialog.show();
        Window shown = dialog.getWindow();
        if (shown != null) {
            int margin = dp(activity, 22);
            shown.setLayout(activity.getResources().getDisplayMetrics().widthPixels - margin * 2,
                    Math.min(activity.getResources().getDisplayMetrics().heightPixels
                            - dp(activity, 120), dp(activity, 560)));
        }
    }

    public static void confirm(Activity activity, String heading, String message,
                               String positive, String negative, Runnable onPositive) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout content = column(activity);
        int pad = dp(activity, 22);
        content.setPadding(pad, pad, pad, dp(activity, 18));
        content.setBackground(rounded(activity, color(activity, R.color.bg_elevated), 20));
        TextView title = text(activity, heading, 18, color(activity, R.color.text));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(title);
        TextView body = text(activity, message, 14, color(activity, R.color.text_dim));
        body.setLineSpacing(dp(activity, 2), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(activity, 10);
        content.addView(body, bodyParams);

        LinearLayout actions = row(activity);
        actions.setGravity(Gravity.END);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(activity, 20);
        TextView cancel = button(activity, negative, false);
        cancel.setOnClickListener(v -> dialog.dismiss());
        TextView ok = button(activity, positive, true);
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            onPositive.run();
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.leftMargin = dp(activity, 10);
        actions.addView(cancel);
        actions.addView(ok, actionParams);
        content.addView(actions, actionsParams);

        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.dimAmount = 0.55f;
            window.setAttributes(params);
        }
        dialog.show();
        Window shown = dialog.getWindow();
        if (shown != null) {
            int margin = dp(activity, 22);
            shown.setLayout(activity.getResources().getDisplayMetrics().widthPixels - margin * 2,
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    public static void insets(Activity activity, View root) {
        if (Build.VERSION.SDK_INT >= 30) {
            activity.getWindow().setDecorFitsSystemWindows(false);
            activity.getWindow().setStatusBarColor(Color.TRANSPARENT);
            activity.getWindow().setNavigationBarColor(Color.TRANSPARENT);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                android.graphics.Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars());
                android.graphics.Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                view.setPadding(0, bars.top, 0, Math.max(bars.bottom, ime.bottom));
                return windowInsets;
            });
        }
    }
}
