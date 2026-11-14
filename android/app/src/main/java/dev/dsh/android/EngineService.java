package dev.dsh.android;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps the engine process alive (and the CPU awake) while the app is in the
 * background, with a low-importance notification for status and control.
 */
public final class EngineService extends Service implements Engine.Listener {
    public static final String ACTION_START = "dev.dsh.android.action.START";
    public static final String ACTION_STOP = "dev.dsh.android.action.STOP";
    private static final int NOTIFICATION_ID = 41;

    private Engine engine;
    private PowerManager.WakeLock wakeLock;
    private boolean foreground;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = Engine.get(this);
        engine.addListener(this);
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:engine");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            engine.stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground(notificationText());
        engine.start();
        return START_STICKY;
    }

    private void startInForeground(String text) {
        Notification notification = buildNotification(text);
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foreground = true;
        } catch (Exception error) {
            // Android 12+ can refuse a foreground start from the background. The
            // engine still starts; the next foreground activity restarts the
            // service and promotes it again.
            foreground = false;
            LogStore.append("service", "foreground start refused: " + error);
        }
        updateWakeLock();
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, EngineService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, App.CHANNEL_ENGINE)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("DSH engine")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
                .build();
    }

    private String notificationText() {
        Engine.State state = engine.state();
        if (state == Engine.State.RUNNING) {
            int port = engine.port();
            return port > 0 ? "Running on port " + port + " · tap to open"
                    : "Running · tap to open";
        }
        if (state == Engine.State.EXTRACTING) return "Preparing the engine…";
        if (state == Engine.State.STARTING) return "Starting the engine…";
        if (state == Engine.State.FAILED) return "Engine stopped with an error";
        return "Waiting for the engine";
    }

    @Override
    public void onState(Engine.State state, String message, int progressPercent) {
        if (!foreground) return;
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(notificationText()));
        }
        updateWakeLock();
    }

    private void updateWakeLock() {
        if (wakeLock == null) return;
        Engine.State state = engine.state();
        boolean needed = state == Engine.State.EXTRACTING
                || state == Engine.State.STARTING
                || state == Engine.State.RUNNING;
        if (needed && !wakeLock.isHeld()) {
            wakeLock.acquire();
        } else if (!needed && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (engine != null) engine.removeListener(this);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
