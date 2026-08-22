package com.axon.input;

import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

import java.util.concurrent.CopyOnWriteArrayList;

/** Shizuku 客户端。负责 Binder 连接、权限请求和 shell 执行。 */
public final class ShizukuBridge {
    public interface Listener {
        default void onShizukuReady(boolean permissionGranted) {}
        default void onShizukuPermissionResult(int requestCode, boolean granted) {}
        default void onShizukuDead() {}
    }

    private static final String SERVICE_DESCRIPTOR = "moe.shizuku.server.IShizukuService";
    private static final String APP_DESCRIPTOR = "moe.shizuku.server.IShizukuApplication";
    private static final String REMOTE_PROCESS_DESCRIPTOR = "moe.shizuku.server.IRemoteProcess";

    // AIDL 事务号按 FIRST_CALL_TRANSACTION + id 计算。
    private static final int TX_NEW_PROCESS = 8;          // 事务 7
    private static final int TX_REQUEST_PERMISSION = 15; // 事务 14
    private static final int TX_CHECK_SELF_PERMISSION = 16; // 事务 15
    private static final int TX_ATTACH_APPLICATION = 18; // 事务 17

    private static final int APP_TX_BIND_APPLICATION = 2; // 事务 1
    private static final int APP_TX_PERMISSION_RESULT = 3; // 事务 2
    private static final int APP_TX_SHOW_PERMISSION = 10001; // 事务 10000

    private static final int PROCESS_TX_GET_INPUT_STREAM = 2;
    private static final int PROCESS_TX_WAIT_FOR = 4;
    private static final int PROCESS_TX_DESTROY = 6;

    private static final String KEY_ATTACH_VERSION = "shizuku:attach-api-version";
    private static final String KEY_ATTACH_PACKAGE = "shizuku:attach-package-name";
    private static final String KEY_PERMISSION_GRANTED = "shizuku:attach-reply-permission-granted";
    private static final String KEY_PERMISSION_ALLOWED = "shizuku:request-permission-reply-allowed";

    private static final int SHIZUKU_API_VERSION = 13;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private static volatile IBinder serviceBinder;
    private static volatile boolean ready;
    private static volatile boolean permissionGranted;
    private static String packageName = "com.axon.input";

    private static final IBinder.DeathRecipient DEATH_RECIPIENT = () -> {
        serviceBinder = null;
        ready = false;
        permissionGranted = false;
        MAIN.post(() -> {
            for (Listener listener : LISTENERS) {
                listener.onShizukuDead();
            }
        });
    };

