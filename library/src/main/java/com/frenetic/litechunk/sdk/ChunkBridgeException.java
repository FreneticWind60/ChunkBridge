package com.frenetic.litechunk.sdk;

/**
 * The only exception the SDK hands to callers.
 * <p>
 * It arrives through {@link ConversionCallback#onFailed(ChunkBridgeException)} and
 * {@link DetectionCallback#onFinished(DetectionResult, ChunkBridgeException)} rather than being thrown, because the
 * work happens in a different process. Programming mistakes (a request without an input) still throw
 * {@link IllegalArgumentException} immediately.
 */
public class ChunkBridgeException extends Exception {

    private final ErrorCode code;
    private final String detail;

    public ChunkBridgeException(ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public ChunkBridgeException(ErrorCode code, String message, String detail) {
        this(code, message, detail, null);
    }

    public ChunkBridgeException(ErrorCode code, String message, String detail, Throwable cause) {
        super(message, cause);
        this.code = code == null ? ErrorCode.INTERNAL : code;
        this.detail = detail;
    }

    /** The category an app switches on to pick its own message. */
    public ErrorCode getCode() {
        return code;
    }

    /**
     * Technical description of the failure, suitable for a log: the original exception's class and message, the
     * archive entry that broke, and so on. May be null.
     */
    public String getDetail() {
        return detail;
    }
}
