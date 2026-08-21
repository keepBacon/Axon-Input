package com.axon.input;

import android.app.Application;

/** Process-level initialization for Axon Input. */
public final class AxonApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (!SignatureVerifier.isValid(this)) {
            throw new SecurityException("Axon Input signature verification failed");
        }
    }
}
