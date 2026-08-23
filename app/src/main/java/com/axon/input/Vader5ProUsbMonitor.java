package com.axon.input;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Flydigi Vader 5 Pro (37d7:2401) USB 2.4G 扩展输入监听。
 *
 * Android 的标准 Gamepad/XInput 接口不包含 M1-M4。这里通过 USB Host 只 claim
 * 厂商扩展接口（优先 interface 1 / EP 0x82 + EP 0x06），启用 test mode 后读取
 * 5A A5 EF 开头的 32 字节扩展报告。接口 0 的标准手柄输入保持给系统使用。
 */
public final class Vader5ProUsbMonitor {
    public interface Listener {
        void onVader5UsbProfile(boolean connected);
        void onVader5UsbBackButtons(int mask);
    }

    private static final int VENDOR_FLYDIGI = 0x37D7;
    private static final int PRODUCT_VADER5_PRO = 0x2401;
    private static final String ACTION_USB_PERMISSION = "com.axon.input.VADER5_USB_PERMISSION";

    private static final byte[] CMD_INFO = packet(0x5A, 0xA5, 0x01, 0x02, 0x03);
    private static final byte[] CMD_SERIAL = packet(0x5A, 0xA5, 0xA1, 0x02, 0xA3);
    private static final byte[] CMD_CONFIG_READ = packet(0x5A, 0xA5, 0x02, 0x02, 0x04);
    private static final byte[] CMD_CONFIG_DATA = packet(0x5A, 0xA5, 0x04, 0x02, 0x06);
    private static final byte[] CMD_TEST_ENABLE = packet(
            0x5A, 0xA5, 0x11, 0x07, 0xFF, 0x01, 0xFF, 0xFF, 0xFF, 0x15);
    private static final byte[] CMD_TEST_DISABLE = packet(
            0x5A, 0xA5, 0x11, 0x07, 0xFF, 0x00, 0xFF, 0xFF, 0xFF, 0x14);

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;
    private final Object connectionLock = new Object();

    private volatile boolean running;
    private volatile Thread worker;
    private volatile boolean receiverRegistered;
    private volatile boolean permissionRequested;
    private volatile boolean permissionDenied;
    private volatile int lastDeviceId = -1;
    private volatile int lastBackMask;
    private volatile boolean lastProfile;

