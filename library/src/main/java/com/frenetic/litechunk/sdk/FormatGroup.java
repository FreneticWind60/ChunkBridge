package com.frenetic.litechunk.sdk;

import java.util.Collections;
import java.util.List;

/**
 * An edition that can be converted to, with every version this engine build supports.
 * <p>
 * Returned by {@link ChunkBridge#formats()}. The list is already the right shape for a "format" dropdown: pick a
 * group, then a version inside it.
 */
public final class FormatGroup {

    private final String editionId;
    private final String editionLabel;
    private final List<FormatVersion> versions;

    FormatGroup(String editionId, String editionLabel, List<FormatVersion> versions) {
        this.editionId = editionId;
        this.editionLabel = editionLabel;
        this.versions = Collections.unmodifiableList(versions);
    }

    /** Stable identifier of the edition, e.g. {@code JAVA} or {@code BEDROCK}. */
    public String getEditionId() {
        return editionId;
    }

    /** Human readable edition name, e.g. {@code Java Edition}. */
    public String getEditionLabel() {
        return editionLabel;
    }

    /** Every target version of this edition, newest first (the order the engine reports). */
    public List<FormatVersion> getVersions() {
        return versions;
    }

    @Override
    public String toString() {
        return editionLabel;
    }
}
