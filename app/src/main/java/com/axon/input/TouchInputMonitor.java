package com.axon.input;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Process;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.StringTokenizer;

/**
 * Shizuku-only read-only touchscreen monitor.
 *
 * The old implementation parsed `getevent -lt/-lp` text. That is fragile because OEM ROMs can
 * change labels, spacing and capability formatting. This implementation starts a tiny native
 * evdev reader under Shizuku and receives a stable Axon-owned text protocol instead.
 */
final class TouchInputMonitor {
    interface Listener {
        void onTouchFrame(TouchPoint[] points);
        default void onTouchStatus(String status) {}
    }

    static final class TouchPoint {
        final int id;
        final float x;
        final float y;

        TouchPoint(int id, float x, float y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }
    }

    private interface PrivilegedProcess extends Closeable {
        InputStream getInputStream();
        OutputStream getOutputStream() throws Exception;
    }

    private static final int MAX_POINTS = 20;
    private static final float NATIVE_COORD_SCALE = 100000f;

    private final Context context;
    private final Listener listener;
    private final String tempBinaryPath;
    private volatile boolean running;
    private volatile PrivilegedProcess process;
    private Thread worker;
    private String lastStatus = "";

    TouchInputMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.tempBinaryPath = "/data/local/tmp/axon_input_touch_monitor_" + Process.myUid();
    }

    synchronized void start() {
        if (running) return;
        running = true;
        updateStatus("等待 Shizuku");
        Thread thread = new Thread(this::runLoop, "AxonTouchInput");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    synchronized void stop() {
        running = false;
        PrivilegedProcess current = process;
        process = null;
        if (current != null) {
            try { current.close(); } catch (Throwable ignored) {}
        }
        Thread thread = worker;
        worker = null;
        if (thread != null) thread.interrupt();
        listener.onTouchFrame(new TouchPoint[0]);
        updateStatus("已停止");
    }

    private void runLoop() {
        while (running) {
            if (!ShizukuBridge.isReady()) {
                updateStatus("等待 Shizuku 连接");
                sleep(350L);
                continue;
            }
            if (!ShizukuBridge.hasPermission()) {
                updateStatus("等待 Shizuku 授权");
                sleep(350L);
                continue;
            }

            PrivilegedProcess current = null;
            try {
                String source = binaryPath();
                if (source == null) {
                    updateStatus("触屏监听组件缺失");
                    sleep(1200L);
                    continue;
                }
                updateStatus("正在传输触屏监听组件");
                // Do not ask the Shizuku shell process to read the app's /data/app nativeLibraryDir.
                // OEM SELinux/path permissions differ. Stream the helper through the remote process stdin
                // and let shell write only its own /data/local/tmp file.
                String command = "exec 2>&1; rm -f " + q(tempBinaryPath)
                        + "; cat > " + q(tempBinaryPath)
                        + " && chmod 700 " + q(tempBinaryPath)
                        + " && echo 'STATUS helper-ready'"
                        + " && exec " + q(tempBinaryPath);
                current = startShizuku(command);
                process = current;
                streamHelperBinary(source, current);
                updateStatus("正在启动触屏监听");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
                    String line;
                    while (running && (line = reader.readLine()) != null) parseNativeLine(line);
                }
                if (running) updateStatus("触屏监听已断开，正在重连");
            } catch (Throwable error) {
                if (running) updateStatus("触屏监听启动失败：" + shortError(error));
            } finally {
                if (process == current) process = null;
                if (current != null) {
                    try { current.close(); } catch (Throwable ignored) {}
                }
                listener.onTouchFrame(new TouchPoint[0]);
            }
            if (running) sleep(500L);
        }
    }

    private void parseNativeLine(String line) {
        if (line == null) return;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || "PING".equals(trimmed)) return;
        if (trimmed.startsWith("STATUS ")) {
            parseStatus(trimmed.substring(7).trim());
            return;
        }
        if (!trimmed.startsWith("TOUCH ")) return;

        StringTokenizer tokens = new StringTokenizer(trimmed);
        if (!tokens.hasMoreTokens()) return;
        tokens.nextToken(); // TOUCH
        if (!tokens.hasMoreTokens()) return;
        int count;
        try {
            count = Integer.parseInt(tokens.nextToken());
        } catch (Throwable ignored) {
            return;
        }
        count = Math.max(0, Math.min(MAX_POINTS, count));
        TouchPoint[] points = new TouchPoint[count];
        for (int i = 0; i < count; i++) {
            if (tokens.countTokens() < 3) return;
            try {
                int id = Integer.parseInt(tokens.nextToken());
                int nx = Integer.parseInt(tokens.nextToken());
                int ny = Integer.parseInt(tokens.nextToken());
                float x = clamp01(nx / NATIVE_COORD_SCALE);
                float y = clamp01(ny / NATIVE_COORD_SCALE);
                points[i] = new TouchPoint(id, x, y);
            } catch (Throwable ignored) {
                return;
            }
        }
        listener.onTouchFrame(points);
        if (count > 0) updateStatus("触屏已连接 · 当前 " + count + " 个触点");
        else if (lastStatus.startsWith("触屏已连接 · 当前")) updateStatus("触屏已连接 · 等待触摸");
    }

    private void parseStatus(String raw) {
        if (raw == null) return;
        if (raw.startsWith("touch-ready ")) {
            String detail = raw.substring("touch-ready ".length()).trim();
            updateStatus(detail.isEmpty() ? "触屏已连接 · 等待触摸" : "触屏已连接 · " + detail);
        } else if (raw.equals("helper-ready")) {
            updateStatus("触屏监听组件已启动");
        } else if (raw.equals("starting")) {
            updateStatus("正在扫描触屏设备");
        } else if (raw.equals("input-permission-denied")) {
            updateStatus("Shizuku 无法读取 /dev/input");
        } else if (raw.equals("no-touchscreen-candidate")) {
            updateStatus("未识别到触屏设备");
        } else if (raw.equals("no-input-devices")) {
            updateStatus("未发现 /dev/input/event 设备");
        } else if (raw.equals("touch-disconnected")) {
            updateStatus("触屏设备断开，正在重新扫描");
        } else if (raw.equals("stopped")) {
            updateStatus("已停止");
        } else {
            updateStatus("触屏监听：" + raw);
        }
    }

    private String binaryPath() {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            if (info.nativeLibraryDir == null) return null;
            return info.nativeLibraryDir + "/libtouchmonitor.so";
        } catch (Throwable ignored) {
            return null;
        }
    }

    private PrivilegedProcess startShizuku(String command) throws Exception {
        ShizukuBridge.ShellProcess shizuku = ShizukuBridge.startShell(command);
        return new PrivilegedProcess() {
            @Override public InputStream getInputStream() { return shizuku.getInputStream(); }
            @Override public OutputStream getOutputStream() throws Exception { return shizuku.getOutputStream(); }
            @Override public void close() { shizuku.close(); }
        };
    }

    private void streamHelperBinary(String source, PrivilegedProcess remote) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        try (FileInputStream input = new FileInputStream(source);
             OutputStream output = remote.getOutputStream()) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.flush();
        }
    }

    private void updateStatus(String status) {
        String value = status == null ? "" : status.trim();
        if (value.equals(lastStatus)) return;
        lastStatus = value;
        listener.onTouchStatus(value);
    }

    private String shortError(Throwable error) {
        if (error == null) return "unknown";
        String simple = error.getClass().getSimpleName();
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) return simple;
        String clean = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > 72) clean = clean.substring(0, 72);
        return simple + " · " + clean;
    }

    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
