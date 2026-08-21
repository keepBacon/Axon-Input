package com.axon.input;

import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.StringTokenizer;

/** 通过 Shizuku shell 权限读取鼠标按键和相对移动，不修改原始鼠标事件。 */
public final class MouseInputMonitor {
    public interface Listener {
        void onMouseState(long packedStats);
        void onMouseMotion(int dx, int dy);
        void onMousePromptButton(int button, boolean pressed);
    }

    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_BACK = 3;
    public static final int BUTTON_FORWARD = 4;

    private final Listener listener;
    private volatile boolean running;
    private volatile ShizukuBridge.ShellProcess process;
    private Thread worker;

    public MouseInputMonitor(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::runLoop, "AxonInputMouseInput");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        ShizukuBridge.ShellProcess p = process;
        process = null;
        if (p != null) p.close();
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
        listener.onMouseState(NativeKeyEngine.nativeResetMouse(SystemClock.uptimeMillis()));
        listener.onMouseMotion(0, 0);
    }

    private void runLoop() {
        while (running) {
            try {
                ShizukuBridge.ShellProcess p = ShizukuBridge.startShell("/system/bin/getevent -lt");
                process = p;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) parseLine(line);
                } finally {
                    if (process == p) process = null;
                    p.close();
                }
            } catch (Throwable ignored) {
                if (!running) break;
                try {
                    Thread.sleep(700L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void parseLine(String line) {
        if (line == null || line.isEmpty()) return;
        String payload = payload(line);

        int axis = detectRelativeAxis(payload);
        if (axis >= 0) {
            int delta = detectRelativeValue(payload);
            if (delta != Integer.MIN_VALUE && delta != 0) {
                if (axis == 0) listener.onMouseMotion(delta, 0);
                else listener.onMouseMotion(0, delta);
            }
            return;
        }

        int button = detectButton(payload);
        if (button < 0) return;
        int value = detectButtonValue(payload);
        if (value < 0) return;
        if (button == NativeKeyEngine.MOUSE_LEFT || button == NativeKeyEngine.MOUSE_RIGHT) {
            long stats = NativeKeyEngine.nativeUpdateMouseButton(
                    button, value != 0, SystemClock.uptimeMillis());
            listener.onMouseState(stats);
        } else {
            listener.onMousePromptButton(button, value != 0);
        }
    }

    private String payload(String line) {
        int colon = line.lastIndexOf(':');
        return colon >= 0 ? line.substring(colon + 1).trim() : line.trim();
    }

    /** 0 = REL_X, 1 = REL_Y, -1 = not a relative mouse axis event. */
    private int detectRelativeAxis(String payload) {
        if (payload.contains("REL_X")) return 0;
        if (payload.contains("REL_Y")) return 1;

        StringTokenizer tokens = new StringTokenizer(payload);
        if (tokens.countTokens() < 3) return -1;
        int eventType = parseHexToken(tokens.nextToken());
        int eventCode = parseHexToken(tokens.nextToken());
        if (eventType != 0x0002) return -1; // EV_REL
        if (eventCode == 0x0000) return 0;  // REL_X
        if (eventCode == 0x0001) return 1;  // REL_Y
        return -1;
    }

    private int detectRelativeValue(String payload) {
        String token = lastToken(payload);
        return parseSignedHexToken(token);
    }

    private int detectButton(String payload) {
        // Android getevent may label Linux code 0x110 as BTN_LEFT or BTN_MOUSE.
        if (payload.contains("BTN_LEFT") || payload.contains("BTN_MOUSE")) {
            return NativeKeyEngine.MOUSE_LEFT;
        }
        if (payload.contains("BTN_RIGHT")) return NativeKeyEngine.MOUSE_RIGHT;
        if (payload.contains("BTN_MIDDLE")) return BUTTON_MIDDLE;
        if (payload.contains("BTN_BACK") || payload.contains("BTN_SIDE")) return BUTTON_BACK;
        if (payload.contains("BTN_FORWARD") || payload.contains("BTN_EXTRA")) return BUTTON_FORWARD;

        StringTokenizer tokens = new StringTokenizer(payload);
        if (tokens.countTokens() < 2) return -1;
        int eventType = parseHexToken(tokens.nextToken());
        int eventCode = parseHexToken(tokens.nextToken());
        if (eventType != 0x0001) return -1; // EV_KEY
        if (eventCode == 0x0110) return NativeKeyEngine.MOUSE_LEFT;
        if (eventCode == 0x0111) return NativeKeyEngine.MOUSE_RIGHT;
        if (eventCode == 0x0112) return BUTTON_MIDDLE;
        if (eventCode == 0x0113 || eventCode == 0x0116) return BUTTON_BACK;
        if (eventCode == 0x0114 || eventCode == 0x0115) return BUTTON_FORWARD;
        return -1;
    }

    private int detectButtonValue(String payload) {
        if (payload.contains(" DOWN")) return 1;
        if (payload.contains(" UP")) return 0;
        int value = parseSignedHexToken(lastToken(payload));
        if (value == 0) return 0;
        if (value == 1 || value == 2) return value;
        return -1;
    }

    private String lastToken(String text) {
        String trimmed = text.trim();
        int split = trimmed.lastIndexOf(' ');
        return split >= 0 ? trimmed.substring(split + 1) : trimmed;
    }

    private int parseHexToken(String token) {
        try {
            return (int) Long.parseLong(token, 16);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Parses Linux input_event values such as ffffffff as signed 32-bit integers. */
    private int parseSignedHexToken(String token) {
        try {
            long raw = Long.parseLong(token, 16) & 0xffffffffL;
            return (int) raw;
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }
}
