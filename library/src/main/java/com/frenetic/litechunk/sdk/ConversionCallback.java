package com.frenetic.litechunk.sdk;

/**
 * Receives the progress of one conversion.
 * <p>
 * All methods are invoked on the main thread of the calling process, so they can touch views directly. Exactly one
 * of {@link #onCompleted}, {@link #onFailed} or {@link #onCancelled} is called, once.
 */
public interface ConversionCallback {

    /**
     * The job was accepted by the conversion service. Not called when the request failed immediately.
     *
     * @param requestId the id of this job, also available from {@link ConversionHandle#getRequestId()}.
     */
    default void onStarted(String requestId) {
    }

    /**
     * Progress update.
     *
     * @param fraction 0.0 to 1.0, monotonic within a stage.
     * @param stage    what the engine is doing, never null.
     */
    default void onProgress(double fraction, Stage stage) {
    }

    /** The world was written successfully. */
    void onCompleted(ConversionResult result);

    /** The conversion failed; the exception's code says why. */
    void onFailed(ChunkBridgeException error);

    /** The conversion was cancelled through {@link ConversionHandle#cancel()}. */
    default void onCancelled(String requestId) {
    }
}
