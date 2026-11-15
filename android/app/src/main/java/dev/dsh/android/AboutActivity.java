package dev.dsh.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Version, credits, and a copyable diagnostics block. */
public final class AboutActivity extends Activity {
    private Engine engine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        engine = Engine.get(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        LinearLayout page = Ui.column(this);
        page.addView(Ui.toolbarBar(this, "About", v -> finish()));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 22), Ui.dp(this, 6), Ui.dp(this, 22), Ui.dp(this, 30));
        scroll.addView(content);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(
                Ui.dp(this, 76), Ui.dp(this, 76));
        logoParams.gravity = Gravity.START;
        content.addView(logo, logoParams);

        TextView name = Ui.text(this, "DSH Android", 26, Ui.color(this, R.color.text));
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = Ui.dp(this, 14);
        content.addView(name, nameParams);

        TextView version = Ui.text(this, "version " + appVersion(), 13,
                Ui.color(this, R.color.text_dim));
        content.addView(version);

        TextView blurb = Ui.text(this,
                "An independent, native Android shell for DeepSeek Harness. The full DSH web "
                        + "engine runs on-device under a bundled Node runtime — no emulator, no "
                        + "remote server. DeepSeek Harness is a DeepSeek project; this shell is "
                        + "not an official DeepSeek app.",
                14, Ui.color(this, R.color.text_dim));
        blurb.setLineSpacing(Ui.dp(this, 3), 1f);
        LinearLayout.LayoutParams blurbParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blurbParams.topMargin = Ui.dp(this, 16);
        content.addView(blurb, blurbParams);

        content.addView(section("Engine"));
        LinearLayout engineCard = card();
        addValue(engineCard, "DeepSeek Harness", Engine.DSH_VERSION);
        addValue(engineCard, "Node runtime", Engine.NODE_VERSION + " (Termux aarch64 build)");
        addValue(engineCard, "Shell and tools", "bash 5.3.20 · git " + Engine.GIT_VERSION
                + " (HTTPS remotes)");
        addValue(engineCard, "Boot mode", "web profile · loopback HTTP · token auth");
        addValue(engineCard, "Current state", engine.state().name().toLowerCase(Locale.US)
                + (engine.port() > 0 ? " · port " + engine.port() : ""));
        addValue(engineCard, "WebView", webViewVersion());
        TextView httpsValue = addValue(engineCard, "Outbound HTTPS", "checking…");
        TextView loopbackValue = addValue(engineCard, "Engine endpoint",
                engine.port() > 0 ? "http://127.0.0.1:" + engine.port() : "not running");
        content.addView(engineCard);
        checkHttps(httpsValue);
        checkLoopback(loopbackValue);

        content.addView(section("Device"));
        LinearLayout deviceCard = card();
        addValue(deviceCard, "Device", Build.MANUFACTURER + " " + Build.MODEL);
        addValue(deviceCard, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        addValue(deviceCard, "ABI", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        content.addView(deviceCard);

        content.addView(section("Data"));
        LinearLayout dataCard = card();
        addValue(dataCard, "DSH home", engine.homeDir().getAbsolutePath());
        addValue(dataCard, "Workspace", engine.workspaceDir().getAbsolutePath());
        content.addView(dataCard);

        content.addView(section("Components and licenses"));
        LinearLayout licenseCard = card();
        addValue(licenseCard, "DeepSeek Harness", "MIT · © DeepSeek");
        addValue(licenseCard, "Node.js", "MIT · © OpenJS Foundation and contributors");
        addValue(licenseCard, "Bash and Readline", "GPL-3.0-or-later · GNU Project");
        addValue(licenseCard, "Git", "GPL-2.0 · © Git contributors");
        addValue(licenseCard, "ncurses", "MIT-style · © Free Software Foundation");
        addValue(licenseCard, "GNU libiconv", "LGPL-2.1-or-later · © Free Software Foundation");
        addValue(licenseCard, "ICU", "Unicode License v3");
        addValue(licenseCard, "OpenSSL", "Apache License 2.0");
        addValue(licenseCard, "Termux runtime packages", "Their respective licenses; full notices and texts below");
        content.addView(licenseCard);

        TextView licenses = Ui.button(this, "Third-party licenses and notices", false);
        licenses.setOnClickListener(v -> startActivity(new Intent(this, LicensesActivity.class)));
        LinearLayout.LayoutParams licensesParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        licensesParams.topMargin = Ui.dp(this, 12);
        content.addView(licenses, licensesParams);

        TextView copy = Ui.button(this, "Copy diagnostics", true);
        copy.setOnClickListener(v -> copyDiagnostics());
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.topMargin = Ui.dp(this, 22);
        content.addView(copy, copyParams);

        TextView source = Ui.button(this, "DeepSeek Harness project", false);
        source.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/deepseek-ai/deepseek-harness")));
            } catch (Exception error) {
                toast("No browser installed");
            }
        });
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = Ui.dp(this, 10);
        content.addView(source, sourceParams);

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page);
        setContentView(root);
        Ui.insets(this, root);
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.outlined(this, Ui.color(this, R.color.card), 18,
                Ui.color(this, R.color.divider)));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        return card;
    }

    private TextView addValue(LinearLayout card, String label, String value) {
        LinearLayout row = Ui.column(this);
        row.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));
        TextView heading = Ui.text(this, label, 11, Ui.color(this, R.color.text_dim));
        heading.setAllCaps(true);
        heading.setLetterSpacing(0.05f);
        TextView detail = Ui.text(this, value, 14, Ui.color(this, R.color.text));
        detail.setPadding(0, Ui.dp(this, 3), 0, 0);
        detail.setTextIsSelectable(true);
        row.addView(heading);
        row.addView(detail);
        card.addView(row);
        return detail;
    }

    private String webViewVersion() {
        if (Build.VERSION.SDK_INT < 26) return "system WebView";
        try {
            android.content.pm.PackageInfo info = android.webkit.WebView.getCurrentWebViewPackage();
            if (info == null) return "unknown";
            return info.packageName + " " + info.versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private void checkHttps(TextView target) {
        new Thread(() -> {
            String result;
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection)
                        new java.net.URL("https://api.deepseek.com/").openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                // 401 is the expected answer without an API key: TLS worked.
                result = code == 401 ? "reachable (TLS ok · HTTP 401 without key)"
                        : "reachable (HTTP " + code + ")";
            } catch (Exception error) {
                result = "failed: " + error.getClass().getSimpleName();
            } finally {
                if (connection != null) connection.disconnect();
            }
            String text = result;
            runOnUiThread(() -> {
                if (!isFinishing()) target.setText(text);
            });
        }, "dsh-net-check").start();
    }

    private void checkLoopback(TextView target) {
        int port = engine.port();
        if (port <= 0) {
            target.setText("not running");
            return;
        }
        new Thread(() -> {
            String result;
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection)
                        new java.net.URL("http://127.0.0.1:" + port + "/").openConnection();
                connection.setConnectTimeout(4_000);
                connection.setReadTimeout(4_000);
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                result = "http://127.0.0.1:" + port + " · responding (HTTP " + code + ")";
            } catch (Exception error) {
                result = "http://127.0.0.1:" + port + " · unreachable ("
                        + error.getClass().getSimpleName() + ")";
            } finally {
                if (connection != null) connection.disconnect();
            }
            String text = result;
            runOnUiThread(() -> {
                if (!isFinishing()) target.setText(text);
            });
        }, "dsh-loopback-check").start();
    }

    private TextView section(String title) {
        TextView view = Ui.label(this, title);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 22);
        params.bottomMargin = Ui.dp(this, 8);
        params.leftMargin = Ui.dp(this, 2);
        view.setLayoutParams(params);
        return view;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private void copyDiagnostics() {
        String text = "DSH Android " + appVersion() + "\n"
                + "engine: " + Engine.DSH_VERSION + "\n"
                + "node: " + Engine.NODE_VERSION + "\n"
                + "state: " + engine.state().name() + "\n"
                + "port: " + engine.port() + "\n"
                + "device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "abi: " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + "\n"
                + "home: " + engine.homeDir().getAbsolutePath() + "\n"
                + "workspace: " + engine.workspaceDir().getAbsolutePath() + "\n";
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("DSH diagnostics", text));
        toast("Diagnostics copied");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
