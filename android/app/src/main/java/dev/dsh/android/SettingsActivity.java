package dev.dsh.android;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Native settings for the shell around the engine. DSH's own settings live in the web UI. */
public final class SettingsActivity extends Activity implements Engine.Listener {
    private Engine engine;
    private TextView engineSummary;
    private TextView allFilesSummary;
    private TextView batterySummary;
    private TextView workspacePath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = Engine.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        LinearLayout page = Ui.column(this);
        page.addView(Ui.toolbarBar(this, "Settings", v -> finish()));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 28));
        scroll.addView(content);

        content.addView(sectionLabel("Engine"));
        LinearLayout engineCard = card();
        engineSummary = addValueRow(engineCard, "Status", "");
        addRow(engineCard, "Restart engine", "Stop and start the Node process",
                () -> {
                    engine.restart();
                    toast("Restarting…");
                });
        addRow(engineCard, "Safe mode", "Back up the provider patch and reset bundles",
                () -> Ui.confirm(this, "Start in safe mode?",
                        "Safe mode backs up cordis.patch.yml and boots with the official bundle "
                                + "defaults. Sessions and installed packages are kept.",
                        "Start safe mode", "Cancel", () -> {
                            engine.enterSafeMode();
                            returnToMain();
                        }));
        addRow(engineCard, "Stop engine", "Leave the process stopped until next launch",
                () -> Ui.confirm(this, "Stop the engine?", "The interface disconnects until it is started again.",
                        "Stop", "Cancel", () -> {
                            engine.stop();
                            toast("Engine stopped");
                        }), true);
        addRow(engineCard, "Re-extract engine files", "Rebuild from the bundled payload; user data is kept",
                () -> Ui.confirm(this, "Re-extract the engine?",
                        "This deletes the extracted engine directory and unpacks it again from the APK. "
                                + "Your DSH home, sessions, and settings are untouched.",
                        "Re-extract", "Cancel", () -> {
                            engine.reinstallEngine();
                            returnToMain();
                        }), true);
        content.addView(engineCard);

        content.addView(sectionLabel("Storage"));
        LinearLayout storageCard = card();
        workspacePath = addValueRow(storageCard, "Workspace", engine.workspaceDir().getAbsolutePath());
        allFilesSummary = addValueRow(storageCard, "Shared storage", allFilesLabel());
        addRow(storageCard, "Grant all-files access", "Lets DSH edit projects under /storage/emulated/0",
                this::requestAllFiles);
        addRow(storageCard, "Copy DSH home path", engine.homeDir().getAbsolutePath(),
                () -> copy("DSH home", engine.homeDir().getAbsolutePath()));
        addRow(storageCard, "Copy engine log", "Open the log viewer to export",
                () -> startActivity(new Intent(this, LogsActivity.class)));
        content.addView(storageCard);

        content.addView(sectionLabel("Behavior"));
        LinearLayout behaviorCard = card();
        Switch telemetry = new Switch(this);
        telemetry.setChecked(Prefs.telemetry(this));
        telemetry.setButtonTintList(android.content.res.ColorStateList.valueOf(
                Ui.color(this, R.color.accent)));
        telemetry.setOnCheckedChangeListener((button, checked) -> {
            Prefs.setTelemetry(this, checked);
            toast("Applies after restarting the engine");
        });
        addControlRow(behaviorCard, "Anonymous telemetry",
                "Official feedback-only OTEL export; off by default", telemetry);
        batterySummary = addValueRow(behaviorCard, "Battery optimization", batteryLabel());
        addRow(behaviorCard, "Battery settings", "Allow the engine to keep running in the background",
                this::openBatterySettings);
        content.addView(behaviorCard);

        content.addView(sectionLabel("Providers"));
        LinearLayout providerCard = card();
        addRow(providerCard, "Model providers", "OpenRouter, Groq, Cerebras, DeepSeek, Together, SiliconFlow",
                () -> Ui.info(this, "Built-in provider presets",
                        ProviderPresets.describe(this)));
        content.addView(providerCard);

        content.addView(sectionLabel("About"));
        LinearLayout aboutCard = card();
        addRow(aboutCard, "About DSH Android", "Version, components, and diagnostics",
                () -> startActivity(new Intent(this, AboutActivity.class)));
        content.addView(aboutCard);

        TextView footnote = Ui.text(this,
                "The DSH interface and its own settings open from the main screen.",
                12, Ui.color(this, R.color.text_dim));
        footnote.setPadding(Ui.dp(this, 2), Ui.dp(this, 18), Ui.dp(this, 2), 0);
        content.addView(footnote);

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page);
        setContentView(root);
        Ui.insets(this, root);
        engine.addListener(this);
    }

    // ------------------------------------------------------------------ rows

    private TextView sectionLabel(String text) {
        TextView label = Ui.label(this, text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 18);
        params.bottomMargin = Ui.dp(this, 8);
        params.leftMargin = Ui.dp(this, 4);
        label.setLayoutParams(params);
        return label;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.outlined(this, Ui.color(this, R.color.card), 18,
                Ui.color(this, R.color.divider)));
        card.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 2);
        card.setLayoutParams(params);
        return card;
    }

    private TextView addValueRow(LinearLayout card, String title, String value) {
        LinearLayout row = Ui.column(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        TextView heading = Ui.text(this, title, 14, Ui.color(this, R.color.text));
        TextView detail = Ui.text(this, value, 12, Ui.color(this, R.color.text_dim));
        detail.setPadding(0, Ui.dp(this, 3), 0, 0);
        detail.setTextIsSelectable(true);
        row.addView(heading);
        row.addView(detail);
        card.addView(row);
        return detail;
    }

    private void addRow(LinearLayout card, String title, String subtitle, Runnable action) {
        addRow(card, title, subtitle, action, false);
    }

    private void addRow(LinearLayout card, String title, String subtitle, Runnable action,
                        boolean danger) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 12), Ui.dp(this, 13));
        row.setClickable(true);
        row.setOnClickListener(v -> action.run());
        LinearLayout texts = Ui.column(this);
        TextView heading = Ui.text(this, title, 15,
                danger ? Ui.color(this, R.color.danger) : Ui.color(this, R.color.text));
        TextView detail = Ui.text(this, subtitle, 12, Ui.color(this, R.color.text_dim));
        detail.setPadding(0, Ui.dp(this, 2), 0, 0);
        texts.addView(heading);
        texts.addView(detail);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = Ui.text(this, "›", 22, Ui.color(this, R.color.text_dim));
        chevron.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 2), 0);
        row.addView(chevron);
        card.addView(row);
    }

    private void addControlRow(LinearLayout card, String title, String subtitle, View control) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        LinearLayout texts = Ui.column(this);
        TextView heading = Ui.text(this, title, 15, Ui.color(this, R.color.text));
        TextView detail = Ui.text(this, subtitle, 12, Ui.color(this, R.color.text_dim));
        detail.setPadding(0, Ui.dp(this, 2), 0, 0);
        texts.addView(heading);
        texts.addView(detail);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(control);
        card.addView(row);
    }

    // --------------------------------------------------------------- actions

    private void requestAllFiles() {
        if (engine.hasAllFilesAccess()) {
            toast("Already granted");
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            }
        }
        requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
    }

    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception error) {
            toast("This device has no battery optimization screen.");
        }
    }

    private String allFilesLabel() {
        return engine.hasAllFilesAccess() ? "Granted" : "Not granted (using app storage)";
    }

    private String batteryLabel() {
        PowerManager power = getSystemService(PowerManager.class);
        if (power == null) return "Unknown";
        return power.isIgnoringBatteryOptimizations(getPackageName())
                ? "Unrestricted" : "Optimized by Android";
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (allFilesSummary != null) allFilesSummary.setText(allFilesLabel());
        if (batterySummary != null) batterySummary.setText(batteryLabel());
        if (workspacePath != null) workspacePath.setText(engine.workspaceDir().getAbsolutePath());
        updateEngineSummary();
    }

    private void updateEngineSummary() {
        if (engineSummary == null) return;
        Engine.State state = engine.state();
        String text = state.name().toLowerCase(java.util.Locale.US);
        if (state == Engine.State.RUNNING && engine.port() > 0) text += " · port " + engine.port();
        if (engine.error() != null && state == Engine.State.FAILED) text += " · " + engine.error();
        engineSummary.setText(text);
    }

    @Override
    public void onState(Engine.State state, String message, int progressPercent) {
        runOnUiThread(this::updateEngineSummary);
    }

    private void returnToMain() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void copy(String label, String value) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            toast("Copied");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (allFilesSummary != null) allFilesSummary.setText(allFilesLabel());
        if (requestCode == 7 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toast("Storage granted; restart the engine to switch workspace");
        }
    }

    @Override
    protected void onDestroy() {
        engine.removeListener(this);
        super.onDestroy();
    }
}