    private static final Binder APPLICATION_BINDER = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) {
                    reply.writeString(APP_DESCRIPTOR);
                }
                return true;
            }

            if (code == APP_TX_BIND_APPLICATION) {
                data.enforceInterface(APP_DESCRIPTOR);
                Bundle result = readBundle(data);
                permissionGranted = result != null && result.getBoolean(KEY_PERMISSION_GRANTED, false);
                ready = true;
                MAIN.post(() -> {
                    for (Listener listener : LISTENERS) {
                        listener.onShizukuReady(permissionGranted);
                    }
                });
                return true;
            }

            if (code == APP_TX_PERMISSION_RESULT) {
                data.enforceInterface(APP_DESCRIPTOR);
                int requestCode = data.readInt();
                Bundle result = readBundle(data);
                boolean granted = result != null && result.getBoolean(KEY_PERMISSION_ALLOWED, false);
                permissionGranted = granted;
                MAIN.post(() -> {
                    for (Listener listener : LISTENERS) {
                        listener.onShizukuPermissionResult(requestCode, granted);
                    }
                });
                return true;
            }

            if (code == APP_TX_SHOW_PERMISSION) {
                data.enforceInterface(APP_DESCRIPTOR);
                return true;
            }

            return super.onTransact(code, data, reply, flags);
        }
    };

    private ShizukuBridge() {}

    public static void addListener(Listener listener) {
        if (listener != null && !LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
            if (ready) {
                MAIN.post(() -> listener.onShizukuReady(permissionGranted));
            }
        }
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static IBinder getBinder() {
        return serviceBinder;
    }

    static synchronized void onBinderReceived(IBinder binder, String clientPackage) {
        if (binder == null || !binder.pingBinder()) {
            return;
        }
        if (serviceBinder == binder && ready) {
            return;
        }

        IBinder old = serviceBinder;
        if (old != null) {
            old.unlinkToDeath(DEATH_RECIPIENT, 0);
        }

        serviceBinder = binder;
        packageName = clientPackage;
        ready = false;
        permissionGranted = false;

        try {
            binder.linkToDeath(DEATH_RECIPIENT, 0);
            attachApplication();
        } catch (Throwable ignored) {
            serviceBinder = null;
            ready = false;
            permissionGranted = false;
        }
    }

    public static boolean isAvailable() {
        IBinder binder = serviceBinder;
        return binder != null && binder.pingBinder();
    }

    public static boolean isReady() {
        return isAvailable() && ready;
    }

    public static boolean hasPermission() {
        if (!isReady()) {
            return false;
        }
        try {
            permissionGranted = checkSelfPermissionRemote();
        } catch (Throwable ignored) {
            // 保留 attachApplication 返回的权限状态。
        }
        return permissionGranted;
    }

    public static boolean requestPermission(int requestCode) {
        IBinder binder = serviceBinder;
        if (binder == null || !binder.pingBinder()) {
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeInt(requestCode);
            if (!binder.transact(TX_REQUEST_PERMISSION, data, reply, 0)) {
                return false;
            }
            reply.readException();
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /** 可持续读取输出的 Shizuku shell 进程。 */
    public static final class ShellProcess implements Closeable {
        private final IBinder processBinder;
        private final ParcelFileDescriptor stdoutFd;
        private InputStream inputStream;
        private boolean closed;

        private ShellProcess(IBinder processBinder, ParcelFileDescriptor stdoutFd) {
            this.processBinder = processBinder;
            this.stdoutFd = stdoutFd;
        }

        public synchronized InputStream getInputStream() {
            if (inputStream == null) {
                inputStream = new ParcelFileDescriptor.AutoCloseInputStream(stdoutFd);
            }
            return inputStream;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                if (inputStream != null) inputStream.close();
                else stdoutFd.close();
            } catch (IOException ignored) {
            }
            destroyProcess(processBinder);
        }
    }

    /** 启动持续运行的 shell 命令并返回输出流。 */
    public static ShellProcess startShell(String command) throws RemoteException {
        if (!hasPermission()) {
            throw new SecurityException("Shizuku permission is not granted");
        }
        IBinder processBinder = createRemoteProcess(command);
        ParcelFileDescriptor stdout = getProcessInputStream(processBinder);
        if (stdout == null) {
            destroyProcess(processBinder);
            throw new RemoteException("Shizuku returned no stdout stream");
        }
        return new ShellProcess(processBinder, stdout);
    }

    /** 使用 Shizuku 身份执行固定命令。不要在 UI 线程调用。 */
    public static int runShell(String command) throws RemoteException {
        if (!hasPermission()) {
            throw new SecurityException("Shizuku permission is not granted");
        }

        IBinder processBinder = createRemoteProcess(command);
        int exitCode = waitForProcess(processBinder);
        destroyProcess(processBinder);
        return exitCode;
    }

    private static IBinder createRemoteProcess(String command) throws RemoteException {
        IBinder binder = serviceBinder;
        if (binder == null || !binder.pingBinder()) {
            throw new RemoteException("Shizuku binder is unavailable");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStringArray(new String[]{"/system/bin/sh", "-c", command});
            data.writeStringArray(null);
            data.writeString(null);
            if (!binder.transact(TX_NEW_PROCESS, data, reply, 0)) {
                throw new RemoteException("newProcess transact failed");
            }
            reply.readException();
            IBinder processBinder = reply.readStrongBinder();
            if (processBinder == null) {
                throw new RemoteException("Shizuku returned no process");
            }
            return processBinder;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static ParcelFileDescriptor getProcessInputStream(IBinder process) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
            if (!process.transact(PROCESS_TX_GET_INPUT_STREAM, data, reply, 0)) {
                throw new RemoteException("getInputStream transact failed");
            }
            reply.readException();
            return reply.readInt() != 0 ? ParcelFileDescriptor.CREATOR.createFromParcel(reply) : null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void attachApplication() throws RemoteException {
        IBinder binder = serviceBinder;
        if (binder == null) {
            throw new RemoteException("No Shizuku binder");
        }

        Bundle args = new Bundle();
        args.putInt(KEY_ATTACH_VERSION, SHIZUKU_API_VERSION);
        args.putString(KEY_ATTACH_PACKAGE, packageName);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            data.writeStrongBinder(APPLICATION_BINDER);
            data.writeInt(1);
            args.writeToParcel(data, 0);
            if (!binder.transact(TX_ATTACH_APPLICATION, data, reply, 0)) {
                throw new RemoteException("attachApplication transact failed");
            }
            reply.readException();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean checkSelfPermissionRemote() throws RemoteException {
        IBinder binder = serviceBinder;
        if (binder == null) {
            return false;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SERVICE_DESCRIPTOR);
            if (!binder.transact(TX_CHECK_SELF_PERMISSION, data, reply, 0)) {
                return permissionGranted;
            }
            reply.readException();
            return reply.readInt() != 0;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static int waitForProcess(IBinder process) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
            if (!process.transact(PROCESS_TX_WAIT_FOR, data, reply, 0)) {
                throw new RemoteException("waitFor transact failed");
            }
            reply.readException();
            return reply.readInt();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void destroyProcess(IBinder process) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(REMOTE_PROCESS_DESCRIPTOR);
            if (process.transact(PROCESS_TX_DESTROY, data, reply, 0)) {
                reply.readException();
            }
        } catch (Throwable ignored) {
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static Bundle readBundle(Parcel data) {
        return data.readInt() != 0 ? Bundle.CREATOR.createFromParcel(data) : null;
    }
}
