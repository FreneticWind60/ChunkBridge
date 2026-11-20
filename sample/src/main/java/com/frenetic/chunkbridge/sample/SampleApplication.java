package com.frenetic.chunkbridge.sample;

import android.app.Application;

import com.frenetic.litechunk.sdk.ChunkBridge;

/**
 * Initialises the SDK once per process.
 *
 * <p>The sample sets {@code android:largeHeap="true"}: a full conversion of a large world needs roughly 300 MB
 * of heap for the engine plus the packed copies. Host apps that skip this only get the lean path on low-heap
 * devices, and the SDK will report {@code OUT_OF_MEMORY} for the rest.
 */
public class SampleApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ChunkBridge.initialize(this);
    }
}
