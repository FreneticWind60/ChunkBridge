package com.frenetic.litechunk.sdk;

/**
 * What the engine is doing right now, as reported by {@link ConversionCallback#onProgress(double, Stage)}.
 * <p>
 * These are stable codes, not display text. The engine reports them as strings because it lives in a different
 * class loader (and a different process); this enum is the SDK's translation of them.
 */
public enum Stage {
    /** Reading and writing chunk terrain, the bulk of the work. */
    TERRAIN,
    /** Second pass that copies entities (Bedrock targets only, and only when entities are enabled). */
    ENTITIES,
    /** The engine compacting its LevelDB output. */
    COMPACT,
    /** Packing the finished world into the output archive. */
    FINISH,
    /** A stage this SDK version does not know, from a newer engine. */
    UNKNOWN;

    /**
     * Maps an engine stage code to this enum.
     *
     * @param engineStage the code from the engine's progress listener, may be null.
     * @return the matching stage, or {@link #UNKNOWN}.
     */
    static Stage fromEngine(String engineStage) {
        if (engineStage == null) {
            return UNKNOWN;
        }
        switch (engineStage) {
            case "terrain":
                return TERRAIN;
            case "entities":
                return ENTITIES;
            case "compact":
                return COMPACT;
            case "finish":
                return FINISH;
            default:
                return UNKNOWN;
        }
    }
}
