package com.axon.input;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * 通过当前选择的 Shizuku / Root 只读监听 getevent。
 *
 * 鼠标按键按 /dev/input/event* 分设备聚合，避免多个输入节点的 DOWN/UP 相互覆盖；
 * 监听进程断开时主动释放左右键，防止 BongoCat 按键视觉卡在按下态。
 */
public final class MouseInputMonitor {
    public interface Listener {
        void onMouseState(long packedStats);
        void onMouseMotion(int dx, int dy);
        void onMousePromptButton(int button, boolean pressed);
    }

    private interface PrivilegedProcess extends Closeable {
        InputStream getInputStream();
    }

    public static final int BUTTON_MIDDLE = 2;
    public static final int BUTTON_BACK = 3;
    public static final int BUTTON_FORWARD = 4;

    private final Context context;
    private final Listener listener;
    private final Map<String, Integer> deviceButtonMasks = new HashMap<>();
    private volatile boolean running;
    private volatile PrivilegedProcess process;
    private Thread worker;
    private int aggregateButtons;

    public MouseInputMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread thread = new Thread(this::runLoop, "AxonInputMouseInput");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        PrivilegedProcess current = process;
        process = null;
        if (current != null) {
            try { current.close(); } catch (Throwable ignored) {}
        }
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
        resetPressedButtons();
        listener.onMouseMotion(0, 0);
    }

    private void runLoop() {
        while (running) {
            int mode = OverlayState.getSensitivityMode(context);
            if (mode != OverlayState.SENSITIVITY_MODE_ROOT
                    && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) {
                sleep(500L);
                continue;
            }

            PrivilegedProcess current = null;
            try {
                // 每次重新连接都先清理上一次可能丢失的 UP 状态。
                resetPressedButtons();
                current = startPrivileged(mode, "/system/bin/getevent -lt");
                process = current;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) parseLine(line);
                }
            } catch (Throwable ignored) {
                // Root 被拒绝时避免反复触发 su 授权弹窗；Shizuku 可等待服务恢复后重连。
                if (mode == OverlayState.SENSITIVITY_MODE_ROOT) running = false;
            } finally {
                if (process == current) process = null;
                if (current != null) {
                    try { current.close(); } catch (Throwable ignored) {}
                }
                resetPressedButtons();
            }

            if (running) sleep(500L);
        }
    }

    private PrivilegedProcess startPrivileged(int mode, String command) throws Exception {
        if (mode == OverlayState.SENSITIVITY_MODE_ROOT) {
            RootBridge.RootProcess root = RootBridge.startShell(command);
            return new PrivilegedProcess() {
                @Override public InputStream getInputStream() { return root.getInputStream(); }
                @Override public void close() { root.close(); }
            };
        }

        ShizukuBridge.ShellProcess shizuku = ShizukuBridge.startShell(command);
        return new PrivilegedProcess() {
            @Override public InputStream getInputStream() { return shizuku.getInputStream(); }
            @Override public void close() { shizuku.close(); }
        };
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
        if (value < 0 || value == 2) return; // EV_KEY repeat 不是新的按下沿。

        boolean pressed = value != 0;
        if (button == NativeKeyEngine.MOUSE_LEFT || button == NativeKeyEngine.MOUSE_RIGHT) {
            updatePrimaryButton(deviceKey(line), button, pressed);
        } else {
            listener.onMousePromptButton(button, pressed);
        }
    }

    private synchronized void updatePrimaryButton(String device, int button, boolean pressed) {
        String key = device == null ? "<unknown>" : device;
        int bit = 1 << button;
        int oldMask = deviceButtonMasks.getOrDefault(key, 0);
        int nextMask = pressed ? (oldMask | bit) : (oldMask & ~bit);
        if (nextMask == oldMask) return;

        if (nextMask == 0) deviceButtonMasks.remove(key);
        else deviceButtonMasks.put(key, nextMask);

        int nextAggregate = 0;
        for (int mask : deviceButtonMasks.values()) nextAggregate |= mask;
        nextAggregate &= 0x3;
        int changed = aggregateButtons ^ nextAggregate;
        if (changed == 0) return;

        aggregateButtons = nextAggregate;
        long now = SystemClock.uptimeMillis();
        long stats = NativeKeyEngine.nativeGetMouseStats(now);
        if ((changed & 1) != 0) {
            stats = NativeKeyEngine.nativeUpdateMouseButton(
                    NativeKeyEngine.MOUSE_LEFT, (nextAggregate & 1) != 0, now);
        }
        if ((changed & 2) != 0) {
            stats = NativeKeyEngine.nativeUpdateMouseButton(
                    NativeKeyEngine.MOUSE_RIGHT, (nextAggregate & 2) != 0, now);
        }
        listener.onMouseState(stats);
    }

    private synchronized void resetPressedButtons() {
        deviceButtonMasks.clear();
        aggregateButtons = 0;
        long now = SystemClock.uptimeMillis();
        long stats = NativeKeyEngine.nativeUpdateMouseButton(NativeKeyEngine.MOUSE_LEFT, false, now);
        stats = NativeKeyEngine.nativeUpdateMouseButton(NativeKeyEngine.MOUSE_RIGHT, false, now);
        listener.onMouseState(stats);
    }

    private String payload(String line) {
        int colon = line.lastIndexOf(':');
        return colon >= 0 ? line.substring(colon + 1).trim() : line.trim();
    }

    private String deviceKey(String line) {
        int start = line.indexOf("/dev/input/");
        if (start < 0) return null;
        int end = line.indexOf(':', start);
        if (end < 0) end = line.indexOf(' ', start);
        if (end < 0) end = line.length();
        return line.substring(start, end).trim();
    }

    /** 0 表示 REL_X，1 表示 REL_Y，-1 表示非相对轴事件。 */
    private int detectRelativeAxis(String payload) {
        if (payload.contains("REL_X")) return 0;
        if (payload.contains("REL_Y")) return 1;

        StringTokenizer tokens = new StringTokenizer(payload);
        if (tokens.countTokens() < 3) return -1;
        int eventType = parseHexToken(tokens.nextToken());
        int eventCode = parseHexToken(tokens.nextToken());
        if (eventType != 0x0002) return -1;
        if (eventCode == 0x0000) return 0;
        if (eventCode == 0x0001) return 1;
        return -1;
    }

    private int detectRelativeValue(String payload) {
        return parseSignedHexToken(lastToken(payload));
    }

    private int detectButton(String payload) {
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
        if (eventType != 0x0001) return -1;
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
        if (value == 0 || value == 1 || value == 2) return value;
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

    private int parseSignedHexToken(String token) {
        try {
            long raw = Long.parseLong(token, 16) & 0xffffffffL;
            return (int) raw;
        } catch (NumberFormatException ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
