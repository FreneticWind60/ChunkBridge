package com.frenetic.litechunk.sdk;

/**
 * What edition and version the input world turned out to be.
 * <p>
 * Delivered by {@link ChunkBridge#detect(java.io.File, DetectionCallback)} and its Uri twin.
 */
public final class DetectionResult {

    private final String editionId;
    private final String editionLabel;
    private final String version;

    DetectionResult(String editionId, String editionLabel, String version) {
        this.editionId = editionId;
        this.editionLabel = editionLabel;
        this.version = version;
    }

    /** Stable identifier of the detected edition, e.g. {@code JAVA}. */
    public String getEditionId() {
        return editionId;
    }

    /** Human readable edition name, e.g. {@code Bedrock Edition}. */
    public String getEditionLabel() {
        return editionLabel;
    }

    /** Version string as the engine reports it, e.g. {@code 1.21.6}; may be null when unknown. */
    public String getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return editionLabel + " " + version;
    }
}
