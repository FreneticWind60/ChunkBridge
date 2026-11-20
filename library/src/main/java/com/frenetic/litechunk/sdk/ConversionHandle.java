package com.frenetic.litechunk.sdk;

/**
 * A conversion that has been handed to the SDK, used to follow or stop it.
 * <p>
 * The handle stays valid after the conversion finishes; {@link #cancel()} on a finished job does nothing.
 */
public interface ConversionHandle {

    /** The id of this job, the same value passed to {@link ConversionCallback#onStarted(String)}. */
    String getRequestId();

    /**
     * Asks the conversion to stop.
     * <p>
     * The engine has no way to abort halfway through a chunk pass, so the SDK stops the conversion service
     * process. Any partially written output file is removed and
     * {@link ConversionCallback#onCancelled(String)} is delivered. Safe to call from any thread, and safe to call
     * more than once.
     */
    void cancel();
}
