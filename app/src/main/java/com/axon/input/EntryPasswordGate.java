package com.axon.input;

import android.content.Context;
import android.widget.FrameLayout;

final class EntryPasswordGate extends FrameLayout {
    interface Listener {
        boolean isPasswordCorrect(String value);
        void onAuthorized();
        void onOpenPasswordSource();
    }

    EntryPasswordGate(Context context, Listener listener) {
        super(context);
        setVisibility(GONE);
        post(() -> {
            if (listener != null) listener.onAuthorized();
        });
    }

    boolean isVerifying() {
        return false;
    }
}
