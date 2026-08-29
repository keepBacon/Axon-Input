package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Mirrors one physical source press to one virtual target without consuming the source.
 * DOWN/UP and keyboard repeat are forwarded in the same callback that observed the source.
 */
public final class SimultaneousClickController {
    public static final int EVENT_NONE = 0;
    public static final int EVENT_DOWN = 1;
    public static final int EVENT_UP = 2;
    public static final int EVENT_REPEAT = 3;

    public interface Listener {
        void onStartFailed();
        /** Used only when the persistent helper became ready after the source was already down. */
        void onVirtualTargetPressed(int targetInputCode);
        void onVirtualTargetReleased(int targetInputCode);
    }

    private interface RemoteProcess extends Closeable {
        InputStream input();
        OutputStream output() throws Exception;
    }

    private final Context context;
    private final Listener listener;
    private final Object lock = new Object();
    private RemoteProcess process;
    private OutputStream output;
    private boolean ready;
    private boolean starting;
    private boolean destroyed;
    private boolean enabled;
    private boolean sourceHeld;
    private boolean emittedHeld;
    private int sourceInputCode = -1;
    private int targetInputCode = -1;
    private int targetEvdevCode = -1;

    public SimultaneousClickController(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void applyConfiguration(boolean enabled, int source, int target, int targetEvdev) {
        int releasedTarget = -1;
        boolean shouldStart = false;
        synchronized (lock) {
            if (destroyed) return;
            boolean valid = enabled && InputBinding.isValid(source) && InputBinding.isValid(target)
                    && source != target && targetEvdev > 0;
            boolean changed = this.enabled != valid || sourceInputCode != source
                    || targetInputCode != target || targetEvdevCode != targetEvdev;
            if (!changed) {
                shouldStart = valid && !ready && !starting;
            } else {
                if (emittedHeld) releasedTarget = targetInputCode;
                stopLocked();
                sourceHeld = false;
                emittedHeld = false;
                this.enabled = valid;
                sourceInputCode = valid ? source : -1;
                targetInputCode = valid ? target : -1;
                targetEvdevCode = valid ? targetEvdev : -1;
                shouldStart = valid;
            }
        }
        if (releasedTarget >= 0 && listener != null) listener.onVirtualTargetReleased(releasedTarget);
        if (shouldStart) ensureStartedAsync();
    }

    public int dispatch(int inputCode, boolean pressed, boolean firstPress, boolean repeat) {
        boolean startNeeded = false;
        int result = EVENT_NONE;
        synchronized (lock) {
            if (destroyed || !enabled || inputCode != sourceInputCode) return EVENT_NONE;
            if (pressed && firstPress) {
                sourceHeld = true;
                if (!ready || output == null) {
                    startNeeded = !starting;
                } else if (!emittedHeld && writeLocked("D\n")) {
                    emittedHeld = true;
                    result = EVENT_DOWN;
                } else if (!emittedHeld) {
                    startNeeded = true;
                }
            } else if (pressed && repeat) {
                if (sourceHeld && emittedHeld && ready && output != null && writeLocked("R\n")) {
                    result = EVENT_REPEAT;
                }
            } else if (!pressed) {
                sourceHeld = false;
                if (emittedHeld) {
                    emittedHeld = false;
                    if (ready && output != null) writeLocked("U\n");
                    result = EVENT_UP;
                }
            }
        }
        if (startNeeded) ensureStartedAsync();
        return result;
    }

    public int getTargetInputCode() {
        synchronized (lock) { return targetInputCode; }
    }

    public void releaseActive() {
        int released = -1;
        synchronized (lock) {
            sourceHeld = false;
            if (emittedHeld) {
                released = targetInputCode;
                emittedHeld = false;
                if (ready && output != null) writeLocked("U\n");
            }
        }
        if (released >= 0 && listener != null) listener.onVirtualTargetReleased(released);
    }

    public void destroy() {
        int released = -1;
        synchronized (lock) {
            if (destroyed) return;
            destroyed = true;
            enabled = false;
            sourceHeld = false;
            if (emittedHeld) released = targetInputCode;
            emittedHeld = false;
            stopLocked();
        }
        if (released >= 0 && listener != null) listener.onVirtualTargetReleased(released);
    }

    private boolean writeLocked(String command) {
        try {
            output.write(command.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return true;
        } catch (Throwable ignored) {
            stopLocked();
            return false;
        }
    }

    private void ensureStartedAsync() {
        synchronized (lock) {
            if (destroyed || !enabled || ready || starting) return;
            starting = true;
        }
        Thread worker = new Thread(() -> {
            RemoteProcess candidate = null;
            BufferedReader reader = null;
            boolean failed = false;
            boolean latePress = false;
            int lateTarget = -1;
            RemoteProcess activeProcess = null;
            try {
                candidate = startProcess();
                if (candidate == null) throw new IllegalStateException("No privileged input backend");
                reader = new BufferedReader(new InputStreamReader(candidate.input(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (!"READY".equals(line)) throw new IllegalStateException(line == null ? "sync mapper exited" : line);
                OutputStream next = candidate.output();
                synchronized (lock) {
                    if (destroyed || !enabled) {
                        try { candidate.close(); } catch (Throwable ignored) {}
                        starting = false;
                        return;
                    }
                    stopLocked();
                    process = candidate;
                    activeProcess = candidate;
                    output = next;
                    ready = true;
                    starting = false;
                    candidate = null;

                    // Normally the helper is pre-warmed when the setting is enabled, so DOWN is
                    // emitted in the same callback as the source.  If the user presses during the
                    // short startup window, never lose that hold: synthesize DOWN as soon as READY
                    // arrives and keep the eventual UP paired to it.
                    if (sourceHeld && !emittedHeld && writeLocked("D\n")) {
                        emittedHeld = true;
                        latePress = true;
                        lateTarget = targetInputCode;
                    }
                }
            } catch (Throwable ignored) {
                synchronized (lock) {
                    starting = false;
                    ready = false;
                    failed = enabled && !destroyed;
                }
            } finally {
                if (candidate != null) try { candidate.close(); } catch (Throwable ignored) {}
            }
            if (latePress && lateTarget >= 0 && listener != null) {
                listener.onVirtualTargetPressed(lateTarget);
            }
            if (failed && listener != null) listener.onStartFailed();
            if (activeProcess != null && reader != null) watchProcess(activeProcess, reader);
        }, "AxonSimultaneousClickStart");
        worker.setDaemon(true);
        worker.start();
    }

    private void watchProcess(RemoteProcess watched, BufferedReader reader) {
        Thread watcher = new Thread(() -> {
            try {
                while (reader.readLine() != null) {
                    // The native helper is intentionally silent after READY.
                }
            } catch (Throwable ignored) {
                // Closing the configured process is expected during disable/rebind/teardown.
            }

            boolean release = false;
            boolean restart = false;
            int releasedTarget = -1;
            synchronized (lock) {
                if (process != watched) return;
                process = null;
                output = null;
                ready = false;
                starting = false;
                if (emittedHeld) {
                    emittedHeld = false;
                    release = true;
                    releasedTarget = targetInputCode;
                }
                restart = enabled && !destroyed;
            }
            if (release && releasedTarget >= 0 && listener != null) {
                listener.onVirtualTargetReleased(releasedTarget);
            }
            if (restart) ensureStartedAsync();
        }, "AxonSimultaneousClickWatch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private RemoteProcess startProcess() throws Exception {
        String source = binaryPath();
        if (source == null) throw new IllegalStateException("sync mapper binary missing");
        int sourceInput;
        int target;
        int evdev;
        synchronized (lock) {
            sourceInput = sourceInputCode;
            target = targetInputCode;
            evdev = targetEvdevCode;
        }
        String kind = InputBinding.uinputKind(target);
        String instanceSuffix = "_" + safeId(sourceInput) + "_" + safeId(target) + "_" + safeId(evdev);
        if (SensitivitySettingsStore.getMode(context) == SensitivitySettingsStore.MODE_ROOT
                && RootBridge.isRootActive()) {
            String temp = "/data/local/tmp/axon_input_sync_mapper_" + android.os.Process.myUid() + "_root" + instanceSuffix;
            RootBridge.RootProcess root = RootBridge.startShell(startCommand(source, temp, kind, evdev));
            return new RemoteProcess() {
                @Override public InputStream input() { return root.getInputStream(); }
                @Override public OutputStream output() { return root.getOutputStream(); }
                @Override public void close() { root.close(); }
            };
        }
        if (ShizukuBridge.isReady() && ShizukuBridge.hasPermission()) {
            String temp = "/data/local/tmp/axon_input_sync_mapper_" + android.os.Process.myUid() + "_shizuku" + instanceSuffix;
            ShizukuBridge.ShellProcess shizuku = ShizukuBridge.startShell(startCommand(source, temp, kind, evdev));
            return new RemoteProcess() {
                @Override public InputStream input() { return shizuku.getInputStream(); }
                @Override public OutputStream output() throws Exception { return shizuku.getOutputStream(); }
                @Override public void close() { shizuku.close(); }
            };
        }
        throw new SecurityException("No privileged input channel");
    }

    private String startCommand(String source, String temp, String kind, int code) {
        return "rm -f " + q(temp)
                + "; cat " + q(source) + " > " + q(temp)
                + " && chmod 700 " + q(temp)
                + " && exec " + q(temp) + " --kind " + q(kind) + " --code " + code + " 2>&1";
    }

    private String binaryPath() {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info == null || info.nativeLibraryDir == null) return null;
            return info.nativeLibraryDir + "/libsyncmapper.so";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void stopLocked() {
        ready = false;
        output = null;
        RemoteProcess current = process;
        process = null;
        if (current != null) try { current.close(); } catch (Throwable ignored) {}
    }

    private static String safeId(int value) {
        long v = value;
        if (v < 0L) v = -v;
        return Long.toString(v);
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
