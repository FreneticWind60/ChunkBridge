package com.frenetic.litechunk.sdk;

/**
 * Stable categories for everything that can go wrong during detection or conversion.
 * <p>
 * The SDK never builds user facing text: an app maps these codes to its own strings, so the message wording can
 * change without breaking anyone. {@link ChunkBridgeException#detail()} carries the technical cause for logs.
 */
public enum ErrorCode {
    /** The input file or Uri could not be opened, or it vanished. */
    INPUT_UNREADABLE,
    /** The archive container (bzip2, xz, lzh, ...) cannot be read by this SDK. */
    ARCHIVE_UNSUPPORTED,
    /** The archive is corrupt, encrypted, or expands beyond the safety limits. */
    ARCHIVE_DAMAGED,
    /** No {@code level.dat} / Bedrock {@code db/} was found inside the input. */
    WORLD_NOT_FOUND,
    /** The requested {@code targetFormatId} is not in {@link ChunkBridge#formats()}. */
    FORMAT_UNSUPPORTED,
    /** The output file or Uri could not be created or written. */
    OUTPUT_UNWRITABLE,
    /** The output file already exists and the request did not allow overwriting it. */
    OUTPUT_EXISTS,
    /** The device ran out of storage while staging or writing. */
    INSUFFICIENT_STORAGE,
    /** The engine hit its heap limit, usually because the world is too large for the process. */
    OUT_OF_MEMORY,
    /** Another conversion is already running in this app. */
    BUSY,
    /** The request was cancelled by the caller. */
    CANCELLED,
    /** The embedded engine could not be loaded on this device. */
    ENGINE_UNAVAILABLE,
    /** Android refused to start the foreground service (app was in the background). */
    FOREGROUND_NOT_ALLOWED,
    /** Anything unexpected. The detail holds the original throwable's description. */
    INTERNAL
}
