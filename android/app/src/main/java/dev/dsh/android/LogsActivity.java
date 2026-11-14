package dev.dsh.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Live engine/web console with copy, share, and document export. */
public final class LogsActivity extends Activity {
    private static final int REQ_EXPORT = 2001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView logView;
    private ScrollView scroll;
    private int lastCount = -1;
    private boolean follow = true;
    private boolean resumed;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!resumed) return;
            List<String> lines = LogStore.snapshot();
            if (lines.size() != lastCount) {
                lastCount = lines.size();
                StringBuilder text = new StringBuilder();
                int start = Math.max(0, lines.size() - 3000);
                for (int i = start; i < lines.size(); i++) {
                    text.append(lines.get(i)).append('\n');
                }
                logView.setText(text);
                if (follow) {
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                }
            }
            handler.postDelayed(this, 900);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        LinearLayout page = Ui.column(this);

        LinearLayout toolbar = Ui.toolbarBar(this, "Logs", v -> finish());
        TextView export = Ui.text(this, "Export", 14, Ui.color(this, R.color.accent));
        export.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
        export.setClickable(true);
        export.setOnClickListener(v -> export());
        toolbar.addView(export);
        page.addView(toolbar);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color0());
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextColor(Ui.color(this, R.color.text_dim));
        logView.setTextIsSelectable(true);
        logView.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 24));
        logView.setLineSpacing(Ui.dp(this, 1), 1f);
        scroll.addView(logView);
        scroll.setOnTouchListener((v, event) -> {
            follow = false;
            return false;
        });
        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = Ui.row(this);
        actions.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 10));
        TextView copy = Ui.button(this, "Copy", false);
        copy.setOnClickListener(v -> copy());
        TextView share = Ui.button(this, "Share", false);
        share.setOnClickListener(v -> share());
        TextView clear = Ui.button(this, "Clear view", false);
        clear.setOnClickListener(v -> {
            LogStore.clear();
            lastCount = -1;
        });
        TextView bottom = Ui.button(this, "Jump to end", false);
        bottom.setOnClickListener(v -> {
            follow = true;
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actions.addView(copy, actionParams);
        actions.addView(share, actionParams);
        actions.addView(bottom, actionParams);
        actions.addView(clear, actionParams);
        page.addView(actions);

        root.addView(page);
        setContentView(root);
        Ui.insets(this, root);
    }

    private int Color0() {
        return Ui.color(this, R.color.bg);
    }

    private String currentText() {
        List<String> lines = LogStore.snapshot();
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }

    private void copy() {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH log", currentText()));
        toast("Log copied");
    }

    private void share() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "DSH Android log");
        intent.putExtra(Intent.EXTRA_TEXT, currentText());
        startActivity(Intent.createChooser(intent, "Share log"));
    }

    private void export() {
        String name = "dsh-log-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
                .format(new Date()) + ".txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQ_EXPORT);
        } catch (Exception error) {
            toast("No document provider installed");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("no stream");
                out.write(currentText().getBytes(StandardCharsets.UTF_8));
                toast("Log exported");
            } catch (Exception error) {
                toast("Export failed: " + error.getMessage());
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
