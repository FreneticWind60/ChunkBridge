/*
 * Copyright (c) 2026 Frenetic
 *
 * Debug-only helper: keeps the app in the foreground while an instrumentation test converts a large world.
 * Android instrumentation has no visible activity of its own, and some vendor task cleaners stop packages whose
 * foreground service notification cannot be dismissed (Vivo "WithOutCancelNoti"), which kills long conversions.
 * This activity only exists in debug builds and is not part of the published SDK.
 */
package com.frenetic.litechunk.sdk.debug;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

public final class KeepAliveActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setGravity(Gravity.CENTER);
        text.setText("ChunkBridge test in progress");
        setContentView(text);
    }
}
