package dev.dsh.android;

import android.app.Activity;
import android.os.Build;
import android.window.OnBackInvokedDispatcher;

/**
 * Registers a predictive-back callback on Android 13+.
 *
 * <p>This class is only touched when {@link Build.VERSION#SDK_INT} is at least
 * 33, so its API-33 references never reach the verifier on older devices.</p>
 */
final class BackCompat {
    private BackCompat() {
    }

    static void enable(Activity activity, Runnable fallback) {
        if (Build.VERSION.SDK_INT < 33) return;
        OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
        dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, fallback::run);
    }
}
