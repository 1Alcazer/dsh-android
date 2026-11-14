package dev.dsh.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** In-app copy of the notices and full copyleft license texts shipped in assets/licenses. */
public final class LicensesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.color(this, R.color.bg));
        LinearLayout page = Ui.column(this);
        page.addView(Ui.toolbarBar(this, "Third-party licenses", v -> finish()));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = Ui.column(this);
        content.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 28));
        scroll.addView(content);

        TextView intro = Ui.text(this,
                "DSH Android bundles the official DeepSeek Harness engine and an Android "
                        + "runtime built from Termux packages. Tap a document to read it. "
                        + "The same files are installed under assets/licenses in the APK.",
                13, Ui.color(this, R.color.text_dim));
        intro.setLineSpacing(Ui.dp(this, 3), 1f);
        content.addView(intro);

        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.outlined(this, Ui.color(this, R.color.card), 18,
                Ui.color(this, R.color.divider)));
        card.setPadding(Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = Ui.dp(this, 16);
        content.addView(card, cardParams);

        String[] files;
        try {
            files = getAssets().list("licenses");
        } catch (Exception error) {
            files = new String[0];
        }
        if (files != null) Arrays.sort(files);
        if (files == null || files.length == 0) {
            TextView empty = Ui.text(this, "License texts are missing from this build.",
                    14, Ui.color(this, R.color.danger));
            empty.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
            card.addView(empty);
        } else {
            for (String file : files) addDocumentRow(card, file);
        }

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page);
        setContentView(root);
        Ui.insets(this, root);
    }

    private void addDocumentRow(LinearLayout card, String file) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14));
        row.setClickable(true);
        row.setOnClickListener(v -> showDocument(file));
        TextView title = Ui.text(this, titleFor(file), 15, Ui.color(this, R.color.text));
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView chevron = Ui.text(this, "›", 22, Ui.color(this, R.color.text_dim));
        chevron.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 2), 0);
        row.addView(chevron);
        card.addView(row);
    }

    private void showDocument(String file) {
        String text = read("licenses/" + file);
        Ui.info(this, titleFor(file), text);
    }

    private String titleFor(String file) {
        if (file.equals("NOTICE.txt")) return "Third-party notices";
        if (file.equals("GPL-2.0.txt")) return "GNU GPL version 2";
        if (file.equals("GPL-3.0.txt")) return "GNU GPL version 3";
        if (file.equals("LGPL-2.1.txt")) return "GNU LGPL version 2.1";
        if (file.equals("DSH-MIT.txt")) return "DeepSeek Harness (MIT)";
        if (file.equals("Node-MIT.txt")) return "Node.js license";
        return file;
    }

    private String read(String asset) {
        try (InputStream in = getAssets().open(asset)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int read;
            while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            return buffer.toString(StandardCharsets.UTF_8.name());
        } catch (Exception error) {
            return "Could not read " + asset + ": " + error.getMessage();
        }
    }
}
