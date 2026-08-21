package com.axon.input;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import moe.shizuku.api.BinderContainer;

/** 按 Shizuku provider 协议接收服务端 Binder。 */
public final class ShizukuBinderProvider extends ContentProvider {
    private static final String METHOD_SEND_BINDER = "sendBinder";
    private static final String METHOD_GET_BINDER = "getBinder";
    private static final String EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (METHOD_SEND_BINDER.equals(method)) {
            if (extras == null) {
                return null;
            }
            extras.setClassLoader(BinderContainer.class.getClassLoader());
            BinderContainer container = extras.getParcelable(EXTRA_BINDER);
            if (container != null && container.binder != null) {
                ShizukuBridge.onBinderReceived(container.binder,
                        getContext() != null ? getContext().getPackageName() : "com.axon.input");
            }
            return new Bundle();
        }

        if (METHOD_GET_BINDER.equals(method)) {
            IBinder binder = ShizukuBridge.getBinder();
            if (binder == null || !binder.pingBinder()) {
                return null;
            }
            Bundle reply = new Bundle();
            reply.putParcelable(EXTRA_BINDER, new BinderContainer(binder));
            return reply;
        }
        return null;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
