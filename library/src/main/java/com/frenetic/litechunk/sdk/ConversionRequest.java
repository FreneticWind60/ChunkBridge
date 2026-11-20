package com.frenetic.litechunk.sdk;

import android.net.Uri;

import java.io.File;

/**
 * One conversion, described before it starts.
 * <p>
 * Build it with {@link Builder}; every value is read once by {@link ChunkBridge#convert(ConversionRequest,
 * ConversionCallback)} and cannot change afterwards.
 *
 * <pre>{@code
 * ConversionRequest request = new ConversionRequest.Builder()
 *         .inputUri(inputUri)                       // or .inputFile(worldFolder)
 *         .outputFile(new File(dir, "converted.mcworld"))
 *         .targetFormatId("BEDROCK_1.26.60")
 *         .convertEntities(true)
 *         .build();
 * }</pre>
 */
public final class ConversionRequest {

    private final File inputFile;
    private final Uri inputUri;
    private final File outputFile;
    private final Uri outputUri;
    private final String targetFormatId;
    private final boolean convertEntities;
    private final boolean leanMode;
    private final boolean overwriteExisting;
    private final boolean deleteSourceArchiveAfterSuccess;
    private final String notificationTitle;
    private final String notificationText;

    private ConversionRequest(Builder builder) {
        this.inputFile = builder.inputFile;
        this.inputUri = builder.inputUri;
        this.outputFile = builder.outputFile;
        this.outputUri = builder.outputUri;
        this.targetFormatId = builder.targetFormatId;
        this.convertEntities = builder.convertEntities;
        this.leanMode = builder.leanMode;
        this.overwriteExisting = builder.overwriteExisting;
        this.deleteSourceArchiveAfterSuccess = builder.deleteSourceArchiveAfterSuccess;
        this.notificationTitle = builder.notificationTitle;
        this.notificationText = builder.notificationText;
    }

    /** The input file, a world folder or a {@code .zip}/{@code .mcworld}/{@code .rar}/{@code .7z}; or null. */
    public File getInputFile() {
        return inputFile;
    }

    /** The input document, or null. */
    public Uri getInputUri() {
        return inputUri;
    }

    /** The archive file to write, or null. */
    public File getOutputFile() {
        return outputFile;
    }

    /** The document to write, or null. */
    public Uri getOutputUri() {
        return outputUri;
    }

    /** Target format id, one of {@link FormatVersion#getFormatId()}. */
    public String getTargetFormatId() {
        return targetFormatId;
    }

    /** Whether entities are copied on Bedrock targets. Terrain is always converted. */
    public boolean isConvertEntities() {
        return convertEntities;
    }

    /** Whether the engine trades speed for a smaller heap footprint. */
    public boolean isLeanMode() {
        return leanMode;
    }

    /** Whether an existing output file may be replaced; false means the job fails with OUTPUT_EXISTS. */
    public boolean isOverwriteExisting() {
        return overwriteExisting;
    }

    /**
     * Whether the input archive is deleted after success. Only applies to {@link #getInputUri()}: the SDK owns the
     * staged copy of a Uri and may delete it, but it never deletes a file the caller passed in.
     */
    public boolean isDeleteSourceArchiveAfterSuccess() {
        return deleteSourceArchiveAfterSuccess;
    }

    /** Title for the foreground notification, or null for the SDK default. */
    public String getNotificationTitle() {
        return notificationTitle;
    }

    /** Text for the foreground notification, or null for the SDK default. */
    public String getNotificationText() {
        return notificationText;
    }

    /** Collects the values of a {@link ConversionRequest}. */
    public static final class Builder {

        private File inputFile;
        private Uri inputUri;
        private File outputFile;
        private Uri outputUri;
        private String targetFormatId;
        private boolean convertEntities = true;
        private boolean leanMode;
        private boolean overwriteExisting;
        private boolean deleteSourceArchiveAfterSuccess;
        private String notificationTitle;
        private String notificationText;

        /** Reads an archive or a world folder directly. The path must be readable by the host app. */
        public Builder inputFile(File file) {
            this.inputFile = file;
            this.inputUri = null;
            return this;
        }

        /** Reads an archive through the ContentResolver. The stream is copied into cache before conversion. */
        public Builder inputUri(Uri uri) {
            this.inputUri = uri;
            this.inputFile = null;
            return this;
        }

        /** Writes the converted archive to this file, replacing it only when {@link #overwriteExisting()} is set. */
        public Builder outputFile(File file) {
            this.outputFile = file;
            this.outputUri = null;
            return this;
        }

        /** Writes the converted archive into an existing document ({@code CreateDocument} result). */
        public Builder outputUri(Uri uri) {
            this.outputUri = uri;
            this.outputFile = null;
            return this;
        }

        /** Required. See {@link FormatVersion#getFormatId()}. */
        public Builder targetFormatId(String formatId) {
            this.targetFormatId = formatId;
            return this;
        }

        /** Copies entities to Bedrock targets; default true. */
        public Builder convertEntities(boolean entities) {
            this.convertEntities = entities;
            return this;
        }

        /** Uses the engine's low memory profile; default false. */
        public Builder leanMode(boolean lean) {
            this.leanMode = lean;
            return this;
        }

        /** Allows replacing an existing output file; default false. */
        public Builder overwriteExisting(boolean overwrite) {
            this.overwriteExisting = overwrite;
            return this;
        }

        /** Deletes the staged copy of a Uri input after success; default false. See the getter for the caveat. */
        public Builder deleteSourceArchiveAfterSuccess(boolean delete) {
            this.deleteSourceArchiveAfterSuccess = delete;
            return this;
        }

        /** Notification title shown while the conversion runs, or null for the SDK default. */
        public Builder notificationTitle(String title) {
            this.notificationTitle = title;
            return this;
        }

        /** Notification text shown while the conversion runs, or null for the SDK default. */
        public Builder notificationText(String text) {
            this.notificationText = text;
            return this;
        }

        /**
         * Validates the shape of the request.
         *
         * @throws IllegalArgumentException when an input or output is missing, both kinds are set, or no target.
         */
        public ConversionRequest build() {
            if (inputFile == null && inputUri == null) {
                throw new IllegalArgumentException("A conversion needs an input file or an input Uri.");
            }
            if (inputFile != null && inputUri != null) {
                throw new IllegalArgumentException("Set either inputFile or inputUri, not both.");
            }
            if (outputFile == null && outputUri == null) {
                throw new IllegalArgumentException("A conversion needs an output file or an output Uri.");
            }
            if (outputFile != null && outputUri != null) {
                throw new IllegalArgumentException("Set either outputFile or outputUri, not both.");
            }
            if (targetFormatId == null || targetFormatId.trim().isEmpty()) {
                throw new IllegalArgumentException("A conversion needs a targetFormatId.");
            }
            return new ConversionRequest(this);
        }
    }
}
