package com.axon.input;

import android.graphics.Color;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/** Compact persistent multi-color sequence with a continuous looping ARGB interpolation. */
final class ColorSequence {
    static final long SEGMENT_MS = 1800L;
    static final int MAX_COLORS = 8;

    private ColorSequence() {}

    static int[] normalize(int[] colors, int fallback) {
        if (colors == null || colors.length == 0) return new int[]{forceOpaque(fallback)};
        int count = Math.min(MAX_COLORS, colors.length);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = forceOpaque(colors[i]);
        return out;
    }

    static String encode(int[] colors, int fallback) {
        int[] values = normalize(colors, fallback);
        StringBuilder out = new StringBuilder(values.length * 9);
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(String.format(java.util.Locale.US, "%08X", values[i]));
        }
        return out.toString();
    }

    static int[] decode(String encoded, int fallback) {
        if (encoded == null || encoded.trim().isEmpty()) return new int[]{forceOpaque(fallback)};
        String[] parts = encoded.split(",");
        List<Integer> values = new ArrayList<>();
        for (String part : parts) {
            String s = part.trim();
            if (s.startsWith("#")) s = s.substring(1);
            try {
                long raw = Long.parseLong(s, 16);
                int color = s.length() <= 6 ? (0xff000000 | (int) raw) : (int) raw;
                values.add(forceOpaque(color));
            } catch (Throwable ignored) {}
            if (values.size() >= MAX_COLORS) break;
        }
        if (values.isEmpty()) return new int[]{forceOpaque(fallback)};
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) out[i] = values.get(i);
        return out;
    }

    static int current(String encoded, int fallback) {
        return current(decode(encoded, fallback));
    }

    static int current(int[] colors) {
        if (colors == null || colors.length == 0) return Color.WHITE;
        if (colors.length == 1) return colors[0];
        long cycle = SEGMENT_MS * colors.length;
        long t = SystemClock.uptimeMillis() % cycle;
        int index = (int) (t / SEGMENT_MS);
        float fraction = (t % SEGMENT_MS) / (float) SEGMENT_MS;
        int next = (index + 1) % colors.length;
        return lerp(colors[index], colors[next], smooth(fraction));
    }

    static boolean animated(String encoded) {
        if (encoded == null || encoded.isEmpty()) return false;
        return encoded.indexOf(',') >= 0;
    }

    static int first(String encoded, int fallback) {
        int[] colors = decode(encoded, fallback);
        return colors[0];
    }

    private static float smooth(float x) {
        x = Math.max(0f, Math.min(1f, x));
        return x * x * (3f - 2f * x);
    }

    private static int lerp(int a, int b, float t) {
        int aa = Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
        int rr = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int gg = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bb = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.argb(aa, rr, gg, bb);
    }

    private static int forceOpaque(int color) {
        return 0xff000000 | (color & 0x00ffffff);
    }
}
