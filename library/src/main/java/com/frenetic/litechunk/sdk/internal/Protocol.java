package com.frenetic.litechunk.sdk.internal;

/**
 * The wire format between the SDK facade (host process) and {@link SdkConversionService} ({@code :chunkbridge}).
 * <p>
 * A {@link android.os.ResultReceiver} carries the results back. That is deliberately the smallest possible
 * cross-process contract: no AIDL, no binder interfaces to version, and the receiver travels as a Parcelable extra
 * that stays valid for the lifetime of the request. Everything else is a primitive extra.
 */
public final class Protocol {

    public static final String ACTION_CONVERT = "com.frenetic.litechunk.sdk.action.CONVERT";
    public static final String ACTION_DETECT = "com.frenetic.litechunk.sdk.action.DETECT";
    /** Sent to the service to stop the running job. Carries only {@link #EXTRA_REQUEST_ID}. */
    public static final String ACTION_CANCEL = "com.frenetic.litechunk.sdk.action.CANCEL";

    public static final String EXTRA_REQUEST_ID = "com.frenetic.litechunk.sdk.extra.REQUEST_ID";
    public static final String EXTRA_RECEIVER = "com.frenetic.litechunk.sdk.extra.RECEIVER";
    /** Absolute path of a file, archive or world folder in the host process's own storage. */
    public static final String EXTRA_INPUT_PATH = "com.frenetic.litechunk.sdk.extra.INPUT_PATH";
    /** String form of the input document Uri. */
    public static final String EXTRA_INPUT_URI = "com.frenetic.litechunk.sdk.extra.INPUT_URI";
    public static final String EXTRA_OUTPUT_PATH = "com.frenetic.litechunk.sdk.extra.OUTPUT_PATH";
    public static final String EXTRA_OUTPUT_URI = "com.frenetic.litechunk.sdk.extra.OUTPUT_URI";
    public static final String EXTRA_TARGET_FORMAT = "com.frenetic.litechunk.sdk.extra.TARGET_FORMAT";
    public static final String EXTRA_ENTITIES = "com.frenetic.litechunk.sdk.extra.ENTITIES";
    public static final String EXTRA_LEAN = "com.frenetic.litechunk.sdk.extra.LEAN";
    public static final String EXTRA_OVERWRITE = "com.frenetic.litechunk.sdk.extra.OVERWRITE";
    public static final String EXTRA_DELETE_SOURCE = "com.frenetic.litechunk.sdk.extra.DELETE_SOURCE";
    public static final String EXTRA_NOTIFICATION_TITLE = "com.frenetic.litechunk.sdk.extra.NOTIFICATION_TITLE";
    public static final String EXTRA_NOTIFICATION_TEXT = "com.frenetic.litechunk.sdk.extra.NOTIFICATION_TEXT";

    /** Job accepted and about to stage its input. */
    public static final int RESULT_STARTED = 1;
    /** Carries {@link #KEY_FRACTION} and {@link #KEY_STAGE}. */
    public static final int RESULT_PROGRESS = 2;
    /** Carries the output description. */
    public static final int RESULT_COMPLETED = 3;
    /** Carries {@link #KEY_ERROR_CODE}, {@link #KEY_ERROR_MESSAGE} and {@link #KEY_ERROR_DETAIL}. */
    public static final int RESULT_FAILED = 4;
    public static final int RESULT_CANCELLED = 5;
    /** Carries the detected edition and version. */
    public static final int RESULT_DETECTED = 6;

    public static final String KEY_REQUEST_ID = "requestId";
    public static final String KEY_FRACTION = "fraction";
    public static final String KEY_STAGE = "stage";
    public static final String KEY_TARGET_FORMAT = "targetFormat";
    public static final String KEY_TARGET_VERSION = "targetVersion";
    public static final String KEY_OUTPUT_PATH = "outputPath";
    public static final String KEY_OUTPUT_URI = "outputUri";
    public static final String KEY_OUTPUT_BYTES = "outputBytes";
    public static final String KEY_ELAPSED_MS = "elapsedMs";
    public static final String KEY_EDITION_ID = "editionId";
    public static final String KEY_EDITION_LABEL = "editionLabel";
    public static final String KEY_VERSION = "version";
    public static final String KEY_ERROR_CODE = "errorCode";
    public static final String KEY_ERROR_MESSAGE = "errorMessage";
    public static final String KEY_ERROR_DETAIL = "errorDetail";

    private Protocol() {
    }
}
