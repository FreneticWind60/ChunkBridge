package com.frenetic.litechunk.sdk;

/**
 * One selectable target version, for example {@code JAVA_1.21.6} ("Java 1.21.6").
 * <p>
 * The {@code formatId} is the value to pass to {@link ConversionRequest.Builder#targetFormatId(String)}.
 */
public final class FormatVersion {

    private final String formatId;
    private final String displayName;

    FormatVersion(String formatId, String displayName) {
        this.formatId = formatId;
        this.displayName = displayName;
    }

    /** Stable identifier used as the conversion target, e.g. {@code BEDROCK_1.26.60}. */
    public String getFormatId() {
        return formatId;
    }

    /** Human readable version, e.g. {@code 1.21.6}. Apps may show this as is. */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
