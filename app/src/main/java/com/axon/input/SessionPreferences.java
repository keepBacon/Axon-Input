package com.axon.input;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 本次运行会话使用的内存配置。
 *
 * 设计目的：
 * 1. 普通开关、位置、大小、颜色只在当前会话有效，不自动写入磁盘。
 * 2. 进程被系统结束后，内存配置自然消失，不会由无障碍服务恢复旧状态。
 * 3. “配置1”仍可显式保存到 JSON 文件，用户需要时再手动加载。
 */
public final class SessionPreferences implements SharedPreferences {
    private static final SessionPreferences INSTANCE = new SessionPreferences();

    private final Map<String, Object> values = new HashMap<>();
    private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();

    private SessionPreferences() {}

    public static SessionPreferences get() {
        return INSTANCE;
    }

    /** 新的一次应用进入，从默认配置开始。 */
    public void reset() {
        Set<String> changed;
        synchronized (this) {
            changed = new HashSet<>(values.keySet());
            values.clear();
        }
        notifyChanged(changed);
    }

    @Override
    public synchronized Map<String, ?> getAll() {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) value = new HashSet<>((Set<?>) value);
            copy.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(copy);
    }

    @Override public synchronized String getString(String key, String defValue) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Override public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
        Object value = values.get(key);
        if (!(value instanceof Set<?>)) return defValues;
        Set<String> copy = new HashSet<>();
        for (Object item : (Set<?>) value) if (item instanceof String) copy.add((String) item);
        return copy;
    }

    @Override public synchronized int getInt(String key, int defValue) {
        Object value = values.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override public synchronized long getLong(String key, long defValue) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override public synchronized float getFloat(String key, float defValue) {
        Object value = values.get(key);
        return value instanceof Float ? (Float) value : defValue;
    }

    @Override public synchronized boolean getBoolean(String key, boolean defValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override public Editor edit() {
        return new MemoryEditor();
    }

    @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged(Set<String> keys) {
        if (keys.isEmpty()) return;
        final ArrayList<OnSharedPreferenceChangeListener> copy;
        synchronized (this) {
            copy = new ArrayList<>(listeners);
        }
        for (String key : keys) {
            for (OnSharedPreferenceChangeListener listener : copy) {
                listener.onSharedPreferenceChanged(this, key);
            }
        }
    }

    private final class MemoryEditor implements Editor {
        private final Map<String, Object> updates = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clear;

        @Override public Editor putString(String key, String value) { updates.put(key, value); removals.remove(key); return this; }
        @Override public Editor putStringSet(String key, Set<String> values) {
            updates.put(key, values == null ? null : new HashSet<>(values)); removals.remove(key); return this;
        }
        @Override public Editor putInt(String key, int value) { updates.put(key, value); removals.remove(key); return this; }
        @Override public Editor putLong(String key, long value) { updates.put(key, value); removals.remove(key); return this; }
        @Override public Editor putFloat(String key, float value) { updates.put(key, value); removals.remove(key); return this; }
        @Override public Editor putBoolean(String key, boolean value) { updates.put(key, value); removals.remove(key); return this; }
        @Override public Editor remove(String key) { removals.add(key); updates.remove(key); return this; }
        @Override public Editor clear() { clear = true; updates.clear(); removals.clear(); return this; }

        @Override public boolean commit() {
            Set<String> changed = new HashSet<>();
            synchronized (SessionPreferences.this) {
                if (clear) {
                    changed.addAll(values.keySet());
                    values.clear();
                }
                for (String key : removals) {
                    if (values.remove(key) != null) changed.add(key);
                }
                for (Map.Entry<String, Object> entry : updates.entrySet()) {
                    Object next = entry.getValue();
                    Object old = next == null ? values.remove(entry.getKey()) : values.put(entry.getKey(), next);
                    if (old == null ? next != null : !old.equals(next)) changed.add(entry.getKey());
                }
            }
            notifyChanged(changed);
            return true;
        }

        @Override public void apply() { commit(); }
    }
}
