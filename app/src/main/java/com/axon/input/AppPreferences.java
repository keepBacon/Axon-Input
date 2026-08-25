package com.axon.input;

import android.content.Context;
import android.content.SharedPreferences;

/** Single access point for Axon Input's primary session/configuration preferences. */
final class AppPreferences {
    private static final String NAME = "axon_input_session";

    private AppPreferences() {}

    static SharedPreferences get(Context context) {
        return context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }
}
