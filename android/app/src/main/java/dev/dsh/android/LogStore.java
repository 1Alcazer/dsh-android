package dev.dsh.android;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * In-memory ring buffer plus an append-only engine log file. Writer methods are
 * synchronized because stdout, stderr, and UI threads all append here.
 */
public final class LogStore {
    private static final int MAX_LINES = 4000;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private LogStore() {
    }

    public static void append(String tag, String line) {
        if (line == null) return;
        String stamped = TIME.format(new Date()) + " " + tag + ": " + line;
        synchronized (LOCK) {
            LINES.addLast(stamped);
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
        }
        Log.d(App.TAG, stamped);
    }

    public static void appendEngine(String stream, String line) {
        append(stream, line);
        App app = App.get();
        if (app != null) appendRaw(app, "engine.log", line + "\n");
    }

    public static void appendRaw(Context context, String fileName, String text) {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) return;
        File file = new File(dir, fileName);
        try (FileOutputStream out = new FileOutputStream(file, true);
             OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write(text);
        } catch (IOException error) {
            Log.w(App.TAG, "cannot append " + fileName, error);
        }
    }

    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            LINES.clear();
        }
    }

    public static File engineLogFile(Context context) {
        return new File(new File(context.getFilesDir(), "logs"), "engine.log");
    }
}
