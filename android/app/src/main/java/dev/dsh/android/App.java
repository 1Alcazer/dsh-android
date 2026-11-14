package dev.dsh.android;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Application entry point: log capture and notification channel setup. */
public final class App extends Application {
    public static final String TAG = "DshAndroid";
    public static final String CHANNEL_ENGINE = "engine";
    private static App instance;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        installCrashHandler();
        createChannels();
    }

    public File logsDir() {
        File dir = new File(getFilesDir(), "logs");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter buffer = new StringWriter();
                buffer.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                buffer.write("\nFATAL on " + thread.getName() + "\n");
                error.printStackTrace(new PrintWriter(buffer));
                LogStore.appendRaw(this, "crash.log", buffer.toString());
            } catch (Throwable ignored) {
                // Never mask the original crash.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel engine = new NotificationChannel(
                CHANNEL_ENGINE,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW);
        engine.setDescription("Keeps the on-device DeepSeek Harness engine running.");
        engine.setShowBadge(false);
        manager.createNotificationChannel(engine);
    }
}
