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

/** 输入倍率代理。Shizuku 和 Root 共用 native evdev 接管与虚拟设备转发。 */
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

    private static final long GAIN_WRITE_DEBOUNCE_MS = 55L;

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
    private final int[] parsedMotion = new int[2];
    private final int[] parsedButtons = new int[1];
    private final int[] parsedGamepad = new int[7];

    private final String tempBinaryBase;
    private final String gainFileBase;
    private final String pidFileBase;

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
        pidFileBase = "/data/local/tmp/axon_input_sensitivity_pid_" + uid;
    }

    /** 应用已保存状态。权限模式变化时才重启代理。 */
    public synchronized void apply(boolean enabled, int mousePercent, int gamepadPercent, int mode) {
        int previousMode = desiredMode;
        int resolvedMode = mode == OverlayState.SENSITIVITY_MODE_ROOT
                ? OverlayState.SENSITIVITY_MODE_ROOT
                : OverlayState.SENSITIVITY_MODE_SHIZUKU;
        int nextMouse = clamp(mousePercent);
        int nextGamepad = clamp(gamepadPercent);
        boolean modeChanged = desiredMode != resolvedMode;
        boolean gainChanged = desiredMouse != nextMouse || desiredGamepad != nextGamepad;
        desiredEnabled = enabled;
        desiredMouse = nextMouse;
        desiredGamepad = nextGamepad;
        desiredMode = resolvedMode;

        if (!enabled) {
            fatalMode = -1;
            stopInternal(context.getString(R.string.status_disabled));
            return;
        }

        if (modeChanged) {
            fatalMode = -1;
            stopProcessOnly();
            controlExecutor.execute(() -> cleanupMode(previousMode));
        }

        if (!modeReady(resolvedMode)) {
            postStatus(context.getString(resolvedMode == OverlayState.SENSITIVITY_MODE_ROOT
                    ? R.string.sensitivity_status_wait_root
                    : R.string.sensitivity_status_wait_shizuku));
            return;
        }

        if (gainChanged && process != null && activeMode == resolvedMode) {
            mainHandler.removeCallbacks(gainFlush);
            mainHandler.postDelayed(gainFlush, GAIN_WRITE_DEBOUNCE_MS);
        }
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
            stopInternal(context.getString(R.string.sensitivity_status_shizuku_lost));
        }
    }

    public synchronized void destroy() {
        desiredEnabled = false;
        stopInternal(context.getString(R.string.sensitivity_status_stopped));
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
                    postStatus(context.getString(R.string.sensitivity_status_proxy_missing));
                    fatal = true;
                    break;
                }
                String tempBinary = tempBinary(mode);
                String gainFile = gainFile(mode);
                String pidFile = pidFile(mode);
                String command = staleProcessStopCommand(pidFile)
                        + "; rm -f " + q(tempBinary)
                        + "; cat " + q(source) + " > " + q(tempBinary)
                        + " && chmod 700 " + q(tempBinary)
                        + " && printf 'mouse=%d\\ngamepad=%d\\n' " + desiredMouse + " " + desiredGamepad
                        + " > " + q(gainFile)
                        + " && printf '%s\\n' $$ > " + q(pidFile)
                        + " && exec " + q(tempBinary) + " --gain-file " + q(gainFile);
                shell = startPrivileged(mode, command);
                process = shell;
                activeMode = mode;
                postStatus(context.getString(R.string.sensitivity_status_running_format, modeName(mode)));

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(shell.getInputStream()))) {
                    String line;
                    while (desiredEnabled && token == generation.get() && desiredMode == mode
                            && (line = reader.readLine()) != null) {
                        if (line.startsWith("ERROR uhid-open") || line.startsWith("ERROR input-backend-open")) {
                            fatal = true;
                            postStatus(context.getString(mode == OverlayState.SENSITIVITY_MODE_ROOT
                                    ? R.string.sensitivity_status_root_uhid_failed
                                    : R.string.sensitivity_status_shizuku_permission_failed));
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
                    postStatus(context.getString(mode == OverlayState.SENSITIVITY_MODE_ROOT
                            ? R.string.sensitivity_status_root_start_failed
                            : R.string.sensitivity_status_shizuku_start_failed));
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
            postStatus(prefix + context.getString(R.string.sensitivity_status_mouse_ready));
        } else if (status.startsWith("gamepad-ready")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_gamepad_ready));
        } else if (status.startsWith("view-ready")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_view_ready));
        } else if (status.startsWith("gamepad-grab-failed")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_gamepad_grab_failed));
        } else if (status.startsWith("gamepad-uhid-failed") || status.startsWith("gamepad-virtual-failed")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_gamepad_uhid_failed));
        } else if (status.startsWith("gain ")) {
            postStatus(prefix + desiredMouse + "% / " + desiredGamepad + "%");
        } else if (status.startsWith("waiting-device")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_wait_device));
        } else if (status.startsWith("view-disconnected")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_view_restart));
        } else if (status.startsWith("mouse-disconnected") || status.startsWith("gamepad-disconnected")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_device_lost));
        } else if (status.startsWith("starting")) {
            postStatus(prefix + context.getString(R.string.sensitivity_status_starting));
        }
    }

    private void parseMotion(String line) {
        if (!LineInts.parse(line, 7, parsedMotion)) return;
        int dx = parsedMotion[0];
        int dy = parsedMotion[1];
        mainHandler.post(() -> listener.onSensitivityMouseMotion(dx, dy));
    }

    private void parseButtons(String line) {
        if (!LineInts.parse(line, 8, parsedButtons)) return;
        int mask = parsedButtons[0];
        if (mask == lastButtons) return;
        lastButtons = mask;
        mainHandler.post(() -> listener.onSensitivityMouseButtons(mask));
    }

    private void parseGamepad(String line) {
        if (!LineInts.parse(line, 8, parsedGamepad)) return;
        int lx = parsedGamepad[0];
        int ly = parsedGamepad[1];
        int rx = parsedGamepad[2];
        int ry = parsedGamepad[3];
        int lt = parsedGamepad[4];
        int rt = parsedGamepad[5];
        int buttons = parsedGamepad[6];
        mainHandler.post(() -> listener.onSensitivityGamepadState(lx, ly, rx, ry, lt, rt, buttons));
    }

    private void writeGains(int mode, int mouse, int gamepad) {
        try {
            String gainFile = gainFile(mode);
            String command = "tmp=" + q(gainFile + ".new")
                    + "; printf 'mouse=%d\\ngamepad=%d\\n' " + mouse + " " + gamepad
                    + " > \"$tmp\" && mv \"$tmp\" " + q(gainFile);
            runPrivileged(mode, command);
        } catch (Throwable ignored) {
            // 代理保留上次有效倍率，下次滑动时重试。
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
            String command = staleProcessStopCommand(pidFile(mode))
                    + "; rm -f " + q(gainFile(mode)) + " " + q(gainFile(mode) + ".new")
                    + " " + q(tempBinary(mode)) + " " + q(pidFile(mode));
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
        return mode == OverlayState.SENSITIVITY_MODE_ROOT ? "Root" : "Shizuku";
    }

    private String tempBinary(int mode) {
        return tempBinaryBase + (mode == OverlayState.SENSITIVITY_MODE_ROOT ? "_root" : "_shizuku");
    }

    private String gainFile(int mode) {
        return gainFileBase + (mode == OverlayState.SENSITIVITY_MODE_ROOT ? "_root.cfg" : "_shizuku.cfg");
    }

    private String pidFile(int mode) {
        return pidFileBase + (mode == OverlayState.SENSITIVITY_MODE_ROOT ? "_root.pid" : "_shizuku.pid");
    }

    private String staleProcessStopCommand(String pidFile) {
        String file = q(pidFile);
        return "if [ -f " + file + " ]; then "
                + "old=$(cat " + file + " 2>/dev/null); "
                + "case \"$old\" in ''|*[!0-9]*) ;; *) kill \"$old\" 2>/dev/null || true; sleep 0.08 ;; esac; "
                + "fi";
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
