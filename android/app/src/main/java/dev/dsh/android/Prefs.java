package dev.dsh.android;

import android.content.Context;
import android.content.SharedPreferences;

/** Small, typed wrapper around app preferences. */
public final class Prefs {
    private static final String FILE = "settings";
    private static final String TELEMETRY = "telemetry";
    private static final String BATTERY_NOTICE = "battery_notice_shown";
    private static final String FIRST_RUN = "first_run_done";

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Official telemetry stays off unless the user explicitly opts in. */
    public static boolean telemetry(Context context) {
        return sp(context).getBoolean(TELEMETRY, false);
    }

    public static void setTelemetry(Context context, boolean enabled) {
        sp(context).edit().putBoolean(TELEMETRY, enabled).apply();
    }

    public static boolean batteryNoticeShown(Context context) {
        return sp(context).getBoolean(BATTERY_NOTICE, false);
    }

    public static void setBatteryNoticeShown(Context context) {
        sp(context).edit().putBoolean(BATTERY_NOTICE, true).apply();
    }

    public static boolean firstRunDone(Context context) {
        return sp(context).getBoolean(FIRST_RUN, false);
    }

    public static void setFirstRunDone(Context context) {
        sp(context).edit().putBoolean(FIRST_RUN, true).apply();
    }
}
