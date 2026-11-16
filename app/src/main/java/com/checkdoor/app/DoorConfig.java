package com.checkdoor.app;

import android.content.Context;
import android.content.SharedPreferences;

public class DoorConfig {
    private static final String PREF_NAME = "checkdoor_prefs";
    private static final String KEY_CLOSED_ANGLE = "closed_angle";
    private static final String KEY_OPEN_ANGLE = "open_angle";
    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_TARGET_IP = "target_ip";
    private static final String KEY_TARGET_PORT = "target_port";

    public static void saveCalibration(Context context, float closedAngle, float openAngle) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putFloat(KEY_CLOSED_ANGLE, closedAngle)
                .putFloat(KEY_OPEN_ANGLE, openAngle)
                .apply();
    }

    public static void saveClosedAngle(Context context, float angle) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_CLOSED_ANGLE, angle).apply();
    }

    public static void saveOpenAngle(Context context, float angle) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_OPEN_ANGLE, angle).apply();
    }

    public static float getClosedAngle(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getFloat(KEY_CLOSED_ANGLE, -1f);
    }

    public static float getOpenAngle(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getFloat(KEY_OPEN_ANGLE, -1f);
    }

    public static void saveThreshold(Context context, float threshold) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putFloat(KEY_THRESHOLD, threshold).apply();
    }

    public static float getThreshold(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getFloat(KEY_THRESHOLD, 8.0f);
    }

    public static void saveNetwork(Context context, String ip, int port) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit()
                .putString(KEY_TARGET_IP, ip)
                .putInt(KEY_TARGET_PORT, port)
                .apply();
    }

    public static String getTargetIp(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_TARGET_IP, "255.255.255.255");
    }

    public static int getTargetPort(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getInt(KEY_TARGET_PORT, 39888);
    }

    public static float calculateAngleDiff(float a, float b) {
        float diff = Math.abs(a - b) % 360f;
        return diff > 180f ? 360f - diff : diff;
    }
}
