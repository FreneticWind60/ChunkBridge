package com.frenetic.litechunk.sdk;

/**
 * Receives the result of a detection request.
 * <p>
 * Called on the main thread. Exactly one of the two arguments is non-null.
 */
public interface DetectionCallback {

    /**
     * @param result the detected edition and version, or null when detection failed.
     * @param error  the failure, or null when detection succeeded.
     */
    void onFinished(DetectionResult result, ChunkBridgeException error);
}
