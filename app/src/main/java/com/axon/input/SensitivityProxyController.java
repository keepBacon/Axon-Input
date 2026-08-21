package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 灵敏度超频进程控制器。
 * 免 Root 模式通过 Shizuku shell 启动，Root 模式通过 su 启动。两种模式共用 native evdev -> UHID 代理。
 * Activity 只负责启停和倍率更新，持续输入处理不依赖 Activity 生命周期。
 */
public final class SensitivityProxyController {
    public interface Listener {
        void onSensitivityStatus(String status);
        void onSensitivityMouseMotion(int dx, int dy);
        void onSensitivityMouseButtons(int mask);
        void onSensitivityGamepadState(int lx, int ly, int rx, int ry, int lt, int rt, int buttons);
    }

    private interface PrivilegedProcess extends Closeable {
        InputStream getInputStream();
    }

    private static final long GAIN_WRITE_DEBOUNCE_MS = 70L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AxonInputSensitivityControl");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();

    private volatile boolean desiredEnabled;
    private volatile int desiredMouse = 100;
    private volatile int desiredGamepad = 100;
    private volatile int desiredMode = OverlayState.SENSITIVITY_MODE_SHIZUKU;
    private volatile int activeMode = -1;
    private volatile PrivilegedProcess process;
    private volatile Thread readerThread;
    private volatile int fatalMode = -1;
    private volatile int lastButtons;

    private final String tempBinaryBase;
    private final String gainFileBase;

    private final Runnable gainFlush = () -> {
        if (!desiredEnabled) return;
        final int mode = desiredMode;
        final int mouse = desiredMouse;
        final int gamepad = desiredGamepad;
        if (!modeReady(mode) || process == null || activeMode != mode) return;
        controlExecutor.execute(() -> writeGains(mode, mouse, gamepad));
    };

