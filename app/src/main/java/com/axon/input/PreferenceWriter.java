package com.axon.input;

import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Small write-through helper that avoids redundant SharedPreferences disk work. */
final class PreferenceWriter {
    private PreferenceWriter() {}

    static boolean putBooleanIfChanged(SharedPreferences preferences, String key, boolean value) {
        if (preferences.getBoolean(key, !value) == value && preferences.contains(key)) return false;
        preferences.edit().putBoolean(key, value).apply();
        return true;
    }

    static boolean putIntIfChanged(SharedPreferences preferences, String key, int value) {
        if (preferences.getInt(key, value) == value && preferences.contains(key)) return false;
        preferences.edit().putInt(key, value).apply();
        return true;
    }

    static boolean putStringIfChanged(SharedPreferences preferences, String key, String value) {
        String next = value == null ? "" : value;
        String current = preferences.getString(key, null);
        if (next.equals(current)) return false;
        preferences.edit().putString(key, next).apply();
        return true;
    }

    static boolean putStringSetIfChanged(SharedPreferences preferences, String key, Set<String> value) {
        Set<String> next = value == null ? new HashSet<>() : new HashSet<>(value);
        Set<String> current = preferences.getStringSet(key, null);
        if (current != null && current.equals(next)) return false;
        // SharedPreferences keeps a reference-compatible set internally on some Android builds;
        // always persist a defensive copy so caller mutations cannot corrupt stored state.
        preferences.edit().putStringSet(key, next).apply();
        return true;
    }
}