    private UsbDevice activeDevice;
    private UsbDeviceConnection activeConnection;
    private UsbInterface activeInterface;
    private UsbEndpoint activeOut;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isVader5(device)) {
                    permissionRequested = false;
                    permissionDenied = !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    wakeWorker();
                }
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isVader5(device)) {
                    permissionRequested = false;
                    permissionDenied = false;
                    lastDeviceId = device.getDeviceId();
                    wakeWorker();
                }
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && isVader5(device)) {
                    closeConnection(false);
                    permissionRequested = false;
                    permissionDenied = false;
                    lastDeviceId = -1;
                    emitBackMask(0);
                    emitProfile(false);
                    wakeWorker();
                }
            }
        }
    };

    public Vader5ProUsbMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        this.listener = listener;
    }

    public synchronized void start() {
        if (running || usbManager == null) return;
        running = true;
        permissionRequested = false;
        permissionDenied = false;
        registerReceiver();
        Thread thread = new Thread(this::runLoop, "AxonVader5Usb");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread thread = worker;
        worker = null;
        closeConnection(true);
        if (thread != null) thread.interrupt();
        unregisterReceiver();
        permissionRequested = false;
        permissionDenied = false;
        lastDeviceId = -1;
        emitBackMask(0);
        emitProfile(false);
    }

    private void runLoop() {
        while (running) {
            UsbDevice device = findVader5();
            if (device == null) {
                emitProfile(false);
                emitBackMask(0);
                sleep(700L);
                continue;
            }

            emitProfile(true);
            if (device.getDeviceId() != lastDeviceId) {
                lastDeviceId = device.getDeviceId();
                permissionRequested = false;
                permissionDenied = false;
            }

            if (!usbManager.hasPermission(device)) {
                closeConnection(false);
                emitBackMask(0);
                if (!permissionRequested && !permissionDenied) requestPermission(device);
                sleep(650L);
                continue;
            }

            permissionRequested = false;
            permissionDenied = false;
            if (!openAndRead(device)) sleep(500L);
        }
        closeConnection(true);
    }

    /** Returns after disconnect/read failure. */
    private boolean openAndRead(UsbDevice device) {
        InterfaceEndpoints target = findExtendedInterface(device);
        if (target == null) return false;

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) return false;
        if (!connection.claimInterface(target.usbInterface, true)) {
            connection.close();
            return false;
        }

        synchronized (connectionLock) {
            if (!running) {
                try { connection.releaseInterface(target.usbInterface); } catch (Throwable ignored) {}
                connection.close();
                return false;
            }
            activeDevice = device;
            activeConnection = connection;
            activeInterface = target.usbInterface;
            activeOut = target.out;
        }

        // Vader 5 Pro 使用“无 Report ID”的 32-byte vendor packet。
        // 发送完整初始化序列并重复一次 test-enable，覆盖部分接收器刚唤醒时吞首包的情况。
        boolean commandsOk = sendPacket(connection, target.out, CMD_INFO);
        sleepQuiet(3L);
        commandsOk &= sendPacket(connection, target.out, CMD_SERIAL);
        sleepQuiet(3L);
        commandsOk &= sendPacket(connection, target.out, CMD_CONFIG_READ);
        sleepQuiet(3L);
        commandsOk &= sendPacket(connection, target.out, CMD_CONFIG_DATA);
        sleepQuiet(3L);
        commandsOk &= sendPacket(connection, target.out, CMD_TEST_ENABLE);
        sleepQuiet(18L);
        commandsOk &= sendPacket(connection, target.out, CMD_TEST_ENABLE);
        if (!commandsOk) {
            closeConnection(false);
            return false;
        }

        UsbRequest request = new UsbRequest();
        if (!request.initialize(connection, target.in)) {
            closeConnection(false);
            return false;
        }

        try {
            while (running && isActive(connection, device)) {
                int capacity = Math.max(32, target.in.getMaxPacketSize());
                ByteBuffer buffer = ByteBuffer.allocateDirect(capacity);
                buffer.limit(32);
                if (!request.queue(buffer)) break;

                UsbRequest completed;
                try {
                    completed = connection.requestWait(1200L);
                } catch (TimeoutException timeout) {
                    // 扩展流正常情况下持续输出。超时说明 test mode/接口未真正生效；
                    // 取消当前请求并重建 USB connection，外层循环会重新下发完整初始化序列。
                    request.cancel();
                    break;
                }
                if (completed != request) break;

                int length = buffer.position();
                if (length <= 0) continue;
                buffer.flip();
                byte[] report = new byte[Math.min(length, 64)];
                buffer.get(report);
                int mask = parseBackMask(report, report.length);
                if (mask >= 0) emitBackMask(mask);
            }
        } catch (Throwable ignored) {
            // Disconnect / interface reset: outer loop rescans and reconnects.
        } finally {
            try { request.cancel(); } catch (Throwable ignored) {}
            try { request.close(); } catch (Throwable ignored) {}
            closeConnection(true);
        }
        return true;
    }

    private void requestPermission(UsbDevice device) {
        try {
            permissionRequested = true;
            Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    0x5155,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(device, pending);
        } catch (Throwable ignored) {
            permissionRequested = false;
            permissionDenied = true;
        }
    }

    private UsbDevice findVader5() {
        try {
            for (Map.Entry<String, UsbDevice> entry : usbManager.getDeviceList().entrySet()) {
                UsbDevice device = entry.getValue();
                if (isVader5(device)) return device;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private InterfaceEndpoints findExtendedInterface(UsbDevice device) {
        InterfaceEndpoints best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < device.getInterfaceCount(); ++i) {
            UsbInterface intf = device.getInterface(i);
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int e = 0; e < intf.getEndpointCount(); ++e) {
                UsbEndpoint endpoint = intf.getEndpoint(e);
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN
                        && endpoint.getMaxPacketSize() >= 32) {
                    if (in == null || endpoint.getAddress() == 0x82) in = endpoint;
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT
                        && endpoint.getMaxPacketSize() >= 32) {
                    if (out == null || endpoint.getAddress() == 0x06) out = endpoint;
                }
            }
            if (in == null || out == null) continue;

            int score = 0;
            if (intf.getId() == 1) score += 100;
            if (in.getAddress() == 0x82) score += 40;
            if (out.getAddress() == 0x06) score += 40;
            if (in.getMaxPacketSize() == 32) score += 10;
            if (out.getMaxPacketSize() == 32) score += 10;
            if (score > bestScore) {
                bestScore = score;
                best = new InterfaceEndpoints(intf, in, out);
            }
        }
        return best;
    }

    private boolean sendPacket(UsbDeviceConnection connection, UsbEndpoint endpoint, byte[] packet) {
        if (connection == null || endpoint == null || packet == null || packet.length != 32) return false;
        UsbRequest request = new UsbRequest();
        try {
            if (!request.initialize(connection, endpoint)) return false;
            ByteBuffer buffer = ByteBuffer.allocateDirect(32);
            buffer.put(packet);
            buffer.flip();
            if (!request.queue(buffer)) return false;
            UsbRequest completed = connection.requestWait(450L);
            return completed == request;
        } catch (TimeoutException ignored) {
            try { request.cancel(); } catch (Throwable ignoredToo) {}
            return false;
        } catch (Throwable ignored) {
            return false;
        } finally {
            try { request.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Extended report: 5A A5 EF ... byte13: C/Z/M1/M2/M3/M4/LM/RM.
     * Returns -1 for unrelated reports, otherwise GamepadOverlayView back-button bitmask.
     */
    static int parseBackMask(byte[] report, int length) {
        if (report == null || length < 14) return -1;
        int off = 0;
        if (!hasMagic(report, 0, length)) {
            // 某些 USB/HID 栈仍可能在 IN 方向保留 0 report-id。
            if (length >= 15 && (report[0] & 0xFF) == 0 && hasMagic(report, 1, length)) off = 1;
            else return -1;
        }
        int ext = report[off + 13] & 0xFF;
        int mask = 0;
        if ((ext & (1 << 2)) != 0) mask |= GamepadOverlayView.BTN_BACK_1; // M1
        if ((ext & (1 << 3)) != 0) mask |= GamepadOverlayView.BTN_BACK_2; // M2
        if ((ext & (1 << 4)) != 0) mask |= GamepadOverlayView.BTN_BACK_3; // M3
        if ((ext & (1 << 5)) != 0) mask |= GamepadOverlayView.BTN_BACK_4; // M4
        return mask;
    }

    private static boolean hasMagic(byte[] data, int off, int length) {
        return off >= 0 && off + 3 <= length
                && (data[off] & 0xFF) == 0x5A
                && (data[off + 1] & 0xFF) == 0xA5
                && (data[off + 2] & 0xFF) == 0xEF;
    }

    private boolean isActive(UsbDeviceConnection connection, UsbDevice device) {
        synchronized (connectionLock) {
            return activeConnection == connection
                    && activeDevice != null
                    && activeDevice.getDeviceId() == device.getDeviceId();
        }
    }

    private void closeConnection(boolean sendDisable) {
        UsbDeviceConnection connection;
        UsbInterface intf;
        UsbEndpoint out;
        synchronized (connectionLock) {
            connection = activeConnection;
            intf = activeInterface;
            out = activeOut;
            activeDevice = null;
            activeConnection = null;
            activeInterface = null;
            activeOut = null;
        }
        if (connection == null) return;
        if (sendDisable && out != null) {
            try { sendPacket(connection, out, CMD_TEST_DISABLE); } catch (Throwable ignored) {}
        }
        if (intf != null) {
            try { connection.releaseInterface(intf); } catch (Throwable ignored) {}
        }
        try { connection.close(); } catch (Throwable ignored) {}
    }

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(usbReceiver, filter, 0x4 /* Context.RECEIVER_NOT_EXPORTED */);
            } else {
                context.registerReceiver(usbReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable ignored) {
            receiverRegistered = false;
        }
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try { context.unregisterReceiver(usbReceiver); } catch (Throwable ignored) {}
    }

    private void emitBackMask(int mask) {
        if (lastBackMask == mask) return;
        lastBackMask = mask;
        if (listener != null) listener.onVader5UsbBackButtons(mask);
    }

    private void emitProfile(boolean connected) {
        if (lastProfile == connected) return;
        lastProfile = connected;
        if (listener != null) listener.onVader5UsbProfile(connected);
    }

    private void wakeWorker() {
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            // Interrupt is used as a wake-up signal after attach/permission/detach.
        }
    }

    private static void sleepQuiet(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static boolean isVader5(UsbDevice device) {
        return device != null
                && device.getVendorId() == VENDOR_FLYDIGI
                && device.getProductId() == PRODUCT_VADER5_PRO;
    }

    private static byte[] packet(int... values) {
        byte[] data = new byte[32];
        for (int i = 0; i < values.length && i < data.length; ++i) data[i] = (byte) values[i];
        return data;
    }

    private static final class InterfaceEndpoints {
        final UsbInterface usbInterface;
        final UsbEndpoint in;
        final UsbEndpoint out;

        InterfaceEndpoints(UsbInterface usbInterface, UsbEndpoint in, UsbEndpoint out) {
            this.usbInterface = usbInterface;
            this.in = in;
            this.out = out;
        }
    }
}