    public SensitivityProxyController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        int uid = Process.myUid();
        tempBinaryBase = "/data/local/tmp/axon_input_sensitivity_proxy_" + uid;
        gainFileBase = "/data/local/tmp/axon_input_sensitivity_gain_" + uid;
    }

    /** Applies persisted state without restarting unless the privilege mode actually changes. */
    public synchronized void apply(boolean enabled, int mousePercent, int gamepadPercent, int mode) {
        int resolvedMode = mode == OverlayState.SENSITIVITY_MODE_ROOT
                ? OverlayState.SENSITIVITY_MODE_ROOT
                : OverlayState.SENSITIVITY_MODE_SHIZUKU;
        boolean modeChanged = desiredMode != resolvedMode;
        desiredEnabled = enabled;
        desiredMouse = clamp(mousePercent);
        desiredGamepad = clamp(gamepadPercent);
        desiredMode = resolvedMode;

        if (!enabled) {
            fatalMode = -1;
            stopInternal("未启用");
            return;
        }

        if (modeChanged) {
            fatalMode = -1;
            stopProcessOnly();
        }

        if (!modeReady(resolvedMode)) {
            postStatus(resolvedMode == OverlayState.SENSITIVITY_MODE_ROOT
                    ? "等待 Root 授权"
                    : "等待 Shizuku 授权");
            return;
        }

        mainHandler.removeCallbacks(gainFlush);
        mainHandler.postDelayed(gainFlush, GAIN_WRITE_DEBOUNCE_MS);
        if (process == null && (readerThread == null || !readerThread.isAlive()) && fatalMode != resolvedMode) {
            startWorker();
        }
    }

    public synchronized boolean isProxyActive() {
        return desiredEnabled && process != null;
    }

    public synchronized void onShizukuAvailable() {
        if (desiredMode != OverlayState.SENSITIVITY_MODE_SHIZUKU) return;
        if (fatalMode == OverlayState.SENSITIVITY_MODE_SHIZUKU) fatalMode = -1;
        if (desiredEnabled) apply(true, desiredMouse, desiredGamepad, desiredMode);
    }

    public synchronized void onShizukuDead() {
        if (desiredMode == OverlayState.SENSITIVITY_MODE_SHIZUKU) {
            stopInternal("免 Root 模式 · Shizuku 已断开");
        }
    }

    public synchronized void destroy() {
        desiredEnabled = false;
        stopInternal("已停止");
        controlExecutor.shutdownNow();
    }

    private void startWorker() {
        final int token = generation.incrementAndGet();
        final int mode = desiredMode;
        Thread worker = new Thread(() -> runWorker(token, mode), "AxonInputSensitivityProxy");
        worker.setDaemon(true);
        readerThread = worker;
        worker.start();
    }

    private void runWorker(int token, int mode) {
        boolean fatal = false;
        while (desiredEnabled && token == generation.get() && desiredMode == mode && modeReady(mode)) {
            PrivilegedProcess shell = null;
            try {
                String source = sensitivityBinaryPath();
                if (source == null) {
                    postStatus("灵敏度超频代理文件缺失");
                    fatal = true;
                    break;
                }
                String tempBinary = tempBinary(mode);
                String gainFile = gainFile(mode);
                String command = "rm -f " + q(tempBinary)
                        + "; cat " + q(source) + " > " + q(tempBinary)
                        + " && chmod 700 " + q(tempBinary)
                        + " && printf 'mouse=%d\\ngamepad=%d\\n' " + desiredMouse + " " + desiredGamepad
                        + " > " + q(gainFile)
                        + " && exec " + q(tempBinary) + " --gain-file " + q(gainFile);
                shell = startPrivileged(mode, command);
                process = shell;
                activeMode = mode;
                postStatus(modeName(mode) + " · 正在接管外设输入");

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(shell.getInputStream()))) {
                    String line;
                    while (desiredEnabled && token == generation.get() && desiredMode == mode
                            && (line = reader.readLine()) != null) {
                        if (line.startsWith("ERROR uhid-open")) {
                            fatal = true;
                            postStatus(mode == OverlayState.SENSITIVITY_MODE_ROOT
                                    ? "Root 模式无法访问 UHID"
                                    : "免 Root 权限不足，可切换 Root 模式");
                        } else if (line.startsWith("ERROR ")) {
                            postStatus(modeName(mode) + " · " + line.substring(6));
                        } else if (line.startsWith("STATUS ")) {
                            parseStatus(mode, line.substring(7));
                        } else if (line.startsWith("MOTION ")) {
                            parseMotion(line);
                        } else if (line.startsWith("BUTTONS ")) {
                            parseButtons(line);
                        } else if (line.startsWith("GAMEPAD ")) {
                            parseGamepad(line);
                        }
                    }
                }
            } catch (Throwable error) {
                if (desiredEnabled && token == generation.get() && desiredMode == mode) {
                    postStatus(mode == OverlayState.SENSITIVITY_MODE_ROOT
                            ? "Root 启动失败或未授权"
                            : "免 Root 代理启动失败");
                    if (mode == OverlayState.SENSITIVITY_MODE_ROOT) fatal = true;
                }
            } finally {
                if (process == shell) process = null;
                activeMode = -1;
                if (shell != null) {
                    try { shell.close(); } catch (Throwable ignored) {}
                }
            }

            if (fatal || !desiredEnabled || token != generation.get() || desiredMode != mode) break;
            SystemClock.sleep(250L);
        }
        if (fatal) fatalMode = mode;
        synchronized (this) {
            if (readerThread == Thread.currentThread()) readerThread = null;
        }
    }

    private void parseStatus(int mode, String status) {
        String prefix = modeName(mode) + " · ";
        if (status.startsWith("mouse-ready")) {
            postStatus(prefix + "鼠标已接管");
        } else if (status.startsWith("gamepad-ready")) {
            postStatus(prefix + "手柄已接管");
        } else if (status.startsWith("gain ")) {
            postStatus(prefix + desiredMouse + "% / " + desiredGamepad + "%");
        } else if (status.startsWith("waiting-device")) {
            postStatus(prefix + "等待鼠标或手柄");
        } else if (status.startsWith("mouse-disconnected") || status.startsWith("gamepad-disconnected")) {
            postStatus(prefix + "设备断开，等待重连");
        } else if (status.startsWith("starting")) {
            postStatus(prefix + "初始化中");
        }
    }

    private void parseMotion(String line) {
        String[] parts = line.split(" ");
        if (parts.length != 3) return;
        try {
            int dx = Integer.parseInt(parts[1]);
            int dy = Integer.parseInt(parts[2]);
            mainHandler.post(() -> listener.onSensitivityMouseMotion(dx, dy));
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseButtons(String line) {
        String[] parts = line.split(" ");
        if (parts.length != 2) return;
        try {
            int mask = Integer.parseInt(parts[1]);
            if (mask == lastButtons) return;
            lastButtons = mask;
            mainHandler.post(() -> listener.onSensitivityMouseButtons(mask));
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseGamepad(String line) {
        String[] parts = line.split(" ");
        if (parts.length != 8) return;
        try {
            int lx = Integer.parseInt(parts[1]);
            int ly = Integer.parseInt(parts[2]);
            int rx = Integer.parseInt(parts[3]);
            int ry = Integer.parseInt(parts[4]);
            int lt = Integer.parseInt(parts[5]);
            int rt = Integer.parseInt(parts[6]);
            int buttons = Integer.parseInt(parts[7]);
            mainHandler.post(() -> listener.onSensitivityGamepadState(lx, ly, rx, ry, lt, rt, buttons));
        } catch (NumberFormatException ignored) {
        }
    }

    private void writeGains(int mode, int mouse, int gamepad) {
        try {
            String gainFile = gainFile(mode);
            String command = "tmp=" + q(gainFile + ".new")
                    + "; printf 'mouse=%d\\ngamepad=%d\\n' " + mouse + " " + gamepad
                    + " > \"$tmp\" && mv \"$tmp\" " + q(gainFile);
            runPrivileged(mode, command);
        } catch (Throwable ignored) {
            // The proxy keeps the last valid gain; the next slider update retries.
        }
    }

    private synchronized void stopInternal(String status) {
        final int modeA = activeMode;
        generation.incrementAndGet();
        mainHandler.removeCallbacks(gainFlush);
        stopProcessOnly();
        lastButtons = 0;
        postStatus(status);
        if (modeA == OverlayState.SENSITIVITY_MODE_SHIZUKU
                || modeA == OverlayState.SENSITIVITY_MODE_ROOT) {
            controlExecutor.execute(() -> cleanupMode(modeA));
        }
    }

    private synchronized void stopProcessOnly() {
        generation.incrementAndGet();
        PrivilegedProcess shell = process;
        process = null;
        activeMode = -1;
        if (shell != null) {
            try { shell.close(); } catch (Throwable ignored) {}
        }
        Thread worker = readerThread;
        readerThread = null;
        if (worker != null) worker.interrupt();
    }

    private void cleanupMode(int mode) {
        if (mode != OverlayState.SENSITIVITY_MODE_SHIZUKU && mode != OverlayState.SENSITIVITY_MODE_ROOT) return;
        try {
            if (!modeReady(mode)) return;
            String command = "rm -f " + q(gainFile(mode)) + " " + q(gainFile(mode) + ".new")
                    + " " + q(tempBinary(mode));
            runPrivileged(mode, command);
        } catch (Throwable ignored) {
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

    private int runPrivileged(int mode, String command) throws Exception {
        if (mode == OverlayState.SENSITIVITY_MODE_ROOT) return RootBridge.runShell(command);
        return ShizukuBridge.runShell(command);
    }

    private boolean modeReady(int mode) {
        if (mode == OverlayState.SENSITIVITY_MODE_ROOT) return true;
        return ShizukuBridge.isReady() && ShizukuBridge.hasPermission();
    }

    private String modeName(int mode) {
        return mode == OverlayState.SENSITIVITY_MODE_ROOT ? "Root" : "免 Root";
    }

    private String tempBinary(int mode) {
        return tempBinaryBase + (mode == OverlayState.SENSITIVITY_MODE_ROOT ? "_root" : "_shizuku");
    }

    private String gainFile(int mode) {
        return gainFileBase + (mode == OverlayState.SENSITIVITY_MODE_ROOT ? "_root.cfg" : "_shizuku.cfg");
    }

    private String sensitivityBinaryPath() {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info.nativeLibraryDir == null) return null;
            return info.nativeLibraryDir + "/libsensitivityproxy.so";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void postStatus(String status) {
        OverlayState.setSensitivityStatus(context, status);
        mainHandler.post(() -> listener.onSensitivityStatus(status));
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(500, value));
    }

    private String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
