package com.frenetic.litechunk.sdk;

import android.net.Uri;

import java.io.File;

/**
 * Where a successful conversion wrote its result, and how big it is.
 * <p>
 * Exactly one of {@link #getOutputFile()} and {@link #getOutputUri()} is non-null, mirroring how the request was
 * built.
 */
public final class ConversionResult {

    private final String requestId;
    private final String targetFormatId;
    private final String targetVersion;
    private final File outputFile;
    private final Uri outputUri;
    private final long outputBytes;
    private final long elapsedMs;

    ConversionResult(
            String requestId,
            String targetFormatId,
            String targetVersion,
            File outputFile,
            Uri outputUri,
            long outputBytes,
            long elapsedMs
    ) {
        this.requestId = requestId;
        this.targetFormatId = targetFormatId;
        this.targetVersion = targetVersion;
        this.outputFile = outputFile;
        this.outputUri = outputUri;
        this.outputBytes = outputBytes;
        this.elapsedMs = elapsedMs;
    }

    /** The id this conversion was given, see {@link ConversionHandle#getRequestId()}. */
    public String getRequestId() {
        return requestId;
    }

    /** The {@code formatId} that was asked for, e.g. {@code BEDROCK_1_21_120}. */
    public String getTargetFormatId() {
        return targetFormatId;
    }

    /** The version the engine actually wrote, e.g. {@code 1.21.120}; may differ from the request's label form. */
    public String getTargetVersion() {
        return targetVersion;
    }

    /** The file the world was written to, or null when the request used an output Uri. */
    public File getOutputFile() {
        return outputFile;
    }

    /** The document the world was written to, or null when the request used an output File. */
    public Uri getOutputUri() {
        return outputUri;
    }

    /** Size of the written archive in bytes. */
    public long getOutputBytes() {
        return outputBytes;
    }

    /** Wall clock time from request to completion, in milliseconds. */
    public long getElapsedMs() {
        return elapsedMs;
    }
}
