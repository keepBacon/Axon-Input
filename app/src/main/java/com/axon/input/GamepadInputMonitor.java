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
        void onGamepadProfile(boolean connected, boolean vader5Pro, boolean legacyThumb2AsL1);
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
        listener.onGamepadProfile(false, false, false);
    }

    private void runLoop() {
        // 只提高只读解析线程的调度优先级，不改变 Native 采样频率；数字 DOWN/UP 更快进入 Service。
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); } catch (Throwable ignored) {}
        while (running) {
            int mode = SensitivitySettingsStore.getMode(context);
            if (mode == SensitivitySettingsStore.MODE_ROOT && !RootBridge.isRootActive()) {
                RootBridge.ensureActivated(context, null);
                sleep(400L);
                continue;
            }
            if (mode == SensitivitySettingsStore.MODE_SHIZUKU
                    && (!ShizukuBridge.isReady() || !ShizukuBridge.hasPermission())) {
                sleep(350L);
                continue;
            }
            PrivilegedProcess current = null;
            try {
                String source = binaryPath();
                if (source == null) {
                    // APK/Native 尚未就绪时不要让 worker 静默死亡；后续刷新或安装修复后可自恢复。
                    sleep(1000L);
                    continue;
                }
                String temp = tempBinaryBase + (mode == SensitivitySettingsStore.MODE_ROOT ? "_root" : "_shizuku");
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
                // helper/权限通道异常只中断本次连接，不终止 monitor worker。
                // Root 通道同时撤销“已激活”缓存并重新探测，允许撤销/重新授权后自动恢复。
                if (mode == SensitivitySettingsStore.MODE_ROOT) {
                    RootBridge.reportRootChannelFailure(context);
                }
            } finally {
                if (process == current) process = null;
                if (current != null) {
                    try { current.close(); } catch (Throwable ignored) {}
                }
                // EOF can happen on unplug, permission loss or helper crash without a final zero state.
                listener.onGamepadState(0, 0, 0, 0, 0, 0, 0);
                listener.onGamepadProfile(false, false, false);
            }
            if (running) sleep(mode == SensitivitySettingsStore.MODE_ROOT ? 450L : 350L);
        }
    }

    private void parseLine(String line) {
        if (line == null) return;
        if (line.startsWith("STATUS gamepad-ready ")) {
            listener.onGamepadProfile(true, line.contains("vader5-pro"), line.contains("dunefox-l1-fix"));
            return;
        }
        if (line.startsWith("STATUS gamepad-disconnected") || line.startsWith("STATUS waiting-gamepad")) {
            listener.onGamepadState(0, 0, 0, 0, 0, 0, 0);
            listener.onGamepadProfile(false, false, false);
            return;
        }
        if (line.startsWith("STATUS vader5-pro-raw-ready")) {
            // hidraw exposes Vader back buttons only; it is not the authoritative primary
            // controller stream. Wait for STATUS gamepad-ready before switching Keyboard Cat
            // away from the Android fallback, otherwise face/shoulder buttons can disappear.
            return;
        }
        if (!line.startsWith("GAMEPAD ") || !LineInts.parse(line, 8, parsedGamepad)) return;
        listener.onGamepadState(
                parsedGamepad[0], parsedGamepad[1], parsedGamepad[2], parsedGamepad[3],
                parsedGamepad[4], parsedGamepad[5], parsedGamepad[6]);
    }

    private PrivilegedProcess startPrivileged(int mode, String command) throws Exception {
        if (mode == SensitivitySettingsStore.MODE_ROOT) {
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
