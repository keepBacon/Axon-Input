package com.axon.input;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-free bridge from Axon's existing global REL_X/REL_Y stream to Live2D renderers.
 *
 * The tracker stores cumulative relative motion rather than consuming individual events. Each
 * Live2DOverlayView keeps its own baseline, so foreground/in-app and accessibility-overlay
 * renderers can hand off without stealing events from one another or adding a second mouse reader.
 */
final class Live2DMouseTracker {
    static final class Sample {
        final long generation;
        final long totalDx;
        final long totalDy;
        final long timestampMs;

        Sample(long generation, long totalDx, long totalDy, long timestampMs) {
            this.generation = generation;
            this.totalDx = totalDx;
            this.totalDy = totalDy;
            this.timestampMs = timestampMs;
        }
    }

    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong TOTAL_DX = new AtomicLong();
    private static final AtomicLong TOTAL_DY = new AtomicLong();
    private static final AtomicLong TIMESTAMP_MS = new AtomicLong();

    private Live2DMouseTracker() {}

    static void addMotion(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        if (dx != 0) TOTAL_DX.addAndGet(dx);
        if (dy != 0) TOTAL_DY.addAndGet(dy);
        TIMESTAMP_MS.set(SystemClock.elapsedRealtime());
        GENERATION.incrementAndGet();
    }

    static Sample snapshot() {
        // Read generation last/first is not required here: a one-event skew is harmless because
        // cumulative totals are monotonic and the following pump catches the newest generation.
        long generation = GENERATION.get();
        return new Sample(generation, TOTAL_DX.get(), TOTAL_DY.get(), TIMESTAMP_MS.get());
    }

    static void clear() {
        TOTAL_DX.set(0L);
        TOTAL_DY.set(0L);
        TIMESTAMP_MS.set(SystemClock.elapsedRealtime());
        GENERATION.incrementAndGet();
    }
}
