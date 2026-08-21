package com.axon.input;

/** 固定容量的一秒滑动计数器，不创建集合对象。 */
final class DpsTracker {
    static final int SPACE = 0;
    static final int FACE_Y = 1;
    static final int FACE_X = 2;
    static final int FACE_B = 3;
    static final int FACE_A = 4;
    static final int L1 = 5;
    static final int R1 = 6;
    static final int TARGET = 7;

    private static final int CHANNELS = 8;
    private static final int CAPACITY = 128;
    private static final long WINDOW_MS = 1000L;

    private final long[][] times = new long[CHANNELS][CAPACITY];
    private final int[] head = new int[CHANNELS];
    private final int[] size = new int[CHANNELS];

    synchronized void record(int channel, long nowMs) {
        if (channel < 0 || channel >= CHANNELS) return;
        trim(channel, nowMs);
        if (size[channel] == CAPACITY) {
            head[channel] = (head[channel] + 1) % CAPACITY;
            size[channel]--;
        }
        int index = (head[channel] + size[channel]) % CAPACITY;
        times[channel][index] = nowMs;
        size[channel]++;
    }

    synchronized int count(int channel, long nowMs) {
        if (channel < 0 || channel >= CHANNELS) return 0;
        trim(channel, nowMs);
        return size[channel];
    }

    synchronized void reset() {
        for (int i = 0; i < CHANNELS; i++) {
            head[i] = 0;
            size[i] = 0;
        }
    }

    synchronized void resetChannel(int channel) {
        if (channel < 0 || channel >= CHANNELS) return;
        head[channel] = 0;
        size[channel] = 0;
    }

    private void trim(int channel, long nowMs) {
        while (size[channel] > 0) {
            long oldest = times[channel][head[channel]];
            if (nowMs - oldest < WINDOW_MS) break;
            head[channel] = (head[channel] + 1) % CAPACITY;
            size[channel]--;
        }
    }
}
