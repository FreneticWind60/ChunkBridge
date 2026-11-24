package com.frenetic.litechunk.sdk;

import com.frenetic.litechunk.api.LevelInfo;

/**
 * Receives the result of {@link ChunkBridge#inspectLevel} and {@link ChunkBridge#applyLevel}.
 * <p>
 * Called once, on the main thread. Exactly one of the two arguments is non-null.
 */
public interface LevelCallback {

    /**
     * @param info  the fields of the world, already refreshed when this is the answer to an apply.
     * @param error the failure, or {@code null} when the call succeeded.
     */
    void onResult(LevelInfo info, ChunkBridgeException error);
}
