package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;

/** 手柄只读监听。普通模式不独占 evdev。启用灵敏度倍率后改用代理数据。 */
public final class GamepadInputMonitor {
    public interface Listener {
        void onGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons);
    }

    private interface PrivilegedProcess extends Closeable {
        InputStream getInputStream();
    }

    private final Context context;
    private final Listener listener;
    private final String tempBinaryBase;
    private volatile boolean running;
    private volatile Thread worker;
    private volatile PrivilegedProcess process;
    private final int[] parsedGamepad = new int[7];

    public GamepadInputMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        tempBinaryBase = "/data/local/tmp/axon_input_gamepad_monitor_" + Process.myUid();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        Thread thread = new Thread(this::runLoop, "AxonInputGamepadInput");
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
        listener.onGamepadState(0, 0, 0, 0, 0, 0, 0);
    }

    private void runLoop() {
        while (running) {
            int mode = OverlayState.getSensitivityMode(context);
            if (mode == OverlayState.SENSITIVITY_MODE_SHIZUKU
                    && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) {
                sleep(650L);
                continue;
            }
            PrivilegedProcess current = null;
            try {
                String source = binaryPath();
                if (source == null) break;
                String temp = tempBinaryBase + (mode == OverlayState.SENSITIVITY_MODE_ROOT ? "_root" : "_shizuku");
                String command = "rm -f " + q(temp)
                        + "; cat " + q(source) + " > " + q(temp)
                        + " && chmod 700 " + q(temp)
                        + " && exec " + q(temp);
                current = startPrivileged(mode, command);
                process = current;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) parseLine(line);
                }
            } catch (Throwable ignored) {
                // Root 被拒绝后不重复请求 su。Shizuku 模式可稍后重试。
                if (mode == OverlayState.SENSITIVITY_MODE_ROOT) running = false;
            } finally {
                if (process == current) process = null;
                if (current != null) {
                    try { current.close(); } catch (Throwable ignored) {}
                }
            }
            if (running) sleep(700L);
        }
    }

    private void parseLine(String line) {
        if (line == null || !line.startsWith("GAMEPAD ")
                || !LineInts.parse(line, 8, parsedGamepad)) return;
        listener.onGamepadState(
                parsedGamepad[0], parsedGamepad[1], parsedGamepad[2], parsedGamepad[3],
                parsedGamepad[4], parsedGamepad[5], parsedGamepad[6]);
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

    private String binaryPath() {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info.nativeLibraryDir == null) return null;
            return info.nativeLibraryDir + "/libgamepadmonitor.so";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
